package org.worldgit.fabric.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.worldgit.fabric.WorldGitMod;

/** 傳送回傳的是目的世界的新實體；騎乘成功後才擷取完整乘客／載具閉包。 */
@Mixin(Entity.class)
abstract class TouchScopeEntityMixin {
    @Inject(method = "teleport", at = @At("RETURN"))
    private void worldgit$transferred(TeleportTransition transition, CallbackInfoReturnable<Entity> result) {
        WorldGitMod.converted((Entity) (Object) this, result.getReturnValue());
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("RETURN"))
    private void worldgit$mounted(Entity vehicle, boolean force, boolean broadcast, CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValue()) WorldGitMod.mounted((Entity) (Object) this, vehicle);
    }
}
