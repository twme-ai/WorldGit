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
    @Shadow @Final private net.minecraft.world.phys.Vec3 center;
    @Shadow @Final private float radius;
    @Inject(method="explode",at=@At("HEAD"),cancellable=true)
    private void worldgit$guard(CallbackInfoReturnable<Integer> ci) {
        int minX=((int)Math.floor(center.x-radius*2-1))>>4,maxX=((int)Math.floor(center.x+radius*2+1))>>4;
        int minZ=((int)Math.floor(center.z-radius*2-1))>>4,maxZ=((int)Math.floor(center.z+radius*2+1))>>4;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(org.worldgit.fabric.EditGuard.locked(level,new net.minecraft.core.BlockPos(x*16,0,z*16))) {ci.setReturnValue(0);return;}
    }
}
