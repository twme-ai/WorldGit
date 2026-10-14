package org.worldgit.fabric.mixin;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(EntityStorage.class)
public interface EntityStorageAccess {
    @Accessor("worker") IOWorker worldgit$region();
    @Accessor("emptyChunks") it.unimi.dsi.fastutil.longs.LongSet worldgit$emptyChunks();
}
