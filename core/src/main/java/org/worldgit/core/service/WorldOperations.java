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
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.core.store.*;

/** 單維度復原入口。自己的 repo、journal、預檢、寫回、驗證與 HEAD barrier；session 鎖防止離線寫入運行中的世界。 */
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
   * 線上平台持有遊戲的 session；UUID removal barrier 僅處理所選維度。呼叫端在建構之前鎖定編輯並 flush，直到 close 之後才解鎖。禁止在遊戲
   * owner 執行緒呼叫。區域選擇另由 lockChunks／局部 source／applyRegions 管理短暫的 chunk 屏障。
   */
  public interface LiveAccess {
    SnapshotSource source(WorldLayout.Dimension dimension) throws IOException;

    /** 局部來源：必須以 owner 活資料或完成 IO 的磁碟快照讀取指定 chunks；不需 scan。 */
    default SnapshotSource source(WorldLayout.Dimension dimension, Set<ChunkPos> chunks)
        throws IOException {
      var source = source(dimension);
      try {
        source.scan(org.worldgit.core.capture.ScanIndex.empty(), true);
        return source;
      } catch (Exception ex) {
        source.close();
        throw ex instanceof IOException io ? io : new IOException(ex);
      }
    }

    /** UUID 的目前位置（含 passengers／未載入 storage）；不擷取無關 chunk。 */
    default Set<ChunkPos> entityChunks(WorldLayout.Dimension dimension, Set<UUID> ids)
        throws IOException {
      var result = new TreeSet<ChunkPos>();
      try (var source = source(dimension)) {
        for (var pos : source.scan(org.worldgit.core.capture.ScanIndex.empty(), true).present())
          for (var entity :
              source
                  .snapshot(pos, IgnoreRules.none())
                  .toCompletableFuture()
                  .join()
                  .orElseThrow()
                  .entities()) if (ids.contains(entity.uuid())) result.add(pos);
      }
      return result;
    }

    /** 區域操作仍與其他 repo 操作互斥；平台只鎖定指定 chunks。 */
    default AutoCloseable lockChunks(Map<DimensionId, Set<ChunkPos>> chunks) throws IOException {
      return () -> {};
    }

    default void applyRegions(Collection<ApplyPlan> plans) throws IOException {
      applyAll(plans);
    }

    void validate(ApplyPlan plan) throws IOException;

    void applyAll(Collection<ApplyPlan> plans) throws IOException;

    /** 線上取消／停用檢查：驗證完成後、發布 HEAD 或完成 journal 之前呼叫。 */
    default void beforeComplete() throws IOException {}

    default EntityTagRegistry.PackResolver packs() {
      return null;
    }
  }

  public static WorldOperations live(WorldLayout layout, LiveAccess access) throws IOException {
    return new WorldOperations(layout, null, Objects.requireNonNull(access), layout.currentDimension());
  }

  private record Prepared(
      SortedMap<DimensionId, RefStore.Commit> commits, SortedMap<DimensionId, ApplyPlan> plans) {}

  private final WorldLayout layout;
  private final WorldRepositories worlds;
  private final SortedMap<DimensionId, DimensionRepository> repos = new TreeMap<>();
  private final Path stateRoot;
  private final WorldSessionLock session;
  private final OfflineApplier applier;
  private final Writer writer;
  private final boolean groupedWriter;
  private final LiveAccess live;
  private final EntityTagRegistry.PackResolver packResolver;
  private final double tolerance;

  public WorldOperations(WorldLayout layout) throws IOException {
    this(layout, null);
  }

  /** writer 注入用於平台以外的離線 adapter／故障驗證；預設正式 Anvil writer。 */
  public WorldOperations(WorldLayout layout, Writer writer) throws IOException {
    this(layout, writer, null, layout.currentDimension());
  }

  public static WorldOperations inDimension(WorldLayout layout, DimensionId dimension) throws IOException {
    return new WorldOperations(layout, null, null, Objects.requireNonNull(dimension));
  }

  public static WorldOperations inDimension(WorldLayout layout, DimensionId dimension,
      EntityTagRegistry.PackResolver packs) throws IOException {
    return new WorldOperations(layout, null, null, Objects.requireNonNull(dimension), packs);
  }

  public static WorldOperations live(WorldLayout layout, LiveAccess access, DimensionId dimension) throws IOException {
    return new WorldOperations(layout, null, Objects.requireNonNull(access), Objects.requireNonNull(dimension));
  }

  private WorldOperations(WorldLayout layout, Writer writer, LiveAccess live, DimensionId dimension) throws IOException {
    this(layout, writer, live, dimension, null);
  }

  private WorldOperations(WorldLayout layout, Writer writer, LiveAccess live, DimensionId dimension,
      EntityTagRegistry.PackResolver packs) throws IOException {
    this.layout = layout;
    packResolver = live == null ? packs : live.packs();
    worlds = new WorldRepositories(layout, dim -> new OfflineSnapshotSource(layout, dim, packResolver));
    applier = new OfflineApplier(layout, packResolver);
    this.live = live;
    this.writer = writer == null ? applier::apply : writer;
    groupedWriter = writer == null;
    stateRoot = worlds.tracked().get(dimension);
    if (stateRoot == null) throw new IOException("維度尚未 init：" + dimension);
    tolerance = WorldGitConfig.readLocal(stateRoot.resolve("worldgit.yml")).entityTolerance();
    WorldSessionLock acquired = null;
    try {
      if (live == null) acquired = WorldSessionLock.acquire(layout);
      repos.put(dimension, new DimensionRepository(stateRoot, dimension, false));
      if (repos.isEmpty()) throw new IOException("世界尚未 init");
      session = acquired;
    } catch (Exception ex) {
      for (var repo : repos.values()) repo.close();
      if (acquired != null) acquired.close();

      throw ex;
    }
  }

  public Map<String, Object> journal() throws IOException {
    return OperationState.read(stateRoot.resolve("apply-state.yml"));
  }

  private void requireComplete() throws IOException {
    requireNotMerging();
    if (OperationState.partial(stateRoot))
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
    var result = new TreeMap<DimensionId, RefStore.Commit>();
    for (var entry : repos.entrySet()) {
      var refs = entry.getValue().refs();
      var commit = refs.readCommit(refs.resolve(revision));
      if (!commit.metadata().dimension().equals(entry.getKey())) throw new IOException("目標 commit 維度不符");
      result.put(entry.getKey(), commit);
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
            layout.world(), layout.dataVersion(), packResolver),
        tolerance,
        delete,
        meta,
        target.metadata().mcDataVersion());
  }

  private String capture(DimensionRepository repo) throws IOException {
    try (var timing = OperationTimings.stage("capture");
        var source =
            live == null
                ? new OfflineSnapshotSource(layout, layout.dimensions().get(repo.dimension()), packResolver)
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
              meta,
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

  @FunctionalInterface
  private interface PersistCompletion {
    void write() throws IOException;
  }

  private Result execute(
      String mode, Prepared prepared, String branch, boolean moveHead, boolean dryRun)
      throws IOException {
    return execute(mode, prepared, branch, moveHead, dryRun, () -> {});
  }

  private Result execute(
      String mode,
      Prepared prepared,
      String branch,
      boolean moveHead,
      boolean dryRun,
      PersistCompletion persistence)
      throws IOException {
    org.worldgit.core.operation.OperationProgress.report(repos.firstKey(), "preflight", 0, null, org.worldgit.core.operation.OperationProgress.Unit.SECTION);
    if (dryRun) return new Result(State.DRY_RUN, stats(prepared), null);
    var old = new TreeMap<DimensionId, RefStore.Head>();
    var journal = new LinkedHashMap<String, Object>();
    journal.put("version", 1);
    journal.put("operation", org.worldgit.core.operation.OperationProgress.operationId().toString());
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
      for (var entry : prepared.commits.entrySet()) {
        var repo = repos.get(entry.getKey());
        String path =
            repo.dimension().equals(DimensionId.OVERWORLD)
                ? "world-meta/worldgit.yml"
                : "worldgit.yml";
        var config = TreeEditor.find(repo.objects(), entry.getValue().tree(), path);
        var saved = org.worldgit.core.capture.ModifiedChunks.read(repo.directory());
        boolean sparse =
            config != null
                && WorldGitConfig.readRepo(
                            new String(
                                repo.objects().readBlob(config.id()), StandardCharsets.UTF_8),
                            path)
                        .track()
                    == WorldGitConfig.Track.MODIFIED_ONLY;
        if (sparse || saved.isPresent()) {
          var chunks = new TreeSet<>(saved.orElse(Set.of()));
          var scope = prepared.plans.get(entry.getKey()).scope();
          for (var p :
              org.worldgit.core.capture.ModifiedChunks.tree(
                  repo.objects(), entry.getValue().tree()))
            if (scope.touchesChunk(p)) chunks.add(p);
          org.worldgit.core.capture.ModifiedChunks.write(repo.directory(), chunks);
        }
      }
      if (mode.startsWith("merge-"))
        for (var entry : prepared.commits.entrySet()) {
          var repo = repos.get(entry.getKey());
          WorldGitConfig.write(
              repo.ignorePath(), TreeFilter.rules(repo.objects(), entry.getValue().tree()));
          restoreConfig(repo, entry.getValue());
        }
      org.worldgit.core.operation.OperationProgress.report(repos.firstKey(), "apply", 0, null, org.worldgit.core.operation.OperationProgress.Unit.SECTION);
      try (var timing = OperationTimings.stage("apply-and-barrier")) {
        if (live != null) live.applyAll(prepared.plans.values());
        else if (groupedWriter) applier.applyAll(prepared.plans.values(), session);
      }
      for (var entry : prepared.plans.entrySet()) {
        if (live == null && !groupedWriter) writer.apply(entry.getValue(), session);
        var repo = repos.get(entry.getKey());
        var target = prepared.commits.get(entry.getKey());
        if (entry.getValue().scope().kind() == Scope.Kind.ALL) restoreConfig(repo, target);
        else {
          updateTouched(repo, entry.getValue());
        }
        repos.get(entry.getKey()).invalidateIndex();
        @SuppressWarnings("unchecked")
        var row = (Map<String, Object>) rows.get(entry.getKey().value());
        row.put("applied", true);
        writeJournal(journal);
      }
      org.worldgit.core.operation.OperationProgress.report(repos.firstKey(), "verify", 0, null, org.worldgit.core.operation.OperationProgress.Unit.CHUNK);
      try (var timing = OperationTimings.stage("verify")) {
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
      }
      if (live != null) live.beforeComplete();
      if (moveHead)
        for (var entry : prepared.commits.entrySet()) {
          var repo = repos.get(entry.getKey());
          var prior = old.get(entry.getKey());
          if ((mode.equals("reset") || mode.equals("pull")) && prior.branch() != null) {
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
      persistence.write();
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
    org.worldgit.core.capture.PlayerTouchedEntities.restore(repo.objects(), target.tree(), repo.directory());
    var config = TreeEditor.find(repo.objects(), target.tree(), path);
    if (config != null) {
      var bytes = repo.objects().readBlob(config.id());
      WorldGitConfig.readRepo(new String(bytes, StandardCharsets.UTF_8), path);
      RegionFile.atomicWrite(repo.configPath(), bytes);
    }
  }

  private void updateTouched(DimensionRepository repo, ApplyPlan plan) throws IOException {
    if (WorldGitConfig.readRepo(repo.configPath()).entities() != WorldGitConfig.Entities.PLAYER_TOUCHED) return;
    var touched = org.worldgit.core.capture.PlayerTouchedEntities.read(repo.directory());
    for(var entity : plan.entities()) {
      touched.remove(entity.uuid());
      if(entity.target() != null) org.worldgit.core.capture.PlayerTouchedEntities.collect(entity.target().data(),touched);
    }
    org.worldgit.core.capture.PlayerTouchedEntities.write(repo.directory(), touched);
  }

  private void writeJournal(Map<String, Object> journal) throws IOException {
    try (var timing = OperationTimings.stage("journal")) {
      OperationState.write(stateRoot.resolve("apply-state.yml"), journal);
    }
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
    var state = OperationState.read(stateRoot.resolve("stash.yml"));
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
        stateRoot.resolve("stash.yml"), Map.of("version", 1, "entries", entries));
  }

  private Stash saveStash(String message) throws IOException {
    String id = UUID.randomUUID().toString();
    var commits = new TreeMap<DimensionId, String>();
    var bases = new TreeMap<DimensionId, String>();
    var author = new CommitMetadata.Identity("WorldGit stash", "worldgit@localhost");
    var now = Instant.now();
    // capture 完成才發布清單；失敗留下 dangling/pinned 物件，但不丟失工作區。
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
    return MergeState.read(stateRoot.resolve("merge-state.bin"));
  }

  public SortedMap<DimensionId, MergeReport> lastMergeReports() throws IOException {
    return Collections.unmodifiableSortedMap(
        reports(MergeState.read(stateRoot.resolve("last-merge-report.bin"))));
  }

  private void requireNotMerging() throws IOException {
    for (var repo : repos.values()) repo.requireLegacyComplete();
    if (merging() != null)
      throw new IOException("世界為 MERGING；請 resolve 後 merge --continue，或 merge --abort。");
  }

  public record PullPreview(
      SortedMap<DimensionId, String> expectedHeads,
      SortedMap<DimensionId, String> targets,
      boolean fastForward,
      MergeResult result) {}

  /** 不自動取得網路更新；輸入來自成功的 WorldRemotes.fetch。dryRun 為線上 preview。 */
  public PullPreview pull(
      Map<DimensionId, String> targets,
      Map<DimensionId, String> expectedHeads,
      boolean ffOnly,
      MergeOptions opts)
      throws IOException {
    requireComplete();
    if (!targets.keySet().equals(repos.keySet())) throw new IOException("pull 目標必須只包含目前維度");
    if (dirty(true)) throw new IOException("pull 要求乾淨工作區（含 untracked）；請先 commit 或 stash push");
    var prior = new TreeMap<DimensionId, String>();
    var commits = new TreeMap<DimensionId, RefStore.Commit>();
    boolean ff = true, already = true;
    String branch = null;
    for (var e : repos.entrySet()) {
      var refs = e.getValue().refs();
      var head = refs.headState();
      if (head.branch() == null || branch != null && !branch.equals(head.branch()))
        throw new IOException("pull 需要目前維度位於分支");
      branch = head.branch();
      prior.put(e.getKey(), head.commit());
      if (expectedHeads != null && !Objects.equals(expectedHeads.get(e.getKey()), head.commit()))
        throw new IOException("pull preview 已過期；請重新預覽");
      String target = targets.get(e.getKey());
      var c = refs.readCommit(target);
      if (!c.metadata().dimension().equals(e.getKey())) throw new IOException("pull 目標維度不符");
      commits.put(e.getKey(), c);
      ff &= refs.isAncestor(head.commit(), target);
      already &= refs.isAncestor(target, head.commit());
    }

    MergeResult result;
    if (already)
      result =
          new MergeResult(
              opts.dryRun() ? "DRY_RUN" : "COMPLETE",
              null,
              new TreeMap<>(),
              new TreeMap<>(),
              prior,
              null);
    else if (ff) {
      var prepared = prepare(commits, null, Scope.all(), true, false);
      var applied = execute("pull", prepared, branch, true, opts.dryRun());
      result =
          new MergeResult(
              applied.state().name(),
              null,
              new TreeMap<>(),
              prepared.plans,
              new TreeMap<>(targets),
              applied.error());
    } else {
      if (ffOnly) throw new IOException("pull --ff-only 拒絕分歧歷史；請一般 pull 進行三方合併");
      result = beginMerge("merge", "remote update", opts, commits);
    }
    return new PullPreview(prior, new TreeMap<>(targets), ff, result);
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
    return beginMerge(mode, revision, opts, null);
  }

  private MergeResult beginMerge(
      String mode,
      String revision,
      MergeOptions opts,
      SortedMap<DimensionId, RefStore.Commit> mergeOverride)
      throws IOException {
    requireComplete();
    if (dirty(true)) throw new IOException("合併要求乾淨工作區（含 untracked）；請先 commit 或 stash push。");
    var targets = mergeOverride == null ? resolveForMerge(revision) : mergeOverride;
    var operation = org.worldgit.core.operation.OperationProgress.operationId();
    var dimensions = new TreeMap<DimensionId, MergeState.Dimension>();
    var plans = new TreeMap<DimensionId, ApplyPlan>();
    var commits = new TreeMap<DimensionId, RefStore.Commit>();
    int nextId = 1;
    var semantics =
        EntityTagRegistry.load(
            layout.world(), layout.dataVersion(), packResolver);
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
        boolean unchanged = false;
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
    try (var source = live == null
        ? new OfflineSnapshotSource(layout, layout.dimensions().get(repo.dimension()), packResolver)
        : live.source(layout.dimensions().get(repo.dimension()))) {
      return repo.workingTree(source, worlds.manifest(), tolerance, text);
    }
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
                layout.world(), layout.dataVersion(), packResolver),
            tolerance,
            !parsed.hasAreas(),
            true,
            layout.dataVersion());
    var plan =
        ApplyPlanner.plan(repo.objects(), repo.dimension(), current, target, Scope.all(), opts);
    applier.validateMetadata(plan);
    if (live != null) live.validate(plan);
    return plan;
  }

  private void saveMerge(MergeState state) throws IOException {
    try (var timing = OperationTimings.stage("merge-state")) {
      state.write(stateRoot.resolve("merge-state.bin"));
      for (var e : state.dimensions().entrySet())
        RegionFile.atomicWrite(
            repos.get(e.getKey()).directory().resolve("MERGE_HEAD"),
            (e.getValue().sourceCommit() + "\n").getBytes(StandardCharsets.US_ASCII));
    }
  }

  private void clearMerge() throws IOException {
    for (var repo : repos.values()) Files.deleteIfExists(repo.directory().resolve("MERGE_HEAD"));
    Files.deleteIfExists(stateRoot.resolve("merge-state.bin"));
    Files.deleteIfExists(MergeState.updatesPath(stateRoot.resolve("merge-state.bin")));
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
    if (OperationState.partial(stateRoot))
      throw new IOException("合併為 PARTIAL；請先 merge --abort");
    var state = activeMerge();
    var selectedRegions =
        state.regions().stream().filter(r -> regionId == 0 || r.id() == regionId).toList();
    if (selectedRegions.isEmpty()) throw new IOException("找不到衝突區域 #" + regionId);
    if (selectedRegions.stream()
        .flatMap(r -> r.atoms().stream())
        .allMatch(a -> a.kind() == MergeReport.Kind.ENTITY || a.path().startsWith("r.")))
      return selectChunks(state, selectedRegions, choice, resolved, dryRun);
    var all = new TreeMap<DimensionId, Set<ChunkPos>>();
    state.dimensions().keySet().forEach(id -> all.put(id, Set.of()));
    try (AutoCloseable lock = live == null ? () -> {} : live.lockChunks(all)) {
      return selectFull(state, regionId, choice, resolved, dryRun);
    } catch (Exception ex) {
      throw ex instanceof IOException io ? io : new IOException(ex);
    }
  }

  private MergeResult selectFull(
      MergeState state, int regionId, MergeReport.Choice choice, boolean resolved, boolean dryRun)
      throws IOException {
    var ds = new TreeMap<>(state.dimensions());
    var plans = new TreeMap<DimensionId, ApplyPlan>();
    var commits = new TreeMap<DimensionId, RefStore.Commit>();
    boolean found = false;
    for (var e : ds.entrySet()) {
      var d = e.getValue();
      var repo = repos.get(e.getKey());
      var regions = new ArrayList<MergeReport.Region>();
      String current = capture(repo), target = current;
      try (var timing = OperationTimings.stage("plan-and-shapes")) {
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
    }
    if (!found) throw new IOException("找不到衝突區域 #" + regionId);
    var updated =
        new MergeState(state.operation(), state.mode(), state.source(), state.message(), ds);
    if (dryRun) return mergeResult("DRY_RUN", updated, plans, new TreeMap<>(), null);
    // 先套用並驗證，再发布 resolved；若中途崩潰，舊狀態仍 unresolved，可 abort。
    var applied =
        execute(
            "merge-select",
            new Prepared(commits, plans),
            null,
            false,
            false,
            () -> saveMerge(updated));
    return mergeResult(
        applied.success() ? "MERGING" : "PARTIAL",
        applied.success() ? updated : state,
        plans,
        new TreeMap<>(),
        applied.error());
  }

  private Set<ChunkPos> affectedChunks(
      DimensionRepository repo, MergeState.Dimension d, List<MergeReport.Region> regions)
      throws IOException {
    var chunks = new TreeSet<ChunkPos>();
    var ids = new HashSet<UUID>();
    for (var r : regions)
      for (var atom : r.atoms()) {
        if (atom.position() != null) chunks.add(atom.position().chunk());
        // ticks／structures 等沒有 position 的 chunk 級 atoms。
        String[] path = atom.path().split("/");
        if (path.length > 1 && path[1].startsWith("c.")) {
          String[] c = path[1].split("\\.");
          chunks.add(new ChunkPos(Integer.parseInt(c[1]), Integer.parseInt(c[2])));
        }
        if (atom.uuid() != null) ids.add(atom.uuid());
      }
    if (!ids.isEmpty()) {
      // 歷史 UUID 端點加上活世界的真正位置；一般方塊區域完全不掃實體。
      var engine = new DiffEngine(repo.objects());
      for (String tree : List.of(d.baseTree(), d.oursTree(), d.theirsTree()))
        for (var e : engine.entities(tree).entrySet())
          if (ids.contains(e.getKey())) chunks.add(e.getValue().chunk());
    }
    return chunks;
  }

  private Set<ChunkPos> entityChunks(WorldLayout.Dimension dimension, Set<UUID> ids)
      throws IOException {
    if (live != null) return live.entityChunks(dimension, ids);
    var result = new TreeSet<ChunkPos>();
    for (var path : RegionFile.list(dimension.entities()))
      try (var region = new RegionFile(path)) {
        for (int i = 0; i < 1024; i++)
          if (region.has(i) && containsEntityIds(region.read(i).list("Entities"), ids))
            result.add(region.pos(i));
      }
    return result;
  }

  private static boolean containsEntityIds(Nbt.ListTag list, Set<UUID> ids) {
    for (Object value : list.values()) {
      var entity = (Nbt.Compound) value;
      if (ids.contains(org.worldgit.core.normalize.EntityNormalizer.uuid(entity))
          || containsEntityIds(entity.list("Passengers"), ids)) return true;
    }
    return false;
  }

  private String captureChunks(DimensionRepository repo, Set<ChunkPos> chunks) throws IOException {
    try (var timing = OperationTimings.stage("capture");
        var source =
            live == null
                ? new OfflineSnapshotSource(layout, layout.dimensions().get(repo.dimension()), packResolver)
                : live.source(layout.dimensions().get(repo.dimension()), chunks)) {
      var editor = new TreeEditor(repo.objects(), null);
      var rules = IgnoreRules.parse(Files.readString(repo.ignorePath()));
      for (var pos : chunks) {
        try {
          var snapshot = source.snapshot(pos, rules).toCompletableFuture().join();
          if (snapshot.isPresent()) {
            if (!snapshot.get().pos().equals(pos)) throw new IOException("来源回傳錯誤 chunk 座標");
            var chunk = snapshot.get();
            if (WorldGitConfig.readRepo(repo.configPath()).entities() == WorldGitConfig.Entities.PLAYER_TOUCHED) {
              var touched = org.worldgit.core.capture.PlayerTouchedEntities.read(repo.directory());
              chunk = org.worldgit.core.capture.PlayerTouchedEntities.filter(chunk, touched);
            }
            editor.replaceTree(pos.treePath(), SnapshotCodec.chunkFiles(chunk));
          }
        } catch (java.util.concurrent.CompletionException ex) {
          throw new IOException("局部 capture 失敗：" + pos, ex.getCause());
        }
      }
      String tree = editor.write();
      repo.objects().flush();
      return tree;
    }
  }

  private ApplyPlan chunkPlan(
      DimensionRepository repo, String current, String target, Set<ChunkPos> chunks)
      throws IOException {
    var rules = IgnoreRules.parse(Files.readString(repo.ignorePath()));
    var options =
        new ApplyPlanner.Options(
            rules,
            rules,
            EntityTagRegistry.load(
                layout.world(), layout.dataVersion(), packResolver),
            0,
            false,
            false,
            layout.dataVersion());
    return ApplyPlanner.plan(
        repo.objects(), repo.dimension(), current, target, Scope.chunkSet(chunks), options);
  }

  private ApplyPlan regionPlan(
      DimensionRepository repo,
      String current,
      String target,
      Set<ChunkPos> chunks,
      List<MergeReport.Region> regions)
      throws IOException {
    var plan = chunkPlan(repo, current, target, chunks);
    var masks = new HashMap<String, long[]>();
    var files = new HashSet<String>();
    for (var r : regions)
      for (var atom : r.atoms()) {
        if (atom.kind() == MergeReport.Kind.BLOCK) {
          var mask = masks.computeIfAbsent(atom.path(), p -> new long[64]);
          int i = atom.position().index();
          mask[i >>> 6] |= 1L << (i & 63);
        } else if (atom.kind() == MergeReport.Kind.FILE) files.add(atom.path());
      }
    var ops = new ArrayList<ApplyPlan.ChunkOp>();
    for (var chunk : plan.chunks().values()) {
      var sections = new TreeMap<Integer, ApplyPlan.SectionOp>();
      for (var section : chunk.sections().values()) {
        String path = chunk.pos().treePath() + "/s." + section.y() + ".bin";
        if (files.contains(path)) sections.put(section.y(), section);
        else {
          var selected = masks.get(path);
          if (selected == null) continue;
          var mask = selected.clone();
          for (int i = 0; i < 4096; i++) if (!section.covers(i)) mask[i >>> 6] &= ~(1L << (i & 63));
          if (Arrays.stream(mask).anyMatch(n -> n != 0))
            sections.put(section.y(), new ApplyPlan.SectionOp(section.y(), section.blob(), mask));
        }
      }
      var op =
          new ApplyPlan.ChunkOp(
              chunk.pos(),
              chunk.delete(),
              sections,
              chunk.biomes(),
              chunk.setTicks(),
              chunk.ticks(),
              chunk.setStructures(),
              chunk.structures());
      if (!op.empty()) ops.add(op);
    }
    return new ApplyPlan(
            plan.dimension(),
            plan.baseTree(),
            plan.targetTree(),
            plan.dataVersion(),
            plan.scope(),
            ops,
            plan.entities(),
            plan.worldMeta(),
            plan.untrackedKept())
        .withRules(plan.ignoreRules());
  }

  private String overlayChunks(
      DimensionRepository repo, String full, String local, Set<ChunkPos> chunks)
      throws IOException {
    var editor = new TreeEditor(repo.objects(), full);
    for (var pos : chunks) {
      var entry = TreeEditor.find(repo.objects(), local, pos.treePath());
      if (entry == null) editor.remove(pos.treePath());
      else {
        var blobs = new TreeMap<String, byte[]>();
        for (var leaf : repo.objects().readTree(entry.id()).values())
          blobs.put(leaf.name(), repo.objects().readBlob(leaf.id()));
        editor.replaceTree(pos.treePath(), blobs);
      }
    }
    return editor.write();
  }

  private MergeResult selectChunks(
      MergeState state,
      List<MergeReport.Region> selected,
      MergeReport.Choice choice,
      boolean resolved,
      boolean dryRun)
      throws IOException {
    var affected = new TreeMap<DimensionId, Set<ChunkPos>>();
    for (var entry : state.dimensions().entrySet()) {
      var regions = selected.stream().filter(r -> r.dimension().equals(entry.getKey())).toList();
      if (!regions.isEmpty())
        affected.put(
            entry.getKey(), affectedChunks(repos.get(entry.getKey()), entry.getValue(), regions));
    }
    var plans = new TreeMap<DimensionId, ApplyPlan>();
    var targets = new TreeMap<DimensionId, String>();
    var ds = new TreeMap<>(state.dimensions());
    var journal = new LinkedHashMap<String, Object>();
    journal.put("version", 2);
    journal.put("operation", org.worldgit.core.operation.OperationProgress.operationId().toString());
    journal.put("merge", state.operation().toString());
    journal.put("mode", "merge-select");
    journal.put("state", "APPLYING");
    boolean attempted = false;
    var locking = new TreeMap<>(affected);
    var entityIds = regionEntityIds(state, selected);
    if (!entityIds.isEmpty()) repos.keySet().forEach(id -> locking.put(id, Set.of()));
    try (var lockTiming = OperationTimings.stage("region-operation");
        AutoCloseable lock = live == null ? () -> {} : live.lockChunks(locking)) {
      if (!entityIds.isEmpty())
        for (var dimension : layout.dimensions().values())
          if (repos.containsKey(dimension.id())) {
            var positions = entityChunks(dimension, entityIds);
            if (!positions.isEmpty()) {
              var expanded = new TreeSet<>(affected.getOrDefault(dimension.id(), Set.of()));
              expanded.addAll(positions);
              affected.put(dimension.id(), expanded);
            }
          }
      var rows = new TreeMap<String, Object>();
      for (var entry : affected.entrySet()) {
        var repo = repos.get(entry.getKey());
        var d = ds.get(entry.getKey());
        String current = captureChunks(repo, entry.getValue()), target = current;
        try (var timing = OperationTimings.stage("plan-and-shapes")) {
          var selectedIds = new HashSet<Integer>();
          // 先清除 root／乘客 UUID 的舊位置，再於 region 所屬維度生成來源。
          // 乘客可能已被玩家拆離，成為另一個 chunk 的 root。
          if (choice != MergeReport.Choice.MANUAL && !entityIds.isEmpty()) {
            target = MergeEngine.withoutEntities(repo.objects(), target, entityIds);
          }
          for (var r : selected)
            if (r.dimension().equals(entry.getKey())) {
              selectedIds.add(r.id());
              if (choice != MergeReport.Choice.MANUAL)
                target =
                    MergeEngine.select(
                        repo.objects(),
                        target,
                        switch (choice) {
                          case OURS -> d.oursTree();
                          case THEIRS -> d.theirsTree();
                          case BASE -> d.baseTree();
                          default -> throw new AssertionError();
                        },
                        r);
            }
          var plan = regionPlan(repo, current, target, entry.getValue(), selected);
          if (live != null) live.validate(plan);
          plans.put(entry.getKey(), plan);
          targets.put(entry.getKey(), target);
          var prior = repo.refs().readCommit(d.resultCommit());
          String fullTarget = overlayChunks(repo, prior.tree(), target, entry.getValue());
          fullTarget = org.worldgit.core.capture.PlayerTouchedEntities.reconcile(repo.objects(), fullTarget, List.of(d.baseTree(), d.oursTree(), d.theirsTree()));
          var cells =
              selected.stream()
                  .filter(r -> r.dimension().equals(entry.getKey()))
                  .flatMap(r -> r.atoms().stream())
                  .filter(a -> a.kind() == MergeReport.Kind.BLOCK)
                  .map(MergeReport.Atom::position)
                  .toList();
          var report =
              new MergeReport(
                  d.report().automaticallyMergedSections(),
                  d.report().regions().stream()
                      .map(r -> selectedIds.contains(r.id()) ? r.selected(choice, resolved) : r)
                      .toList(),
                  d.report().ruleDifferences(),
                  MergeEngine.updateShapesNear(
                      repo.objects(),
                      d.baseTree(),
                      d.oursTree(),
                      d.theirsTree(),
                      fullTarget,
                      d.report().updateShapes(),
                      cells),
                  d.report().warnings());
          String c =
              dryRun
                  ? prior.id()
                  : provisional(
                      repo,
                      fullTarget,
                      prior.parents().isEmpty() ? null : prior.parents().getFirst(),
                      prior.metadata());
          if (!dryRun)
            repo.refs()
                .updateRef("refs/worldgit/operations/" + journal.get("operation") + "/to", null, c);
          ds.put(entry.getKey(), d.withResult(c, report));
          rows.put(
              entry.getKey().value(),
              Map.of(
                  "from",
                  d.resultCommit(),
                  "to",
                  c,
                  "chunks",
                  entry.getValue().stream().map(p -> List.of(p.x(), p.z())).toList()));
        }
      }
      var updated =
          new MergeState(state.operation(), state.mode(), state.source(), state.message(), ds);
      if (dryRun) return mergeResult("DRY_RUN", updated, plans, new TreeMap<>(), null);
      journal.put("dimensions", rows);
      writeJournal(journal);
      attempted = true;
      try (var timing = OperationTimings.stage("apply-and-barrier")) {
        if (live != null) live.applyRegions(plans.values());
        else if (groupedWriter) applier.applyAll(plans.values(), session);
        else for (var plan : plans.values()) writer.apply(plan, session);
      }
      for(var entry : plans.entrySet()) updateTouched(repos.get(entry.getKey()),entry.getValue());
      try (var timing = OperationTimings.stage("verify")) {
        for (var entry : affected.entrySet()) {
          var repo = repos.get(entry.getKey());
          var checked =
              chunkPlan(
                  repo,
                  captureChunks(repo, entry.getValue()),
                  targets.get(entry.getKey()),
                  entry.getValue());
          if (!checked.empty())
            throw new IOException("區域 chunk 驗證失敗：" + entry.getKey() + " " + checked.stats());
          repo.invalidateIndex();
        }
      }
      if (live != null) live.beforeComplete();
      // journal 一直到增量狀態 force 完成才 COMPLETE；崩潰或保存失敗可 abort。
      try (var timing = OperationTimings.stage("merge-state")) {
        MergeState.append(stateRoot.resolve("merge-state.bin"), state, updated);
      }
      journal.put("state", "COMPLETE");
      writeJournal(journal);
      return mergeResult("MERGING", updated, plans, new TreeMap<>(), null);
    } catch (Exception ex) {
      if (!attempted) throw ex instanceof IOException io ? io : new IOException("區域操作失敗", ex);
      journal.put("state", "PARTIAL");
      journal.put("error", ex.toString());
      writeJournal(journal);
      return mergeResult("PARTIAL", state, plans, new TreeMap<>(), ex.toString());
    }
  }

  private Set<UUID> regionEntityIds(MergeState state, List<MergeReport.Region> selected)
      throws IOException {
    var roots = new HashSet<UUID>();
    selected.forEach(
        r ->
            r.atoms()
                .forEach(
                    a -> {
                      if (a.uuid() != null) roots.add(a.uuid());
                    }));
    var ids = new HashSet<>(roots);
    if (ids.isEmpty()) return ids;
    for (var entry : state.dimensions().entrySet()) {
      var engine = new DiffEngine(repos.get(entry.getKey()).objects());
      var d = entry.getValue();
      for (String tree : List.of(d.baseTree(), d.oursTree(), d.theirsTree()))
        for (var entity : engine.entities(tree).entrySet())
          if (roots.contains(entity.getKey()))
            collectEntityIds(entity.getValue().entity().data(), ids);
    }
    return ids;
  }

  private static void collectEntityIds(Nbt.Compound entity, Set<UUID> ids) {
    ids.add(org.worldgit.core.normalize.EntityNormalizer.uuid(entity));
    for (var child : entity.list("Passengers").values())
      collectEntityIds((Nbt.Compound) child, ids);
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
    if (OperationState.partial(stateRoot))
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
    if (!dryRun) {
      var journal = new LinkedHashMap<String, Object>();
      journal.put("version", 2);
      journal.put("operation", org.worldgit.core.operation.OperationProgress.operationId().toString());
      journal.put("merge", state.operation().toString());
      journal.put("mode", "merge-mark-resolved");
      journal.put("state", "APPLYING");
      writeJournal(journal);
      try {
        if (live != null) live.beforeComplete();
        MergeState.append(stateRoot.resolve("merge-state.bin"), state, updated);
        journal.put("state", "COMPLETE");
        writeJournal(journal);
      } catch (IOException ex) {
        journal.put("state", "PARTIAL");
        journal.put("error", message(ex));
        writeJournal(journal);
        return mergeResult("PARTIAL", state, new TreeMap<>(), new TreeMap<>(), message(ex));
      }
    }
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
    if (OperationState.partial(stateRoot))
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
    // continue 的完整 capture 也更新全部提示，涵蓋 MERGING 期間的區域外 manual 編輯。
    var completedReports = new TreeMap<>(state.dimensions());
    for (var e : completedReports.entrySet()) {
      var d = e.getValue();
      var report = d.report();
      e.setValue(
          d.withResult(
              d.resultCommit(),
              new MergeReport(
                  report.automaticallyMergedSections(),
                  report.regions(),
                  report.ruleDifferences(),
                  MergeEngine.updateShapes(
                      repos.get(e.getKey()).objects(),
                      e.getKey(),
                      d.baseTree(),
                      d.oursTree(),
                      d.theirsTree(),
                      trees.get(e.getKey())),
                  report.warnings())));
    }
    state =
        new MergeState(
            state.operation(), state.mode(), state.source(), state.message(), completedReports);
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
      try (var timing = OperationTimings.stage("verify-full-commit")) {
        for (var e : repos.entrySet()) {
          var checked = mergePlan(e.getValue(), capture(e.getValue()), trees.get(e.getKey()));
          if (!checked.empty())
            throw new IOException("提交前維度驗證失敗：" + e.getKey() + " " + checked.stats());
        }
      }
      if (live != null) live.beforeComplete();
      for (var e : targets.entrySet()) {
        var refs = repos.get(e.getKey()).refs();
        var h = state.dimensions().get(e.getKey()).original();
        if (h.branch() != null)
          refs.updateRef("refs/heads/" + h.branch(), h.commit(), e.getValue());
        else refs.checkout(h, new RefStore.Head(e.getValue(), null));
        completed.add(e.getKey());
      }
      for (var e : targets.entrySet()) {
        org.worldgit.core.capture.PlayerTouchedEntities.restore(repos.get(e.getKey()).objects(), trees.get(e.getKey()), repos.get(e.getKey()).directory());
        repos.get(e.getKey()).invalidateIndex();
        Files.deleteIfExists(repos.get(e.getKey()).directory().resolve("untracked.yml"));
      }
      state.write(stateRoot.resolve("last-merge-report.bin"));
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
                    layout.world(), layout.dataVersion(), packResolver),
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

    if (error != null) throw error;
  }
}
