package org.worldgit.fabric.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.worldgit.fabric.WorldGitMod;

/** 傳送回傳的是目的世界的新實體；騎乘成功後才擷取完整乘客／載具閉包。 */
@Mixin(Entity.class)
abstract class TouchScopeEntityMixin {
    @Inject(method = "changeDimension", at = @At("RETURN"))
    private void worldgit$transferred(net.minecraft.server.level.ServerLevel destination, CallbackInfoReturnable<Entity> result) {
        WorldGitMod.converted((Entity) (Object) this, result.getReturnValue());
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("RETURN"))
    private void worldgit$mounted(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValue()) WorldGitMod.mounted((Entity) (Object) this, vehicle);
    }
}
