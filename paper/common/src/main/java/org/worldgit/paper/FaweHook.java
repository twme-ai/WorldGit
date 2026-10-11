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
      // 官方 2.8.x 使用 jnbt getEntities；新版本改為 linbus entities。只解析目前版本存在的型別。
      for (Object entity : entities(set)) {var id=uuid(entity);if(id!=null)ids.add(id);}
      sess.entityIds(ids,chunk.getX(),chunk.getZ());return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    private static Iterable<?> entities(IChunkSet set) {
      try {
        java.lang.reflect.Method accessor;
        try { accessor=com.fastasyncworldedit.core.queue.IBlocks.class.getMethod("entities"); }
        catch(NoSuchMethodException legacy) { accessor=com.fastasyncworldedit.core.queue.IBlocks.class.getMethod("getEntities"); }
        return (Iterable<?>)accessor.invoke(set);
      } catch(ReflectiveOperationException error) {throw new IllegalStateException("無法讀取 FAWE entities",error);}
    }

    private static java.util.UUID uuid(Object entity) {
      try {
        var compound=Class.forName("com.sk89q.jnbt.CompoundTag");
        Object tag=entity;
        if (!compound.isInstance(entity)) {
          Object lin=entity.getClass().getMethod("linTag").invoke(entity);
          tag=compound.getConstructor(Class.forName("org.enginehub.linbus.tree.LinCompoundTag")).newInstance(lin);
        }
        return (java.util.UUID)compound.getMethod("getUUID").invoke(tag);
      } catch(ReflectiveOperationException error) {throw new IllegalStateException("無法讀取 FAWE entity UUID",error);}
    }

    @Override
    public Extent construct(Extent child) {
      return child;
    }
  }
}
