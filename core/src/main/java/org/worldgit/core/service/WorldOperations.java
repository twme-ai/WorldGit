package org.worldgit.core.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.capture.SnapshotSource;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.store.*;

/** 一組維度的離線復原入口。全組 repo/session 鎖、預檢、journal、寫回、驗證、HEAD barrier。 */
public final class WorldOperations implements AutoCloseable {
  public enum State {
    COMPLETE,
    DRY_RUN,
    PARTIAL
  }

  public record Result(
      State state, SortedMap<DimensionId, ApplyPlan.Stats> dimensions, String error) {
    public Result {
      dimensions = Collections.unmodifiableSortedMap(new TreeMap<>(dimensions));
    }

    public boolean success() {
      return state != State.PARTIAL;
    }
  }

  public record Branch(
      String name, boolean current, SortedMap<DimensionId, String> commits, boolean consistent) {}

  public record Stash(
      String id,
      String time,
      String message,
      SortedMap<DimensionId, String> commits,
      SortedMap<DimensionId, String> bases) {}

  @FunctionalInterface
  public interface Writer {
    void apply(ApplyPlan plan, WorldSessionLock lock) throws IOException;
  }

  /**
   * 線上平台持有遊戲的 session；全組套用必須有跨維度 UUID removal barrier。 呼叫端在建構之前鎖定編輯並 flush，直到 close 之後才解鎖。禁止在遊戲
   * owner 執行緒呼叫。
   */
  public interface LiveAccess {
    SnapshotSource source(WorldLayout.Dimension dimension) throws IOException;

    void validate(ApplyPlan plan) throws IOException;

    void applyAll(Collection<ApplyPlan> plans) throws IOException;

    default EntityTagRegistry.PackResolver packs() {
      return null;
    }
  }

  public static WorldOperations live(WorldLayout layout, LiveAccess access) throws IOException {
    return new WorldOperations(layout, null, Objects.requireNonNull(access));
  }

  private record Prepared(
      SortedMap<DimensionId, RefStore.Commit> commits, SortedMap<DimensionId, ApplyPlan> plans) {}

  private final WorldLayout layout;
  private final WorldRepositories worlds;
  private final SortedMap<DimensionId, DimensionRepository> repos = new TreeMap<>();
  private final RepoLock groupLock;
  private final WorldSessionLock session;
  private final OfflineApplier applier;
  private final Writer writer;
  private final boolean groupedWriter;
  private final LiveAccess live;
  private final double tolerance;

  public WorldOperations(WorldLayout layout) throws IOException {
    this(layout, null);
  }

  /** writer 注入用於平台以外的離線 adapter／故障驗證；預設正式 Anvil writer。 */
  public WorldOperations(WorldLayout layout, Writer writer) throws IOException {
    this(layout, writer, null);
  }

  private WorldOperations(WorldLayout layout, Writer writer, LiveAccess live) throws IOException {
    this.layout = layout;
    worlds = new WorldRepositories(layout);
    applier = new OfflineApplier(layout);
    this.live = live;
    this.writer = writer == null ? applier::apply : writer;
    groupedWriter = writer == null;
    tolerance = WorldGitConfig.readLocal(worlds.root().resolve("worldgit.yml")).entityTolerance();
    groupLock = RepoLock.acquire(worlds.root());
    WorldSessionLock acquired = null;
    try {
      if (live == null) acquired = WorldSessionLock.acquire(layout);
      for (var entry : worlds.tracked().entrySet())
        repos.put(entry.getKey(), new DimensionRepository(entry.getValue(), entry.getKey(), false));
      if (repos.isEmpty()) throw new IOException("世界尚未 init");
      session = acquired;
    } catch (Exception ex) {
      for (var repo : repos.values()) repo.close();
      if (acquired != null) acquired.close();
      groupLock.close();
      throw ex;
    }
  }

  public Map<String, Object> journal() throws IOException {
    return OperationState.read(worlds.root().resolve("apply-state.yml"));
  }

  private void requireComplete() throws IOException {
    requireNotMerging();
    if (OperationState.partial(worlds.root()))
      throw new IOException("世界為 PARTIAL；請用 switch --force 或 reset --hard 全範圍重新套用以恢復。");
  }

  private SortedMap<DimensionId, DimensionRepository> selected(DimensionId dimension)
      throws IOException {
    if (dimension == null) return repos;
    var repo = repos.get(dimension);
    if (repo == null) throw new IOException("維度尚未 init：" + dimension);
    var result = new TreeMap<DimensionId, DimensionRepository>();
    result.put(dimension, repo);
    return result;
  }

  private SortedMap<DimensionId, RefStore.Commit> resolve(String revision) throws IOException {
    // 分支名稱在全維度同步。單一 hash／HEAD~n 由入口維度的 snapshot 及祖先配對不變維度。
    boolean branch = false;
    for (var repo : repos.values()) branch |= repo.refs().branches().containsKey(revision);
    var result = new TreeMap<DimensionId, RefStore.Commit>();
    if (branch || revision.equals("HEAD")) {
      for (var entry : repos.entrySet()) {
        String id =
            branch
                ? entry.getValue().refs().branches().get(revision)
                : entry.getValue().refs().head();
        if (id == null) throw new IOException("維度缺少目標分支／HEAD：" + entry.getKey());
        result.put(entry.getKey(), entry.getValue().refs().readCommit(id));
      }
      return result;
    }
    DimensionId anchor =
        repos.containsKey(DimensionId.OVERWORLD) ? DimensionId.OVERWORLD : repos.firstKey();
    RefStore.Commit commit;
    try {
      var refs = repos.get(anchor).refs();
      commit = refs.readCommit(refs.resolve(revision));
    } catch (IOException ex) {
      // 接受另一維度的 commit hash 作為 snapshot 入口。
      commit = null;
      for (var entry : repos.entrySet())
        try {
          commit = entry.getValue().refs().readCommit(entry.getValue().refs().resolve(revision));
          anchor = entry.getKey();
          break;
        } catch (IOException ignored) {
        }
      if (commit == null) throw ex;
    }
    var snapshots = new ArrayList<UUID>();
    var refs = repos.get(anchor).refs();
    var cursor = commit;
    while (true) {
      snapshots.add(cursor.metadata().snapshot());
      if (cursor.parents().isEmpty()) break;
      cursor = refs.readCommit(cursor.parents().getFirst());
    }
    for (var entry : repos.entrySet()) {
      if (entry.getKey().equals(anchor)) {
        result.put(anchor, commit);
        continue;
      }
      try {
        var other = entry.getValue().refs();
        result.put(
            entry.getKey(),
            other.readCommit(
                other.resolve("refs/worldgit/groups/" + commit.metadata().snapshot())));
        continue;
      } catch (IOException missingGroup) {
        /* Phase 1 repo：退回 anchor 的 first-parent snapshot 配對。 */
      }
      var bySnapshot = new HashMap<UUID, RefStore.Commit>();
      for (var candidate : entry.getValue().refs().allCommits()) {
        var existing = bySnapshot.putIfAbsent(candidate.metadata().snapshot(), candidate);
        if (existing != null && !existing.id().equals(candidate.id()))
          throw new IOException("snapshot 對應多個 commit：" + candidate.metadata().snapshot());
      }
      RefStore.Commit chosen = null;
      for (UUID snapshot : snapshots)
        if (bySnapshot.containsKey(snapshot)) {
          chosen = bySnapshot.get(snapshot);
          break;
        }
      if (chosen == null)
        throw new IOException("無法將 snapshot 配對到維度：" + entry.getKey() + "；請改用同步分支名稱。");
      result.put(entry.getKey(), chosen);
    }
    return result;
  }

  private ApplyPlanner.Options options(
      DimensionRepository repo, RefStore.Commit target, boolean meta, boolean delete)
      throws IOException {
    DataVersions.requireSame(target.metadata().mcDataVersion(), layout.dataVersion());
    if (!target.metadata().dimension().equals(repo.dimension()))
      throw new IOException("目標 commit 的維度不符");
    String configPath =
        repo.dimension().equals(DimensionId.OVERWORLD) ? "world-meta/worldgit.yml" : "worldgit.yml";
    var targetConfig = TreeEditor.find(repo.objects(), target.tree(), configPath);
    if (targetConfig != null)
      WorldGitConfig.readRepo(
          new String(repo.objects().readBlob(targetConfig.id()), StandardCharsets.UTF_8),
          configPath);
    var ruleEntry = TreeEditor.find(repo.objects(), target.tree(), ".wgignore");
    String targetRules =
        ruleEntry == null
            ? ""
            : new String(repo.objects().readBlob(ruleEntry.id()), StandardCharsets.UTF_8);
    String currentRules =
        Files.exists(repo.ignorePath()) ? Files.readString(repo.ignorePath()) : "";
    if (!targetRules.equals(currentRules))
      throw new IOException("目標與工作區的 .wgignore 不同；請先調整成一致，避免把未追蹤內容當空氣覆蓋。");
    var rules = IgnoreRules.parse(currentRules);
    if (delete && rules.hasAreas()) throw new IOException("有 area 排除規則時不可刪除 untracked chunk");
    return new ApplyPlanner.Options(
        rules,
        rules,
        EntityTagRegistry.load(
            layout.world(), layout.dataVersion(), live == null ? null : live.packs()),
        tolerance,
        delete,
        meta,
        target.metadata().mcDataVersion());
  }

  private String capture(DimensionRepository repo) throws IOException {
    try (var source =
        live == null
            ? new OfflineSnapshotSource(layout, layout.dimensions().get(repo.dimension()))
            : live.source(layout.dimensions().get(repo.dimension()))) {
      return repo.workingTree(source, worlds.manifest(), tolerance);
    }
  }

  private Prepared prepare(
      SortedMap<DimensionId, RefStore.Commit> targets,
      DimensionId dimension,
      Scope scope,
      boolean meta,
      boolean delete)
      throws IOException {
    var plans = new TreeMap<DimensionId, ApplyPlan>();
    var commits = new TreeMap<DimensionId, RefStore.Commit>();
    for (var entry : selected(dimension).entrySet()) {
      if (!targets.containsKey(entry.getKey())) continue;
      var target = targets.get(entry.getKey());
      var options =
          options(
              entry.getValue(),
              target,
              meta && entry.getKey().equals(DimensionId.OVERWORLD),
              delete);
      var plan =
          ApplyPlanner.plan(
              entry.getValue().objects(),
              entry.getKey(),
              capture(entry.getValue()),
              target.tree(),
              scope,
              options);
      applier.validateMetadata(plan);
      if (live != null) live.validate(plan);
      plans.put(entry.getKey(), plan);
      commits.put(entry.getKey(), target);
    }
    return new Prepared(commits, plans);
  }

  private static SortedMap<DimensionId, ApplyPlan.Stats> stats(Prepared prepared) {
    var result = new TreeMap<DimensionId, ApplyPlan.Stats>();
    prepared.plans.forEach((id, p) -> result.put(id, p.stats()));
    return result;
  }

  public SortedMap<DimensionId, ApplyPlan> plan(
      String revision, DimensionId dimension, Scope scope, boolean metadata, boolean delete)
      throws IOException {
    return Collections.unmodifiableSortedMap(
        prepare(resolve(revision), dimension, scope, metadata, delete).plans);
  }

  public Result verify(String revision, DimensionId dimension, Scope scope, boolean metadata)
      throws IOException {
    var prepared = prepare(resolve(revision), dimension, scope, metadata, false);
    boolean zero = prepared.plans.values().stream().allMatch(ApplyPlan::empty);
    return new Result(
        zero ? State.COMPLETE : State.PARTIAL, stats(prepared), zero ? null : "世界與目標仍有差異");
  }

  public Result restore(
      String revision, DimensionId dimension, Scope scope, boolean dryRun, boolean delete)
      throws IOException {
    requireComplete();
    return execute(
        "restore",
        prepare(resolve(revision), dimension, scope, scope.kind() == Scope.Kind.ALL, delete),
        null,
        false,
        dryRun);
  }

  public Result switchTo(
      String revision, boolean stash, boolean force, boolean dryRun, boolean delete)
      throws IOException {
    if (stash && force) throw new IOException("--stash 與 --force 不可同時使用");
    requireNotMerging();
    if (!force) requireComplete();
    var prepared = prepare(resolve(revision), null, Scope.all(), true, delete);
    if (dirty() && !force && !stash)
      throw new IOException("工作區有未提交變動；請 commit、stash push、switch --stash 或 --force。");
    if (stash && !dryRun && dirty()) saveStash("switch 前自動 stash");
    boolean branch =
        repos.values().stream()
            .allMatch(
                r -> {
                  try {
                    return r.refs().branches().containsKey(revision);
                  } catch (IOException e) {
                    return false;
                  }
                });
    return execute("switch", prepared, branch ? revision : null, true, dryRun);
  }

  public Result resetHard(String revision, boolean force, boolean dryRun) throws IOException {
    requireNotMerging();
    if (revision != null && !force)
      throw new IOException("reset --hard <commit> 會改寫歷史，必須加 --force；不可用於已 push 的歷史。");
    return execute(
        "reset",
        prepare(resolve(revision == null ? "HEAD" : revision), null, Scope.all(), true, false),
        null,
        revision != null,
        dryRun);
  }

  private boolean dirty() throws IOException {
    return dirty(false);
  }

  private boolean dirty(boolean includeUntracked) throws IOException {
    for (var repo : repos.values()) {
      String head = repo.refs().head();
      if (head == null) return true;
      String tree = capture(repo);
      var editor = new TreeEditor(repo.objects(), tree);
      // switch 保留且標記的 untracked chunk 不阻擋後續切換；explicit commit 會重新追蹤它們。
      if (!includeUntracked)
        for (var pos : untracked(repo.directory()))
          if (TreeEditor.find(repo.objects(), repo.refs().readCommit(head).tree(), pos.treePath())
              == null) editor.remove(pos.treePath());
      String tracked = editor.write();
      repo.objects().flush();
      if (!new DiffEngine(repo.objects())
          .compare(
              repo.dimension(),
              repo.refs().readCommit(head).tree(),
              tracked,
              tolerance,
              DiffEngine.Detail.SUMMARY)
          .empty()) return true;
    }
    return false;
  }

  private Result execute(
      String mode, Prepared prepared, String branch, boolean moveHead, boolean dryRun)
      throws IOException {
    if (dryRun) return new Result(State.DRY_RUN, stats(prepared), null);
    var old = new TreeMap<DimensionId, RefStore.Head>();
    var journal = new LinkedHashMap<String, Object>();
    journal.put("version", 1);
    journal.put("operation", UUID.randomUUID().toString());
    journal.put("mode", mode);
    journal.put("state", "APPLYING");
    journal.put("time", Instant.now().toString());
    var rows = new TreeMap<String, Object>();
    for (var entry : prepared.commits.entrySet()) {
      var repo = repos.get(entry.getKey());
      var head = repo.refs().headState();
      old.put(entry.getKey(), head);
      var row = new LinkedHashMap<String, Object>();
      row.put("from", head.commit());
      row.put("from-branch", head.branch());
      row.put("to", entry.getValue().id());
      row.put("scope", prepared.plans.get(entry.getKey()).scope().toString());
      row.put("applied", false);
      rows.put(entry.getKey().value(), row);
      // pin 原始與目標 commit；journal 可供重套，即使原分支後來移動也不會 GC 掉。
      String pin = "refs/worldgit/operations/" + journal.get("operation");
      if (head.commit() != null) repo.refs().updateRef(pin + "/from", null, head.commit());
      repo.refs().updateRef(pin + "/to", null, entry.getValue().id());
    }
    journal.put("dimensions", rows);
    writeJournal(journal);
    try {
      if (mode.startsWith("merge-"))
        for (var entry : prepared.commits.entrySet()) {
          var repo = repos.get(entry.getKey());
          WorldGitConfig.write(
              repo.ignorePath(), TreeFilter.rules(repo.objects(), entry.getValue().tree()));
          restoreConfig(repo, entry.getValue());
        }
      if (live != null) live.applyAll(prepared.plans.values());
      else if (groupedWriter) applier.applyAll(prepared.plans.values(), session);
      for (var entry : prepared.plans.entrySet()) {
        if (live == null && !groupedWriter) writer.apply(entry.getValue(), session);
        repos.get(entry.getKey()).invalidateIndex();
        @SuppressWarnings("unchecked")
        var row = (Map<String, Object>) rows.get(entry.getKey().value());
        row.put("applied", true);
        writeJournal(journal);
      }
      var checked =
          prepare(
              prepared.commits,
              null,
              prepared.plans.get(prepared.plans.firstKey()).scope(),
              prepared.plans.values().stream().anyMatch(p -> !p.worldMeta().isEmpty()),
              false);
      for (var entry : checked.plans.entrySet())
        if (!entry.getValue().empty())
          throw new IOException("套用驗證失敗：" + entry.getKey() + " " + entry.getValue().stats());
      if (moveHead)
        for (var entry : prepared.commits.entrySet()) {
          var repo = repos.get(entry.getKey());
          var prior = old.get(entry.getKey());
          if (mode.equals("reset") && prior.branch() != null) {
            repo.refs()
                .updateRef("refs/heads/" + prior.branch(), prior.commit(), entry.getValue().id());
          } else repo.refs().checkout(prior, new RefStore.Head(entry.getValue().id(), branch));
        }
      for (var entry : prepared.plans.entrySet()) {
        var repo = repos.get(entry.getKey());
        var kept = new TreeSet<>(entry.getValue().untrackedKept());
        for (var pos : untracked(repo.directory()))
          if (!entry.getValue().scope().touchesChunk(pos)) kept.add(pos);
        saveUntracked(repo.directory(), new ArrayList<>(kept));
        if (moveHead) restoreConfig(repo, prepared.commits.get(entry.getKey()));
        repo.invalidateIndex();
      }
      journal.put("state", "COMPLETE");
      writeJournal(journal);
      return new Result(State.COMPLETE, stats(prepared), null);
    } catch (Exception ex) {
      var errors = new ArrayList<String>();
      errors.add(message(ex));
      for (var entry : old.entrySet())
        try {
          var refs = repos.get(entry.getKey()).refs();
          var current = refs.headState();
          var prior = entry.getValue();
          if (prior.branch() != null
              && !Objects.equals(refs.branches().get(prior.branch()), prior.commit()))
            refs.updateRef(
                "refs/heads/" + prior.branch(),
                refs.branches().get(prior.branch()),
                prior.commit());
          if (!refs.headState().equals(prior)) refs.checkout(refs.headState(), prior);
        } catch (Exception rollback) {
          errors.add("HEAD 回復失敗：" + entry.getKey() + " " + message(rollback));
        }
      journal.put("state", "PARTIAL");
      journal.put("error", String.join("；", errors));
      writeJournal(journal);
      return new Result(State.PARTIAL, stats(prepared), String.join("；", errors));
    }
  }

  private void restoreConfig(DimensionRepository repo, RefStore.Commit target) throws IOException {
    String path =
        repo.dimension().equals(DimensionId.OVERWORLD) ? "world-meta/worldgit.yml" : "worldgit.yml";
    var config = TreeEditor.find(repo.objects(), target.tree(), path);
    if (config != null) {
      var bytes = repo.objects().readBlob(config.id());
      WorldGitConfig.readRepo(new String(bytes, StandardCharsets.UTF_8), path);
      RegionFile.atomicWrite(repo.configPath(), bytes);
    }
  }

  private void writeJournal(Map<String, Object> journal) throws IOException {
    OperationState.write(worlds.root().resolve("apply-state.yml"), journal);
  }

  private static String message(Exception ex) {
    return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
  }

  public List<Branch> branches() throws IOException {
    var names = new TreeSet<String>();
    for (var repo : repos.values()) names.addAll(repo.refs().branches().keySet());
    var result = new ArrayList<Branch>();
    for (String name : names) {
      var commits = new TreeMap<DimensionId, String>();
      boolean current = true;
      for (var entry : repos.entrySet()) {
        String id = entry.getValue().refs().branches().get(name);
        if (id != null) commits.put(entry.getKey(), id);
        current &= name.equals(entry.getValue().refs().headState().branch());
      }
      result.add(new Branch(name, current, commits, commits.size() == repos.size()));
    }
    return result;
  }

  public void createBranch(String name, String start) throws IOException {
    requireComplete();
    JGitStore.validateBranch(name);
    var targets = resolve(start == null ? "HEAD" : start);
    for (var repo : repos.values())
      if (repo.refs().branches().containsKey(name)) throw new IOException("分支已存在：" + name);
    mutateBranch(name, targets, false);
  }

  public void deleteBranch(String name) throws IOException {
    requireComplete();
    JGitStore.validateBranch(name);
    var targets = new TreeMap<DimensionId, RefStore.Commit>();
    for (var entry : repos.entrySet()) {
      var refs = entry.getValue().refs();
      var head = refs.headState();
      if (name.equals(head.branch())) throw new IOException("不可刪除目前分支：" + name);
      String id = refs.branches().get(name);
      if (id == null) throw new IOException("維度缺少分支：" + entry.getKey());
      if (!refs.isAncestor(id, head.commit())) throw new IOException("分支尚未合併：" + name);
      targets.put(entry.getKey(), refs.readCommit(id));
    }
    mutateBranch(name, targets, true);
  }

  private void mutateBranch(
      String name, SortedMap<DimensionId, RefStore.Commit> targets, boolean delete)
      throws IOException {
    var completed = new ArrayList<DimensionId>();
    try {
      for (var entry : targets.entrySet()) {
        repos
            .get(entry.getKey())
            .refs()
            .updateRef(
                "refs/heads/" + name,
                delete ? entry.getValue().id() : null,
                delete ? null : entry.getValue().id());
        completed.add(entry.getKey());
      }
    } catch (IOException ex) {
      for (var id : completed)
        try {
          repos
              .get(id)
              .refs()
              .updateRef(
                  "refs/heads/" + name,
                  delete ? null : targets.get(id).id(),
                  delete ? targets.get(id).id() : null);
        } catch (IOException rollback) {
          ex.addSuppressed(rollback);
        }
      if (ex.getSuppressed().length > 0) {
        var state = new LinkedHashMap<String, Object>();
        state.put("state", "PARTIAL");
        state.put("mode", "branch");
        state.put("error", message(ex));
        writeJournal(state);
      }
      throw ex;
    }
  }

  @SuppressWarnings("unchecked")
  public List<Stash> stashes() throws IOException {
    var state = OperationState.read(worlds.root().resolve("stash.yml"));
    var result = new ArrayList<Stash>();
    for (var value : (List<Map<String, Object>>) state.getOrDefault("entries", List.of())) {
      var commits = new TreeMap<DimensionId, String>();
      var bases = new TreeMap<DimensionId, String>();
      ((Map<String, String>) value.get("commits"))
          .forEach((k, v) -> commits.put(new DimensionId(k), v));
      ((Map<String, String>) value.get("bases"))
          .forEach((k, v) -> bases.put(new DimensionId(k), v));
      result.add(
          new Stash(
              (String) value.get("id"),
              (String) value.get("time"),
              (String) value.get("message"),
              commits,
              bases));
    }
    return result;
  }

  private void writeStashes(List<Stash> stashes) throws IOException {
    var entries = new ArrayList<Map<String, Object>>();
    for (var stash : stashes) {
      var commits = new TreeMap<String, String>();
      var bases = new TreeMap<String, String>();
      stash.commits.forEach((k, v) -> commits.put(k.value(), v));
      stash.bases.forEach((k, v) -> bases.put(k.value(), v));
      entries.add(
          Map.of(
              "id",
              stash.id,
              "time",
              stash.time,
              "message",
              stash.message,
              "commits",
              commits,
              "bases",
              bases));
    }
    OperationState.write(
        worlds.root().resolve("stash.yml"), Map.of("version", 1, "entries", entries));
  }

  private Stash saveStash(String message) throws IOException {
    String id = UUID.randomUUID().toString();
    var commits = new TreeMap<DimensionId, String>();
    var bases = new TreeMap<DimensionId, String>();
    var author = new CommitMetadata.Identity("WorldGit stash", "worldgit@localhost");
    var now = Instant.now();
    // capture 全組完成才發布清單；失敗留下 dangling/pinned 物件，但不丟失工作區。
    var trees = new TreeMap<DimensionId, String>();
    for (var entry : repos.entrySet()) trees.put(entry.getKey(), capture(entry.getValue()));
    for (var entry : repos.entrySet()) {
      String base = entry.getValue().refs().head();
      bases.put(entry.getKey(), base);
      var metadata =
          new CommitMetadata(
              author,
              author,
              message,
              now,
              layout.dataVersion(),
              entry.getKey(),
              CommitMetadata.Source.CLI,
              false,
              UUID.fromString(id),
              List.of());
      String commit =
          entry.getValue().refs().createCommit(trees.get(entry.getKey()), base, metadata);
      entry.getValue().refs().updateRef("refs/worldgit/stash/" + id, null, commit);
      commits.put(entry.getKey(), commit);
    }
    var stash = new Stash(id, now.toString(), message, commits, bases);
    var stashes = new ArrayList<>(stashes());
    stashes.addFirst(stash);
    writeStashes(stashes);
    return stash;
  }

  public Result stashPush(String message, boolean dryRun) throws IOException {
    requireComplete();
    var prepared = prepare(resolve("HEAD"), null, Scope.all(), true, true);
    if (dryRun) return new Result(State.DRY_RUN, stats(prepared), null);
    if (!dirty() && prepared.plans.values().stream().allMatch(ApplyPlan::empty))
      return new Result(State.COMPLETE, stats(prepared), null);
    saveStash(message == null ? "工作區 stash" : message);
    return execute("stash-push", prepared, null, false, false);
  }

  private Stash stashAt(int index) throws IOException {
    var list = stashes();
    if (index < 0 || index >= list.size()) throw new IOException("找不到 stash@{" + index + "}");
    return list.get(index);
  }

  public Result stashPop(int index, boolean dryRun) throws IOException {
    requireComplete();
    var stash = stashAt(index);
    if (!stash.commits.keySet().equals(repos.keySet())) throw new IOException("stash 維度組與目前不一致");
    for (var entry : repos.entrySet())
      if (!Objects.equals(entry.getValue().refs().head(), stash.bases.get(entry.getKey())))
        throw new IOException("stash 基底與 HEAD 不同；請先切回原基底。跨分支 stash 合併留待 Phase 3。");
    if (dirty(true))
      throw new IOException("stash pop 要求乾淨工作區（包含保留的 untracked chunk）；請先 commit 或 stash push。");
    var targets = new TreeMap<DimensionId, RefStore.Commit>();
    stash.commits.forEach(
        (id, c) -> {
          try {
            targets.put(id, repos.get(id).refs().readCommit(c));
          } catch (IOException ex) {
            throw new UncheckedIOException(ex);
          }
        });
    var result =
        execute("stash-pop", prepare(targets, null, Scope.all(), true, true), null, false, dryRun);
    if (result.success() && !dryRun) stashDrop(index);
    return result;
  }

  public void stashDrop(int index) throws IOException {
    requireComplete();
    var stash = stashAt(index);
    var list = new ArrayList<>(stashes());
    list.remove(index);
    // 先更新目錄，ref 清理失敗只會保守保留物件；不會留下不可讀的 stash。
    writeStashes(list);
    for (var entry : stash.commits.entrySet()) {
      var refs = repos.get(entry.getKey()).refs();
      refs.updateRef("refs/worldgit/stash/" + stash.id, entry.getValue(), null);
      refs.updateRef(
          "refs/worldgit/snapshots/" + stash.id + "/" + entry.getValue(), entry.getValue(), null);
    }
  }

  public static Set<ChunkPos> untracked(Path directory) throws IOException {
    var result = new TreeSet<ChunkPos>();
    Object values =
        OperationState.read(directory.resolve("untracked.yml")).getOrDefault("chunks", List.of());
    if (!(values instanceof List<?> list)) throw new IOException("untracked 清單損毀");
    for (Object value : list) {
      String[] pair = value.toString().split(",");
      if (pair.length != 2) throw new IOException("untracked 座標無效");
      result.add(new ChunkPos(Integer.parseInt(pair[0]), Integer.parseInt(pair[1])));
    }
    return result;
  }

  private static void saveUntracked(Path directory, List<ChunkPos> positions) throws IOException {
    OperationState.write(
        directory.resolve("untracked.yml"),
        Map.of("chunks", positions.stream().map(p -> p.x() + "," + p.z()).toList()));
  }

  public record MergeOptions(
      boolean noCommit,
      MergeReport.Choice strategy,
      int distance,
      boolean dryRun,
      CommitMetadata.Identity author,
      CommitMetadata.Source source) {
    public MergeOptions {
      if (distance < 0 || distance > 16) throw new IllegalArgumentException("k 必須為 0..16");
      Objects.requireNonNull(author);
      Objects.requireNonNull(source);
      if (strategy != null
          && strategy != MergeReport.Choice.OURS
          && strategy != MergeReport.Choice.THEIRS)
        throw new IllegalArgumentException("strategy 只能 ours/theirs");
    }
  }

  public record MergeResult(
      String state,
      MergeState merging,
      SortedMap<DimensionId, MergeReport> reports,
      SortedMap<DimensionId, ApplyPlan> plans,
      SortedMap<DimensionId, String> commits,
      String error) {
    public MergeResult {
      reports = Collections.unmodifiableSortedMap(new TreeMap<>(reports));
      plans = Collections.unmodifiableSortedMap(new TreeMap<>(plans));
      commits = Collections.unmodifiableSortedMap(new TreeMap<>(commits));
    }

    public boolean success() {
      return !state.equals("PARTIAL");
    }
  }

  public MergeState merging() throws IOException {
    return MergeState.read(worlds.root().resolve("merge-state.bin"));
  }

  public SortedMap<DimensionId, MergeReport> lastMergeReports() throws IOException {
    return Collections.unmodifiableSortedMap(
        reports(MergeState.read(worlds.root().resolve("last-merge-report.bin"))));
  }

  private void requireNotMerging() throws IOException {
    if (merging() != null)
      throw new IOException("世界為 MERGING；請 resolve 後 merge --continue，或 merge --abort。");
  }

  public MergeResult merge(String revision, MergeOptions options) throws IOException {
    return beginMerge("merge", revision, options);
  }

  public MergeResult revert(String revision, MergeOptions options) throws IOException {
    return beginMerge("revert", revision, options);
  }

  public MergeResult cherryPick(String revision, MergeOptions options) throws IOException {
    return beginMerge("cherry-pick", revision, options);
  }

  private MergeResult beginMerge(String mode, String revision, MergeOptions opts)
      throws IOException {
    requireComplete();
    if (dirty(true)) throw new IOException("合併要求乾淨工作區（含 untracked）；請先 commit 或 stash push。");
    var targets = resolveForMerge(revision);
    var operation = UUID.randomUUID();
    var dimensions = new TreeMap<DimensionId, MergeState.Dimension>();
    var plans = new TreeMap<DimensionId, ApplyPlan>();
    var commits = new TreeMap<DimensionId, RefStore.Commit>();
    int nextId = 1;
    var semantics =
        EntityTagRegistry.load(
            layout.world(), layout.dataVersion(), live == null ? null : live.packs());
    for (var entry : repos.entrySet()) {
      var id = entry.getKey();
      var repo = entry.getValue();
      var refs = repo.refs();
      var original = refs.headState();
      var ours = refs.readCommit(original.commit());
      var target = targets.get(id);
      String empty = repo.objects().writeTree(List.of());
      var baseCommit = (RefStore.Commit) null;
      var theirs = target;
      String otherParent = target == null ? null : target.id();
      DataVersions.requireSame(ours.metadata().mcDataVersion(), layout.dataVersion());
      if (target != null)
        DataVersions.requireSame(target.metadata().mcDataVersion(), layout.dataVersion());
      if (mode.equals("merge")) {
        String base = target == null ? null : MergeBases.unique(refs, ours.id(), target.id());
        if (base != null) baseCommit = refs.readCommit(base);
      } else {
        // revision 是 snapshot group；未變動維度的目標 head 可能是更早的 commit，該維度的 patch 必須為零。
        if (target == null) throw new IOException("patch 來源維度缺少歷史：" + id);
        boolean unchanged = groupUnchanged(revision, id, target, targets);
        if (unchanged) {
          baseCommit = target;
          theirs = target;
        } else {
          if (target.parents().size() > 1)
            throw new IOException("revert/cherry-pick 不支援 merge commit；請選單 parent commit");
          RefStore.Commit parent =
              target.parents().isEmpty() ? null : refs.readCommit(target.parents().getFirst());
          if (mode.equals("revert")) {
            baseCommit = target;
            theirs = parent;
          } else {
            baseCommit = parent;
            theirs = target;
          }
        }
        otherParent = null;
      }
      if (baseCommit != null)
        DataVersions.requireSame(baseCommit.metadata().mcDataVersion(), layout.dataVersion());
      if (theirs != null)
        DataVersions.requireSame(theirs.metadata().mcDataVersion(), layout.dataVersion());
      String rawBase = baseCommit == null ? empty : baseCommit.tree(),
          rawTheirs = theirs == null ? empty : theirs.tree();
      String br = TreeFilter.rules(repo.objects(), rawBase),
          or = TreeFilter.rules(repo.objects(), ours.tree()),
          tr = TreeFilter.rules(repo.objects(), rawTheirs);
      String rules = IgnoreRuleMerge.merge(br, or, tr);
      String bt = TreeFilter.filter(repo.objects(), rawBase, rules, semantics),
          ot = TreeFilter.filter(repo.objects(), ours.tree(), rules, semantics),
          tt = TreeFilter.filter(repo.objects(), rawTheirs, rules, semantics);
      if (!rules.equals(or)) ot = captureRules(repo, rules);
      var result =
          new MergeEngine(
                  repo.objects(),
                  id,
                  bt,
                  ot,
                  tt,
                  authors(ours),
                  theirs == null ? List.of() : authors(theirs))
              .merge(opts.distance());
      var regions = new ArrayList<MergeReport.Region>();
      String tree = result.tree();
      for (var region : result.report().regions()) {
        var numbered =
            new MergeReport.Region(
                nextId++,
                id,
                region.bounds(),
                region.blockCount(),
                region.oursAuthors(),
                region.theirsAuthors(),
                region.redstone(),
                region.choice(),
                region.resolved(),
                region.atoms());
        if (opts.strategy() != null) {
          tree =
              MergeEngine.select(
                  repo.objects(),
                  tree,
                  opts.strategy() == MergeReport.Choice.OURS ? ot : tt,
                  numbered);
          numbered = numbered.selected(opts.strategy(), true);
        }
        regions.add(numbered);
      }
      var differences =
          or.equals(tr)
              ? List.<MergeReport.RuleDifference>of()
              : List.of(new MergeReport.RuleDifference(id, br, or, tr, rules));
      var report =
          new MergeReport(
              result.report().automaticallyMergedSections(),
              regions,
              differences,
              MergeEngine.updateShapes(repo.objects(), id, bt, ot, tt, tree),
              result.report().warnings());
      var metadata =
          new CommitMetadata(
              opts.author(),
              opts.author(),
              mode + " " + revision,
              Instant.now(),
              layout.dataVersion(),
              id,
              opts.source(),
              false,
              operation,
              List.of());
      String pin = opts.dryRun() ? ours.id() : provisional(repo, tree, ours.id(), metadata);
      if (!opts.dryRun()) pinMergeTrees(repo, operation, bt, ot, tt, ours.id(), metadata);
      var working = capture(repo);
      String originalTree = working;
      if (!or.equals(tr)) {
        var originalEditor = new TreeEditor(repo.objects(), captureRules(repo, ""));
        originalEditor.putBlob(".wgignore", or.getBytes(StandardCharsets.UTF_8));
        originalTree = originalEditor.write();
      }
      if (!opts.dryRun()) {
        String originalPin = provisional(repo, originalTree, ours.id(), metadata);
        refs.updateRef("refs/worldgit/merges/" + operation + "/original", null, originalPin);
      }
      var plan = mergePlan(repo, working, tree);
      plans.put(id, plan);
      commits.put(id, new RefStore.Commit(pin, tree, List.of(ours.id()), metadata));
      dimensions.put(
          id,
          new MergeState.Dimension(
              original,
              originalTree,
              baseCommit == null ? null : baseCommit.id(),
              target == null ? ours.id() : target.id(),
              otherParent,
              bt,
              ot,
              tt,
              pin,
              report));
    }
    var state = new MergeState(operation, mode, revision, mode + " " + revision, dimensions);
    if (opts.dryRun()) return mergeResult("DRY_RUN", state, plans, new TreeMap<>(), null);
    saveMerge(state);
    var applied = execute("merge-start", new Prepared(commits, plans), null, false, false);
    if (!applied.success())
      return mergeResult("PARTIAL", state, plans, new TreeMap<>(), applied.error());
    if (state.remaining() == 0 && !opts.noCommit())
      return continueMerge(opts.author(), opts.source(), false);
    return mergeResult("MERGING", state, plans, new TreeMap<>(), null);
  }

  private SortedMap<DimensionId, RefStore.Commit> resolveForMerge(String revision)
      throws IOException {
    boolean branch = false;
    for (var repo : repos.values()) branch |= repo.refs().branches().containsKey(revision);
    if (!branch) return resolve(revision);
    var result = new TreeMap<DimensionId, RefStore.Commit>();
    for (var e : repos.entrySet()) {
      String id = e.getValue().refs().branches().get(revision);
      if (id != null) result.put(e.getKey(), e.getValue().refs().readCommit(id));
    }
    return result;
  }

  private boolean groupUnchanged(
      String revision,
      DimensionId id,
      RefStore.Commit target,
      SortedMap<DimensionId, RefStore.Commit> targets)
      throws IOException {
    // 完整 group refs 的 snapshot 若有 source commit，對不变維度不反向套用舊提交。
    UUID snapshot = null;
    boolean branch = revision.equals("HEAD");
    for (var repo : repos.values()) branch |= repo.refs().branches().containsKey(revision);
    if (branch)
      snapshot =
          targets.values().stream()
              .max(Comparator.comparing(c -> c.metadata().time()))
              .orElseThrow()
              .metadata()
              .snapshot();
    else
      for (var repo : repos.values())
        try {
          snapshot = repo.refs().readCommit(repo.refs().resolve(revision)).metadata().snapshot();
          break;
        } catch (IOException ignored) {
        }
    return snapshot != null && !target.metadata().snapshot().equals(snapshot);
  }

  private static List<String> authors(RefStore.Commit commit) {
    var result = new TreeSet<String>();
    result.add(commit.metadata().author().git());
    commit.metadata().contributions().forEach(c -> result.add(c.author().git()));
    return List.copyOf(result);
  }

  private static String provisional(
      DimensionRepository repo, String tree, String parent, CommitMetadata metadata)
      throws IOException {
    // 候選樹不是世界組存檔；獨立 UUID 避免 legacy snapshot fallback 把同 snapshot 多個候選當作真實歷史。
    var m =
        new CommitMetadata(
            metadata.author(),
            metadata.committer(),
            metadata.message(),
            metadata.time(),
            metadata.mcDataVersion(),
            metadata.dimension(),
            metadata.source(),
            false,
            UUID.randomUUID(),
            metadata.contributions());
    return repo.refs().createCommit(tree, parent, m);
  }

  private void pinMergeTrees(
      DimensionRepository repo,
      UUID operation,
      String base,
      String ours,
      String theirs,
      String parent,
      CommitMetadata meta)
      throws IOException {
    for (var e : Map.of("base", base, "ours", ours, "theirs", theirs).entrySet()) {
      String c = provisional(repo, e.getValue(), parent, meta);
      repo.refs().updateRef("refs/worldgit/merges/" + operation + "/" + e.getKey(), null, c);
    }
  }

  private String captureRules(DimensionRepository repo, String text) throws IOException {
    var rules = IgnoreRules.parse(text);
    var editor = new TreeEditor(repo.objects(), null);
    try (var source =
        live == null
            ? new OfflineSnapshotSource(layout, layout.dimensions().get(repo.dimension()))
            : live.source(layout.dimensions().get(repo.dimension()))) {
      var scan = source.scan(org.worldgit.core.capture.ScanIndex.empty(), true);
      for (var pos : new TreeSet<>(scan.candidates()))
        try {
          var snapshot = source.snapshot(pos, rules).toCompletableFuture().join();
          if (snapshot.isPresent())
            editor.replaceTree(
                pos.treePath(),
                org.worldgit.core.normalize.SnapshotCodec.chunkFiles(snapshot.get()));
        } catch (java.util.concurrent.CompletionException ex) {
          throw new IOException("合併規則 capture 失敗", ex.getCause());
        }
      if (repo.dimension().equals(DimensionId.OVERWORLD)) {
        var metadata =
            org.worldgit.core.normalize.MetadataNormalizer.normalize(source.worldMetadata(), rules);
        metadata.put("worldgit.yml", Files.readAllBytes(repo.configPath()));
        editor.replaceTree("world-meta", metadata);
        var original = capture(repo);
        var manifest = TreeEditor.find(repo.objects(), original, "dimensions");
        if (manifest != null) editor.putBlob("dimensions", repo.objects().readBlob(manifest.id()));
      } else editor.putBlob("worldgit.yml", Files.readAllBytes(repo.configPath()));
    }
    editor.putBlob(".wgignore", text.getBytes(StandardCharsets.UTF_8));
    return editor.write();
  }

  private ApplyPlan mergePlan(DimensionRepository repo, String current, String target)
      throws IOException {
    String rules = TreeFilter.rules(repo.objects(), target);
    var parsed = IgnoreRules.parse(rules);
    var opts =
        new ApplyPlanner.Options(
            parsed,
            parsed,
            EntityTagRegistry.load(
                layout.world(), layout.dataVersion(), live == null ? null : live.packs()),
            tolerance,
            !parsed.hasAreas(),
            repo.dimension().equals(DimensionId.OVERWORLD),
            layout.dataVersion());
    var plan =
        ApplyPlanner.plan(repo.objects(), repo.dimension(), current, target, Scope.all(), opts);
    applier.validateMetadata(plan);
    if (live != null) live.validate(plan);
    return plan;
  }

  private void saveMerge(MergeState state) throws IOException {
    state.write(worlds.root().resolve("merge-state.bin"));
    for (var e : state.dimensions().entrySet())
      RegionFile.atomicWrite(
          repos.get(e.getKey()).directory().resolve("MERGE_HEAD"),
          (e.getValue().sourceCommit() + "\n").getBytes(StandardCharsets.US_ASCII));
  }

  private void clearMerge() throws IOException {
    for (var repo : repos.values()) Files.deleteIfExists(repo.directory().resolve("MERGE_HEAD"));
    Files.deleteIfExists(worlds.root().resolve("merge-state.bin"));
  }

  private MergeState activeMerge() throws IOException {
    var state = merging();
    if (state == null) throw new IOException("世界沒有進行中的合併");
    if (!state.dimensions().keySet().equals(repos.keySet())) throw new IOException("MERGING 維度組不同");
    for (var e : state.dimensions().entrySet())
      if (!repos.get(e.getKey()).refs().headState().equals(e.getValue().original()))
        throw new IOException("合併期間 HEAD 被外部修改；請先恢復原 HEAD");
    return state;
  }

  public MergeResult selectRegion(
      int regionId, MergeReport.Choice choice, boolean resolved, boolean dryRun)
      throws IOException {
    if (OperationState.partial(worlds.root()))
      throw new IOException("合併為 PARTIAL；請先 merge --abort");
    var state = activeMerge();
    var ds = new TreeMap<>(state.dimensions());
    var plans = new TreeMap<DimensionId, ApplyPlan>();
    var commits = new TreeMap<DimensionId, RefStore.Commit>();
    boolean found = false;
    for (var e : ds.entrySet()) {
      var d = e.getValue();
      var repo = repos.get(e.getKey());
      var regions = new ArrayList<MergeReport.Region>();
      String current = capture(repo), target = current;
      boolean changed = false;
      for (var r : d.report().regions()) {
        if (regionId == 0 || r.id() == regionId) {
          found = true;
          changed = true;
          if (choice != MergeReport.Choice.MANUAL) {
            String source =
                switch (choice) {
                  case OURS -> d.oursTree();
                  case THEIRS -> d.theirsTree();
                  case BASE -> d.baseTree();
                  default -> throw new AssertionError();
                };
            target = MergeEngine.select(repo.objects(), target, source, r);
          }
          r = r.selected(choice, resolved);
        }
        regions.add(r);
      }
      if (!changed) continue;
      var report =
          new MergeReport(
              d.report().automaticallyMergedSections(),
              regions,
              d.report().ruleDifferences(),
              MergeEngine.updateShapes(
                  repo.objects(), e.getKey(), d.baseTree(), d.oursTree(), d.theirsTree(), target),
              d.report().warnings());
      var prior = repo.refs().readCommit(d.resultCommit());
      String c =
          dryRun
              ? prior.id()
              : provisional(
                  repo,
                  target,
                  prior.parents().isEmpty() ? null : prior.parents().getFirst(),
                  prior.metadata());
      commits.put(e.getKey(), new RefStore.Commit(c, target, prior.parents(), prior.metadata()));
      plans.put(e.getKey(), mergePlan(repo, current, target));
      e.setValue(d.withResult(c, report));
    }
    if (!found) throw new IOException("找不到衝突區域 #" + regionId);
    var updated =
        new MergeState(state.operation(), state.mode(), state.source(), state.message(), ds);
    if (dryRun) return mergeResult("DRY_RUN", updated, plans, new TreeMap<>(), null);
    // 先套用並驗證，再发布 resolved；若中途崩潰，舊狀態仍 unresolved，可 abort。
    var applied = execute("merge-select", new Prepared(commits, plans), null, false, false);
    if (applied.success()) saveMerge(updated);
    return mergeResult(
        applied.success() ? "MERGING" : "PARTIAL",
        applied.success() ? updated : state,
        plans,
        new TreeMap<>(),
        applied.error());
  }

  public List<MergeReport.PreviewBlock> regionPreview(int regionId, MergeReport.Choice choice)
      throws IOException {
    var state = activeMerge();
    for (var e : state.dimensions().entrySet())
      for (var r : e.getValue().report().regions())
        if (r.id() == regionId) {
          var d = e.getValue();
          String tree =
              switch (choice) {
                case OURS -> d.oursTree();
                case THEIRS -> d.theirsTree();
                case BASE -> d.baseTree();
                case MANUAL -> throw new IOException("manual 預覽請從目前世界 capture");
              };
          return MergeEngine.preview(repos.get(e.getKey()).objects(), tree, r);
        }
    throw new IOException("找不到衝突區域 #" + regionId);
  }

  public MergeResult markResolved(int regionId, boolean manual, boolean dryRun) throws IOException {
    if (OperationState.partial(worlds.root()))
      throw new IOException("合併為 PARTIAL；請先 merge --abort");
    var state = activeMerge();
    if (manual) return selectRegion(regionId, MergeReport.Choice.MANUAL, true, dryRun);
    var ds = new TreeMap<>(state.dimensions());
    boolean found = false;
    for (var e : ds.entrySet()) {
      var d = e.getValue();
      var rs = new ArrayList<MergeReport.Region>();
      for (var r : d.report().regions()) {
        if (regionId == 0 || r.id() == regionId) {
          found = true;
          r = r.selected(r.choice(), true);
        }
        rs.add(r);
      }
      e.setValue(
          d.withResult(
              d.resultCommit(),
              new MergeReport(
                  d.report().automaticallyMergedSections(),
                  rs,
                  d.report().ruleDifferences(),
                  d.report().updateShapes(),
                  d.report().warnings())));
    }
    if (!found) throw new IOException("找不到衝突區域 #" + regionId);
    var updated =
        new MergeState(state.operation(), state.mode(), state.source(), state.message(), ds);
    if (!dryRun) saveMerge(updated);
    return mergeResult(
        dryRun ? "DRY_RUN" : "MERGING", updated, new TreeMap<>(), new TreeMap<>(), null);
  }

  public MergeResult continueMerge(
      CommitMetadata.Identity author, CommitMetadata.Source source, boolean dryRun)
      throws IOException {
    return commitMerge(author, source, null, dryRun);
  }

  public MergeResult commitMerge(
      CommitMetadata.Identity author, CommitMetadata.Source source, String message, boolean dryRun)
      throws IOException {
    if (OperationState.partial(worlds.root()))
      throw new IOException("合併為 PARTIAL；請先 merge --abort");
    var state = activeMerge();
    if (state.remaining() != 0) throw new IOException("尚有 " + state.remaining() + " 個衝突區域未解決");
    var targets = new TreeMap<DimensionId, String>();
    var snapshot = UUID.randomUUID();
    var now = Instant.now();
    // 目前世界就是 manual 的權威來源；全維度 capture／DataVersion 檢查完成才移動任一 HEAD。
    var trees = new TreeMap<DimensionId, String>();
    for (var e : repos.entrySet()) {
      var d = state.dimensions().get(e.getKey());
      if (!Files.readString(e.getValue().ignorePath())
          .equals(
              TreeFilter.rules(
                  e.getValue().objects(), e.getValue().refs().readCommit(d.resultCommit()).tree())))
        throw new IOException("MERGING 期間 .wgignore 被修改；請 abort 後提交規則");
      trees.put(e.getKey(), capture(e.getValue()));
    }
    if (dryRun) return mergeResult("DRY_RUN", state, new TreeMap<>(), new TreeMap<>(), null);
    for (var e : repos.entrySet()) {
      var d = state.dimensions().get(e.getKey());
      var parents = new ArrayList<String>();
      parents.add(d.original().commit());
      if (state.mode().equals("merge")
          && d.otherParent() != null
          && !parents.contains(d.otherParent())) parents.add(d.otherParent());
      var meta =
          new CommitMetadata(
              author,
              author,
              message == null ? state.message() : message,
              now,
              layout.dataVersion(),
              e.getKey(),
              source,
              false,
              snapshot,
              List.of());
      String kind =
          state.mode().equals("merge")
              ? "Merge"
              : state.mode().equals("revert") ? "Revert" : "CherryPick";
      targets.put(
          e.getKey(),
          e.getValue()
              .refs()
              .createCommit(
                  trees.get(e.getKey()),
                  parents,
                  meta,
                  Map.of("WorldGit-" + kind + "-Source", d.sourceCommit())));
    }
    var completed = new ArrayList<DimensionId>();
    try {
      for (var e : targets.entrySet()) {
        var refs = repos.get(e.getKey()).refs();
        var h = state.dimensions().get(e.getKey()).original();
        if (h.branch() != null)
          refs.updateRef("refs/heads/" + h.branch(), h.commit(), e.getValue());
        else refs.checkout(h, new RefStore.Head(e.getValue(), null));
        completed.add(e.getKey());
      }
      for (var e : targets.entrySet()) {
        repos
            .get(e.getKey())
            .refs()
            .updateRef("refs/worldgit/groups/" + snapshot, null, e.getValue());
        repos.get(e.getKey()).invalidateIndex();
        Files.deleteIfExists(repos.get(e.getKey()).directory().resolve("untracked.yml"));
      }
      state.write(worlds.root().resolve("last-merge-report.bin"));
      clearMerge();
      return mergeResult("COMPLETE", null, reports(state), new TreeMap<>(), targets, null);
    } catch (IOException ex) {
      for (var id : completed)
        try {
          var refs = repos.get(id).refs();
          var old = state.dimensions().get(id).original();
          if (old.branch() != null)
            refs.updateRef("refs/heads/" + old.branch(), targets.get(id), old.commit());
          else refs.checkout(refs.headState(), old);
        } catch (IOException rollback) {
          ex.addSuppressed(rollback);
        }
      var journal = new LinkedHashMap<String, Object>();
      journal.put("state", "PARTIAL");
      journal.put("mode", "merge-commit");
      journal.put("error", message(ex));
      writeJournal(journal);
      return mergeResult("PARTIAL", state, new TreeMap<>(), targets, message(ex));
    }
  }

  public MergeResult abortMerge(boolean dryRun) throws IOException {
    var state = activeMerge();
    var commits = new TreeMap<DimensionId, RefStore.Commit>();
    var plans = new TreeMap<DimensionId, ApplyPlan>();
    for (var e : repos.entrySet()) {
      var d = state.dimensions().get(e.getKey());
      var repo = e.getValue();
      var prior = repo.refs().readCommit(d.original().commit());
      String tree = d.originalTree();
      var meta = prior.metadata();
      String c = dryRun ? prior.id() : provisional(repo, tree, prior.id(), meta);
      commits.put(e.getKey(), new RefStore.Commit(c, tree, List.of(prior.id()), meta));
      if (d.report().ruleDifferences().isEmpty())
        plans.put(e.getKey(), mergePlan(repo, capture(repo), tree));
      else {
        var options =
            new ApplyPlanner.Options(
                IgnoreRules.none(),
                IgnoreRules.none(),
                EntityTagRegistry.load(
                    layout.world(), layout.dataVersion(), live == null ? null : live.packs()),
                tolerance,
                true,
                repo.dimension().equals(DimensionId.OVERWORLD),
                layout.dataVersion());
        var plan =
            ApplyPlanner.plan(
                repo.objects(),
                repo.dimension(),
                captureRules(repo, ""),
                tree,
                Scope.all(),
                options);
        applier.validateMetadata(plan);
        if (live != null) live.validate(plan);
        plans.put(e.getKey(), plan);
      }
    }
    if (dryRun) return mergeResult("DRY_RUN", state, plans, new TreeMap<>(), null);
    var applied = execute("merge-abort", new Prepared(commits, plans), null, false, false);
    if (applied.success()) clearMerge();
    return mergeResult(
        applied.success() ? "COMPLETE" : "PARTIAL",
        applied.success() ? null : state,
        reports(state),
        plans,
        new TreeMap<>(),
        applied.error());
  }

  private static SortedMap<DimensionId, MergeReport> reports(MergeState state) {
    var result = new TreeMap<DimensionId, MergeReport>();
    if (state != null) state.dimensions().forEach((id, d) -> result.put(id, d.report()));
    return result;
  }

  private static MergeResult mergeResult(
      String status,
      MergeState state,
      SortedMap<DimensionId, ApplyPlan> plans,
      SortedMap<DimensionId, String> commits,
      String error) {
    return mergeResult(status, state, reports(state), plans, commits, error);
  }

  private static MergeResult mergeResult(
      String status,
      MergeState state,
      SortedMap<DimensionId, MergeReport> reports,
      SortedMap<DimensionId, ApplyPlan> plans,
      SortedMap<DimensionId, String> commits,
      String error) {
    return new MergeResult(status, state, reports, plans, commits, error);
  }

  @Override
  public void close() throws IOException {
    IOException error = null;
    for (var repo : repos.values())
      try {
        repo.close();
      } catch (IOException ex) {
        error = ex;
      }
    try {
      if (session != null) session.close();
    } catch (IOException ex) {
      error = ex;
    }
    try {
      groupLock.close();
    } catch (IOException ex) {
      error = ex;
    }
    if (error != null) throw error;
  }
}
