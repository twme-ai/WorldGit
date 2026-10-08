package org.worldgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import org.spongepowered.asm.mixin.*;
import org.worldgit.fabric.AxiomEdits;

@Pseudo
@Mixin(targets="com.moulberry.axiom.packets.AxiomServerboundSetBlock", remap=false)
abstract class AxiomSetBlockMixin {
    @Shadow(remap=false) @Final private int sequenceId;
    @WrapMethod(method="handle", remap=false)
    private void worldgit$axiom(MinecraftServer server, ServerPlayer player, Operation<Void> original) throws Exception {
        if (!AxiomEdits.allowed(server, player)) {
            if (sequenceId >= 0) player.connection.send(new ClientboundBlockChangedAckPacket(sequenceId));
            return;
        }
        try (var scope = AxiomEdits.enter(player, false)) { original.call(server, player); }
    }
}
