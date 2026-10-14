package org.worldgit.fabric.mixin;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(SectionStorage.class)
public interface SectionStorageAccess {
    @Accessor("worker") IOWorker worldgit$region();
    @Accessor("dirty") it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet worldgit$dirtySections();
}
