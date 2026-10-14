package org.worldgit.fabric.mixin;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ThreadedLevelLightEngine.class)
public interface LightQueueAccess {
  @Invoker("addTask") void worldgit$enqueue(int x, int z, ThreadedLevelLightEngine.TaskType type, Runnable task);
}
