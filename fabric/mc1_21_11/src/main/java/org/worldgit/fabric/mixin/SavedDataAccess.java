package org.worldgit.fabric.mixin;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(DimensionDataStorage.class)
public interface SavedDataAccess {
    @Accessor("pendingWriteFuture") CompletableFuture<?> worldgit$pendingWrite();
}
