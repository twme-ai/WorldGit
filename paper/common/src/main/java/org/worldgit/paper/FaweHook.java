package org.worldgit.paper;

import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.extent.Extent;

/** 只在 FAWE 存在時才會被載入：用 IBatchProcessor 取得每個 chunk 的實際批次寫入（//set 等 bulk 操作看不到逐格）。 */
final class FaweHook {
  private FaweHook() {}

  static Extent wrap(Extent extent, WorldEditHook.Sess sess) {
    return extent.addProcessor(new Proc(sess));
  }

  private static final class Proc implements IBatchProcessor {
    private final WorldEditHook.Sess sess;

    Proc(WorldEditHook.Sess sess) {
      this.sess = sess;
    }

    @Override
    public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
      boolean any = false;
      int min = set.getMinSectionPosition(), max = set.getMaxSectionPosition();
      for (int layer = min; layer <= max && !any; layer++) {
        if (!set.hasSection(layer)) continue;
        char[] blocks = set.loadIfPresent(layer);
        if (blocks == null) continue;
        for (char c : blocks)
          if (c != 0) {
            any = true;
            break;
          }
      }
      if (any) sess.blockChunk(chunk.getX(), chunk.getZ());
      return set;
    }

    @Override
    public Extent construct(Extent child) {
      return child;
    }
  }
}
