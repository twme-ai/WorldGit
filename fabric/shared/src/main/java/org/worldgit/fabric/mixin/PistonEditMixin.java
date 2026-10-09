package org.worldgit.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 活塞事件包含本體、頭、來源／目的及破壞；在任何變更前保守擋整個事件。 */
@Mixin(PistonBaseBlock.class)
abstract class PistonEditMixin {
    @Inject(method="triggerEvent",at=@At("HEAD"),cancellable=true)
    private void worldgit$guard(BlockState state,Level level,BlockPos pos,int type,int data,CallbackInfoReturnable<Boolean> ci) {
        if(java.util.stream.IntStream.rangeClosed(-13,13).anyMatch(i->org.worldgit.fabric.EditGuard.locked(level,pos.relative(state.getValue(PistonBaseBlock.FACING),i)))) ci.setReturnValue(false);
    }
}
