package org.worldgit.fabric.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
abstract class ExplosionEditMixin {
    @Shadow @Final private ServerLevel level;
    @Inject(method="explode",at=@At("HEAD"),cancellable=true)
    private void worldgit$guard(CallbackInfoReturnable<Integer> ci) {
        if(org.worldgit.fabric.EditGuard.locked(level)) ci.setReturnValue(0);
    }
}
