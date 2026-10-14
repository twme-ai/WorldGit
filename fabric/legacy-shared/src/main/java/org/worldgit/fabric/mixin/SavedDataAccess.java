package org.worldgit.fabric.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(net.minecraft.world.level.storage.DimensionDataStorage.class)
public interface SavedDataAccess {
  @Accessor("cache") java.util.Map<String,net.minecraft.world.level.saveddata.SavedData> worldgit$cache();
}
