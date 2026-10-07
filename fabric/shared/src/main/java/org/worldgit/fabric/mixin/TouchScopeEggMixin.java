package org.worldgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.worldgit.fabric.TouchScope;

/** 玩家擲出的雞蛋孵出的幼體；自然投射物不取得觸及資格。 */
@Mixin(net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg.class)
abstract class TouchScopeEggMixin {
    @WrapMethod(method = "onHit")
    private void worldgit$egg(HitResult hit, Operation<Void> original) {
        var self = (Projectile) (Object) this;
        TouchScope.enter(self.level() instanceof ServerLevel && self.getOwner() instanceof ServerPlayer ? TouchScope.Kind.THROWN : TouchScope.Kind.NONE);
        try { original.call(hit); }
        finally { TouchScope.exit(); }
    }
}
