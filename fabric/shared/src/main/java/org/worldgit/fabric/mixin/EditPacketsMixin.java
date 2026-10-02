package org.worldgit.fabric.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.worldgit.fabric.WorldGitMod;

/** 凍結 tick 仍允許玩家行走；容器／物品／實體互動必須另外攔截。 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class EditPacketsMixin {
    @Shadow public ServerPlayer player;
    @Inject(method={"handlePlayerAction","handleUseItemOn","handleUseItem","handleInteract","handleContainerClick","handleContainerButtonClick","handleSetCreativeModeSlot",
        "handleSignUpdate","handleEditBook","handleSetCommandBlock","handleSetStructureBlock","handleSetJigsawBlock","handleJigsawGenerate"},at=@At("HEAD"),cancellable=true)
    private void worldgit$lock(CallbackInfo ci) {
        var runtime=WorldGitMod.runtime(player.level().getServer());
        if(runtime!=null && runtime.editsLocked()) ci.cancel();
    }

    @Inject(method="handleChatCommand",at=@At("HEAD"),cancellable=true)
    private void worldgit$command(net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet,CallbackInfo ci) {
        guardCommand(packet.command(),ci);
    }
    @Inject(method="handleSignedChatCommand",at=@At("HEAD"),cancellable=true)
    private void worldgit$signedCommand(net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet,CallbackInfo ci) {
        guardCommand(packet.command(),ci);
    }
    private void guardCommand(String command,CallbackInfo ci) {
        var runtime=WorldGitMod.runtime(player.level().getServer());
        if(runtime!=null && runtime.editsLocked() && !command.equals("wg") && !command.startsWith("wg ")) ci.cancel();
    }
}
