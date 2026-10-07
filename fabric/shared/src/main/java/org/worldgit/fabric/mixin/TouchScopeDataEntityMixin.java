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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.worldgit.fabric.TouchScope;
import org.worldgit.fabric.WorldGitMod;

/** /data merge|modify entity：被修改的實體算玩家觸及。 */
@Mixin(net.minecraft.server.commands.data.EntityDataAccessor.class)
abstract class TouchScopeDataEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "setData", at = @At("RETURN"))
    private void worldgit$touch(net.minecraft.nbt.CompoundTag tag, CallbackInfo ci) { WorldGitMod.touch(entity); }
}
