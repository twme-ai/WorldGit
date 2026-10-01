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

  public void initialize(String template, WorldGitConfig.Track track) throws IOException {
    if (store.head() != null) throw new IOException("維度已 init：" + dimension);
    if (!Files.exists(ignorePath()))
      WorldGitConfig.write(ignorePath(), IgnoreTemplates.load(template));
    if (!Files.exists(configPath()))
      WorldGitConfig.write(configPath(), WorldGitConfig.write(new WorldGitConfig.Repo(track)));
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
   * 指定明細層級與 chunk 視窗的 status：遊戲內預覽只需要玩家附近的方塊級差異，不必展開整個世界。
   * window 為 null 表示全世界；實體仍全域比對後才裁切（見 DiffEngine）。
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
   * 擷取後先問 gate：回傳 false 時不寫 commit（仍更新 index，行為同 status）。自動 commit 用它實作「小變動合併到較長間隔」，
   * 不必先 status 再 commit 而重複擷取一次。初始 commit（沒有 HEAD）不受 gate 影響。
   */
  public CommitResult commit(
      SnapshotSource source,
      Map<DimensionId, String> dimensions,
      CommitMetadata metadata,
      double tolerance,
      java.util.function.Predicate<Status> gate)
      throws IOException {
    if (OperationState.partial(directory.getParent())) throw new IOException("世界為 PARTIAL，請先 switch --force 或 reset --hard 恢復");
    if (!dimension.equals(metadata.dimension())) throw new IOException("metadata 維度與 repo 不符");
    if (metadata.mcDataVersion() != source.dataVersion())
      throw new IOException("metadata DataVersion 與來源不符");
    Capture c = capture(source, dimensions, tolerance, false, DiffEngine.Detail.SUMMARY, null);
    var status=c.status;
    String commitTree=c.tree;
    var keptUntracked=WorldOperations.untracked(directory);
    if (!keptUntracked.isEmpty() && !metadata.auto()) {
      String base=c.head==null ? null : store.readCommit(c.head).tree();
      status=new Status(new DiffEngine(store).compare(dimension,base,c.tree,tolerance,DiffEngine.Detail.SUMMARY),
          status.candidates(),status.payloadsRead(),status.ignoreChanged(),status.warnings());
    } else if (!keptUntracked.isEmpty()) {
      var editor=new TreeEditor(store,c.tree);
      String base=c.head==null ? null : store.readCommit(c.head).tree();
      for(var pos:keptUntracked) if(TreeEditor.find(store,base,pos.treePath())==null) editor.remove(pos.treePath());
      commitTree=editor.write();store.flush();
    }
    String id = null;
    if (c.head == null || (!status.diff.empty() && gate.test(status)))
      id = store.commit(commitTree, c.head, metadata);
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
  public String workingTree(SnapshotSource source, Map<DimensionId,String> dimensions, double tolerance) throws IOException {
    Capture capture = capture(source, dimensions, tolerance, true, DiffEngine.Detail.SUMMARY, null);
    capture.index.save(directory.resolve("worldgit.index"));
    return capture.tree;
  }

  public void invalidateIndex() throws IOException { Files.deleteIfExists(directory.resolve("worldgit.index")); }

  private Capture capture(
      SnapshotSource source,
      Map<DimensionId, String> dimensions,
      double tolerance,
      boolean full,
      DiffEngine.Detail detail,
      Set<ChunkPos> window)
      throws IOException {
    if (!dimension.equals(source.dimension())) throw new IOException("快照來源維度不符");
    String head = store.head(), base = head == null ? null : store.readCommit(head).tree();
    String ignore = Files.exists(ignorePath()) ? Files.readString(ignorePath()) : "";
    IgnoreRules rules = IgnoreRules.parse(ignore);
    WorldGitConfig.Repo config = WorldGitConfig.readRepo(configPath());
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
    full |= old.tree().isEmpty();
    // 全量 capture 從空樹重建，否則遺失 index 時會保留已從磁碟刪除的舊 chunk。
    String working = full ? null : old.tree();
    var editor = new TreeEditor(store, working);
    SnapshotSource.Scan scan = source.scan(old, full);
    for (ChunkPos pos : new TreeSet<>(scan.candidates())) {
      Optional<ChunkSnapshot> snapshot;
      try {
        snapshot = source.snapshot(pos, rules).toCompletableFuture().join();
      } catch (CompletionException e) {
        throw new IOException("讀取 chunk " + pos + " 失敗", e.getCause());
      }
      if (snapshot.isEmpty()) editor.remove(pos.treePath());
      else {
        if (!pos.equals(snapshot.get().pos())) throw new IOException("來源回傳錯誤 chunk 座標");
        editor.replaceTree(pos.treePath(), SnapshotCodec.chunkFiles(snapshot.get()));
      }
    }
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
    } else editor.putBlob("worldgit.yml", configText.getBytes(StandardCharsets.UTF_8));
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
    if (config.track() == WorldGitConfig.Track.MODIFIED_ONLY)
      warnings.add("track: modified-only 已記錄；Phase 1 尚未篩選自然地形，目前仍追蹤全部 full chunk。");
    String comparison = tree;
    var untracked = WorldOperations.untracked(directory);
    if (!untracked.isEmpty() && base != null) {
      var comparisonEditor = new TreeEditor(store, tree);
      for (var pos : untracked)
        if (TreeEditor.find(store, base, pos.treePath()) == null) comparisonEditor.remove(pos.treePath());
      comparison = comparisonEditor.write(); store.flush();
      warnings.add("保留的 untracked chunk：" + untracked.size() + "；explicit commit 會將它們重新納入追蹤。");
    }
    if (OperationState.partial(directory.getParent())) warnings.add("世界為 PARTIAL，需全範圍重套恢復。");
    var diff = new DiffEngine(store).compare(dimension, base, comparison, tolerance, detail, window);
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
