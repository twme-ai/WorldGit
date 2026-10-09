package org.worldgit.fabric;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.worldgit.core.model.DimensionId;
import org.worldgit.fabric.WorldGitMod;

/** 批量動作在開始前檢查維度，避免逐格攔截造成一半的活塞／爆炸／樹。 */
public final class EditGuard {
    private EditGuard() {}
    public static boolean locked(Level level,net.minecraft.core.BlockPos pos) {
        if(!(level instanceof ServerLevel serverLevel)) return false;
        var runtime=WorldGitMod.runtime(serverLevel.getServer());
        return runtime!=null && !runtime.internalMutation() && runtime.editsLocked()
            && runtime.editsLocked(new DimensionId(serverLevel.dimension().identifier().toString()),new org.worldgit.core.model.ChunkPos(pos.getX()>>4,pos.getZ()>>4));
    }
    public static boolean locked(Level level) {
        if(!(level instanceof ServerLevel serverLevel)) return false;
        var runtime=WorldGitMod.runtime(serverLevel.getServer());
        return runtime!=null && !runtime.internalMutation() && runtime.editsLocked(new DimensionId(serverLevel.dimension().identifier().toString()));
    }
}
