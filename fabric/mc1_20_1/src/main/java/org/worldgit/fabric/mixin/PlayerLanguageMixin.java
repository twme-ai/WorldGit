package org.worldgit.fabric.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.server.level.ServerPlayer.class)
abstract class PlayerLanguageMixin implements org.worldgit.fabric.PlayerLanguageAccess {
  private int worldgit$viewDistance=10;
  public int worldgit$viewDistance() { return worldgit$viewDistance; }
  private String worldgit$language="en_us";
  public String worldgit$language() { return worldgit$language; }
  @Inject(method="updateOptions",at=@At("TAIL"))
  private void worldgit$language(net.minecraft.network.protocol.game.ServerboundClientInformationPacket packet,CallbackInfo ci) { worldgit$language=packet.language(); worldgit$viewDistance=packet.viewDistance(); }
}
