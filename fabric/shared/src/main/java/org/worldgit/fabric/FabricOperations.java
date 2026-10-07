package org.worldgit.fabric;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.capture.SnapshotSource;
import org.worldgit.core.config.EntityTagRegistry;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.EntityNormalizer;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.platform.*;

/** Repo 執行緒上執行；owner futures 的等待不阻塞伺服器。共用 core journal／HEAD／stash 語意。 */
final class FabricOperations implements WorldOperations.LiveAccess {
    private boolean regionMode;
    private final ServerRuntime runtime;
    private final WorldLayout layout;
    private final SortedMap<DimensionId,FabricLiveWorld> worlds=new TreeMap<>();
    private final Map<UUID,Nbt.Compound> oldEntities=new HashMap<>();
    FabricOperations(ServerRuntime runtime,WorldLayout layout) { this.runtime=runtime;this.layout=layout; }
    @Override public SnapshotSource source(WorldLayout.Dimension dimension) { return new FabricLiveWorld(runtime,layout,dimension); }
    @Override public SnapshotSource source(WorldLayout.Dimension dimension,Set<ChunkPos> chunks) {
        try(var timing=org.worldgit.core.service.OperationTimings.stage("flush")) { runtime.flushChunks(Map.of(dimension.id(),chunks)); }
        return new FabricLiveWorld(runtime,layout,dimension);
    }
    @Override public AutoCloseable lockChunks(Map<DimensionId,Set<ChunkPos>> chunks) {
        try(var timing=org.worldgit.core.service.OperationTimings.stage("lock")) { return runtime.lockChunks(chunks); }
    }
    @Override public Set<ChunkPos> entityChunks(WorldLayout.Dimension dimension,Set<UUID> ids) throws IOException {
        var positions=new TreeSet<ChunkPos>();
        for(var path:RegionFile.list(dimension.entities())) try(var region=new RegionFile(path)) {
            for(int i=0;i<1024;i++) if(region.has(i) && contains(region.read(i).list("Entities"),ids)) positions.add(region.pos(i));
        }
        positions.addAll(runtime.onServer(()->{
            var result=new TreeSet<ChunkPos>();
            var level=runtime.server().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(dimension.id().value())));
            if(level!=null) for(var entity:level.getAllEntities()) if(ids.contains(entity.getUUID())) result.add(ServerRuntime.corePos(entity.chunkPosition()));
            return result;
        }));
        return positions;
    }
    @Override public void beforeComplete() throws IOException {
        if(runtime.cancelRequested()) throw new IOException("操作已取消");
    }
    @Override public void applyRegions(Collection<ApplyPlan> plans) throws IOException {
        regionMode=true;
        try { applyAll(plans); } finally { regionMode=false; }
    }
    @Override public EntityTagRegistry.PackResolver packs() { return ModPacks.INSTANCE; }
    @Override public void validate(ApplyPlan plan) throws IOException {
        if(plan.chunks().values().stream().anyMatch(ApplyPlan.ChunkOp::delete))
            throw new IOException("stash 的新增 chunk 需離線清理；線上預設保留未追蹤 chunk");
        LiveMetadata.validate(runtime,plan);
        // Check every block/biome identifier before the first mutation.
        var states=new HashSet<String>(); var biomes=new HashSet<String>(); var entityTypes=new HashSet<String>();
        for(var chunk:plan.chunks().values()) {
            for(var section:chunk.sections().values()) for(var state:section.section().blocks()) states.add(state.canonical());
            for(var biome:chunk.biomes().values()) for(var id:biome.samples()) if(!id.isEmpty()) biomes.add(id);
        }
        for(var entity:plan.entities()) if(entity.target()!=null) entityTypes(entity.target().data(),entityTypes);
        rejectCrossDimensionDuplicates(plan);
        runtime.onServer(()->{
            var level=runtime.server().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(plan.dimension().value())));
            if(level==null) throw new IOException("維度尚未載入："+plan.dimension());
            for(var chunk:plan.chunks().values()) {
                for(var section:chunk.sections().values()) if(section.y()<level.getMinSectionY() || section.y()>level.getMaxSectionY())
                    throw new IOException("section 超出世界高度："+section.y());
            }
            for(var state:states) net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(runtime.server().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),state,false);
            for(var id:biomes) runtime.server().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,net.minecraft.resources.Identifier.parse(id)));
            for(var id:entityTypes) runtime.server().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENTITY_TYPE).getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,net.minecraft.resources.Identifier.parse(id)));
            return null;
        });
    }
    /**
     * 各維度的歷史互相獨立，還原可能讓某個 UUID 同時存在於兩個維度（例如實體已傳送到地獄後還原主世界）。
     * 在第一個寫入之前，以已載入的實體與磁碟 entity region 預檢其他維度；只要有重複就拒絕並指出維度。
     */
    private void rejectCrossDimensionDuplicates(ApplyPlan plan) throws IOException {
        var targets=new TreeSet<UUID>();
        for(var entity:plan.entities()) if(entity.target()!=null) collect(entity.target().data(),targets,new HashSet<>());
        if(targets.isEmpty()) return;
        for(var other:layout.dimensions().values()) {
            if(other.id().equals(plan.dimension())) continue;
            var live=runtime.onServer(()->{
                var level=runtime.server().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(other.id().value())));
                if(level==null) return Optional.<UUID>empty();
                for(var id:targets) { var entity=level.getEntity(id); if(entity!=null && !entity.isRemoved()) return Optional.of(id); }
                return Optional.<UUID>empty();
            });
            if(live.isPresent()) throw new DuplicateEntityException(live.get(),other.id());
            for(var path:RegionFile.list(other.entities())) try(var region=new RegionFile(path)) {
                for(int i=0;i<1024;i++) if(region.has(i)) {
                    var found=firstContained(region.read(i).list("Entities"),targets);
                    if(found!=null) {
                        var pos=region.pos(i);
                        boolean authoritative=runtime.onServer(()->{
                            var level=runtime.server().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(other.id().value())));
                            return level!=null && ((org.worldgit.fabric.mixin.ServerLevelAccess)level).worldgit$entities().areEntitiesLoaded(
                                ((long)pos.x() & 0xffffffffL) | ((long)pos.z() << 32));
                        });
                        // 已載入的 entity storage 以活世界為準，不能用尚未 flush 的舊檔案判斷已離開的 UUID。
                        if(!authoritative) throw new DuplicateEntityException(found,other.id());
                    }
                }
            }
        }
    }
    private static UUID firstContained(Nbt.ListTag entities,Set<UUID> ids) {
        for(var p:entities.values()) {
            var e=(Nbt.Compound)p; var id=EntityNormalizer.uuid(e);
            if(ids.contains(id)) return id;
            var nested=firstContained(e.list("Passengers"),ids); if(nested!=null) return nested;
        }
        return null;
    }
    @Override public void applyAll(Collection<ApplyPlan> plans) throws IOException {
        var budget=runtime.onServer(()->runtime.server().getPlayerList().getPlayers().isEmpty() ? ApplyBudget.DEFAULT : ApplyBudget.WITH_PLAYERS);
        var batches=new ArrayList<ApplyPlan>();
        var ids=new TreeSet<UUID>();
        var unique=new HashSet<UUID>();
        for(var plan:plans) for(var op:plan.entities()) {
            ids.add(op.uuid());
            if(op.target()!=null) collect(op.target().data(),ids,unique);
        }
        // Disk reads only locate old UUIDs. Owner ticket loading makes pending entity IO authoritative.
        if(!ids.isEmpty()) for(var dimension:layout.dimensions().values()) {
            if(plans.stream().noneMatch(p->p.dimension().equals(dimension.id()))) continue;
            var positions=new TreeSet<ChunkPos>();
            for(var path:RegionFile.list(dimension.entities())) try(var region=new RegionFile(path)) {
                for(int i=0;i<1024;i++) if(region.has(i) && contains(region.read(i).list("Entities"),ids)) positions.add(region.pos(i));
            }
            positions.addAll(runtime.onServer(()->{
                var result=new TreeSet<ChunkPos>();
                var level=runtime.server().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(dimension.id().value())));
                if(level!=null) for(var entity:level.getAllEntities()) if(ids.contains(entity.getUUID())) result.add(ServerRuntime.corePos(entity.chunkPosition()));
                return result;
            }));
            for(var pos:positions) {
                var list=ids.stream().map(id->new ApplyPlan.EntityOp(id,pos,null)).toList();
                for(int i=0;i<list.size();i+=64) batches.add(new ApplyPlan(dimension.id(),null,null,layout.dataVersion(),Scope.all(),List.of(),list.subList(i,Math.min(i+64,list.size())),Map.of(),List.of()));
            }
        }
        // 全組 removal 已排在最前；terrain 然後 spawn，metadata 全組最後。
        for(var plan:plans) for(var batch:plan.batches(budget.sectionsPerTick()))
            if(batch.worldMeta().isEmpty() && (batch.entities().isEmpty() || batch.entities().stream().anyMatch(e->e.target()!=null)))
                batches.addAll(org.worldgit.fabric.logic.LiveBatches.limitTickets(batch,budget.maxLoadedChunks()));
        for(var plan:plans) for(var batch:plan.batches(budget.sectionsPerTick())) if(!batch.worldMeta().isEmpty()) batches.add(batch);
        var affected=new TreeMap<DimensionId,Set<ChunkPos>>();
        for(var batch:batches) {
            var chunks=affected.computeIfAbsent(batch.dimension(),k->new TreeSet<>());
            chunks.addAll(batch.chunks().keySet());
            for(var e:batch.entities()) { if(e.hint()!=null) chunks.add(e.hint()); if(e.targetChunk()!=null) chunks.add(e.targetChunk()); }
        }
        int complete=0, sections=0, totalSections=plans.stream().mapToInt(p->p.stats().sections()).sum();
        Throwable error=null;
        try {
            for(var e:affected.entrySet()) world(e.getKey()).protectPlayers(PlayerProtection.operation(e.getValue(),runtime.operationId(),true)).toCompletableFuture().join();
            for(var batch:batches) {
                if(runtime.cancelRequested()) throw new IOException("套用已取消；請全量 switch --force 或 reset --hard 恢復");
                runtime.progress(new ApplyProgress(runtime.operationId(),ApplyProgress.Phase.APPLYING,complete,batches.size(),sections,totalSections,false));
                var world=world(batch.dimension());
                world.nextApplyTick(batch).toCompletableFuture().join();
                if(runtime.cancelRequested()) throw new IOException("套用已取消");
                try(var timing=org.worldgit.core.service.OperationTimings.stage("apply-owner")) { world.apply(batch,budget).toCompletableFuture().join(); }
                complete++; sections+=batch.stats().sections();
            }
        } catch(Throwable ex) { error=ex; }
        // Always try every dimension cleanup, even if a prior barrier failed.
        runtime.progress(new ApplyProgress(runtime.operationId(),ApplyProgress.Phase.LIGHTING,complete,batches.size(),sections,totalSections,runtime.cancelRequested()));
        for(var entry:affected.entrySet()) try(var timing=org.worldgit.core.service.OperationTimings.stage("lighting")) { world(entry.getKey()).finishApply(entry.getValue()).toCompletableFuture().join(); }
        catch(Throwable ex) { if(error==null) error=ex; else error.addSuppressed(ex); }
        try {
            runtime.progress(new ApplyProgress(runtime.operationId(),ApplyProgress.Phase.SAVING,complete,batches.size(),sections,totalSections,runtime.cancelRequested()));
            try(var timing=org.worldgit.core.service.OperationTimings.stage("flush-after-apply")) {
                if(regionMode) runtime.flushChunks(affected); else runtime.flushBlocking();
            }
        } catch(Throwable ex) { if(error==null) error=ex; else error.addSuppressed(ex); }
        for(var w:worlds.values()) try { w.close(); } catch(Throwable ex) { if(error==null) error=ex; else error.addSuppressed(ex); }
        if(error!=null) {
            if(error instanceof CompletionException && error.getCause()!=null) error=error.getCause();
            throw error instanceof IOException io ? io : new IOException("線上套用失敗",error);
        }
    }
    private FabricLiveWorld world(DimensionId id) { return worlds.computeIfAbsent(id,k->new FabricLiveWorld(runtime,layout,layout.dimensions().get(k),oldEntities)); }
    private static void collect(Nbt.Compound entity,Set<UUID> ids,Set<UUID> unique) throws IOException {
        UUID id=EntityNormalizer.uuid(entity); ids.add(id);
        if(!unique.add(id)) throw new IOException("目標實體 UUID 重複："+id);
        for(var p:entity.list("Passengers").values()) collect((Nbt.Compound)p,ids,unique);
    }
    private static void entityTypes(Nbt.Compound entity,Set<String> ids) {
        ids.add(entity.string("id"));
        for(var passenger:entity.list("Passengers").values()) entityTypes((Nbt.Compound)passenger,ids);
    }
    private static boolean contains(Nbt.ListTag entities,Set<UUID> ids) {
        for(var p:entities.values()) { var e=(Nbt.Compound)p; if(ids.contains(EntityNormalizer.uuid(e)) || contains(e.list("Passengers"),ids)) return true; }
        return false;
    }
}
