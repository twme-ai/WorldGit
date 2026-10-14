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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.worldgit.fabric.TouchScope;
import org.worldgit.fabric.WorldGitMod;

/** 範圍內新加入世界的實體。 */
@Mixin(ServerLevel.class)
abstract class TouchScopeAddEntityMixin {
    // 舊版 teleportTo 不經 changeDimension；ENTITY_LOAD 只在 section 可見時觸發。
    // 原版加入傳送實體後就繼承跨維度資格，不能等可見 lookup 發布。
    @Inject(method = "addDuringTeleport(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void worldgit$teleported(Entity entity, CallbackInfo ci) {
        var level = (ServerLevel) (Object) this;
        var runtime = WorldGitMod.runtime(level.getServer());
        if (runtime != null) runtime.touched().loaded(entity, level);
    }

    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void worldgit$touch(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        var kind = TouchScope.current();
        if (kind == null || !cir.getReturnValue()) return;
        if (kind == TouchScope.Kind.USE && (entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof Projectile)) return;
        if (kind == TouchScope.Kind.AXIOM) org.worldgit.fabric.AxiomEdits.spawned(entity);
        else WorldGitMod.touch(entity);
    }
}
