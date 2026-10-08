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

/** 範圍內新加入世界的實體。 */
@Mixin(ServerLevel.class)
abstract class TouchScopeAddEntityMixin {
    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void worldgit$touch(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        var kind = TouchScope.current();
        if (kind == null || !cir.getReturnValue()) return;
        if (kind == TouchScope.Kind.USE && (entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof Projectile)) return;
        if (kind == TouchScope.Kind.AXIOM) org.worldgit.fabric.AxiomEdits.spawned(entity);
        else WorldGitMod.touch(entity);
    }
}
