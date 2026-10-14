package org.worldgit.fabric.fixture.mixin;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
/** 獨立 fixture：注入原版 SavedTick pending 容器，驗證正式 lock 的 unpack 邊界。 */
@Mixin(LevelChunk.class)
public interface PendingTicksAccess {
  @Mutable @Accessor("blockTicks") void worldgit$blockTicks(LevelChunkTicks<net.minecraft.world.level.block.Block> ticks);
  @Mutable @Accessor("fluidTicks") void worldgit$fluidTicks(LevelChunkTicks<net.minecraft.world.level.material.Fluid> ticks);
}
