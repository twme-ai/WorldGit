package org.worldgit.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BoneMealItem.class)
abstract class BoneMealEditMixin {
    @Inject(method="growCrop",at=@At("HEAD"),cancellable=true)
    private static void worldgit$crop(ItemStack stack,Level level,BlockPos pos,CallbackInfoReturnable<Boolean> ci) {
        if(org.worldgit.fabric.EditGuard.locked(level,pos)) ci.setReturnValue(false);
    }
    @Inject(method="growWaterPlant",at=@At("HEAD"),cancellable=true)
    private static void worldgit$water(ItemStack stack,Level level,BlockPos pos,net.minecraft.core.Direction direction,CallbackInfoReturnable<Boolean> ci) {
        if(org.worldgit.fabric.EditGuard.locked(level,pos)) ci.setReturnValue(false);
    }
}
