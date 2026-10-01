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
}
