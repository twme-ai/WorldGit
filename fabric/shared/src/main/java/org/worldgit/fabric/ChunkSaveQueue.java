package org.worldgit.fabric;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.worldgit.core.model.*;
import org.worldgit.fabric.mixin.*;

/** Serialize on the owner with a tick budget; wait for storage workers on the coordinator. */
final class ChunkSaveQueue {
    private record Job(ServerLevel level,ChunkPos pos,int retries) {}
    private final ServerRuntime runtime;
    private final ArrayDeque<Job> jobs=new ArrayDeque<>();
    private final Set<ServerLevel> levels=new LinkedHashSet<>();
    private final CompletableFuture<CompletableFuture<?>[]> submitted=new CompletableFuture<>();
    private final boolean metadata;
    private ChunkSaveQueue(ServerRuntime runtime,boolean metadata) {this.runtime=runtime;this.metadata=metadata;}
    @SuppressWarnings("unchecked") private static EntityManagerAccess<Entity> entities(ServerLevel level) {
        return (EntityManagerAccess<Entity>)(Object)((ServerLevelAccess)level).worldgit$entities();
    }
    static void flush(ServerRuntime runtime,Map<DimensionId,Set<ChunkPos>> scope,boolean metadata) {
        var queue=new ChunkSaveQueue(runtime,metadata);
        runtime.postToServer(()->queue.start(scope));
        try {
            var futures=queue.submitted.get(600,TimeUnit.SECONDS);
            long start=System.nanoTime();CompletableFuture.allOf(futures).get(600,TimeUnit.SECONDS);
            var timings=org.worldgit.core.service.OperationTimings.current();if(timings!=null)timings.record("io-barrier",System.nanoTime()-start);
        }catch(InterruptedException error){Thread.currentThread().interrupt();queue.submitted.completeExceptionally(error);throw new CompletionException(error);}
        catch(ExecutionException|TimeoutException error){queue.submitted.completeExceptionally(error);throw new CompletionException(error);}
    }
    private void start(Map<DimensionId,Set<ChunkPos>> scope) {
        try {
            for(var level:runtime.server().getAllLevels()) {
                var dimension=ServerRuntime.dimensionId(level);
                if(scope!=null && !scope.containsKey(dimension))continue;
                levels.add(level);var positions=new TreeSet<ChunkPos>();
                if(scope==null) {
                    // Census is cheap; the per-tick serialization quota applies only to work.
                    for(var pos:runtime.loadedChunks(dimension)) {
                        var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());
                        if(chunk!=null && chunk.isUnsaved())positions.add(pos);
                    }
                    var manager=((ServerLevelAccess)level).worldgit$entities();var access=entities(level);
                    var storage=(EntityStorageAccess)access.worldgit$storage();
                    for(long packed:access.worldgit$chunksToSave()) {
                        // A prior nonempty save must still be cleared after the last entity leaves.
                        // Only vanilla-certified empty storage AND no current saveable entity may skip.
                        boolean empty=manager.areEntitiesLoaded(packed) && storage.worldgit$emptyChunks().contains(packed)
                            && access.worldgit$sections().getExistingSectionsInChunk(packed)
                                .flatMap(section->section.getEntities()).noneMatch(Entity::shouldBeSaved);
                        if(!empty)positions.add(new ChunkPos((int)packed,(int)(packed>>32)));
                    }
                    for(long packed:((SectionStorageAccess)level.getPoiManager()).worldgit$dirtyChunks())
                        positions.add(new ChunkPos((int)packed,(int)(packed>>32)));
                }else positions.addAll(scope.get(dimension));
                for(var pos:positions)jobs.add(new Job(level,pos,0));
            }
            pump();
        }catch(Throwable error){submitted.completeExceptionally(error);}
    }
    private void pump() {
        if(submitted.isDone())return;
        try {
            long start=System.nanoTime();int count=0;
            while(!jobs.isEmpty() && count<8 && (count==0 || System.nanoTime()-start<5_000_000)) {
                var job=jobs.remove();var level=job.level;var pos=job.pos;
                var manager=((ServerLevelAccess)level).worldgit$entities();var access=entities(level);
                if(!access.worldgit$store(Integer.toUnsignedLong(pos.x())|((long)pos.z()<<32),e->{})) {
                    if(job.retries>=1200)throw new IOException("entity IO 尚未完成："+pos);
                    if(runtime.stoppingServer()) {access.worldgit$storage().flush(false);manager.processPendingLoads();}
                    jobs.add(new Job(level,pos,job.retries+1));count++;continue;
                }
                var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());
                if(chunk!=null) {ChunkTickLocks.beforeSave(level,pos);((ChunkMapAccess)level.getChunkSource().chunkMap).worldgit$save(chunk);}
                level.getPoiManager().flush(new net.minecraft.world.level.ChunkPos(pos.x(),pos.z()));count++;
            }
            if(!jobs.isEmpty()) {if(runtime.stoppingServer())runtime.postToServer(this::pump);else runtime.nextTick(this::pump);return;}
            var waits=new ArrayList<CompletableFuture<?>>();
            if(metadata)saveMetadata(waits);
            for(var level:levels) {
                waits.add(level.getChunkSource().chunkMap.synchronize(true));
                waits.add(((SectionStorageAccess)level.getPoiManager()).worldgit$region().synchronize(true));
                waits.add(((EntityStorageAccess)entities(level).worldgit$storage()).worldgit$region().synchronize(true));
            }
            submitted.complete(waits.toArray(CompletableFuture<?>[]::new));
        }catch(Throwable error){submitted.completeExceptionally(error);}
    }
    private void saveMetadata(List<CompletableFuture<?>> waits) {waits.addAll(Platform.saveMetadata(runtime.server(),levels));}
}
