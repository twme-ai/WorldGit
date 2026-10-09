package org.worldgit.fabric.mixin;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Mixin(Level.class)
abstract class BlockEntityTickMixin {
  @Redirect(method="tickBlockEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
  private void worldgit$ticker(TickingBlockEntity ticker) {
    if(!org.worldgit.fabric.EditGuard.locked((Level)(Object)this,ticker.getPos())) ticker.tick();
  }
}
