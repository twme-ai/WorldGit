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

  public void mark(ChunkPos chunk) {
    dirty.compute(chunk, (key, old) -> generation.incrementAndGet());
  }

  public Set<ChunkPos> chunks() {
    return Set.copyOf(dirty.keySet());
  }

  public Batch capture() {
    return new Batch(dirty);
  }

  public void acknowledge(Batch batch) {
    batch.generations.forEach((chunk, g) -> dirty.remove(chunk, g));
  }
}
