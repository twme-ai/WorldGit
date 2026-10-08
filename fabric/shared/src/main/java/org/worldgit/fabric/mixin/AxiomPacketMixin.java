package org.worldgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.worldgit.fabric.AxiomEdits;

/** 僅使用兩版 6.1.3 bytecode 核對過的公開 handle 簽章，無 Axiom 編譯相依。 */
@Pseudo
@Mixin(targets={
    "com.moulberry.axiom.packets.AxiomServerboundSetBuffer",
    "com.moulberry.axiom.packets.AxiomServerboundSpawnEntity",
    "com.moulberry.axiom.packets.AxiomServerboundManipulateEntity",
    "com.moulberry.axiom.packets.AxiomServerboundDeleteEntity",
    "com.moulberry.axiom.packets.AxiomServerboundSetTime",
    "com.moulberry.axiom.packets.AxiomServerboundTickBlocks",
    "com.moulberry.axiom.packets.AxiomServerboundFixArea",
    "com.moulberry.axiom.packets.AxiomServerboundAnnotationUpdate"
}, remap=false)
abstract class AxiomPacketMixin {
    @WrapMethod(method="handle", remap=false)
    private void worldgit$axiom(MinecraftServer server, ServerPlayer player, Operation<Void> original) throws Exception {
        if (!AxiomEdits.allowed(server, player)) return;
        boolean manipulate = getClass().getName().endsWith("AxiomServerboundManipulateEntity");
        try (var scope = AxiomEdits.enter(player, manipulate)) { original.call(server, player); }
    }
}
