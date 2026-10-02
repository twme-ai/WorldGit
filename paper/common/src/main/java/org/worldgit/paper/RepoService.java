package org.worldgit.paper;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.World;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.model.*;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.core.service.WorldRepositories.Batch;
import org.worldgit.core.service.WorldRepositories.Outcome;
import org.worldgit.core.store.RefStore;

/**
 * 所有 repo 操作的單一入口。repo 狀態（HEAD、index、operation lock）不屬於任何 region，所以全部經過同一條背景執行緒，
 * 避免競爭（docs/08 §7）。呼叫端永遠在背景拿結果，不會在 tick 執行緒等待；快照的「複製」才回到擁有執行緒（LiveCopier）。
 */
public final class RepoService implements AutoCloseable {
  /** 單次 status/commit 的實測，供 log 與量測使用。 */
  public record Measurement(
      DimensionId dimension, int candidates, int live, int payloadsRead, long totalMillis, String copy, String census) {}

  private final WorldGitPlugin plugin;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "WorldGit-Repo");
            t.setDaemon(true);
            return t;
          });
  private final List<Measurement> measurements = new CopyOnWriteArrayList<>();
  private volatile ApplyQueue active;
  private volatile boolean stopping;
  private final ApplyUi applyUi;
  boolean stopping() { return stopping; }
  boolean cancel() { var q=active; if(q==null) return false; q.cancelled.set(true); return true; }
  void clearPreviews() {
    for(var p:plugin.getServer().getOnlinePlayers()) { plugin.fabric().clear(p); plugin.displays().clear(p); }
  }
  void shutdownApply() {
    stopping=true;
    applyUi.shutdown();
    var q=active;
    if(q==null) return;
    synchronized(this) {
      q.cancelled.set(true); q.stopping=true;
      try {
        var root=WorldMapper.map().layout().repositoryRoot();
        var journal=org.worldgit.core.service.OperationState.read(root.resolve("apply-state.yml"));
        if(!journal.isEmpty() && !"COMPLETE".equals(journal.get("state"))) {
          journal.put("state","PARTIAL"); journal.put("error","插件關閉中");
          org.worldgit.core.service.OperationState.write(root.resolve("apply-state.yml"),journal);
        }
      } catch(IOException e) { plugin.getLogger().warning("保存 PARTIAL journal 失敗："+e); }
    }
    executor.shutdownNow();
  }
  boolean applying() { return active!=null; }
  ApplyQueue activeQueue() { return active; }
  interface Operation<T> { T run(PaperOperations operations) throws IOException; }
  <T> CompletableFuture<T> operation(String target,Operation<T> work) {
    if(active!=null) return CompletableFuture.failedFuture(new IOException("已有進行中的操作；可用 /wg cancel 取消"));
    return submit(()->{
      var q=new ApplyQueue(plugin); active=q;
      var locks=new ArrayList<AutoCloseable>();
      try {
        var mapping=WorldMapper.map(); applyUi.start(q,target);
        try(var ops=new PaperOperations(plugin,mapping,q)) {
          // capture、stash 保存、計畫、驗證、HEAD 均在同一編輯鎖內。
          for(var entry:mapping.worlds().entrySet()) {
            var live=new PaperLiveWorld(plugin,plugin.state(entry.getKey(),entry.getValue()),entry.getValue(),mapping.layout(),false);
            locks.add(live.lockEdits(Set.of(),"WorldGit "+target));
            live.close();
          }
          return work.run(ops);
        }
      } finally {
        for(int i=locks.size()-1;i>=0;i--) try { locks.get(i).close(); } catch(Exception e) { plugin.getLogger().warning("解除編輯鎖失敗："+e); }
        active=null; if(!stopping) applyUi.stop();
      }
    });
  }
  void planned(int total) { applyUi.total=total; }
  private volatile long lastCommitMillis = System.currentTimeMillis();

  RepoService(WorldGitPlugin plugin) {
    this.plugin = plugin;
    applyUi=new ApplyUi(plugin);
    plugin.platform().asyncRepeating(1,1000,applyUi::tick);
  }

  public long lastCommitMillis() {
    return lastCommitMillis;
  }

  public List<Measurement> measurements() {
    return List.copyOf(measurements);
  }

  public <T> CompletableFuture<T> submit(Callable<T> task) {
    var future = new CompletableFuture<T>();
    try {
      executor.execute(
          () -> {
            try {
              future.complete(task.call());
            } catch (Throwable t) {
              future.completeExceptionally(t);
            }
          });
    } catch (RejectedExecutionException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  // ---------------------------------------------------------------- 共用

  private record Context(
      WorldMapper.Mapping mapping, WorldRepositories repos, WorldGitConfig.Local local) {
    WorldLayout layout() {
      return mapping.layout();
    }
  }

  private Context context() throws IOException {
    var mapping = WorldMapper.map();
    var repos = new WorldRepositories(mapping.layout());
    var local = WorldGitConfig.readLocal(mapping.layout().repositoryRoot().resolve("worldgit.yml"));
    return new Context(mapping, repos, local);
  }

  public WorldGitConfig.Local readLocal() throws IOException {
    return context().local();
  }

  private PaperLiveWorld live(Context c, DimensionId id, boolean inline) {
    World world = c.mapping().worlds().get(id);
    return new PaperLiveWorld(plugin, plugin.state(id, world), world, c.layout(), inline);
  }

  private static String error(Throwable t) {
    Throwable root = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    String message = root.getMessage();
    return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
  }

  /** 已 init 且對應到線上世界的維度。 */
  private SortedMap<DimensionId, Path> trackedLive(Context c) {
    var result = new TreeMap<DimensionId, Path>();
    c.repos().tracked().forEach((id, path) -> {
      if (c.mapping().worlds().containsKey(id)) result.put(id, path);
    });
    return result;
  }

  public record Layout(Path repositoryRoot, SortedSet<DimensionId> tracked, SortedSet<DimensionId> untracked, List<String> unsupportedWorlds) {}

  public CompletableFuture<Layout> layout() {
    return submit(
        () -> {
          Context c = context();
          var tracked = new TreeSet<>(trackedLive(c).keySet());
          var untracked = new TreeSet<>(c.mapping().worlds().keySet());
          untracked.removeAll(tracked);
          return new Layout(c.layout().repositoryRoot(), tracked, untracked, c.mapping().unsupported());
        });
  }

  // ---------------------------------------------------------------- init

  public CompletableFuture<Batch<DimensionRepository.CommitResult>> init(
      String template, WorldGitConfig.Track track, CommitMetadata.Identity actor) {
    return submit(() -> doInit(template, track, actor));
  }

  private Batch<DimensionRepository.CommitResult> doInit(
      String template, WorldGitConfig.Track track, CommitMetadata.Identity actor) throws IOException {
    Context c = context();
    UUID snapshot = UUID.randomUUID();
    var result = new TreeMap<DimensionId, Outcome<DimensionRepository.CommitResult>>();
    var selected = new TreeMap<DimensionId, Path>();
    for (DimensionId id : c.mapping().worlds().keySet())
      selected.put(id, c.layout().repositoryRoot().resolve(id.directoryName()));
    if (selected.isEmpty()) throw new IOException("沒有可追蹤的維度");
    // 先建立全部 repo，讓主世界 commit 內的 dimensions 清單完整。
    for (var e : selected.entrySet())
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), true)) {
        repo.initialize(template, track);
      } catch (Exception ex) {
        result.put(e.getKey(), new Outcome<>(null, error(ex)));
      }
    var manifest = new TreeMap<DimensionId, String>();
    for (var id : selected.keySet())
      if (!id.equals(DimensionId.OVERWORLD)) manifest.put(id, "../" + id.directoryName());
    for (var e : selected.entrySet()) {
      if (result.containsKey(e.getKey())) continue;
      long t0 = System.nanoTime();
      DimensionState state = plugin.state(e.getKey(), c.mapping().worlds().get(e.getKey()));
      var batch = state.dirty().capture();
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false);
          var live = live(c, e.getKey(), false)) {
        var metadata = metadata(actor, actor, "初始化世界", snapshot, e.getKey(), live.dataVersion(), false, List.of());
        var r = repo.commit(live, manifest, metadata, c.local().entityTolerance());
        record(e.getKey(), r.status(), live, t0);
        state.dirty().acknowledge(batch);
        result.put(e.getKey(), new Outcome<>(r, null));
      } catch (Exception ex) {
        plugin.getLogger().log(Level.WARNING, "init " + e.getKey() + " 失敗", ex);
        result.put(e.getKey(), new Outcome<>(null, error(ex)));
      }
    }
    pinGroup(c,snapshot,result);
    lastCommitMillis = System.currentTimeMillis();
    return new Batch<>(snapshot, result);
  }

  // ---------------------------------------------------------------- status / diff

  public CompletableFuture<Batch<DimensionRepository.Status>> status(boolean full) {
    return submit(() -> doStatus(full, DiffEngine.Detail.SUMMARY, null));
  }

  /** window：每個維度要展開方塊級差異的 chunk；沒列出的維度不展開（摘要）。 */
  public CompletableFuture<Batch<DimensionRepository.Status>> diff(Map<DimensionId, Set<ChunkPos>> windows, boolean full) {
    return submit(() -> doStatus(full, DiffEngine.Detail.BLOCKS, windows));
  }

  private Batch<DimensionRepository.Status> doStatus(
      boolean full, DiffEngine.Detail detail, Map<DimensionId, Set<ChunkPos>> windows) throws IOException {
    Context c = context();
    if(org.worldgit.core.service.OperationState.partial(c.layout().repositoryRoot())) throw new IOException("世界為 PARTIAL；請用 /wg switch <目標> --force 或 /wg reset --hard 恢復");
    var tracked = trackedLive(c);
    if (tracked.isEmpty()) throw new IOException("世界尚未 init（/wg init）");
    var manifest = c.repos().manifest();
    var result = new TreeMap<DimensionId, Outcome<DimensionRepository.Status>>();
    for (var e : tracked.entrySet()) {
      long t0 = System.nanoTime();
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false);
          var live = live(c, e.getKey(), false)) {
        Set<ChunkPos> window = windows == null ? null : windows.getOrDefault(e.getKey(), Set.of());
        var status =
            detail == DiffEngine.Detail.SUMMARY || window == null
                ? repo.status(live, manifest, c.local().entityTolerance(), full)
                : repo.status(live, manifest, c.local().entityTolerance(), full, detail, window);
        record(e.getKey(), status, live, t0);
        result.put(e.getKey(), new Outcome<>(status, null));
      } catch (Exception ex) {
        plugin.getLogger().log(Level.WARNING, "status " + e.getKey() + " 失敗", ex);
        result.put(e.getKey(), new Outcome<>(null, error(ex)));
      }
    }
    return new Batch<>(null, result);
  }

  // ---------------------------------------------------------------- commit

  /** 提交請求。gate 回傳 false 的維度不寫 commit，dirty 與作者歸屬保留給下一次。 */
  public record CommitRequest(
      String message, CommitMetadata.Identity committer, boolean auto, Predicate<DimensionRepository.Status> gate) {}

  public CompletableFuture<Batch<DimensionRepository.CommitResult>> commit(CommitRequest request) {
    return submit(() -> doCommit(request, false));
  }

  /** 關閉流程用：在呼叫端執行緒完成（Paper 主執行緒），複製階段不排程而是內聯。 */
  public Batch<DimensionRepository.CommitResult> commitInline(CommitRequest request) throws IOException {
    return doCommit(request, true);
  }

  private Batch<DimensionRepository.CommitResult> doCommit(CommitRequest request, boolean inline) throws IOException {
    Context c = context();
    if(org.worldgit.core.service.OperationState.partial(c.layout().repositoryRoot())) throw new IOException("世界為 PARTIAL；請用 /wg switch <目標> --force 或 /wg reset --hard 恢復");
    var tracked = trackedLive(c);
    if (tracked.isEmpty()) throw new IOException("世界尚未 init（/wg init）");
    var manifest = c.repos().manifest();
    UUID snapshot = UUID.randomUUID();
    Attribution.Drained drained = plugin.attribution().drain();
    var result = new TreeMap<DimensionId, Outcome<DimensionRepository.CommitResult>>();
    var restore = new TreeMap<DimensionId, List<Attribution.Contributor>>();
    for (var e : tracked.entrySet()) {
      DimensionId id = e.getKey();
      DimensionState state = plugin.state(id, c.mapping().worlds().get(id));
      var batch = state.dirty().capture();
      long t0 = System.nanoTime();
      boolean keep = true;
      try (var repo = new DimensionRepository(e.getValue(), id, false);
          var live = live(c, id, inline)) {
        var contributors = drained.of(id);
        var primary = drained.primary(id);
        var committer = request.committer();
        var author = primary.map(Attribution.Contributor::identity).orElse(committer);
        var metadata =
            metadata(
                author,
                committer,
                request.message(),
                snapshot,
                id,
                live.dataVersion(),
                request.auto(),
                contributors.stream().map(Attribution.Contributor::toContribution).toList());
        var r = repo.commit(live, manifest, metadata, c.local().entityTolerance(), request.gate());
        record(id, r.status(), live, t0);
        boolean gated = !r.changed() && !r.status().diff().empty();
        if (gated) {
          keep = true; // 變動留待下次
        } else {
          state.dirty().acknowledge(batch);
          keep = false;
        }
        result.put(id, new Outcome<>(r, null));
      } catch (Exception ex) {
        plugin.getLogger().log(Level.WARNING, "commit " + id + " 失敗", ex);
        result.put(id, new Outcome<>(null, error(ex)));
      }
      if (keep) restore.put(id, drained.of(id));
    }
    if (!restore.isEmpty()) plugin.attribution().restore(new Attribution.Drained(restore));
    if (result.values().stream().anyMatch(o -> o.success() && o.value().changed()))
      lastCommitMillis = System.currentTimeMillis();
    pinGroup(c,snapshot,result);
    return new Batch<>(snapshot, result);
  }

  private void pinGroup(Context c,UUID snapshot,Map<DimensionId,Outcome<DimensionRepository.CommitResult>> result) throws IOException {
    if(result.values().stream().anyMatch(o->!o.success())) return;
    for(var entry:trackedLive(c).entrySet()) try(var repo=new DimensionRepository(entry.getValue(),entry.getKey(),false)) {
      String head=repo.refs().head(); if(head!=null) repo.refs().updateRef("refs/worldgit/groups/"+snapshot,null,head);
    }
  }

  private CommitMetadata metadata(
      CommitMetadata.Identity author,
      CommitMetadata.Identity committer,
      String message,
      UUID snapshot,
      DimensionId dimension,
      int dataVersion,
      boolean auto,
      List<CommitMetadata.Contribution> contributions) {
    return new CommitMetadata(
        author, committer, message, Instant.now(), dataVersion, dimension, CommitMetadata.Source.PLUGIN, auto, snapshot, contributions);
  }

  private void record(DimensionId id, DimensionRepository.Status status, PaperLiveWorld live, long t0) {
    var c = plugin.state(id, null).census();
    var m = new Measurement(id, status.candidates(), live.liveCount(), status.payloadsRead(), (System.nanoTime() - t0) / 1_000_000, live.stats().toString(),
        "loaded=" + c.loaded().size() + " unsaved=" + c.unsaved().size() + " entityChunks=" + c.entityChunks().size() + " dirty=" + plugin.state(id, null).dirty().chunks().size());
    measurements.add(m);
    while (measurements.size() > 200) measurements.removeFirst();
    plugin.getLogger().info(id + " candidates=" + m.candidates() + " live=" + m.live() + " payloadsRead=" + m.payloadsRead() + " ms=" + m.totalMillis() + " copy{" + m.copy() + "} census{" + m.census() + "}");
  }

  // ---------------------------------------------------------------- log

  public CompletableFuture<Map<DimensionId, Outcome<List<RefStore.Commit>>>> log(int limit) {
    return submit(
        () -> {
          Context c = context();
          var result = new TreeMap<DimensionId, Outcome<List<RefStore.Commit>>>();
          for (var e : c.repos().tracked().entrySet())
            try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false)) {
              result.put(e.getKey(), new Outcome<>(repo.log(limit), null));
            } catch (Exception ex) {
              result.put(e.getKey(), new Outcome<>(null, error(ex)));
            }
          return result;
        });
  }

  /** 等待已排入的背景操作完成（關閉流程在目前執行緒接手前使用）。 */
  public void awaitIdle() {
    try {
      submit(() -> null).get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      plugin.getLogger().warning("等待背景操作完成逾時：" + e);
    }
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
