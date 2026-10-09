package org.worldgit.platform;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.worldgit.core.model.ChunkPos;

/** capture 與事件並行時，以 generation 有條件清除；不會吞掉 capture 後的新事件。 */
public final class DirtyChunkTracker {
  public record Batch(Map<ChunkPos, Long> generations) {
    public Batch {
      generations = Map.copyOf(generations);
    }
  }

  private final AtomicLong generation = new AtomicLong();
  private final ConcurrentMap<ChunkPos, Long> dirty = new ConcurrentHashMap<>();
  private final ConcurrentMap<ChunkPos, Long> unindexed = new ConcurrentHashMap<>();

  public void mark(ChunkPos chunk) {
    dirty.compute(chunk, (key, old) -> {
      long next=generation.incrementAndGet();unindexed.put(key,next);return next;
    });
  }

  public Set<ChunkPos> chunks() {
    return Set.copyOf(dirty.keySet());
  }

  public Batch capture() {
    return new Batch(dirty);
  }

  /** capture 游標獨立於 commit 游標；status 建立索引不會吞掉自動 commit 的觸發。 */
  public Batch indexBatch() { return new Batch(unindexed); }
  public void indexed(Batch batch) { batch.generations.forEach((chunk,g)->unindexed.remove(chunk,g)); }

  public void acknowledge(Batch batch) {
    batch.generations.forEach((chunk, g) -> dirty.remove(chunk, g));
    indexed(batch);
  }
}
