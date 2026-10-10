package org.worldgit.fabric;

import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.DimensionId;
import org.worldgit.fabric.mixin.LevelTicksAccess;

/** Retain scheduled ticks in their original containers and preserve remaining delays. */
public final class ChunkTickLocks implements AutoCloseable {
  private static final net.minecraft.server.level.TicketType PIN=new net.minecraft.server.level.TicketType(0,net.minecraft.server.level.TicketType.FLAG_LOADING);
  private final Map<ServerLevel,Set<ChunkPos>> pinned=new IdentityHashMap<>();
  private static final Map<ServerLevel,ChunkTickLocks> ACTIVE=new IdentityHashMap<>();
  private static final Map<ServerLevel,Map<ChunkPos,Long>> SINCE=new IdentityHashMap<>();
  final ServerRuntime runtime;final Map<DimensionId,Set<ChunkPos>> scope;
  final Map<LevelTicksAccess,java.util.function.LongPredicate> checks=new IdentityHashMap<>();
  ChunkTickLocks(ServerRuntime runtime,Map<DimensionId,Set<ChunkPos>> scope) {this.runtime=runtime;this.scope=scope;}
  void acquire() {
    for(var level:runtime.server().getAllLevels()) {
      var dimension=ServerRuntime.dimensionId(level);var chunks=scope.get(dimension);if(chunks==null)continue;
      var times=new HashMap<ChunkPos,Long>();SINCE.put(level,times);ACTIVE.put(level,this);
      var retained=new HashSet<ChunkPos>();pinned.put(level,retained);
      for(var p:chunks){var held=level.getChunkSource().getChunkNow(p.x(),p.z());if(held!=null) {
        // 尚未展開的 pending 排程不隨時間調整、shift 也看不到；鎖住時先展開（決策 #170）。
        held.unpackTicks(level.getGameTime());
        times.put(p,level.getGameTime());retained.add(p);
        level.getChunkSource().addTicketWithRadius(PIN,new net.minecraft.world.level.ChunkPos(p.x(),p.z()),0);
      }}
      SINCE.put(level,times);
      for(var ticks:List.of(level.getBlockTicks(),level.getFluidTicks())) {
        var access=(LevelTicksAccess)ticks;var original=access.worldgit$check();checks.put(access,original);
        access.worldgit$check(p->!runtime.ticksLocked(dimension,new ChunkPos((int)p,(int)(p>>32))) && original.test(p));
      }
    }
  }
  public static void loaded(ServerLevel level,ChunkPos pos) {
    var active=ACTIVE.get(level);if(active==null || !active.scope.getOrDefault(ServerRuntime.dimensionId(level),Set.of()).contains(pos))return;
    var times=SINCE.get(level);if(times.putIfAbsent(pos,level.getGameTime())!=null)return;
    var held=level.getChunkSource().getChunkNow(pos.x(),pos.z());if(held!=null)held.unpackTicks(level.getGameTime());
    active.pinned.get(level).add(pos);level.getChunkSource().addTicketWithRadius(PIN,new net.minecraft.world.level.ChunkPos(pos.x(),pos.z()),0);
  }
  static long elapsed(ServerLevel level,ChunkPos pos) {var times=SINCE.get(level);return times==null || !times.containsKey(pos) ? 0 : level.getGameTime()-times.get(pos);}
  static void beforeSave(ServerLevel level,ChunkPos pos) {
    var times=SINCE.get(level);if(times==null || !times.containsKey(pos))return;
    var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());if(chunk==null)return;
    long elapsed=elapsed(level,pos);
    shift(level.getBlockTicks(),(LevelChunkTicks<net.minecraft.world.level.block.Block>)chunk.getBlockTicks(),chunk.getPos(),elapsed);
    shift(level.getFluidTicks(),(LevelChunkTicks<net.minecraft.world.level.material.Fluid>)chunk.getFluidTicks(),chunk.getPos(),elapsed);
    times.put(pos,level.getGameTime());chunk.markUnsaved();
  }
  static void beforeSaveAll(ServerLevel level) {var times=SINCE.get(level);if(times!=null)for(var pos:new HashSet<>(times.keySet()))beforeSave(level,pos);}
  static void replaced(ServerLevel level,ChunkPos pos) {var times=SINCE.get(level);if(times!=null && times.containsKey(pos))times.put(pos,level.getGameTime());}
  private static <T> void shift(LevelTicks<T> levelTicks,LevelChunkTicks<T> chunkTicks,net.minecraft.world.level.ChunkPos pos,long elapsed) {
    var ticks=chunkTicks.getAll().toList();chunkTicks.removeIf(t->true);
    for(var tick:ticks)chunkTicks.schedule(new ScheduledTick<>(tick.type(),tick.pos(),tick.triggerTick()+elapsed,tick.priority(),tick.subTickOrder()));
    levelTicks.removeContainer(pos);levelTicks.addContainer(pos,chunkTicks);
  }
  public void close() {
    for(var level:runtime.server().getAllLevels()) {
      var chunks=scope.get(ServerRuntime.dimensionId(level));if(chunks==null)continue;
      for(var pos:chunks) {
        var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());if(chunk==null)continue;
        long elapsed=elapsed(level,pos);
        shift(level.getBlockTicks(),(LevelChunkTicks<net.minecraft.world.level.block.Block>)chunk.getBlockTicks(),chunk.getPos(),elapsed);shift(level.getFluidTicks(),(LevelChunkTicks<net.minecraft.world.level.material.Fluid>)chunk.getFluidTicks(),chunk.getPos(),elapsed);
      }
      for(var pos:chunks) {var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());if(chunk!=null)chunk.markUnsaved();}
      for(var pos:pinned.getOrDefault(level,Set.of()))level.getChunkSource().removeTicketWithRadius(PIN,new net.minecraft.world.level.ChunkPos(pos.x(),pos.z()),0);
      SINCE.remove(level);ACTIVE.remove(level);
    }
    checks.forEach(LevelTicksAccess::worldgit$check);
  }
}
