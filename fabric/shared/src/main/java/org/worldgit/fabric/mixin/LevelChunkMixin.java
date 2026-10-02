package org.worldgit.fabric.mixin;

import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.worldgit.fabric.WorldGitMod;

/** Preserve dirty generations even when vanilla saves the chunk before the next scan. */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Inject(method = "markUnsaved", at = @At("TAIL"))
    private void worldgit$markDirty(CallbackInfo ci) {
        WorldGitMod.chunkChanged((LevelChunk) (Object) this);
    }
    @Inject(method="setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",at=@At("HEAD"),cancellable=true)
    private void worldgit$lock(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.level.block.state.BlockState> ci) {
        var chunk=(LevelChunk)(Object)this;
        if(chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            var runtime=WorldGitMod.runtime(level.getServer());
            if(runtime!=null && runtime.editsLocked(new org.worldgit.core.model.DimensionId(level.dimension().identifier().toString()),new org.worldgit.core.model.ChunkPos(pos.getX()>>4,pos.getZ()>>4)) && !runtime.internalMutation()) ci.setReturnValue(null);
        }
    }

}
