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

  private static ServerLevel level(World world) { return ((CraftWorld) world).getHandle(); }
  private static net.minecraft.nbt.CompoundTag toTag(Nbt.Compound n) throws IOException {
    return NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(Nbt.write(n))), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
  }

  @Override public void applyChunk(World world, org.worldgit.core.apply.ApplyPlan.ChunkOp op,
      org.worldgit.core.config.IgnoreRules rules) throws IOException {
    var level = level(world);
    var chunk = level.getChunkSource().getChunkNow(op.pos().x(), op.pos().z());
    if (chunk == null) throw new IOException("owner 上的 chunk 尚未 FULL：" + op.pos());
    if (op.delete()) throw new IOException("線上不刪除 chunk；請離線使用 --delete-untracked");
    chunk.markUnsaved(); // 部分失敗也必須持久化並由 PARTIAL 完整重套。
    var light = level.getChunkSource().getLightEngine();
    for (var patch : op.sections().values()) {
      int si = chunk.getSectionIndexFromSectionY(patch.y());
      if (si < 0 || si >= chunk.getSections().length) throw new IOException("section 超出維度高度：" + patch.y());
      var old = chunk.getSection(si);
      var target = patch.section();
      var states = old.getStates().copy();
      var palette = new java.util.HashMap<org.worldgit.core.model.BlockState, net.minecraft.world.level.block.state.BlockState>();
      var removed = new java.util.HashMap<Integer,Nbt.Compound>();
      for (var pos : new java.util.ArrayList<>(chunk.getBlockEntitiesPos())) {
        int i = (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 15) << 8);
        if ((pos.getY() >> 4) != patch.y() || !patch.covers(i)) continue;
        var tag = chunk.getBlockEntityNbtForSaving(pos, level.registryAccess());
        if (tag != null) removed.put(i, Raw.convert(tag));
        chunk.removeBlockEntity(pos);
      }
      var changed = new java.util.ArrayList<net.minecraft.core.BlockPos>();
      for (int i=0;i<4096;i++) if (patch.covers(i)) {
        int x=i&15,y=i>>8,z=(i>>4)&15;
        var state = palette.computeIfAbsent(target.block(i), b -> ((org.bukkit.craftbukkit.block.data.CraftBlockData)org.bukkit.Bukkit.createBlockData(b.canonical())).getState());
        var previous = old.getBlockState(x,y,z);
        states.set(x,y,z,state);
        if (previous != state) changed.add(new net.minecraft.core.BlockPos(op.pos().x()*16+x, patch.y()*16+y, op.pos().z()*16+z));
      }
      var section = new net.minecraft.world.level.chunk.LevelChunkSection(states, old.getBiomes().copy());
      chunk.getSections()[si] = section;
      var targetBes = target.blockEntities();
      for (int i=0;i<4096;i++) if (patch.covers(i)) {
        var pos = new net.minecraft.core.BlockPos(op.pos().x()*16+(i&15), patch.y()*16+(i>>8), op.pos().z()*16+((i>>4)&15));
        var state = section.getBlockState(i&15,i>>8,(i>>4)&15);
        if (!state.hasBlockEntity()) continue;
        var bytes = targetBes.get(i);
        if (bytes == null) { chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE); continue; }
        var n = Nbt.read(bytes); var before = removed.get(i);
        if (before != null && before.string("id").equals(n.string("id")))
          before.forEach((k,v)-> { if(rules.ignoredField(n.string("id"),k,false)) n.put(k,Nbt.copy(v)); });
        n.put("x",pos.getX()); n.put("y",pos.getY()); n.put("z",pos.getZ());
        var be = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,state,toTag(n),level.registryAccess());
        if (be == null) throw new IOException("無法載入 block entity：" + pos);
        chunk.setBlockEntity(be);
      }
      var sp = net.minecraft.core.SectionPos.of(op.pos().x(),patch.y(),op.pos().z());
      light.updateSectionStatus(sp,section.hasOnlyAir());
      for (var pos : changed) light.checkBlock(pos);
      // checkConsistency 僅補缺漏；逐格 remove/add 也移除舊 POI，避免殘留有效 section。
      var pois = level.getPoiManager();
      for (int i=0;i<4096;i++) if (patch.covers(i)) {
        var pos = new net.minecraft.core.BlockPos(op.pos().x()*16+(i&15),patch.y()*16+(i>>8),op.pos().z()*16+((i>>4)&15));
        var a = net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(old.getBlockState(i&15,i>>8,(i>>4)&15));
        var b = net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(section.getBlockState(i&15,i>>8,(i>>4)&15));
        if (!a.equals(b)) { if(a.isPresent()) pois.remove(pos); if(b.isPresent()) pois.add(pos,b.get()); }
      }
      pois.checkConsistencyWithBlocks(sp,section);
    }
    for (var patch : op.biomes().values()) {
      var section = chunk.getSection(chunk.getSectionIndexFromSectionY(patch.y()));
      var samples = section.getBiomes().copy();
      var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
      for (int i=0;i<64;i++) if (!patch.samples().get(i).isEmpty())
        samples.set(i&3,i>>4,(i>>2)&3,registry.getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,net.minecraft.resources.Identifier.parse(patch.samples().get(i)))));
      chunk.getSections()[chunk.getSectionIndexFromSectionY(patch.y())] = new net.minecraft.world.level.chunk.LevelChunkSection(section.getStates(),samples);
    }
    if (op.setTicks()) {
      var n = op.ticks()==null ? new Nbt.Compound() : Nbt.read(op.ticks());
      var blocks = (net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.block.Block>)chunk.getBlockTicks();
      var fluids = (net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.material.Fluid>)chunk.getFluidTicks();
      blocks.removeIf(t->true); fluids.removeIf(t->true);
      long sequence=0;
      for(Object v:n.list("block_ticks").values()) {
        var t=(Nbt.Compound)v; var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(t.string("i")));
        blocks.schedule(new net.minecraft.world.ticks.SavedTick<>(block,new net.minecraft.core.BlockPos(t.integer("x",0),t.integer("y",0),t.integer("z",0)),t.integer("t",0),net.minecraft.world.ticks.TickPriority.byValue(t.integer("p",0))).unpack(level.getGameTime(),sequence++));
      }
      for(Object v:n.list("fluid_ticks").values()) {
        var t=(Nbt.Compound)v; var fluid=net.minecraft.core.registries.BuiltInRegistries.FLUID.getValue(net.minecraft.resources.Identifier.parse(t.string("i")));
        fluids.schedule(new net.minecraft.world.ticks.SavedTick<>(fluid,new net.minecraft.core.BlockPos(t.integer("x",0),t.integer("y",0),t.integer("z",0)),t.integer("t",0),net.minecraft.world.ticks.TickPriority.byValue(t.integer("p",0))).unpack(level.getGameTime(),sequence++));
      }
    }
    if (op.setStructures()) {
      var n = op.structures()==null ? new Nbt.Compound() : Nbt.read(op.structures());
      var registry=level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
      var starts=new java.util.HashMap<net.minecraft.world.level.levelgen.structure.Structure,net.minecraft.world.level.levelgen.structure.StructureStart>();
      for(var entry:n.compound("starts").entrySet()) {
        var structure=registry.getValue(net.minecraft.resources.Identifier.parse(entry.getKey()));
        var start=net.minecraft.world.level.levelgen.structure.StructureStart.loadStaticStart(net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext.fromLevel(level),toTag((Nbt.Compound)entry.getValue()),level.getSeed());
        if(structure==null || start==null) throw new IOException("不支援的 structure："+entry.getKey());
        starts.put(structure,start);
      }
      var refs=new java.util.HashMap<net.minecraft.world.level.levelgen.structure.Structure,it.unimi.dsi.fastutil.longs.LongSet>();
      for(var entry:n.compound("References").entrySet()) {
        var structure=registry.getValue(net.minecraft.resources.Identifier.parse(entry.getKey()));
        if(structure==null) throw new IOException("不支援的 structure reference："+entry.getKey());
        refs.put(structure,new it.unimi.dsi.fastutil.longs.LongOpenHashSet((long[])entry.getValue()));
      }
      chunk.setAllStarts(starts); chunk.setAllReferences(refs);
    }
    net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk,java.util.EnumSet.allOf(net.minecraft.world.level.levelgen.Heightmap.Types.class));
    chunk.initializeLightSources(); chunk.markUnsaved();
  }

  @Override public void removeEntities(World world,int x,int z,java.util.Set<java.util.UUID> ids) {
    for(var entity : world.getChunkAt(x,z).getEntities())
      if(!(entity instanceof org.bukkit.entity.Player) && ids.contains(entity.getUniqueId())) removeTree(entity);
  }
  private static void removeTree(org.bukkit.entity.Entity entity) {
    for(var passenger:entity.getPassengers()) if(!(passenger instanceof org.bukkit.entity.Player)) removeTree(passenger);
    entity.remove();
  }
  @Override public void spawnEntity(World world,org.worldgit.core.model.EntitySnapshot snapshot) throws IOException {
    var entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(toTag(org.worldgit.paper.EntitySpawnData.prepare(snapshot)),level(world),net.minecraft.world.entity.EntitySpawnReason.LOAD, e->e);
    if(entity==null) throw new IOException("無法反序列化實體："+snapshot.uuid());
    // LOAD 保留 UUID 與 passengers；不以 Bukkit isValid 判斷非 ticking Folia chunk 的加入結果。
    if(!level(world).tryAddFreshEntityWithPassengers(entity)) throw new IOException("實體 UUID 衝突："+snapshot.uuid());
  }
  @Override public java.util.concurrent.CompletionStage<Void> finishChunk(World world,int x,int z) {
    var level=level(world); var chunk=level.getChunkSource().getChunkNow(x,z);
    if(chunk==null) return java.util.concurrent.CompletableFuture.failedFuture(new IOException("finish chunk 未載入"));
    chunk.markUnsaved();
    // Paper 的 vanilla waitForPendingTasks 是 UnsupportedOperationException stub。
    // 在同 chunk 的 Starlight queue 放 marker；false 保留正常的 block/section 更新。
    var result=new java.util.concurrent.CompletableFuture<Void>();
    var tasks=level.getChunkSource().getLightEngine().starlight$getLightEngine().getServerLightQueue()
        .queueChunkLightTask(new net.minecraft.world.level.ChunkPos(x,z),()->false,ca.spottedleaf.concurrentutil.util.Priority.NORMAL);
    tasks.queueOrRunTask(()->result.complete(null));
    tasks.schedule();
    return result;
  }
  @Override public void saveRegion(World world) { level(world).moonrise$getChunkTaskScheduler().chunkHolderManager.saveAllChunks(true,false,false,true); }
  @Override public void flushIo(World world) { ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush(level(world)); }
  @Override public AutoCloseable freeze(World world) {
    var manager=level(world).getServer().tickRateManager(); boolean frozen=manager.isFrozen(); int steps=manager.frozenTicksToRun();
    if(manager.isSprinting()) throw new IllegalStateException("請先停止 tick sprint 再套用");
    manager.setFrozenTicksToRun(0); manager.setFrozen(true); manager.tick();
    return ()->{ manager.setFrozen(frozen); manager.tick(); manager.setFrozenTicksToRun(steps); };
  }
  @Override public OwnerTick ownerTick(World world) {
    try {
      var type=Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler");
      var region=type.getMethod("getCurrentRegion").invoke(null);
      var data=region.getClass().getMethod("getData").invoke(region);
      return new OwnerTick(((Number)region.getClass().getField("id").get(region)).longValue(),((Number)data.getClass().getMethod("getCurrentTick").invoke(data)).longValue());
    } catch(ClassNotFoundException e) { return new OwnerTick(0,org.bukkit.Bukkit.getCurrentTick()); }
      catch(ReflectiveOperationException e) { throw new IllegalStateException("無法取得 Folia owner tick",e); }
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
