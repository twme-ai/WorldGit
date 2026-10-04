package org.worldgit.core.remote;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

/** fetch/push 不讀寫活世界；成功 fetch 才發布全組 tracking refs，PARTIAL push 可依原 journal 重試。 */
public final class WorldRemotes implements AutoCloseable {
  public record TransferResult(
      String state,
      String operation,
      SortedMap<DimensionId, String> commits,
      SortedMap<DimensionId, List<GitTransfer.PackSize>> packs,
      String error) {
    public boolean success() {
      return state.equals("COMPLETE") || state.equals("DRY_RUN");
    }
  }

  public record Tracking(String remote, String branch, int ahead, int behind, boolean estimated) {}

  @FunctionalInterface
  public interface Observer {
    void publishing(DimensionId dimension) throws IOException;
  }

  private final RepositoryGroup group;
  private final Credentials credentials;
  private final Observer observer;
  private final int timeoutSeconds;

  public WorldRemotes(WorldLayout layout, Credentials credentials) throws IOException {
    this(layout.repositoryRoot(), new WorldRepositories(layout).tracked(), credentials);
  }

  public WorldRemotes(WorldLayout layout,Credentials credentials,int timeoutSeconds) throws IOException {
    this(layout.repositoryRoot(),new WorldRepositories(layout).tracked(),credentials,d->{},timeoutSeconds);
  }

  public WorldRemotes(Path root, Map<DimensionId, Path> paths, Credentials credentials)
      throws IOException {
    this(root, paths, credentials, d -> {});
  }

  public WorldRemotes(
      Path root, Map<DimensionId, Path> paths, Credentials credentials, Observer observer)
      throws IOException {
    this(root,paths,credentials,observer,60);
  }

  public WorldRemotes(Path root,Map<DimensionId,Path> paths,Credentials credentials,Observer observer,int timeoutSeconds) throws IOException {
    if(timeoutSeconds<1 || timeoutSeconds>3600)throw new IllegalArgumentException("transport timeout 無效");
    this.timeoutSeconds=timeoutSeconds;
    group = new RepositoryGroup(root, paths);
    this.credentials = credentials;
    this.observer = observer;
  }

  public SortedMap<String, RemoteSpec> remotes() throws IOException {
    return RemoteSpec.read(group.root());
  }

  public void configure(String action, String name, String url, boolean dryRun) throws IOException {
    RemoteSpec.validateName(name);
    var pending = OperationState.read(group.root().resolve("push-state.yml"));
    if (name.equals(pending.get("remote")) && !"COMPLETE".equals(pending.get("state")))
      throw new IOException("remote 有未完成 push；先以原參數重試，不可變更 URL 或移除");
    var remotes = remotes();
    if (action.equals("add")) {
      if (remotes.containsKey(name)) throw new IOException("remote 已存在");
      remotes.put(name, RemoteSpec.parse(url));
    } else if (action.equals("set-url")) {
      if (!remotes.containsKey(name)) throw new IOException("remote 不存在");
      remotes.put(name, RemoteSpec.parse(url));
    } else if (action.equals("remove")) {
      if (remotes.remove(name) == null) throw new IOException("remote 不存在");
    } else throw new IOException("remote 動作無效");
    if (!dryRun) RemoteSpec.write(group.root(), remotes);
  }

  private RemoteSpec remote(String name) throws IOException {
    var r = remotes().get(name);
    if (r == null) throw new IOException("remote 不存在：" + name);
    return r;
  }

  private GitTransfer transport(String name, RemoteSpec remote, DimensionId id) throws IOException {
    String url = remote.expand(id);
    return new GitTransfer(group.repos().get(id).directory(), url, credentials.resolve(name, url),JGitStore.PACK_LIMIT,timeoutSeconds);
  }

  /** 供平台判斷 PR 來源是否為本機分支；不建立或切換分支。 */
  public boolean hasBranch(String branch) throws IOException {
    for(var r:group.repos().values()) if(!r.refs().branches().containsKey(branch)) return false;
    return !group.repos().isEmpty();
  }

  public String branch() throws IOException {
    return currentBranch();
  }

  private String currentBranch() throws IOException {
    String branch = null;
    for (var r : group.repos().values()) {
      String b = r.refs().headState().branch();
      if (b == null || branch != null && !branch.equals(b))
        throw new IOException("需要全組同名分支，不能 detached");
      branch = b;
    }
    return branch;
  }

  public TransferResult fetch(String name, boolean dryRun) throws IOException {
    var remote = remote(name);
    String operation = UUID.randomUUID().toString();
    var packs = new TreeMap<DimensionId, List<GitTransfer.PackSize>>();
    var heads = new TreeMap<DimensionId, String>();
    var refs = new TreeMap<DimensionId, SortedMap<String, String>>();
    Path fetchPath = group.root().resolve("fetch-state.yml");
    var fetchJournal = new LinkedHashMap<String, Object>();
    if (!dryRun) {
      recoverFetch();
      fetchJournal.put("state", "FETCHING");
      fetchJournal.put("remote", name);
      fetchJournal.put("operation", operation);
      OperationState.write(fetchPath, fetchJournal);
    }
    try {
      for (var e : group.repos().entrySet())
        try (var t = transport(name, remote, e.getKey())) {
          var advertised = t.advertised(false);
          refs.put(e.getKey(), advertised);
          if (dryRun) continue;
          String prefix = "refs/worldgit/incoming/" + name + "/";
          t.fetchStages(prefix);
          for (var ref : advertised.entrySet())
            if (ref.getKey().startsWith("refs/heads/")
                || ref.getKey().startsWith("refs/tags/")
                || ref.getKey().startsWith("refs/worldgit/groups/")
                || ref.getKey().startsWith("refs/worldgit/publications/"))
              t.fetch(ref.getKey(), prefix + ref.getKey().substring(5));
          packs.put(e.getKey(), t.sizes());
        }
      if (dryRun) return new TransferResult("DRY_RUN", operation, heads, packs, null);
      validatePublications(refs);
      var changes = new ArrayList<RefChange>();
      for (var e : refs.entrySet()) {
        var repo = group.repos().get(e.getKey());
        var store = repo.refs();
        for (var ref : e.getValue().entrySet()) {
          String target = ref.getKey();
          if (target.startsWith("refs/heads/"))
            target = "refs/remotes/" + name + "/" + target.substring(11);
          else if (!target.startsWith("refs/tags/") && !target.startsWith("refs/worldgit/groups/"))
            continue;
          String old = store.refsByPrefix(target).get(target);
          if ((target.startsWith("refs/tags/") || target.startsWith("refs/worldgit/groups/"))
              && old != null
              && !old.equals(ref.getValue()))
            throw new IOException("遠端 tag／snapshot group 與本機不同：" + target);
          changes.add(new RefChange(e.getKey(), target, old, ref.getValue()));
        }
      }
      var rows = new ArrayList<Map<String, Object>>();
      for (var c : changes) {
        var row = new LinkedHashMap<String, Object>();
        row.put("dimension", c.dimension().value());
        row.put("ref", c.ref());
        row.put("old", c.old());
        row.put("target", c.target());
        rows.add(row);
      }
      fetchJournal.put("changes", rows);
      fetchJournal.put("state", "PUBLISHING");
      OperationState.write(fetchPath, fetchJournal);
      updateAll(changes);
      String branch;
      try {
        branch = currentBranch();
      } catch (IOException detached) {
        branch = null;
      }
      for (var e : refs.entrySet()) {
        String tip = e.getValue().get("refs/heads/" + branch);
        if (tip != null) heads.put(e.getKey(), tip);
      }
      OperationState.write(
          group.root().resolve("fetch-state.yml"),
          Map.of("state", "COMPLETE", "remote", name, "operation", operation));
      return new TransferResult("COMPLETE", operation, heads, packs, null);
    } catch (Exception ex) {
      if (!dryRun) {
        fetchJournal.put("state", "PARTIAL");
        fetchJournal.put("error", ex.getMessage());
        OperationState.write(fetchPath, fetchJournal);
      }
      return new TransferResult("PARTIAL", operation, heads, packs, ex.getMessage());
    }
  }

  private void recoverFetch() throws IOException {
    Path path = group.root().resolve("fetch-state.yml");
    var journal = OperationState.read(path);
    if (Objects.equals(journal.get("state"), "COMPLETE")
        || !(journal.get("changes") instanceof List<?> rows)) return;
    var changes = new ArrayList<RefChange>();
    for (Object value : rows) {
      if (!(value instanceof Map<?, ?> row)) throw new IOException("fetch journal 無效");
      var d = new DimensionId((String) row.get("dimension"));
      var repo = group.repos().get(d);
      if (repo == null) throw new IOException("fetch 恢復缺少維度");
      String ref = (String) row.get("ref"),
          old = (String) row.get("old"),
          target = (String) row.get("target"),
          current = repo.refs().refsByPrefix(ref).get(ref);
      if (!Objects.equals(current, old) && !Objects.equals(current, target))
        throw new IOException("fetch 恢復遭並行更新阻擋");
      if (!Objects.equals(current, old)) changes.add(new RefChange(d, ref, current, old));
    }
    updateAll(changes);
    journal.put("state", "PARTIAL");
    journal.remove("changes");
    journal.put("recovered", true);
    OperationState.write(path, journal);
  }

  private record RefChange(DimensionId dimension, String ref, String old, String target) {}

  private void updateAll(List<RefChange> changes) throws IOException {
    var done = new ArrayList<RefChange>();
    try {
      for (var c : changes) {
        group.repos().get(c.dimension).refs().updateRef(c.ref, c.old, c.target);
        done.add(c);
      }
    } catch (IOException ex) {
      Collections.reverse(done);
      for (var c : done)
        try {
          group.repos().get(c.dimension).refs().updateRef(c.ref, c.target, c.old);
        } catch (IOException rollback) {
          ex.addSuppressed(rollback);
        }
      throw ex;
    }
  }

  private void validatePublications(Map<DimensionId, SortedMap<String, String>> refs)
      throws IOException {
    var branches = new TreeSet<String>();
    for (var r : refs.values())
      for (String name : r.keySet())
        if (name.startsWith("refs/heads/")) branches.add(name.substring(11));
    for (String branch : branches) {
      Map<String, Object> publication = null;
      boolean has = false, missing = false;
      for (var e : refs.entrySet()) {
        String marker = e.getValue().get("refs/worldgit/publications/" + branch);
        String tip = e.getValue().get("refs/heads/" + branch);
        if (tip == null) throw new IOException("遠端分支缺少維度：" + branch + " " + e.getKey());
        if (marker == null) {
          missing = true;
          continue;
        }
        has = true;
        var repo = group.repos().get(e.getKey());
        var commit = repo.refs().readCommit(marker);
        var blob = TreeEditor.find(repo.objects(), commit.tree(), "publication.yml");
        if (blob == null) throw new IOException("publication 格式無效");
        var map =
            SafeYaml.parse(new String(repo.objects().readBlob(blob.id()), StandardCharsets.UTF_8));
        if (publication != null && !publication.equals(map))
          throw new IOException("遠端 PARTIAL push：publication 不一致；請原推送端重試 push");
        publication = map;
        if (!(map.get("commits") instanceof Map<?, ?> targets)
            || !tip.equals(targets.get(e.getKey().value())))
          throw new IOException("遠端 PARTIAL push：分支未完成；請原推送端重試 push");
      }
      if (has && missing) throw new IOException("遠端 PARTIAL push：缺少 publication；請原推送端重試 push");
      var commits = new TreeMap<DimensionId, RefStore.Commit>();
      for (var e : refs.entrySet()) {
        var repo = group.repos().get(e.getKey());
        commits.put(e.getKey(), repo.refs().readCommit(e.getValue().get("refs/heads/" + branch)));
      }
      RepositoryGroup.validateSnapshot(
          commits, (d, snapshot) -> refs.get(d).get("refs/worldgit/groups/" + snapshot));
    }
  }

  public TransferResult push(
      String name,
      String requestedBranch,
      boolean tags,
      boolean forceLease,
      boolean dryRun,
      CommitMetadata.Identity author)
      throws IOException {
    var selection = OperationState.read(group.root().resolve("clone-selection.yml"));
    if (selection.get("world-dimensions") instanceof List<?> dimensions
        && dimensions.size() > group.repos().size())
      throw new IOException("部分維度 clone 不能發布完整世界；請先 clone 全維度以維持 snapshot group");
    var remote = remote(name);
    String branch = requestedBranch == null ? currentBranch() : requestedBranch;
    JGitStore.validateBranch(branch);
    var tips = new TreeMap<DimensionId, String>();
    for (var e : group.repos().entrySet()) {
      String id = e.getValue().refs().branches().get(branch);
      if (id == null) throw new IOException("某維度缺少分支：" + branch);
      tips.put(e.getKey(), id);
    }
    group.resolve(branch);
    Path journalPath = group.root().resolve("push-state.yml");
    var journal = OperationState.read(journalPath);
    boolean resume = !journal.isEmpty() && !Objects.equals(journal.get("state"), "COMPLETE");
    var targets = new TreeMap<DimensionId, Map<String, String>>();
    var expected = new TreeMap<DimensionId, Map<String, String>>();
    UUID operation =
        resume ? UUID.fromString((String) journal.get("operation")) : UUID.randomUUID();
    if (resume) {
      if (!name.equals(journal.get("remote"))
          || !branch.equals(journal.get("branch"))
          || tags != Boolean.TRUE.equals(journal.get("tags"))
          || forceLease != Boolean.TRUE.equals(journal.get("force-lease")))
        throw new IOException("有未完成 push；請以原 remote/branch/選項重試");
      @SuppressWarnings("unchecked")
      var rows = (Map<String, Map<String, Object>>) journal.get("dimensions");
      for (var e : group.repos().entrySet()) {
        var row = rows.get(e.getKey().value());
        if (row == null || !tips.get(e.getKey()).equals(row.get("tip")))
          throw new IOException("未完成 push 的本機 tip 已改變；先恢復原分支再重試");
        if (!remote.expand(e.getKey()).equals(row.get("url")))
          throw new IOException("未完成 push 的 URL 已改變；請恢復原 remote 設定");
        @SuppressWarnings("unchecked")
        var t = (Map<String, String>) row.get("targets");
        @SuppressWarnings("unchecked")
        var x = (Map<String, String>) row.get("expected");
        targets.put(e.getKey(), new TreeMap<>(t));
        expected.put(e.getKey(), new TreeMap<>(x));
      }
    } else {
      var publicationTips = new TreeMap<String, String>();
      tips.forEach((d, c) -> publicationTips.put(d.value(), c));
      for (var e : group.repos().entrySet())
        try (var t = transport(name, remote, e.getKey())) {
          var advertised = t.advertised(true);
          var r = e.getValue();
          String old = advertised.get("refs/heads/" + branch);
          String tip = tips.get(e.getKey());
          String tracked = null;
          try {
            tracked = r.refs().resolve("refs/remotes/" + name + "/" + branch);
          } catch (IOException absent) {
          }
          if (forceLease && !Objects.equals(old, tracked))
            throw new IOException("force-with-lease 拒絕：遠端已改變；請 fetch 後重新確認");
          if (!forceLease && old != null && !old.equals(tip)) {
            boolean ff = false;
            try {
              ff = r.refs().isAncestor(old, tip);
            } catch (IOException unknown) {
            }
            if (!ff) throw new IOException("非 fast-forward；請先 pull，或確認後使用 --force-with-lease");
          }
          var to = new TreeMap<String, String>();
          to.put("refs/heads/" + branch, tip);
          to.putAll(r.refs().refsByPrefix("refs/worldgit/groups/"));
          for (var pin : to.entrySet())
            if (pin.getKey().startsWith("refs/worldgit/groups/")
                && advertised.containsKey(pin.getKey())
                && !advertised.get(pin.getKey()).equals(pin.getValue()))
              throw new IOException("遠端 snapshot group 已存在且不同");
          if (tags) to.putAll(r.refs().refsByPrefix("refs/tags/"));
          for (var tag : to.entrySet())
            if (tag.getKey().startsWith("refs/tags/")
                && advertised.containsKey(tag.getKey())
                && !advertised.get(tag.getKey()).equals(tag.getValue()))
              throw new IOException("遠端 tag 已存在且不同");
          if (!dryRun) {
            String markerRef = "refs/worldgit/publications/" + branch;
            String oldMarker = advertised.get(markerRef);
            if (oldMarker != null) {
              try {
                r.refs().readCommit(oldMarker);
              } catch (IOException absent) {
                t.fetch(
                    markerRef, "refs/worldgit/incoming/" + name + "/publication-parent/" + branch);
              }
            }
            String text =
                new org.yaml.snakeyaml.Yaml()
                    .dump(
                        Map.of(
                            "operation",
                            operation.toString(),
                            "branch",
                            branch,
                            "commits",
                            publicationTips));
            var tree = new TreeEditor(r.objects(), null);
            tree.putBlob("publication.yml", text.getBytes(StandardCharsets.UTF_8));
            String treeId = tree.write();
            r.objects().flush();
            var meta =
                new CommitMetadata(
                    author,
                    author,
                    "WorldGit publication",
                    Instant.now(),
                    r.refs().readCommit(tip).metadata().mcDataVersion(),
                    e.getKey(),
                    CommitMetadata.Source.CLI,
                    false,
                    UUID.randomUUID(),
                    List.of());
            to.put(
                markerRef,
                r.refs()
                    .createCommit(
                        treeId,
                        oldMarker == null ? List.of() : List.of(oldMarker),
                        meta,
                        Map.of()));
          }
          var ex = new TreeMap<String, String>();
          for (String ref : to.keySet()) ex.put(ref, advertised.getOrDefault(ref, ""));
          targets.put(e.getKey(), to);
          expected.put(e.getKey(), ex);
        }
    }
    var packs = new TreeMap<DimensionId, List<GitTransfer.PackSize>>();
    if (dryRun) return new TransferResult("DRY_RUN", operation.toString(), tips, packs, null);
    if (!resume) {
      var rows = new TreeMap<String, Object>();
      for (var e : targets.entrySet())
        rows.put(
            e.getKey().value(),
            new LinkedHashMap<>(
                Map.of(
                    "tip",
                    tips.get(e.getKey()),
                    "url",
                    remote.expand(e.getKey()),
                    "targets",
                    e.getValue(),
                    "expected",
                    expected.get(e.getKey()),
                    "complete",
                    false)));
      journal =
          new LinkedHashMap<>(
              Map.of(
                  "state",
                  "PUSHING",
                  "operation",
                  operation.toString(),
                  "remote",
                  name,
                  "branch",
                  branch,
                  "tags",
                  tags,
                  "force-lease",
                  forceLease,
                  "dimensions",
                  rows));
      OperationState.write(journalPath, journal);
    }
    try {
      for (var e : group.repos().entrySet())
        try (var t = transport(name, remote, e.getKey())) {
          var advertised = t.advertised(true);
          var to = targets.get(e.getKey());
          var ex = expected.get(e.getKey());
          for (var ref : to.entrySet())
            if (!Objects.equals(advertised.get(ref.getKey()), ref.getValue())
                && !Objects.equals(advertised.getOrDefault(ref.getKey(), ""), ex.get(ref.getKey())))
              throw new IOException("push 恢復遭並行更新阻擋；不會覆寫遠端");
          var meta = e.getValue().refs().readCommit(tips.get(e.getKey())).metadata();
          t.stage(to, operation, meta);
          var pending = new TreeMap<String, String>();
          var leases = new TreeMap<String, String>();
          for (var ref : to.entrySet())
            if (!Objects.equals(advertised.get(ref.getKey()), ref.getValue())) {
              pending.put(ref.getKey(), ref.getValue());
              leases.put(ref.getKey(), ex.get(ref.getKey()));
            }
          observer.publishing(e.getKey());
          if (!pending.isEmpty()) t.publish(pending, leases, true);
          packs.put(e.getKey(), t.sizes());
          @SuppressWarnings("unchecked")
          var rows = (Map<String, Map<String, Object>>) journal.get("dimensions");
          rows.get(e.getKey().value()).put("complete", true);
          OperationState.write(journalPath, journal);
        }
      for (var e : tips.entrySet()) {
        var refs = group.repos().get(e.getKey()).refs();
        String ref = "refs/remotes/" + name + "/" + branch;
        String old = refs.refsByPrefix(ref).get(ref);
        refs.updateRef(ref, old, e.getValue());
      }
      journal.put("state", "COMPLETE");
      journal.remove("error");
      OperationState.write(journalPath, journal);
      return new TransferResult("COMPLETE", operation.toString(), tips, packs, null);
    } catch (Exception ex) {
      journal.put("state", "PARTIAL");
      journal.put("error", ex.getMessage());
      OperationState.write(journalPath, journal);
      return new TransferResult("PARTIAL", operation.toString(), tips, packs, ex.getMessage());
    }
  }

  public SortedMap<DimensionId, String> trackingHeads(String name, String branch)
      throws IOException {
    var result = new TreeMap<DimensionId, String>();
    for (var e : group.repos().entrySet())
      result.put(e.getKey(), e.getValue().refs().resolve("refs/remotes/" + name + "/" + branch));
    return result;
  }

  public List<Tracking> tracking() throws IOException {
    var result = new ArrayList<Tracking>();
    String branch;
    try {
      branch = currentBranch();
    } catch (IOException detached) {
      return result;
    }
    for (String remote : remotes().keySet()) {
      var ours = new HashSet<UUID>();
      var theirs = new HashSet<UUID>();
      boolean estimated = false, missing = false;
      for (var repo : group.repos().values()) {
        String target;
        try {
          target = repo.refs().resolve("refs/remotes/" + remote + "/" + branch);
        } catch (IOException ex) {
          missing = true;
          break;
        }
        estimated |= snapshots(repo.refs(), repo.refs().head(), ours);
        estimated |= snapshots(repo.refs(), target, theirs);
      }
      if (!missing) {
        var a = new HashSet<>(ours);
        a.removeAll(theirs);
        var b = new HashSet<>(theirs);
        b.removeAll(ours);
        result.add(new Tracking(remote, branch, a.size(), b.size(), estimated));
      }
    }
    return result;
  }

  private static boolean snapshots(RefStore refs, String tip, Set<UUID> out) throws IOException {
    var seen = new HashSet<String>();
    var queue = new ArrayDeque<String>();
    queue.add(tip);
    int count = 0;
    while (!queue.isEmpty()) {
      String id = queue.remove();
      if (!seen.add(id)) continue;
      if (++count > 20000) return true;
      var c = refs.readCommit(id);
      out.add(c.metadata().snapshot());
      queue.addAll(c.parents());
    }
    return false;
  }

  @Override
  public void close() throws IOException {
    group.close();
  }
}
