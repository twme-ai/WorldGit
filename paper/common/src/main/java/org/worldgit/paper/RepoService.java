package org.worldgit.paper;

import org.worldgit.core.service.OperationTimings;

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
    q.cancelled.set(true); q.stopping=true;
    executor.shutdownNow();
  }
  boolean applying() { return active!=null; }
  ApplyQueue activeQueue() { return active; }
  interface Operation<T> { T run(PaperOperations operations) throws IOException; }
  <T> CompletableFuture<T> operation(String target,Operation<T> work) {
    return operation(target,work,false,false);
  }
  <T> CompletableFuture<T> mergeOperation(String target,Operation<T> work) { return operation(target,work,true,false); }
  <T> CompletableFuture<T> regionOperation(String target,Operation<T> work) { return operation(target,work,true,true); }
  <T> CompletableFuture<T> operation(DimensionId dimension,String target,Operation<T> work) { return operation(target,work,false,false,dimension); }
  <T> CompletableFuture<T> mergeOperation(DimensionId dimension,String target,Operation<T> work) { return operation(target,work,true,false,dimension); }
  <T> CompletableFuture<T> regionOperation(DimensionId dimension,String target,Operation<T> work) { return operation(target,work,true,true,dimension); }
  private <T> CompletableFuture<T> operation(String target,Operation<T> work,boolean merge,boolean region) { return operation(target,work,merge,region,DimensionId.OVERWORLD); }
  private <T> CompletableFuture<T> operation(String target,Operation<T> work,boolean merge,boolean region,DimensionId dimension) {
    return submit(()->{
      var q=new ApplyQueue(plugin); active=q;
      var locks=new ArrayList<AutoCloseable>();
      try {
        try(var timing=OperationTimings.start(target)) {
        var mapping=plugin.mapping();
        try(var ops=new PaperOperations(plugin,mapping,q,merge,dimension)) {
          // capture、stash 保存、計畫、驗證、HEAD 均在同一編輯鎖內。
          try(var lockTiming=OperationTimings.stage("lock")) {
          if(!region) for(var entry:mapping.worlds().entrySet()) {
            if(!entry.getKey().equals(dimension)) continue;
            var live=new PaperLiveWorld(plugin,plugin.state(entry.getKey(),entry.getValue()),entry.getValue(),mapping.layout(),false);
            locks.add(live.lockEdits(Set.of(),"WorldGit "+target));
            live.close();
          }
          }
          return work.run(ops);
        }
        }
      } finally {
        for(int i=locks.size()-1;i>=0;i--) try { locks.get(i).close(); } catch(Exception e) { plugin.getLogger().warning("解除編輯鎖失敗："+e); }
        active=null; if(!stopping) {

          // 仍在同一 repo executor，先讀 durable MERGING 再完成 future／送出成功訊息。
          // 若另排 refresh，polling fetch 可插隊，緊接的 conflict-select 會讀到舊 UI。
          try { plugin.merges().refreshFromRepository(); }
          catch(IOException e) { plugin.getLogger().warning("讀取 MERGING 失敗："+e.getMessage()); }
        }
        if(region) plugin.getLogger().info("WGREGIONDONE "+target);
      }
    });
  }
  void planned(int total) { applyUi.total=total;if(active!=null)active.total=total; }
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
    var action = OperationUi.current();
    if(action!=null)action.retain();
    try {
      executor.execute(() -> OperationUi.within(action, () -> {
        try (var progress = action==null ? null : new org.worldgit.core.operation.OperationProgress(action.id,action.operation,action::event)) {
          if(action!=null) {action.progress=progress;progress.publish(action.dimension,"start",0,null,org.worldgit.core.operation.OperationProgress.Unit.CHUNK);}
          if(action!=null && plugin.remote()!=null)plugin.remote().refreshCredentialMask();
          try { var result=task.call();if(action!=null) {action.observe(result);if(action.mapping!=null && plugin.touched()!=null)plugin.touched().refresh(action.mapping);}future.complete(result);if(action!=null)recordHead(action); }
          catch(Throwable error) {if(action!=null)action.failed(error);future.completeExceptionally(error);}
        } finally {if(action!=null) {action.progress=null;action.release();}}
        return null;
      }));
    } catch(RejectedExecutionException error) {if(action!=null) {action.failed(error);action.release();}future.completeExceptionally(error);}
    return future;
  }
  private void recordHead(OperationUi.Action action) {
    if(action.mapping==null || action.dimension==null)return;
    var path=action.mapping.layout().repository(action.dimension);
    if(!Files.exists(path.resolve("HEAD")))return;
    try(var store=new org.worldgit.core.store.JGitStore(path,false)) {
      var head=store.headState();action.summary.put("head",(head.branch()==null?"HEAD":head.branch())+"@"+Messages.shortId(head.commit()));
    }catch(IOException error) {action.summary.put("head","unavailable");}
  }

  // ---------------------------------------------------------------- 共用

  private record Context(
      WorldMapper.Mapping mapping, WorldRepositories repos, WorldGitConfig.Local local) {
    WorldLayout layout() {
      return mapping.layout();
    }
  }

  private Context context() throws IOException {
    var mapping = plugin.mapping();
    var repos = new WorldRepositories(mapping.layout());
    var action=OperationUi.current();
    var local = WorldGitConfig.readLocal((action!=null && action.dimension!=null ? mapping.layout().repository(action.dimension) : mapping.layout().repositoryRoot()).resolve("worldgit.yml"));
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
    var action=OperationUi.current();
    if(action!=null && action.dimension!=null) result.keySet().removeIf(id->!id.equals(action.dimension));
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
    var action=OperationUi.current();
    var dimensions=action!=null && action.dimension!=null ? Set.of(action.dimension) : c.mapping().worlds().keySet();
    var repositories=new WorldRepositories(c.layout(),d->{plugin.touched().seed(c.mapping().worlds().get(d.id()),c.layout().repository(d.id()));return live(c,d.id(),false);});
    var batch=repositories.initDimensions(dimensions,template,track,actor);
    lastCommitMillis=System.currentTimeMillis();
    return batch;
  }

  // ---------------------------------------------------------------- status / diff

  public CompletableFuture<Batch<DimensionRepository.Status>> status(boolean full) {
    return submit(() -> doStatus(full, DiffEngine.Detail.SUMMARY, null));
  }

  /** window：每個維度要展開方塊級差異的 chunk；沒列出的維度不展開（摘要）。 */
  public CompletableFuture<Batch<DimensionRepository.Status>> diff(Map<DimensionId, Set<ChunkPos>> windows, boolean full) {
    return submit(() -> doStatus(full, DiffEngine.Detail.BLOCKS, windows));
  }

  CompletableFuture<Batch<DimensionRepository.Status>> diffRevision(DimensionId dimension,String revision,Set<ChunkPos> window) {
    return submit(()->{
      var c=context();var path=trackedLive(c).get(dimension);if(path==null)throw new IOException("維度尚未 init："+dimension);
      try(var repo=new DimensionRepository(path,dimension,false);var live=live(c,dimension,false)) {
        String working=repo.workingTree(live,c.repos().manifest(),c.local().entityTolerance());
        String target=repo.refs().readCommit(repo.refs().resolve(revision)).tree();
        var diff=new DiffEngine(repo.objects()).compare(dimension,target,working,c.local().entityTolerance(),DiffEngine.Detail.BLOCKS,window);
        return new Batch<>(null,new TreeMap<>(Map.of(dimension,new Outcome<>(new DimensionRepository.Status(diff,window.size(),0,false,List.of()),null))));
      }
    });
  }

  private Batch<DimensionRepository.Status> doStatus(
      boolean full, DiffEngine.Detail detail, Map<DimensionId, Set<ChunkPos>> windows) throws IOException {
    Context c = context();
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
    var tracked = trackedLive(c);
    if (tracked.isEmpty()) throw new IOException("世界尚未 init（/wg init）");
    var manifest = c.repos().manifest();
    UUID snapshot = UUID.randomUUID();
    Attribution.Drained drained = plugin.attribution().drain();
    var result = new TreeMap<DimensionId, Outcome<DimensionRepository.CommitResult>>();
    var restore = new TreeMap<DimensionId, List<Attribution.Contributor>>();
    drained.byDimension().forEach((id,contributors)->{if(!tracked.containsKey(id))restore.put(id,contributors);});
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
                UUID.randomUUID(),
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
        String reason=error(ex);
        // 單維度批次把正常的狀態拒絕保留在 Outcome；和 Commands.fail 一樣不當成未處理例外。
        if(ex instanceof IOException && (reason.startsWith("世界為 PARTIAL") || reason.startsWith("世界為 MERGING")))
          plugin.getLogger().warning("commit " + id + " 失敗：" + reason);
        else plugin.getLogger().log(Level.WARNING, "commit " + id + " 失敗", ex);
        result.put(id, new Outcome<>(null, error(ex)));
      }
      if (keep) restore.put(id, drained.of(id));
    }
    if (!restore.isEmpty()) plugin.attribution().restore(new Attribution.Drained(restore));
    if (result.values().stream().anyMatch(o -> o.success() && o.value().changed()))
      lastCommitMillis = System.currentTimeMillis();

    return new Batch<>(snapshot, result);
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
    var action=OperationUi.current();
    var world=action!=null && action.mapping!=null?action.mapping.worlds().get(id):plugin.world(id);
    var state=plugin.state(id,world);
    var c = state.census();
    var m = new Measurement(id, status.candidates(), live.liveCount(), status.payloadsRead(), (System.nanoTime() - t0) / 1_000_000, live.stats().toString(),
        "loaded=" + c.loaded().size() + " unsaved=" + c.unsaved().size() + " entityChunks=" + c.entityChunks().size() + " dirty=" + state.dirty().chunks().size());
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
          for (var e : trackedLive(c).entrySet())
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
