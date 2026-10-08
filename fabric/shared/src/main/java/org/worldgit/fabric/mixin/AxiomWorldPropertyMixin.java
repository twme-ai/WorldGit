package org.worldgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.*;
import org.worldgit.fabric.AxiomEdits;

@Pseudo
@Mixin(targets="com.moulberry.axiom.packets.AxiomServerboundSetWorldProperty", remap=false)
abstract class AxiomWorldPropertyMixin {
    @Shadow(remap=false) @Final private int updateId;
    @WrapMethod(method="handle", remap=false)
    private void worldgit$axiom(MinecraftServer server, ServerPlayer player, Operation<Void> original) throws Exception {
        if (!AxiomEdits.allowed(server, player)) {
            // Axiom adds this public method to the vanilla connection. Its own handler also acknowledges rejected updates.
            player.connection.getClass().getMethod("ackWorldPropertiesUpTo", int.class).invoke(player.connection, updateId);
            return;
        }
        try (var scope = AxiomEdits.enter(player, false)) { original.call(server, player); }
    }
}
