package org.worldgit.core.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import org.worldgit.core.anvil.*;
import org.worldgit.core.capture.*;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

/** 單維度的正式入口；取得 lock 後才能開啟，commit/status 共用 capture。 */
public final class DimensionRepository implements AutoCloseable {
  public record Status(
      WorldDiff diff,
      int candidates,
      int payloadsRead,
      boolean ignoreChanged,
      List<String> warnings) {
    public Status {
      warnings = List.copyOf(warnings);
    }
  }

  public record CommitResult(String commit, Status status) {
    public boolean changed() {
      return commit != null;
    }
  }

  private record Capture(String head, String tree, ScanIndex index, Status status) {}

  private final Path directory;
  private final DimensionId dimension;
  private final RepoLock lock;
  private final JGitStore store;

  public DimensionRepository(Path directory, DimensionId dimension, boolean create)
      throws IOException {
    this.directory = directory;
    this.dimension = dimension;
    lock = RepoLock.acquire(directory);
    try {
      store = new JGitStore(directory, create);
    } catch (Throwable t) {
      lock.close();
      throw t;
    }
  }

  public Path directory() {
    return directory;
  }

  public DimensionId dimension() {
    return dimension;
  }

  public ObjectStore objects() {
    return store;
  }

  public RefStore refs() {
    return store;
  }
  /** 舊位置仍可讀；未完成的全組操作不能被新單維度寫入繞過。 */
  public void requireLegacyComplete() throws IOException {
    if (directory.getFileName().toString().equals(dimension.directoryName())) {
      Path parent=directory.getParent();
      if(OperationState.partial(parent) || Files.exists(parent.resolve("merge-state.bin")))
        throw new IOException("舊版全組 PARTIAL／MERGING 尚未完成；請先用舊版恢復，再 migrate");
      for(String name:List.of("push-state.yml","fetch-state.yml","tag-state.yml")) {
        var state=OperationState.read(parent.resolve(name));
        if(!state.isEmpty() && !"COMPLETE".equals(state.get("state"))) throw new IOException("舊版 "+name+" 尚未完成；請先恢復，再 migrate");
      }
    }
  }

  public void initialize(String template, WorldGitConfig.Track track) throws IOException {
    initialize(template, track, template.equals("creative") ? WorldGitConfig.Entities.PLAYER_TOUCHED : WorldGitConfig.Entities.ALL);
  }

  public void initialize(String template, WorldGitConfig.Track track, WorldGitConfig.Entities entities) throws IOException {
    if (store.head() != null) throw new IOException("維度已 init：" + dimension);
    if (!Files.exists(ignorePath()))
      WorldGitConfig.write(ignorePath(), IgnoreTemplates.load(template));
    if (!Files.exists(configPath()))
      WorldGitConfig.write(configPath(), WorldGitConfig.write(new WorldGitConfig.Repo(track, entities)));
  }

  public Path ignorePath() {
    return directory.resolve(".wgignore");
  }

  public Path configPath() {
    return directory.resolve("worldgit-repo.yml");
  }

  public Status status(
      SnapshotSource source, Map<DimensionId, String> dimensions, double tolerance, boolean full)
      throws IOException {
    return status(source, dimensions, tolerance, full, DiffEngine.Detail.SUMMARY, null);
  }

  /**
   * 指定明細層級與 chunk 視窗的 status：遊戲內預覽只需要玩家附近的方塊級差異，不必展開整個世界。 window 為 null 表示全世界；實體仍全域比對後才裁切（見
   * DiffEngine）。
   */
  public Status status(
      SnapshotSource source,
      Map<DimensionId, String> dimensions,
      double tolerance,
      boolean full,
      DiffEngine.Detail detail,
      Set<ChunkPos> window)
      throws IOException {
    Capture c = capture(source, dimensions, tolerance, full, detail, window);
    c.index.save(directory.resolve("worldgit.index"));
    return c.status;
  }

  public CommitResult commit(
      SnapshotSource source,
      Map<DimensionId, String> dimensions,
      CommitMetadata metadata,
      double tolerance)
      throws IOException {
    return commit(source, dimensions, metadata, tolerance, status -> true);
  }

  /**
   * 擷取後先問 gate：回傳 false 時不寫 commit（仍更新 index，行為同 status）。自動 commit 用它實作「小變動合併到較長間隔」， 不必先 status 再
   * commit 而重複擷取一次。初始 commit（沒有 HEAD）不受 gate 影響。
   */
  public CommitResult commit(
      SnapshotSource source,
      Map<DimensionId, String> dimensions,
      CommitMetadata metadata,
      double tolerance,
      java.util.function.Predicate<Status> gate)
      throws IOException {
    requireLegacyComplete();
    if (Files.exists(directory.resolve("merge-state.bin")))
      throw new IOException("世界為 MERGING，請 resolve 後 merge --continue 或 merge --abort");
    if (OperationState.partial(directory))
      throw new IOException("世界為 PARTIAL，請先 switch --force 或 reset --hard 恢復");
    if (!dimension.equals(metadata.dimension())) throw new IOException("metadata 維度與 repo 不符");
    if (metadata.mcDataVersion() != source.dataVersion())
      throw new IOException("metadata DataVersion 與來源不符");
    Capture c = capture(source, dimensions, tolerance, false, DiffEngine.Detail.SUMMARY, null);
    var status = c.status;
    String commitTree = c.tree;
    var keptUntracked = WorldOperations.untracked(directory);
    if (!keptUntracked.isEmpty() && !metadata.auto()) {
      String base = c.head == null ? null : store.readCommit(c.head).tree();
      status =
          new Status(
              new DiffEngine(store)
                  .compare(dimension, base, c.tree, tolerance, DiffEngine.Detail.SUMMARY),
              status.candidates(),
              status.payloadsRead(),
              status.ignoreChanged(),
              status.warnings());
    } else if (!keptUntracked.isEmpty()) {
      var editor = new TreeEditor(store, c.tree);
      String base = c.head == null ? null : store.readCommit(c.head).tree();
      for (var pos : keptUntracked)
        if (TreeEditor.find(store, base, pos.treePath()) == null) editor.remove(pos.treePath());
      commitTree = editor.write();
      store.flush();
    }
    String id = null;
    if (c.head == null || (!status.diff.empty() && gate.test(status)))
      id = store.commit(commitTree, c.head, metadata);
    if (id != null) PlayerTouchedEntities.restore(store, commitTree, directory);
    if (id != null && !metadata.auto()) Files.deleteIfExists(directory.resolve("untracked.yml"));
    String head = id == null ? c.head : id;
    new ScanIndex(
            head == null ? "" : head,
            c.index.rules(),
            c.tree,
            c.index.scannedAt(),
            c.index.chunks())
        .save(directory.resolve("worldgit.index"));
    return new CommitResult(id, status);
  }

  /** 全量 capture 的中性 tree；不移動 HEAD。apply 不可依賴舊 index 的候選猜測。 */
  public String workingTree(
      SnapshotSource source, Map<DimensionId, String> dimensions, double tolerance)
      throws IOException {
    Capture capture = capture(source, dimensions, tolerance, true, DiffEngine.Detail.SUMMARY, null);
    capture.index.save(directory.resolve("worldgit.index"));
    return capture.tree;
  }

  /** 合併規則的 capture，保留實體模式與維度資料歸屬，不改 sidecar。 */
  public String workingTree(SnapshotSource source, Map<DimensionId, String> dimensions,
      double tolerance, String rules) throws IOException {
    return capture(source, dimensions, tolerance, true, DiffEngine.Detail.SUMMARY, null, rules).tree;
  }

  public void invalidateIndex() throws IOException {
    Files.deleteIfExists(directory.resolve("worldgit.index"));
  }

  private Capture capture(
      SnapshotSource source,
      Map<DimensionId, String> dimensions,
      double tolerance,
      boolean full,
      DiffEngine.Detail detail,
      Set<ChunkPos> window)
      throws IOException {
    return capture(source, dimensions, tolerance, full, detail, window, null);
  }

  private Capture capture(
      SnapshotSource source,
      Map<DimensionId, String> dimensions,
      double tolerance,
      boolean full,
      DiffEngine.Detail detail,
      Set<ChunkPos> window, String ignoreOverride)
      throws IOException {
    if (!dimension.equals(source.dimension())) throw new IOException("快照來源維度不符");
    String head = store.head(), base = head == null ? null : store.readCommit(head).tree();
    String ignore = ignoreOverride != null ? ignoreOverride : Files.exists(ignorePath()) ? Files.readString(ignorePath()) : "";
    IgnoreRules rules = IgnoreRules.parse(ignore);
    WorldGitConfig.Repo config = WorldGitConfig.readRepo(configPath());
    Optional<Set<ChunkPos>> modified = Optional.empty();
    if (config.track() == WorldGitConfig.Track.MODIFIED_ONLY) {
      modified = source.modifiedChunks();
      var saved = ModifiedChunks.read(directory);
      if (modified.isEmpty()) modified = saved;
      else if (saved.isPresent()) {
        var union = new TreeSet<>(modified.get());
        union.addAll(saved.get());
        modified = Optional.of(union);
      }
      modified = modified.map(Set::copyOf);
    }
    var touched = PlayerTouchedEntities.read(directory);
    boolean playerTouched = config.entities() == WorldGitConfig.Entities.PLAYER_TOUCHED;
    var seenEntities = new TreeSet<UUID>();
    String modifiedPolicy = modified.map(s -> new TreeSet<>(s).toString()).orElse("unknown");
    String configText = WorldGitConfig.write(config);
    String rulesHash =
        OfflineSnapshotSource.hash(
            0,
            (ignore
                    + "\0"
                    + configText
                    + "\0"
                    + tolerance
                    + "\0"
                    + source.normalizationFingerprint())
                .concat("\0" + modifiedPolicy + "\0" + (playerTouched ? touched.toString() : "all"))
                .getBytes(StandardCharsets.UTF_8));
    var warnings = new ArrayList<String>();
    warnings.addAll(source.warnings());
    ScanIndex old;
    try {
      old = ScanIndex.read(directory.resolve("worldgit.index"));
      if (!old.tree().isEmpty()) store.readTree(old.tree());
    } catch (IOException | RuntimeException e) {
      old = ScanIndex.empty();
      warnings.add("index 快取無效，已全量重建：" + e.getMessage());
    }
    if (!Objects.equals(old.head(), head == null ? "" : head) || !old.rules().equals(rulesHash))
      old = ScanIndex.empty();
    full |= old.tree().isEmpty() || playerTouched && !touched.isEmpty();
    // 全量 capture 從空樹重建，否則遺失 index 時會保留已從磁碟刪除的舊 chunk。
    String working = full ? null : old.tree();
    var editor = new TreeEditor(store, working);
    org.worldgit.core.operation.OperationProgress.report(dimension, "scan", 0, null, org.worldgit.core.operation.OperationProgress.Unit.CHUNK);
    SnapshotSource.Scan scan = source.scan(old, full);
    long completed = 0;
    for (ChunkPos pos : new TreeSet<>(scan.candidates())) {
      org.worldgit.core.operation.OperationProgress.report(dimension, "capture", completed++, (long) scan.candidates().size(), org.worldgit.core.operation.OperationProgress.Unit.CHUNK);
      if (modified.isPresent() && !modified.get().contains(pos)) {
        editor.remove(pos.treePath());
        continue;
      }
      Optional<ChunkSnapshot> snapshot;
      try {
        snapshot = source.snapshot(pos, rules).toCompletableFuture().join();
      } catch (CompletionException e) {
        throw new IOException("讀取 chunk " + pos + " 失敗", e.getCause());
      }
      if (snapshot.isEmpty()) editor.remove(pos.treePath());
      else {
        if (!pos.equals(snapshot.get().pos())) throw new IOException("來源回傳錯誤 chunk 座標");
        var chunk = snapshot.get();
        if (playerTouched) {
          var raw = source.snapshot(pos, IgnoreRules.none()).toCompletableFuture().join();
          if (raw.isPresent()) for (var entity : raw.get().entities()) PlayerTouchedEntities.collect(entity.data(), seenEntities);
          chunk = PlayerTouchedEntities.filter(chunk, touched);
        }
        editor.replaceTree(pos.treePath(), SnapshotCodec.chunkFiles(chunk));
      }
    }
    if (playerTouched) {
      touched.retainAll(seenEntities);
      editor.putBlob(PlayerTouchedEntities.FILE, PlayerTouchedEntities.bytes(touched));
    }
    org.worldgit.core.operation.OperationProgress.report(dimension, "capture", scan.candidates().size(), (long) scan.candidates().size(), org.worldgit.core.operation.OperationProgress.Unit.CHUNK);
    editor.putBlob(".wgignore", ignore.getBytes(StandardCharsets.UTF_8));
    if (dimension.equals(DimensionId.OVERWORLD)) {
      var meta = MetadataNormalizer.normalize(source.worldMetadata(), rules);
      meta.put("worldgit.yml", configText.getBytes(StandardCharsets.UTF_8));
      editor.replaceTree("world-meta", meta);
      StringBuilder yaml = new StringBuilder("dimensions:\n");
      for (var e : new TreeMap<>(dimensions).entrySet())
        yaml.append("  '")
            .append(e.getKey().value())
            .append("': '")
            .append(e.getValue().replace("'", "''"))
            .append("'\n");
      if (dimensions.isEmpty()) yaml = new StringBuilder("dimensions: {}\n");
      editor.putBlob("dimensions", yaml.toString().getBytes(StandardCharsets.UTF_8));
    } else {
      editor.putBlob("worldgit.yml", configText.getBytes(StandardCharsets.UTF_8));
      editor.replaceTree("dimension-meta", MetadataNormalizer.normalize(source.worldMetadata(), rules));
    }
    String tree = editor.write();
    store.flush();
    if (head != null && !scan.candidates().isEmpty()) tree = sticky(base, tree, tolerance);
    boolean ignoreChanged = false;
    if (base != null) {
      var e = TreeEditor.find(store, base, ".wgignore");
      ignoreChanged =
          e == null
              || !Arrays.equals(store.readBlob(e.id()), ignore.getBytes(StandardCharsets.UTF_8));
    }
    if (ignoreChanged) warnings.add(".wgignore 已修改；新規則排除的已追蹤內容會在下次 commit 從快照移除。");
    if (config.track() == WorldGitConfig.Track.MODIFIED_ONLY && modified.isEmpty())
      warnings.add("track: modified-only 缺少完整的曾編輯 chunk 集合；保守追蹤全部 full chunk。");
    String comparison = tree;
    var untracked = WorldOperations.untracked(directory);
    if (!untracked.isEmpty() && base != null) {
      var comparisonEditor = new TreeEditor(store, tree);
      for (var pos : untracked)
        if (TreeEditor.find(store, base, pos.treePath()) == null)
          comparisonEditor.remove(pos.treePath());
      comparison = comparisonEditor.write();
      store.flush();
      warnings.add("保留的 untracked chunk：" + untracked.size() + "；explicit commit 會將它們重新納入追蹤。");
    }
    if (OperationState.partial(directory)) warnings.add("世界為 PARTIAL，需全範圍重套恢復。");
    var diff =
        new DiffEngine(store).compare(dimension, base, comparison, tolerance, detail, window);
    if (diff.entities().size() > 50) warnings.add("實體變動較多；可考慮生存範本或 entity * !persistent。");
    var status =
        new Status(diff, scan.candidates().size(), scan.payloadsRead(), ignoreChanged, warnings);
    return new Capture(
        head,
        tree,
        new ScanIndex(head == null ? "" : head, rulesHash, tree, scan.scannedAt(), scan.stamps()),
        status);
  }

  private String sticky(String head, String tree, double tolerance) throws IOException {
    var engine = new DiffEngine(store);
    var old = engine.entities(head);
    var current = engine.entities(tree);
    if (old.isEmpty() || current.isEmpty()) return tree;
    var grouped = new TreeMap<ChunkPos, List<EntitySnapshot>>();
    var originalChunks = new TreeSet<ChunkPos>();
    boolean changed = false;
    for (var e : current.values()) {
      originalChunks.add(e.chunk());
      var anchor = old.get(e.entity().uuid());
      var chosen = e;
      if (anchor != null
          && EntityNormalizer.stickyEqual(anchor.entity(), e.entity(), tolerance)
          && TreeEditor.find(store, tree, anchor.chunk().treePath()) != null) {
        chosen = anchor;
        changed |=
            !Arrays.equals(anchor.entity().bytes(), e.entity().bytes())
                || !anchor.chunk().equals(e.chunk());
      }
      grouped.computeIfAbsent(chosen.chunk(), k -> new ArrayList<>()).add(chosen.entity());
    }
    if (!changed) return tree;
    var editor = new TreeEditor(store, tree);
    originalChunks.addAll(grouped.keySet());
    for (var pos : originalChunks) {
      var entities = grouped.get(pos);
      if (entities == null || entities.isEmpty()) editor.remove(pos.treePath() + "/entities.bin");
      else editor.putBlob(pos.treePath() + "/entities.bin", SnapshotCodec.entities(entities));
    }
    String result = editor.write();
    store.flush();
    return result;
  }

  public WorldDiff diff(String a, String b, double tolerance) throws IOException {
    return diff(a, b, tolerance, DiffEngine.Detail.SUMMARY);
  }

  public WorldDiff diff(String a, String b, double tolerance, DiffEngine.Detail detail)
      throws IOException {
    String before = a == null ? null : store.readCommit(store.resolve(a)).tree(),
        after = b == null ? null : store.readCommit(store.resolve(b)).tree();
    return new DiffEngine(store).compare(dimension, before, after, tolerance, detail);
  }

  public List<RefStore.Commit> log(int limit) throws IOException {
    return store.log(limit);
  }

  public List<Long> repack() throws IOException {
    List<Long> sizes = store.repack();
    Files.deleteIfExists(directory.resolve("worldgit.index"));
    return sizes;
  }

  @Override
  public void close() throws IOException {
    try {
      store.close();
    } finally {
      lock.close();
    }
  }
}
