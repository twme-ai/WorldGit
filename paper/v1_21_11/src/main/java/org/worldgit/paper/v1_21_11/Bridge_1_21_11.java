package org.worldgit.paper.v1_21_11;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.paper.NmsBridge;

/** Minecraft 1.21.11 的 NMS 轉接層（Mojang 名稱）。共用邏輯都在 paper:common，這裡只放內部 API。 */
public final class Bridge_1_21_11 implements NmsBridge {
  /**
   * 直接讀 {@code ChunkAccess.unsaved} 原始欄位（volatile，任何執行緒可讀）。{@code LevelChunk.isUnsaved()} 在 Paper/Folia 被覆寫成
   * 含排程 tick 與 chunk PDC，有流水的 chunk 永遠為真，且在 Folia 的非 region 執行緒會 NPE（experiments/03 §3）。
   */
  private static final VarHandle UNSAVED;

  static {
    try {
      var lookup = MethodHandles.privateLookupIn(ChunkAccess.class, MethodHandles.lookup());
      UNSAVED = lookup.findVarHandle(ChunkAccess.class, "unsaved", boolean.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public String minecraftVersion() {
    return "1.21.11";
  }

  @Override
  public Nbt.Compound copyGameRules(World world) throws IOException {
    ServerLevel level = ((CraftWorld) world).getHandle();
    var tag = net.minecraft.world.level.gamerules.GameRules.codec(level.enabledFeatures())
        .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, level.getGameRules()).getOrThrow();
    return Raw.convert((CompoundTag) tag);
  }

  @Override
  public void census(World world, CensusSink sink) {
    ServerLevel level = ((CraftWorld) world).getHandle();
    for (NewChunkHolder holder :
        level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolders()) {
      ChunkAccess chunk = holder.getCurrentChunk();
      if (!(chunk instanceof LevelChunk)) continue;
      ChunkEntitySlices entities = holder.getEntityChunk();
      sink.chunk(
          holder.chunkX,
          holder.chunkZ,
          (boolean) UNSAVED.getVolatile(chunk),
          entities != null && !entities.isEmpty());
    }
  }

  @Override
  public RawChunk copy(World world, int x, int z) {
    ServerLevel level = ((CraftWorld) world).getHandle();
    LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
    if (chunk == null) return null;
    // 這兩行是唯一必須在擁有執行緒做的事：palette container／方塊實體 NBT 的複製，以及實體序列化。
    SerializableChunkData data = SerializableChunkData.copyOf(level, chunk);
    NewChunkHolder holder = level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(x, z);
    ChunkEntitySlices slices = holder == null ? null : holder.getEntityChunk();
    CompoundTag entities = slices == null ? null : slices.save();
    return new Raw(x, z, data, entities);
  }

  private record Raw(int x, int z, SerializableChunkData data, CompoundTag entityTag) implements RawChunk {
    @Override
    public Nbt.Compound terrain() throws IOException {
      return convert(data.write());
    }

    @Override
    public List<Nbt.Compound> entities() throws IOException {
      var result = new ArrayList<Nbt.Compound>();
      if (entityTag == null) return result;
      for (var tag : entityTag.getListOrEmpty("Entities"))
        if (tag instanceof CompoundTag compound) result.add(convert(compound));
      return result;
    }

    private static Nbt.Compound convert(CompoundTag tag) throws IOException {
      var bytes = new ByteArrayOutputStream(4096);
      NbtIo.write(tag, new DataOutputStream(bytes));
      return Nbt.read(bytes.toByteArray());
    }
  }
}
