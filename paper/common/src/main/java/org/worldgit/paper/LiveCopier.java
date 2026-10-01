package org.worldgit.paper;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.World;
import org.worldgit.core.model.ChunkPos;

/**
 * 線上 chunk 的「複製階段」排程器。複製必須在擁有該 chunk 的執行緒（Paper 主執行緒／Folia region 執行緒）做，
 * 所以這裡把工作切成每 tick 最多 {@code chunksPerTick} 個 chunk 的小單位（每條鏈每 tick 一個，鏈的位置隨 chunk 所屬 region 移動），
 * 再以滑動視窗限制「已複製但尚未被背景消費」的份數，避免大量 commit 時耗盡記憶體。
 *
 * <p>消費者（背景 repo 執行緒）依 chunk 排序呼叫 {@link #take}；複製順序與消費順序相同，所以視窗不會卡死。
 * 編碼、正規化、雜湊與寫 repo 都在消費者那邊，不佔 tick。
 */
final class LiveCopier {
  /** 複製階段的實測統計（用來量測 tick 影響）。 */
  static final class Stats {
    final LongAdder chunks = new LongAdder(), nanos = new LongAdder(), failures = new LongAdder();
    final AtomicLong maxNanos = new AtomicLong();

    void record(long ns) {
      chunks.increment();
      nanos.add(ns);
      maxNanos.accumulateAndGet(ns, Math::max);
    }

    @Override
    public String toString() {
      long n = chunks.sum();
      return "chunks=" + n + " totalMs=" + nanos.sum() / 1_000_000.0 + " avgUs=" + (n == 0 ? 0 : nanos.sum() / n / 1000)
          + " maxUs=" + maxNanos.get() / 1000 + " failures=" + failures.sum();
    }
  }

  private final ChunkScheduler platform;
  private final NmsBridge bridge;
  private final World world;
  private final int chains;
  private final Semaphore window;
  private final boolean inline;
  private final Stats stats;
  private final ConcurrentLinkedDeque<ChunkPos> queue = new ConcurrentLinkedDeque<>();
  private final ConcurrentMap<ChunkPos, CompletableFuture<Optional<NmsBridge.RawChunk>>> futures = new ConcurrentHashMap<>();
  private final Set<ChunkPos> started = ConcurrentHashMap.newKeySet();
  private final AtomicInteger activeChains = new AtomicInteger();
  private volatile boolean cancelled;

  LiveCopier(ChunkScheduler platform, NmsBridge bridge, World world, int chunksPerTick, int windowSize, boolean inline, Stats stats) {
    this.platform = platform;
    this.bridge = bridge;
    this.world = world;
    this.chains = chunksPerTick;
    this.window = new Semaphore(windowSize);
    this.inline = inline;
    this.stats = stats;
  }

  /** 設定要複製的 chunk（已排序），並開始排程。 */
  void start(Collection<ChunkPos> sorted) {
    for (ChunkPos pos : sorted) {
      futures.put(pos, new CompletableFuture<>());
      queue.add(pos);
    }
    pump();
  }

  boolean has(ChunkPos pos) {
    return futures.containsKey(pos);
  }

  /**
   * 取得複製結果並釋放視窗名額。Optional.empty 表示複製當下 chunk 已不是載入的 FULL chunk，呼叫端應改讀磁碟。
   * 回傳 null 表示沒有排入複製。
   */
  Optional<NmsBridge.RawChunk> take(ChunkPos pos, long timeoutSeconds) throws Exception {
    CompletableFuture<Optional<NmsBridge.RawChunk>> future = futures.get(pos);
    if (future == null) return null;
    if (inline) {
      // 關閉流程：目前執行緒就是擁有者，隨取隨複製，不排程也不佔視窗。
      if (!future.isDone()) complete(pos, future);
    } else if (!future.isDone()) {
      // 需要的 chunk 不在前頭時（排序不同），把它提到隊首，避免消費者等不到。
      if (queue.remove(pos)) queue.addFirst(pos);
      pump();
    }
    try {
      return future.get(timeoutSeconds, TimeUnit.SECONDS);
    } finally {
      futures.remove(pos);
      if (started.remove(pos)) window.release();
      if (!inline) pump();
    }
  }

  void cancel() {
    cancelled = true;
    queue.clear();
    futures.values().forEach(f -> f.cancel(false));
    futures.clear();
  }

  private void pump() {
    if (inline) return;
    // 滑動視窗滿時立即交還背景消費者；不能在 step() 取得不到 permit 後反覆重試（會忙迴圈鎖死 take）。
    while (!cancelled && !queue.isEmpty() && activeChains.get() < chains && window.availablePermits() > 0) {
      if (activeChains.incrementAndGet() > chains) {
        activeChains.decrementAndGet();
        return;
      }
      step();
    }
  }

  private void step() {
    if (cancelled || !window.tryAcquire()) {
      activeChains.decrementAndGet();
      return;
    }
    ChunkPos pos = queue.pollFirst();
    if (pos == null) {
      window.release();
      activeChains.decrementAndGet();
      return;
    }
    var future = futures.get(pos);
    if (future == null) { // 已被取消或取走
      window.release();
      step();
      return;
    }
    started.add(pos);
    try {
      platform.region(world, pos.x(), pos.z(), () -> {
        try {
          complete(pos, future);
        } finally {
          nextLater(pos);
        }
      });
    } catch (RuntimeException e) {
      future.completeExceptionally(e);
      activeChains.decrementAndGet();
    }
  }

  private void nextLater(ChunkPos last) {
    if (cancelled) {
      activeChains.decrementAndGet();
      return;
    }
    try {
      platform.regionDelayed(world, last.x(), last.z(), 1, this::step);
    } catch (RuntimeException e) { // 插件關閉等：鏈結束，未完成的 future 由 take 的 timeout 回報
      activeChains.decrementAndGet();
    }
  }

  private void complete(ChunkPos pos, CompletableFuture<Optional<NmsBridge.RawChunk>> future) {
    long t0 = System.nanoTime();
    try {
      future.complete(Optional.ofNullable(bridge.copy(world, pos.x(), pos.z())));
    } catch (Throwable t) {
      stats.failures.increment();
      future.completeExceptionally(t);
    } finally {
      stats.record(System.nanoTime() - t0);
    }
  }
}
