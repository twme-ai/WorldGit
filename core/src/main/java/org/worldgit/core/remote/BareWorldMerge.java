package org.worldgit.core.remote;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.DataVersions;
import org.worldgit.core.config.*;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

/** Hub PR 的裸 repo API。授權、受保護分支政策及 HTTP 層由 Hub 負責。 */
public final class BareWorldMerge implements AutoCloseable {
  public record Candidate(
      String base,
      String ours,
      String theirs,
      String baseTree,
      String oursTree,
      String theirsTree,
      String tree,
      MergeReport report) {}

  public record Preview(
      String oursBranch,
      String theirsBranch,
      SortedMap<DimensionId, Candidate> dimensions,
      int distance,
      boolean fastForward,
      boolean canMerge) {}

  public record Result(
      String state,
      UUID snapshot,
      SortedMap<DimensionId, String> commits,
      SortedMap<DimensionId, MergeReport> reports,
      String error) {}

  private final RepositoryGroup group;
  private final EntitySemantics semantics;

  /** 單維度正式入口；Hub 舊 map 入口保留作過渡。 */
  public BareWorldMerge(Path repository, DimensionId dimension) throws IOException { this(repository, Map.of(dimension, repository)); }

  public BareWorldMerge(Path root, Map<DimensionId, Path> paths) throws IOException {
    this(root, paths, EntitySemantics.OFFLINE);
  }

  public BareWorldMerge(Path root, Map<DimensionId, Path> paths, EntitySemantics semantics)
      throws IOException {
    group = new RepositoryGroup(root, paths);
    this.semantics = semantics;
  }

  public Preview preview(String oursBranch, String theirsBranch, int distance) throws IOException {
    JGitStore.validateBranch(oursBranch);
    JGitStore.validateBranch(theirsBranch);
    var ours = group.resolve(oursBranch);
    var theirs = group.resolve(theirsBranch);
    var candidates = new TreeMap<DimensionId, Candidate>();
    int next = 1;
    boolean ff = true, can = true;
    for (var e : group.repos().entrySet()) {
      var d = e.getKey();
      var repo = e.getValue();
      var o = ours.get(d);
      var t = theirs.get(d);
      DataVersions.requireSame(o.metadata().mcDataVersion(), t.metadata().mcDataVersion());
      String base = MergeBases.unique(repo.refs(), o.id(), t.id());
      String empty = repo.objects().writeTree(List.of());
      String bt = base == null ? empty : repo.refs().readCommit(base).tree();
      if (base != null)
        DataVersions.requireSame(
            o.metadata().mcDataVersion(), repo.refs().readCommit(base).metadata().mcDataVersion());
      requirePacks(repo.objects(), o.tree(), t.tree());
      String br = TreeFilter.rules(repo.objects(), bt),
          or = TreeFilter.rules(repo.objects(), o.tree()),
          tr = TreeFilter.rules(repo.objects(), t.tree());
      String rules = IgnoreRuleMerge.merge(br, or, tr);
      bt = TreeFilter.filter(repo.objects(), bt, rules, semantics);
      String ot = TreeFilter.filter(repo.objects(), o.tree(), rules, semantics),
          tt = TreeFilter.filter(repo.objects(), t.tree(), rules, semantics);
      var merged =
          new MergeEngine(
                  repo.objects(),
                  d,
                  bt,
                  ot,
                  tt,
                  List.of(o.metadata().author().name()),
                  List.of(t.metadata().author().name()))
              .merge(distance);
      var regions = new ArrayList<MergeReport.Region>();
      for (var r : merged.report().regions())
        regions.add(
            new MergeReport.Region(
                next++,
                d,
                r.bounds(),
                r.blockCount(),
                r.oursAuthors(),
                r.theirsAuthors(),
                r.redstone(),
                r.choice(),
                r.resolved(),
                r.atoms()));
      var differences =
          or.equals(tr)
              ? List.<MergeReport.RuleDifference>of()
              : List.of(new MergeReport.RuleDifference(d, br, or, tr, rules));
      var report =
          new MergeReport(
              merged.report().automaticallyMergedSections(),
              regions,
              differences,
              merged.report().updateShapes(),
              merged.report().warnings());
      candidates.put(d, new Candidate(base, o.id(), t.id(), bt, ot, tt, merged.tree(), report));
      ff &= repo.refs().isAncestor(o.id(), t.id());
      can &= regions.isEmpty();
    }
    return new Preview(oursBranch, theirsBranch, candidates, distance, ff, can);
  }

  private static void requirePacks(ObjectStore objects, String a, String b) throws IOException {
    var ae = TreeEditor.find(objects, a, "world-meta/level.nbt");
    var be = TreeEditor.find(objects, b, "world-meta/level.nbt");
    if (ae != null
        && be != null
        && !Nbt.equal(
            Nbt.read(objects.readBlob(ae.id())).compound("DataPacks"),
            Nbt.read(objects.readBlob(be.id())).compound("DataPacks")))
      throw new IOException("DataPacks 不同；不支援自動遷移");
  }

  /** 全部區域必須選 ours/theirs/base。Preview 的 tips 是 lease，變動後拒絕舊選擇。 */
  public Result merge(
      Preview expected,
      Map<Integer, MergeReport.Choice> choices,
      int distance,
      CommitMetadata.Identity author,
      String message,
      boolean dryRun)
      throws IOException {
    return merge(expected, choices, distance, author, message, dryRun, Map.of());
  }

  /** Hub 可附加關聯 trailer（例如 WorldGit-Merge-PR），既有入口完全相容。 */
  public Result merge(Preview expected, Map<Integer, MergeReport.Choice> choices, int distance,
      CommitMetadata.Identity author, String message, boolean dryRun, Map<String, String> trailers)
      throws IOException {
    if (distance != expected.distance()) throw new IOException("合併區域距離已改變；請重新 preview");
    var fresh = preview(expected.oursBranch(), expected.theirsBranch(), distance);
    for (var e : expected.dimensions().entrySet()) {
      var actual = fresh.dimensions().get(e.getKey());
      if (actual == null
          || !actual.ours().equals(e.getValue().ours())
          || !actual.theirs().equals(e.getValue().theirs()))
        throw new IOException("合併 preview 已過期；分支已改變");
    }
    if (!expected.dimensions().keySet().equals(fresh.dimensions().keySet()))
      throw new IOException("合併維度組已改變");
    var valid = new HashSet<Integer>();
    for (var c : fresh.dimensions().values()) for (var r : c.report().regions()) valid.add(r.id());
    if (!valid.containsAll(choices.keySet())) throw new IOException("未知衝突區域");
    var trees = new TreeMap<DimensionId, String>();
    var reports = new TreeMap<DimensionId, MergeReport>();
    boolean remaining = false;
    for (var e : fresh.dimensions().entrySet()) {
      var c = e.getValue();
      var repo = group.repos().get(e.getKey());
      String tree = c.tree();
      var regions = new ArrayList<MergeReport.Region>();
      for (var region : c.report().regions()) {
        var choice = choices.get(region.id());
        if (choice == null) {
          remaining = true;
          regions.add(region);
          continue;
        }
        String source =
            switch (choice) {
              case OURS -> c.oursTree();
              case THEIRS -> c.theirsTree();
              case BASE -> c.baseTree();
              default -> throw new IOException("裸 repo 選擇只接受 ours/theirs/base");
            };
        tree = MergeEngine.select(repo.objects(), tree, source, region);
        regions.add(region.selected(choice, true));
      }
      tree = org.worldgit.core.capture.PlayerTouchedEntities.reconcile(repo.objects(), tree, List.of(c.baseTree(),c.oursTree(),c.theirsTree()));
      trees.put(e.getKey(), tree);
      reports.put(
          e.getKey(),
          new MergeReport(
              c.report().automaticallyMergedSections(),
              regions,
              c.report().ruleDifferences(),
              MergeEngine.updateShapes(
                  repo.objects(), e.getKey(), c.baseTree(), c.oursTree(), c.theirsTree(), tree),
              c.report().warnings()));
    }
    if (remaining) return new Result("CONFLICTS", null, new TreeMap<>(), reports, null);
    if (dryRun) return new Result("DRY_RUN", null, new TreeMap<>(), reports, null);
    Path journalPath = group.root().resolve("bare-merge-state.yml");
    var oldJournal = OperationState.read(journalPath);
    if (!oldJournal.isEmpty() && !Objects.equals(oldJournal.get("state"), "COMPLETE"))
      throw new IOException("有未完成裸 repo 合併；先 recover");
    var before = new TreeMap<DimensionId, Collection<String>>();
    for (var e : group.repos().entrySet()) {
      var refs = e.getValue().refs();
      var ids = new HashSet<String>();
      for (String prefix :
          List.of(
              "refs/heads/",
              "refs/tags/",
              "refs/worldgit/groups/",
              "refs/worldgit/transfers/",
              "refs/worldgit/publications/")) ids.addAll(refs.refsByPrefix(prefix).values());
      before.put(e.getKey(), ids);
    }
    UUID snapshot = UUID.randomUUID();
    var commits = new TreeMap<DimensionId, String>();
    var changes = new ArrayList<Map<String, Object>>();
    for (var e : trees.entrySet()) {
      var repo = group.repos().get(e.getKey());
      var c = fresh.dimensions().get(e.getKey());
      var metadata = repo.refs().readCommit(c.ours()).metadata();
      var m =
          new CommitMetadata(
              author,
              author,
              message,
              Instant.now(),
              metadata.mcDataVersion(),
              e.getKey(),
              CommitMetadata.Source.HUB,
              false,
              snapshot,
              List.of());
      var parents = new ArrayList<String>();
      parents.add(c.ours());
      if (!c.ours().equals(c.theirs())) parents.add(c.theirs());
      var extra = new TreeMap<String, String>(trailers);
      extra.put("WorldGit-Merge-Source", fresh.theirsBranch());
      extra.put("WorldGit-Merge-Ours", c.ours());
      extra.put("WorldGit-Merge-Theirs", c.theirs());
      String id = repo.refs().createCommit(e.getValue(), parents, m, extra);
      commits.put(e.getKey(), id);
      change(changes, e.getKey(), "refs/heads/" + fresh.oursBranch(), c.ours(), id);
      if (group.repos().size() > 1) change(changes, e.getKey(), "refs/worldgit/groups/" + snapshot, null, id);
    }
    var commitMap = new TreeMap<String, String>();
    commits.forEach((d, c) -> commitMap.put(d.value(), c));
    if (group.repos().size() > 1) for (var e : group.repos().entrySet()) {
      var tree = new TreeEditor(e.getValue().objects(), null);
      tree.putBlob(
          "publication.yml",
          new org.yaml.snakeyaml.Yaml()
              .dump(
                  Map.of(
                      "operation",
                      snapshot.toString(),
                      "branch",
                      fresh.oursBranch(),
                      "commits",
                      commitMap))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var m = e.getValue().refs().readCommit(commits.get(e.getKey())).metadata();
      var pm =
          new CommitMetadata(
              author,
              author,
              "WorldGit publication",
              m.time(),
              m.mcDataVersion(),
              e.getKey(),
              m.source(),
              false,
              UUID.randomUUID(),
              List.of());
      String ref = "refs/worldgit/publications/" + fresh.oursBranch();
      String old = e.getValue().refs().refsByPrefix(ref).get(ref);
      String id =
          e.getValue()
              .refs()
              .createCommit(tree.write(), old == null ? List.of() : List.of(old), pm, Map.of());
      change(changes, e.getKey(), ref, old, id);
    }
    for (var e : group.repos().entrySet()) {
      var targets = new TreeMap<String, String>();
      for (var c : changes)
        if (c.get("dimension").equals(e.getKey().value()))
          targets.put((String) c.get("ref"), (String) c.get("target"));
      try (var transfer =
          new GitTransfer(
              e.getValue().directory(),
              e.getValue().directory().toUri().toString(),
              new Credentials.Secret(Credentials.Mode.BASIC, "anonymous", ""))) {
        transfer.stageLocal(
            targets,
            before.get(e.getKey()),
            e.getValue().refs().readCommit(commits.get(e.getKey())).metadata());
      }
    }
    var journal = new LinkedHashMap<String, Object>();
    journal.put("state", "APPLYING");
    journal.put("changes", changes);
    OperationState.write(journalPath, journal);
    try {
      for (var c : changes)
        group
            .repos()
            .get(new DimensionId((String) c.get("dimension")))
            .refs()
            .updateRef((String) c.get("ref"), (String) c.get("old"), (String) c.get("target"));
      journal.put("state", "COMPLETE");
      OperationState.write(journalPath, journal);
      return new Result("COMPLETE", snapshot, commits, reports, null);
    } catch (Exception e) {
      journal.put("state", "PARTIAL");
      OperationState.write(journalPath, journal);
      return new Result("PARTIAL", snapshot, commits, reports, e.getMessage());
    }
  }

  private static void change(
      List<Map<String, Object>> changes, DimensionId d, String ref, String old, String target) {
    var row = new LinkedHashMap<String, Object>();
    row.put("dimension", d.value());
    row.put("ref", ref);
    row.put("old", old);
    row.put("target", target);
    changes.add(row);
  }

  /** journal 中每個 ref 只能是 old 或 target；不覆寫第三方更新。恢復至合併前。 */
  public void recover() throws IOException {
    Path path = group.root().resolve("bare-merge-state.yml");
    var journal = OperationState.read(path);
    if (journal.isEmpty() || Objects.equals(journal.get("state"), "COMPLETE")) return;
    @SuppressWarnings("unchecked")
    var changes = (List<Map<String, Object>>) journal.get("changes");
    for (var c : changes) {
      var refs = group.repos().get(new DimensionId((String) c.get("dimension"))).refs();
      String current = refs.refsByPrefix((String) c.get("ref")).get(c.get("ref"));
      if (!Objects.equals(current, c.get("old")) && !Objects.equals(current, c.get("target")))
        throw new IOException("恢復遭並行 ref 更新阻擋");
    }
    var reverse = new ArrayList<>(changes);
    Collections.reverse(reverse);
    for (var c : reverse) {
      var refs = group.repos().get(new DimensionId((String) c.get("dimension"))).refs();
      String ref = (String) c.get("ref"), current = refs.refsByPrefix(ref).get(ref);
      if (!Objects.equals(current, c.get("old")))
        refs.updateRef(ref, current, (String) c.get("old"));
    }
    journal.put("state", "COMPLETE");
    journal.put("recovered", true);
    OperationState.write(path, journal);
  }

  @Override
  public void close() throws IOException {
    group.close();
  }
}
