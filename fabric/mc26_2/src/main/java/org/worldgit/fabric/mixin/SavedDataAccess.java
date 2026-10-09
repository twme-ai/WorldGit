package org.worldgit.fabric.mixin;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(SavedDataStorage.class)
public interface SavedDataAccess {
    @Accessor("pendingWriteFuture") CompletableFuture<?> worldgit$pendingWrite();
}
