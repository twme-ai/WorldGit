package org.worldgit.fabric.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
abstract class EntityChunkBoundaryMixin {
  @Shadow private net.minecraft.world.level.entity.EntityInLevelCallback levelCallback;
  @Inject(method="setPosRaw",at=@At("HEAD"),cancellable=true)
  private void worldgit$position(double x,double y,double z,CallbackInfo ci) {
    // Projectiles and native position setters can bypass Entity.move(). Ignore
    // unregistered entities so their constructor/load positioning remains valid.
    if(levelCallback==null || levelCallback==net.minecraft.world.level.entity.EntityInLevelCallback.NULL)return;
    var entity=(Entity)(Object)this;
    if(!(entity.level() instanceof net.minecraft.server.level.ServerLevel level))return;
    var runtime=org.worldgit.fabric.WorldGitMod.runtime(level.getServer());
    if(runtime==null || runtime.internalMutation() || !runtime.editsLocked())return;
    if(org.worldgit.fabric.EditGuard.locked(entity.level(),entity.blockPosition())
        || org.worldgit.fabric.EditGuard.locked(entity.level(),net.minecraft.core.BlockPos.containing(x,y,z)))ci.cancel();
  }
  @Inject(method="move",at=@At("HEAD"),cancellable=true)
  private void worldgit$move(MoverType type,Vec3 delta,CallbackInfo ci) {
    var entity=(Entity)(Object)this;
    var destination=net.minecraft.core.BlockPos.containing(entity.position().add(delta));
    if(org.worldgit.fabric.EditGuard.locked(entity.level(),entity.blockPosition()) || org.worldgit.fabric.EditGuard.locked(entity.level(),destination)) ci.cancel();
  }
  @Inject(method="teleport",at=@At("HEAD"),cancellable=true)
  private void worldgit$teleport(net.minecraft.world.level.portal.TeleportTransition transition,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Entity> ci) {
    var entity=(Entity)(Object)this;
    if(org.worldgit.fabric.EditGuard.locked(entity.level(),entity.blockPosition()) || org.worldgit.fabric.EditGuard.locked(transition.newLevel(),net.minecraft.core.BlockPos.containing(transition.position())))ci.setReturnValue(null);
  }
}
