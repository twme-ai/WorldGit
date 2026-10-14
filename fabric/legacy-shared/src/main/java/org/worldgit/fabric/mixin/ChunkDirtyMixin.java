package org.worldgit.fabric.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.world.level.chunk.ChunkAccess.class)
abstract class ChunkDirtyMixin {
  @Inject(method="setUnsaved(Z)V",at=@At("TAIL"))
  private void worldgit$dirty(boolean unsaved,CallbackInfo ci) {
    if(unsaved && (Object)this instanceof net.minecraft.world.level.chunk.LevelChunk chunk) org.worldgit.fabric.WorldGitMod.chunkChanged(chunk);
  }
}
