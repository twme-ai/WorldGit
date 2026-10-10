package org.worldgit.fabric;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.ticks.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.*;
import org.worldgit.platform.*;

/** Server owner 上的有界套用。ticket 保留到 entity IO、光照與客戶端更新 barrier 完成。 */
final class FabricApply {
    private static final TicketType TICKET = new TicketType(0, TicketType.FLAG_LOADING);
    private final ServerRuntime runtime;
    private final ServerLevel level;
    private final Map<String, net.minecraft.world.level.block.state.BlockState> states = new HashMap<>();
    private final Map<UUID, Nbt.Compound> oldEntities;
    private final Set<ChunkPos> changed = new TreeSet<>();
    private int budgetTick = -1, usedSections;
    private long usedNanos;

    FabricApply(ServerRuntime runtime, ServerLevel level, Map<UUID,Nbt.Compound> oldEntities) {
        this.runtime=runtime; this.level=level; this.oldEntities=oldEntities;
    }

    static CompoundTag vanilla(Nbt.Compound tag) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(Nbt.write(tag))));
    }

    CompletionStage<Void> apply(ApplyPlan batch, ApplyBudget budget) {
        // Decode immutable blobs on coordinator, before entering owner thread.
        var sections = new HashMap<ApplyPlan.SectionOp, Section>();
        try {
            for(var chunk:batch.chunks().values()) for(var section:chunk.sections().values()) sections.put(section,section.section());
        } catch(IOException ex) { return CompletableFuture.failedFuture(ex); }
        var work = new ArrayDeque<Throwing>();
        for(var op:batch.chunks().values()) {
            if(op.delete()) return CompletableFuture.failedFuture(new IOException("線上不刪除 chunk；請離線清理新增 chunk"));
            for(var section:op.sections().values()) work.add(()->replaceSection(op.pos(), section, sections.get(section), batch.ignoreRules()));
            work.add(()->chunkData(op,batch));
        }
        for(var op:batch.entities()) work.add(()->entity(op,batch.ignoreRules()));
        if(!batch.worldMeta().isEmpty()) work.add(()->LiveMetadata.apply(runtime,batch));
        var tickets = new TreeSet<ChunkPos>();
        tickets.addAll(batch.chunks().keySet());
        for(var op:batch.entities()) {
            if(op.hint()!=null) tickets.add(op.hint());
            if(op.targetChunk()!=null) tickets.add(op.targetChunk());
        }
        // Loading is sequential; never hold more than the configured global cap.
        if(tickets.size()>budget.maxLoadedChunks()) return CompletableFuture.failedFuture(new IOException("批次 ticket 超出 ApplyBudget"));
        var done=new CompletableFuture<Void>();
        runtime.postToServer(()->load(tickets, work, budget, done));
        return done;
    }

    CompletionStage<Map<ChunkPos,ChunkCapture.Raw>> atomic(ApplyPlan plan,Map<ChunkPos,AtomicChunkCheck> checks,org.worldgit.core.config.EntityTagRegistry registry) {
        var done=new CompletableFuture<Map<ChunkPos,ChunkCapture.Raw>>();
        try {for(var op:plan.chunks().values())for(var section:op.sections().values())section.section();}
        catch(IOException e) {return CompletableFuture.failedFuture(e);}
        runtime.postToServer(()->{
            try {
                if(runtime.cancelRequested())throw new IOException("操作已取消");
                long start=System.nanoTime();var rules=IgnoreRules.parse(plan.ignoreRules());
                for(var entry:checks.entrySet()) {
                    var p=entry.getKey();var chunk=level.getChunkSource().getChunkNow(p.x(),p.z());if(chunk==null)throw new IOException("Chunk unloaded after preflight");
                    var raw=ChunkCapture.capture(level,chunk);var entities=new ArrayList<Nbt.Compound>();for(var e:raw.entities())entities.add(ChunkCapture.toCore(e));
                    if(!entry.getValue().matches(new org.worldgit.core.normalize.ChunkNormalizer(rules,registry).normalize(p,ChunkCapture.toCore(raw.chunk()),entities)))throw new AtomicChangedException();
                }
                runtime.mutate(()->{
                    for(var op:plan.entities())entity(new ApplyPlan.EntityOp(op.uuid(),op.hint(),null),plan.ignoreRules());
                    for(var op:plan.chunks().values()) {for(var section:op.sections().values())replaceSection(op.pos(),section,section.section(),plan.ignoreRules());chunkData(op,plan);}
                    for(var op:plan.entities())if(op.target()!=null)entity(op,plan.ignoreRules());
                });
                var receipts=new TreeMap<ChunkPos,ChunkCapture.Raw>();for(var p:checks.keySet())receipts.put(p,ChunkCapture.capture(level,level.getChunkSource().getChunkNow(p.x(),p.z())));
                ServerRuntime.LOG.info("WorldGit atomic chunks={} sections={} ownerNanos={}",checks.size(),plan.stats().sections(),System.nanoTime()-start);
                done.complete(Map.copyOf(receipts));
            } catch(Throwable error) {done.completeExceptionally(error);}
        });return done;
    }

    private void load(Set<ChunkPos> tickets, ArrayDeque<Throwing> work, ApplyBudget budget, CompletableFuture<Void> done) {
        try {
            for(var pos:tickets) level.getChunkSource().addTicketWithRadius(TICKET, mc(pos), 0);
            for(var pos:tickets) level.getChunk(pos.x(),pos.z());
            awaitEntities(tickets,work,budget,done,0);
        } catch(Throwable ex) { release(tickets,done,ex); }
    }

    private void awaitEntities(Set<ChunkPos> tickets, ArrayDeque<Throwing> work, ApplyBudget budget, CompletableFuture<Void> done, int ticks) {
        if(tickets.stream().anyMatch(p->!level.areEntitiesLoaded((Integer.toUnsignedLong(p.x()) | ((long)p.z()<<32))))) {
            if(ticks>1200) { release(tickets,done,new IOException("等待 entity chunk IO 逾時")); return; }
            runtime.nextTick(()->awaitEntities(tickets,work,budget,done,ticks+1));
        } else pump(tickets,work,budget,done);
    }

    private void pump(Set<ChunkPos> tickets, ArrayDeque<Throwing> work, ApplyBudget budget, CompletableFuture<Void> done) {
        try {
            int tick=runtime.server().getTickCount();
            if(budgetTick!=tick) { budgetTick=tick; usedSections=0; usedNanos=0; }
            while(!work.isEmpty() && usedSections<budget.sectionsPerTick() && usedNanos<budget.nanosPerTick()) {
                long start=System.nanoTime();
                runtime.mutate(work.remove()::run);
                usedSections++; // Chunk-level/BE/entity work also consumes a slot.
                usedNanos+=System.nanoTime()-start;
            }
            if(!work.isEmpty()) { runtime.nextTick(()->pump(tickets,work,budget,done)); return; }
            // The barrier includes all queued light checks; packet is built afterwards on owner.
            var pending=new ArrayList<CompletableFuture<?>>();
            for(var pos:tickets) pending.add(level.getChunkSource().getLightEngine().waitForPendingTasks(pos.x(),pos.z()));
            level.getChunkSource().getLightEngine().tryScheduleUpdate();
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((v,ex)->runtime.postToServer(()->{
                Throwable failure=ex;
                try { for(var pos:tickets) if(changed.contains(pos)) send(pos); }
                catch(Throwable packetError) { if(failure==null) failure=packetError; else failure.addSuppressed(packetError); }
                release(tickets,done,failure);
            }));
        } catch(Throwable ex) { release(tickets,done,ex); }
    }

    private void release(Set<ChunkPos> tickets, CompletableFuture<Void> done, Throwable error) {
        for(var pos:tickets) try { level.getChunkSource().removeTicketWithRadius(TICKET,mc(pos),0); }
        catch(Throwable ex) { if(error==null) error=ex; else error.addSuppressed(ex); }
        if(error==null) done.complete(null); else done.completeExceptionally(error);
    }

    boolean atomicLightingSafe(ApplyPlan plan) throws IOException {
        for(var pos:LiveApplyVerification.affected(plan)) {
            var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());if(chunk==null || chunk.getBlockEntities().size()>64)return false;
            var box=new net.minecraft.world.phys.AABB(pos.x()*16,level.getMinY()-1,pos.z()*16,pos.x()*16+16,level.getMaxY()+2,pos.z()*16+16);
            if(level.getEntities((Entity)null,box,e->!(e instanceof net.minecraft.world.entity.player.Player)).size()>32)return false;
        }
        for(var op:plan.chunks().values()) {
            var chunk=level.getChunkSource().getChunkNow(op.pos().x(),op.pos().z());if(chunk==null)return false;
            for(var patch:op.sections().values()) {
                var old=chunk.getSection(chunk.getSectionIndexFromSectionY(patch.y()));
                for(int i=0;i<4096;i++)if(patch.covers(i)) {
                    var before=old.getBlockState(i&15,i>>8,(i>>4)&15);var after=state(patch.section().block(i));
                    if(before!=after && (Platform.lightDampening(before)!=Platform.lightDampening(after) || before.getLightEmission()!=after.getLightEmission() || before.useShapeForLightOcclusion() || after.useShapeForLightOcclusion()))return false;
                }
            }
        }
        return true;
    }
    private net.minecraft.world.level.block.state.BlockState state(org.worldgit.core.model.BlockState block) {
        return states.computeIfAbsent(block.canonical(), name->{
            try { return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK),name,false).blockState(); }
            catch(Exception ex) { throw new IllegalArgumentException("無法讀取目標方塊："+name,ex); }
        });
    }

    private void replaceSection(ChunkPos pos, ApplyPlan.SectionOp op, Section target, String ignore) throws IOException {
        var chunk=level.getChunk(pos.x(),pos.z());
        int index=chunk.getSectionIndexFromSectionY(op.y());
        if(index<0 || index>=chunk.getSections().length) throw new IOException("section 超出世界高度："+op.y());
        var previous=chunk.getSection(index);
        // 即使後續 BE 載入失敗，也要在清理 barrier 標 dirty、完成光照與送包。
        changed.add(pos); chunk.markUnsaved();
        var replacement=previous.copy();
        var oldBes=new HashMap<BlockPos, Nbt.Compound>();
        var rules=IgnoreRules.parse(ignore);
        for(var bePos:new ArrayList<>(chunk.getBlockEntitiesPos())) {
            int local=(bePos.getX()&15)|((bePos.getZ()&15)<<4)|((bePos.getY()&15)<<8);
            if((bePos.getY()>>4)==op.y() && op.covers(local)) {
                var tag=chunk.getBlockEntityNbtForSaving(bePos,level.registryAccess());
                if(tag!=null) oldBes.put(bePos,ChunkCapture.toCore(tag));
                chunk.removeBlockEntity(bePos);
            }
        }
        var light=level.getChunkSource().getLightEngine();
        var poi=level.getPoiManager();
        for(int i=0;i<4096;i++) if(op.covers(i)) {
            var block=state(target.block(i));
            var before=previous.getBlockState(i&15,i>>8,(i>>4)&15);
            replacement.setBlockState(i&15,i>>8,(i>>4)&15,block,false);
            if(before!=block) {
                var at=position(pos,op.y(),i);
                // Remove stale records explicitly; consistency checks alone keep valid stale POI.
                net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(before).ifPresent(type->poi.remove(at));
                net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(block).ifPresent(type->poi.add(at,type));
            }
        }
        replacement.recalcBlockCounts();
        chunk.getSections()[index]=replacement;
        for(var entry:target.blockEntities().entrySet()) if(op.covers(entry.getKey())) {
            var at=position(pos,op.y(),entry.getKey());
            var data=Nbt.read(entry.getValue());
            var old=oldBes.get(at);
            if(old!=null && old.string("id").equals(data.string("id"))) old.forEach((k,v)->{
                if(rules.ignoredField(data.string("id"),k,false)) data.put(k,Nbt.copy(v));
            });
            data.put("x",at.getX()); data.put("y",at.getY()); data.put("z",at.getZ());
            var be=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(at,chunk.getBlockState(at),vanilla(data),level.registryAccess());
            if(be==null) throw new IOException("目標 block entity 無法載入："+at);
            chunk.addAndRegisterBlockEntity(be);
        }
        Heightmap.primeHeightmaps(chunk,chunk.getPersistedStatus().heightmapsAfter());
        chunk.initializeLightSources();
        light.updateSectionStatus(SectionPos.of(mc(pos),op.y()),replacement.hasOnlyAir());
        for(int i=0;i<4096;i++) if(op.covers(i) && previous.getBlockState(i&15,i>>8,(i>>4)&15)!=replacement.getBlockState(i&15,i>>8,(i>>4)&15))
            light.checkBlock(position(pos,op.y(),i));
        poi.checkConsistencyWithBlocks(SectionPos.of(mc(pos),op.y()),replacement);
        chunk.markUnsaved(); changed.add(pos);
    }

    private void chunkData(ApplyPlan.ChunkOp op, ApplyPlan plan) throws IOException {
        var chunk=level.getChunk(op.pos().x(),op.pos().z());
        for(var biome:op.biomes().values()) {
            int index=chunk.getSectionIndexFromSectionY(biome.y());
            var section=chunk.getSection(index);
            var samples=section.getBiomes().copy();
            for(int i=0;i<64;i++) if(!biome.samples().get(i).isEmpty()) {
                var key=net.minecraft.resources.ResourceKey.create(Registries.BIOME,net.minecraft.resources.Identifier.parse(biome.samples().get(i)));
                samples.set(i&3,i>>4,(i>>2)&3,level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(key));
            }
            chunk.getSections()[index]=new LevelChunkSection(section.getStates(),samples);
        }
        if(op.setTicks()) {
            var box=new BoundingBox(op.pos().x()*16,level.getMinY(),op.pos().z()*16,op.pos().x()*16+15,level.getMaxY(),op.pos().z()*16+15);
            chunk.unpackTicks(level.getGameTime()); // pending 排程不在容器佇列內，clearArea 看不到（決策 #170）
            level.getBlockTicks().clearArea(box); level.getFluidTicks().clearArea(box);
            var ticks=op.ticks()==null ? new Nbt.Compound() : Nbt.read(op.ticks());
            for(Object value:ticks.list("block_ticks").values()) {
                var t=(Nbt.Compound)value;
                var type=level.registryAccess().lookupOrThrow(Registries.BLOCK).getValue(net.minecraft.resources.Identifier.parse(t.string("i")));
                level.getBlockTicks().schedule(new SavedTick<>(type,tickPos(t),t.integer("t",0),TickPriority.byValue(t.integer("p",0))).unpack(level.getGameTime(),0));
            }
            for(Object value:ticks.list("fluid_ticks").values()) {
                var t=(Nbt.Compound)value;
                var type=level.registryAccess().lookupOrThrow(Registries.FLUID).getValue(net.minecraft.resources.Identifier.parse(t.string("i")));
                level.getFluidTicks().schedule(new SavedTick<>(type,tickPos(t),t.integer("t",0),TickPriority.byValue(t.integer("p",0))).unpack(level.getGameTime(),0));
            }
        }
        if(op.setTicks()) ChunkTickLocks.replaced(level,op.pos());
        if(op.setStructures()) {
            var raw=ChunkCapture.toCore(SerializableChunkData.copyOf(level,chunk).write());
            raw.put("structures",op.structures()==null ? new Nbt.Compound() : Nbt.read(op.structures()));
            var data=SerializableChunkData.parse(level,PalettedContainerFactory.create(level.registryAccess()),vanilla(raw));
            var parsed=data.read(level,level.getPoiManager(),new RegionStorageInfo("worldgit",level.dimension(),"chunk"),chunk.getPos());
            chunk.setAllStarts(parsed.getAllStarts()); chunk.setAllReferences(parsed.getAllReferences());
        }
        chunk.markUnsaved(); changed.add(op.pos());
    }

    private void entity(ApplyPlan.EntityOp op, String ignore) throws IOException {
        if(op.target()==null) {
            // Coordinator has loaded every known entity storage chunk before this global phase.
            for(var e:new ArrayList<Entity>(entities())) if(e.getUUID().equals(op.uuid()) && !(e instanceof Player)) {
                var output=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());
                if(e.save(output)) oldEntities.put(e.getUUID(),ChunkCapture.toCore(output.buildResult()));
                e.getSelfAndPassengers().filter(p->!(p instanceof Player)).toList().forEach(Entity::discard);
            }
            return;
        }
        var target=op.target().data();
        var old=oldEntities.get(op.uuid());
        var rules=IgnoreRules.parse(ignore);
        if(old!=null) old.forEach((k,v)->{if(rules.ignoredField(target.string("id"),k,false)) target.put(k,Nbt.copy(v));});
        passengerPositions(target,target.list("Pos"));
        var entity=EntityType.loadEntityRecursive(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess(),vanilla(target)),level,EntitySpawnReason.LOAD,e->e);
        if(entity==null || !level.tryAddFreshEntityWithPassengers(entity)) throw new IOException("實體無法生成／UUID 重複："+op.uuid());
    }

    private List<Entity> entities() { var list=new ArrayList<Entity>(); level.getAllEntities().forEach(list::add); return list; }
    private static void passengerPositions(Nbt.Compound e,Nbt.ListTag position) {
        e.putIfAbsent("Pos",Nbt.copy(position));
        for(Object p:e.list("Passengers").values()) passengerPositions((Nbt.Compound)p,position);
    }
    private static BlockPos tickPos(Nbt.Compound t) { return new BlockPos(t.integer("x",0),t.integer("y",0),t.integer("z",0)); }
    private static BlockPos position(ChunkPos chunk,int sy,int i) { return new BlockPos(chunk.x()*16+(i&15),sy*16+(i>>8),chunk.z()*16+((i>>4)&15)); }
    private static net.minecraft.world.level.ChunkPos mc(ChunkPos p) { return new net.minecraft.world.level.ChunkPos(p.x(),p.z()); }
    private void send(ChunkPos pos) {
        var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());
        if(chunk==null) return;
        chunk.markUnsaved();
        var packet=new ClientboundLevelChunkWithLightPacket(chunk,level.getChunkSource().getLightEngine(),null,null);
        for(var player:level.getChunkSource().chunkMap.getPlayers(chunk.getPos(),false)) player.connection.send(packet);
    }
    CompletionStage<Void> finish(Collection<ChunkPos> chunks) {
        var result=new CompletableFuture<Void>();
        runtime.postToServer(()->{
            var waits=new ArrayList<CompletableFuture<?>>();
            for(var pos:changed) waits.add(level.getChunkSource().getLightEngine().waitForPendingTasks(pos.x(),pos.z()));
            level.getChunkSource().getLightEngine().tryScheduleUpdate();
            CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).whenComplete((v,error)->runtime.postToServer(()->{
                try { for(var pos:changed) send(pos); }
                catch(Throwable ex) { result.completeExceptionally(ex); return; }
                if(error==null) result.complete(null); else result.completeExceptionally(error);
            }));
        });
        return result;
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
