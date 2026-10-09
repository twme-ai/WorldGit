package org.worldgit.fabric.mixin;

import java.util.function.Consumer;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;

@Mixin(PersistentEntitySectionManager.class)
public interface EntityManagerAccess<T extends EntityAccess> {
    @Invoker("storeChunkSections") boolean worldgit$store(long chunk, Consumer<T> consume);
    @Accessor("permanentStorage") EntityPersistentStorage<T> worldgit$storage();
    @Invoker("getAllChunksToSave") it.unimi.dsi.fastutil.longs.LongSet worldgit$chunksToSave();
    @Accessor("sectionStorage") EntitySectionStorage<T> worldgit$sections();
}
