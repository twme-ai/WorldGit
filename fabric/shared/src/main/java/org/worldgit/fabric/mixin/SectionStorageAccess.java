package org.worldgit.fabric.mixin;

import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 指定 chunk 的 POI flush 後等待同一個 vanilla IO queue。 */
@Mixin(SectionStorage.class)
public interface SectionStorageAccess {
    @Accessor("simpleRegionStorage") SimpleRegionStorage worldgit$region();
    @Accessor("dirtyChunks") it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet worldgit$dirtyChunks();
}
