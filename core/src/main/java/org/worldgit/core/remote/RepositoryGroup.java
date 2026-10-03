package org.worldgit.core.remote;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

/** 沒有世界資料夾也可使用的維度組鎖、revision 與 tag 入口。 */
public final class RepositoryGroup implements AutoCloseable {
  private final Path root;
  private final RepoLock lock;
  private final SortedMap<DimensionId, DimensionRepository> repos = new TreeMap<>();

  public RepositoryGroup(Path root, Map<DimensionId, Path> paths) throws IOException {
    this.root = root;
    lock = RepoLock.acquire(root);
    try {
      for (var e : new TreeMap<>(paths).entrySet())
        repos.put(e.getKey(), new DimensionRepository(e.getValue(), e.getKey(), false));
    } catch (Exception e) {
      close();
      throw e;
    }
    if (repos.isEmpty()) {
      close();
      throw new IOException("世界沒有 repo");
    }
    try {
      recoverTags();
    } catch (IOException ex) {
      close();
      throw ex;
    }
  }

  public Path root() {
    return root;
  }

  public SortedMap<DimensionId, DimensionRepository> repos() {
    return Collections.unmodifiableSortedMap(repos);
  }

  public SortedMap<DimensionId, String> heads() throws IOException {
    var r = new TreeMap<DimensionId, String>();
    for (var e : repos.entrySet()) r.put(e.getKey(), e.getValue().refs().head());
    return r;
  }

  public SortedMap<DimensionId, RefStore.Commit> resolve(String revision) throws IOException {
    var result = new TreeMap<DimensionId, RefStore.Commit>();
    boolean branch =
        repos.values().stream()
            .anyMatch(
                r -> {
                  try {
                    return r.refs().branches().containsKey(revision);
                  } catch (IOException e) {
                    return false;
                  }
                });
    if (branch
        || revision.equals("HEAD")
        || revision.startsWith("refs/remotes/")
        || tags().contains(revision)) {
      for (var e : repos.entrySet())
        result.put(
            e.getKey(), e.getValue().refs().readCommit(e.getValue().refs().resolve(revision)));
      validate(result);
      return result;
    }
    var anchor =
        repos.containsKey(DimensionId.OVERWORLD) ? DimensionId.OVERWORLD : repos.firstKey();
    var ref = repos.get(anchor).refs();
    RefStore.Commit c;
    try {
      c = ref.readCommit(ref.resolve(revision));
    } catch (IOException missing) {
      c = null;
      for (var entry : repos.entrySet())
        try {
          var other = entry.getValue().refs();
          c = other.readCommit(other.resolve(revision));
          anchor = entry.getKey();
          break;
        } catch (IOException absent) {
        }
      if (c == null) throw missing;
    }
    for (var e : repos.entrySet()) {
      var other = e.getValue().refs();
      String id =
          e.getKey().equals(anchor)
              ? c.id()
              : other.resolve("refs/worldgit/groups/" + c.metadata().snapshot());
      result.put(e.getKey(), other.readCommit(id));
    }
    validate(result);
    return result;
  }

  public void validate(Map<DimensionId, RefStore.Commit> commits) throws IOException {
    if (!commits.keySet().equals(repos.keySet())) throw new IOException("快照必須涵蓋已開啟的維度組");
    validateSnapshot(commits, this::groupCommit);
  }

  @FunctionalInterface
  public interface GroupPin {
    String resolve(DimensionId dimension, UUID snapshot) throws IOException;
  }

  /** carrier 可以是任何被修改的維度，主世界未改時不能只用其舊 UUID 配對。 */
  public static void validateSnapshot(Map<DimensionId, RefStore.Commit> commits, GroupPin pins)
      throws IOException {
    if (commits.isEmpty()) throw new IOException("快照清單不可空");
    var anchor = commits.values().iterator().next();
    var candidates = new HashSet<UUID>();
    for (var e : commits.entrySet()) {
      var c = e.getValue();
      if (!c.metadata().dimension().equals(e.getKey())
          || c.metadata().mcDataVersion() != anchor.metadata().mcDataVersion())
        throw new IOException("維度或 DataVersion 不一致");
      candidates.add(c.metadata().snapshot());
    }
    if (candidates.size() == 1) return;
    for (UUID snapshot : candidates) {
      boolean coherent = true;
      for (var e : commits.entrySet())
        try {
          coherent &= e.getValue().id().equals(pins.resolve(e.getKey(), snapshot));
        } catch (IOException absent) {
          coherent = false;
        }
      if (coherent) return;
    }
    throw new IOException("分支不能依 snapshot group 配對；請先完成全維度 commit/push");
  }

  private String groupCommit(DimensionId dimension, UUID snapshot) throws IOException {
    return repos.get(dimension).refs().resolve("refs/worldgit/groups/" + snapshot);
  }

  public SortedSet<String> tags() throws IOException {
    var names = new TreeSet<String>();
    for (var repo : repos.values())
      for (String ref : repo.refs().refsByPrefix("refs/tags/").keySet())
        names.add(ref.substring(10));
    return names;
  }

  public void tag(
      String name,
      String revision,
      String message,
      CommitMetadata.Identity author,
      boolean delete,
      boolean dryRun)
      throws IOException {
    JGitStore.validateBranch(name);
    var targets = delete ? null : resolve(revision == null ? "HEAD" : revision);
    for (var r : repos.values()) {
      boolean exists = r.refs().refsByPrefix("refs/tags/").containsKey("refs/tags/" + name);
      if (exists != delete) throw new IOException(delete ? "某維度缺少 tag" : "tag 已存在");
    }
    if (dryRun) return;
    var changes = new ArrayList<Map<String, Object>>();
    String ref = "refs/tags/" + name;
    for (var e : repos.entrySet()) {
      var refs = e.getValue().refs();
      String old = refs.refsByPrefix("refs/tags/").get(ref);
      String target =
          delete ? null : refs.tagObject(name, targets.get(e.getKey()).id(), message, author);
      var row = new LinkedHashMap<String, Object>();
      row.put("dimension", e.getKey().value());
      row.put("ref", ref);
      row.put("old", old);
      row.put("target", target);
      changes.add(row);
    }
    var journal = new LinkedHashMap<String, Object>();
    journal.put("state", "PUBLISHING");
    journal.put("changes", changes);
    OperationState.write(root.resolve("tag-state.yml"), journal);
    try {
      for (var c : changes)
        repos
            .get(new DimensionId((String) c.get("dimension")))
            .refs()
            .updateRef(ref, (String) c.get("old"), (String) c.get("target"));
      journal.put("state", "COMPLETE");
      OperationState.write(root.resolve("tag-state.yml"), journal);
    } catch (IOException ex) {
      try {
        recoverTags();
      } catch (IOException rollback) {
        ex.addSuppressed(rollback);
      }
      throw ex;
    }
  }

  private void recoverTags() throws IOException {
    Path path = root.resolve("tag-state.yml");
    var journal = OperationState.read(path);
    if (journal.isEmpty() || "COMPLETE".equals(journal.get("state"))) return;
    if (!(journal.get("changes") instanceof List<?> changes))
      throw new IOException("tag journal 無效");
    // 先檢查全組 lease，第三方改動時不能覆寫。
    for (Object item : changes) {
      if (!(item instanceof Map<?, ?> c)) throw new IOException("tag journal 無效");
      var repo = repos.get(new DimensionId((String) c.get("dimension")));
      if (repo == null) throw new IOException("tag 恢復缺少維度");
      String current = repo.refs().refsByPrefix("refs/tags/").get((String) c.get("ref"));
      if (!Objects.equals(current, c.get("old")) && !Objects.equals(current, c.get("target")))
        throw new IOException("tag 恢復遭並行更新阻擋");
    }
    for (Object item : changes) {
      var c = (Map<?, ?>) item;
      var refs = repos.get(new DimensionId((String) c.get("dimension"))).refs();
      String ref = (String) c.get("ref");
      String current = refs.refsByPrefix("refs/tags/").get(ref);
      if (!Objects.equals(current, c.get("old")))
        refs.updateRef(ref, current, (String) c.get("old"));
    }
    journal.put("state", "COMPLETE");
    journal.put("recovered", true);
    OperationState.write(path, journal);
  }

  @Override
  public void close() throws IOException {
    IOException error = null;
    for (var r : repos.values())
      try {
        r.close();
      } catch (IOException e) {
        error = e;
      }
    lock.close();
    if (error != null) throw error;
  }
}
