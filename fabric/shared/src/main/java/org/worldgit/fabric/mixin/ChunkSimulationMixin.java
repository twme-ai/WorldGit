package org.worldgit.fabric.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.worldgit.fabric.WorldGitMod;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.DimensionId;

/** Keep the level clock and unrelated chunks running during a chunk edit. */
@Mixin(ServerLevel.class)
abstract class ChunkSimulationMixin {
  private boolean worldgit$locked(ChunkPos pos) {
    var level=(ServerLevel)(Object)this;var runtime=WorldGitMod.runtime(level.getServer());
    return runtime!=null && runtime.ticksLocked(new DimensionId(level.dimension().identifier().toString()),pos);
  }
  @Inject(method="tickChunk",at=@At("HEAD"),cancellable=true)
  private void worldgit$chunk(LevelChunk chunk,int speed,CallbackInfo ci) {
    if(worldgit$locked(org.worldgit.fabric.ServerRuntime.corePos(chunk.getPos()))) ci.cancel();
  }
  @Inject(method="tickNonPassenger",at=@At("HEAD"),cancellable=true)
  private void worldgit$entity(Entity entity,CallbackInfo ci) {
    if(worldgit$locked(org.worldgit.fabric.ServerRuntime.corePos(entity.chunkPosition()))) ci.cancel();
  }
  @Inject(method="tickPassenger",at=@At("HEAD"),cancellable=true)
  private void worldgit$passenger(Entity vehicle,Entity entity,CallbackInfo ci) {
    if(worldgit$locked(org.worldgit.fabric.ServerRuntime.corePos(entity.chunkPosition()))) ci.cancel();
  }
  @Redirect(method="runBlockEvents",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;shouldTickBlocksAt(Lnet/minecraft/core/BlockPos;)Z"))
  private boolean worldgit$deferPiston(ServerLevel level,net.minecraft.core.BlockPos pos) {
    // Vanilla reschedules the event when this predicate is false; it must not consume it.
    return !org.worldgit.fabric.EditGuard.locked(level,pos) && level.shouldTickBlocksAt(pos);
  }
}
