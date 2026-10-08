package org.worldgit.fabric.mixin;

import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.worldgit.fabric.AxiomEdits;

/** 操作 handler 查出的實體，在 handler 完成後才保存 UUID 閉包。 */
@Mixin(Level.class)
abstract class AxiomEntityLookupMixin {
    @Inject(method="getEntity(Ljava/util/UUID;)Lnet/minecraft/world/entity/Entity;", at=@At("RETURN"))
    private void worldgit$axiomEntity(UUID uuid, CallbackInfoReturnable<Entity> ci) { AxiomEdits.observed(ci.getReturnValue()); }
}
