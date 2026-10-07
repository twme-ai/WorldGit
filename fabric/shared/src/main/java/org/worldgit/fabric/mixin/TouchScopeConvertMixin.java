package org.worldgit.fabric.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.worldgit.fabric.TouchScope;
import org.worldgit.fabric.WorldGitMod;

/** 變形（村民→殭屍村民等）：舊 UUID 的觸及資格繼承到新實體。 */
@Mixin(Mob.class)
abstract class TouchScopeConvertMixin {
    @Inject(method = "convertTo", at = @At("RETURN"))
    private void worldgit$inherit(CallbackInfoReturnable<Mob> cir) {
        WorldGitMod.converted((Entity) (Object) this, cir.getReturnValue());
    }
}
