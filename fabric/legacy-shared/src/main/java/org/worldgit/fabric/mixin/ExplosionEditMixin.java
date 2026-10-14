package org.worldgit.fabric.mixin;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.world.level.Explosion.class)
abstract class ExplosionEditMixin {
  @Shadow @Final private net.minecraft.world.level.Level level;
  @Shadow @Final private double x;
  @Shadow @Final private double z;
  @Shadow @Final private float radius;
  @Inject(method="explode()V",at=@At("HEAD"),cancellable=true)
  private void worldgit$guard(CallbackInfo ci) {
    int minX=((int)Math.floor(x-radius*2-1))>>4,maxX=((int)Math.floor(x+radius*2+1))>>4;
    int minZ=((int)Math.floor(z-radius*2-1))>>4,maxZ=((int)Math.floor(z+radius*2+1))>>4;
    for(int cx=minX;cx<=maxX;cx++)for(int cz=minZ;cz<=maxZ;cz++)if(org.worldgit.fabric.EditGuard.locked(level,new net.minecraft.core.BlockPos(cx*16,0,cz*16))) {ci.cancel();return;}
  }
}
