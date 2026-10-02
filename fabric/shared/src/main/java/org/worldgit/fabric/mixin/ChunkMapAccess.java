package org.worldgit.fabric.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 呼叫 vanilla 同一個 serializer，不直接寫入 region 檔。 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccess {
    @Invoker("save") boolean worldgit$save(ChunkAccess chunk);
}
