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
    var processor=new Proc(sess);return extent.addProcessor(processor).addPostProcessor(processor);
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
    @Override public java.util.concurrent.Future<?> postProcessSet(IChunk chunk,IChunkGet get,IChunkSet set) {
      var ids=new java.util.HashSet<java.util.UUID>();
      // IBlocks#getEntities 已標記移除；改用 entities()（FaweCompoundTag）。linbus 型別不在編譯期 classpath，
      // 以 WorldEdit 的 jnbt CompoundTag 包裝後讀取 UUID。
      for(var entity:set.entities()) {var id=uuid(entity);if(id!=null)ids.add(id);}
      sess.entityIds(ids,chunk.getX(),chunk.getZ());return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    private static java.util.UUID uuid(com.fastasyncworldedit.core.nbt.FaweCompoundTag entity) {
      try {
        Object lin=com.fastasyncworldedit.core.nbt.FaweCompoundTag.class.getMethod("linTag").invoke(entity);
        var compound=Class.forName("com.sk89q.jnbt.CompoundTag");
        Object tag=compound.getConstructor(Class.forName("org.enginehub.linbus.tree.LinCompoundTag")).newInstance(lin);
        return (java.util.UUID)compound.getMethod("getUUID").invoke(tag);
      } catch(ReflectiveOperationException error) {throw new IllegalStateException("無法讀取 FAWE entity UUID",error);}
    }

    @Override
    public Extent construct(Extent child) {
      return child;
    }
  }
}
