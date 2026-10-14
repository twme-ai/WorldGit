package org.worldgit.fabric.mixin;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ChunkStorage.class)
public interface TerrainStorageAccess {
    @Accessor("worker") IOWorker worldgit$worker();
}
