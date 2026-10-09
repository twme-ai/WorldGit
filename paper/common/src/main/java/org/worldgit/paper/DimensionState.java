package org.worldgit.paper;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.DimensionId;
import org.worldgit.platform.DirtyChunkTracker;

/**
 * 一個維度的線上追蹤狀態：dirty 追蹤（事件、旗標、WorldEdit 共同寫入）與最近一次普查。
 * 全部可由多個 region 執行緒並行存取。
 */
public final class DimensionState {
  /** 一次普查的結果；不可變，整份替換。 */
  public record Census(Set<ChunkPos> loaded, Set<ChunkPos> unsaved, Set<ChunkPos> entityChunks, long at) {
    static final Census EMPTY = new Census(Set.of(), Set.of(), Set.of(), 0);
  }

  private final DimensionId id;
  private final String worldName;
  private final DirtyChunkTracker dirty = new DirtyChunkTracker();
  private volatile Census census = Census.EMPTY;
  private final AtomicLong eventMarks = new AtomicLong();

  public DimensionState(DimensionId id, World world) {
    this(id, world.getName());
  }

  DimensionState(DimensionId id, String worldName) {
    this.id = id;
    this.worldName = Objects.requireNonNull(worldName);
  }

  public DimensionId id() {
    return id;
  }

  public String worldName() {
    return worldName;
  }

  public DirtyChunkTracker dirty() {
    return dirty;
  }

  public Census census() {
    return census;
  }

  /** 事件／WorldEdit 來源的標記（附作者者另外寫入 {@link Attribution}）。 */
  public void markEvent(ChunkPos chunk) {
    eventMarks.incrementAndGet();
    dirty.mark(chunk);
  }

  public long eventMarks() {
    return eventMarks.get();
  }

  /**
   * 以轉接層普查已載入 chunk，更新 census，並把 unsaved 旗標為真的 chunk 標為 dirty（候選，最終以內容雜湊為準）。
   * 旗標在存檔前會一直為真，所以每次普查都重新標記：commit 後才被改動的 chunk 不會因為 acknowledge 而漏掉。
   */
  public Census refresh(NmsBridge bridge, World world) {
    var loaded = new HashSet<ChunkPos>();
    var unsaved = new HashSet<ChunkPos>();
    var entities = new HashSet<ChunkPos>();
    bridge.census(
        world,
        (x, z, flag, hasEntities) -> {
          var pos = new ChunkPos(x, z);
          loaded.add(pos);
          if (flag) unsaved.add(pos);
          if (hasEntities) entities.add(pos);
        });
    for (ChunkPos pos : unsaved) dirty.mark(pos);
    // 先前沿用線上內容的 chunk 卸載後，必須重新以磁碟內容核對。
    for (ChunkPos pos : census.loaded()) if(!loaded.contains(pos)) dirty.mark(pos);
    // 實體跨 chunk 移動或被移除時，來源 chunk 可能已沒有實體且 terrain 不會設 unsaved。
    // 來源與目的地都必須再擷取，否則 HEAD 會留著來源的 UUID，而目的地又加進同一個 UUID。
    for (ChunkPos pos : census.entityChunks()) dirty.mark(pos);
    for (ChunkPos pos : entities) dirty.mark(pos);
    var result = new Census(Set.copyOf(loaded), Set.copyOf(unsaved), Set.copyOf(entities), System.currentTimeMillis());
    census = result;
    return result;
  }
}
