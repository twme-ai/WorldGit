package org.worldgit.fabric.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import org.worldgit.fabric.logic.MessageKeys;
import org.worldgit.fabric.logic.Msg;

/** 客戶端指令 /wgc palette|seethrough|reload|clear|status；全部是本機顯示設定，不影響伺服器或 repo。 */
final class ClientCommands {
  private ClientCommands() {}

  static void register(CommandDispatcher<FabricClientCommandSource> d) {
    // Local clearing works even with an older remote server. Revision requests use the
    // existing command channel; server replies with v2 diff/status, no new payload type.
    d.register(ClientPlatform.literal("wg")
        .executes(c->forward(c.getSource(),""))
        .then(ClientPlatform.argument("options",StringArgumentType.greedyString())
            .executes(c->forward(c.getSource(),StringArgumentType.getString(c,"options")))));
    d.register(
        ClientPlatform.literal("wgc")
            .then(
                ClientPlatform.literal("palette")
                    .then(
                        ClientPlatform.argument("name", StringArgumentType.word())
                            .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[] {"auto", "default", "colorblind"}, b))
                            .executes(c -> palette(c.getSource(), StringArgumentType.getString(c, "name")))))
            .then(
                ClientPlatform.literal("seethrough")
                    .then(ClientPlatform.argument("on", BoolArgumentType.bool()).executes(c -> seeThrough(c.getSource(), BoolArgumentType.getBool(c, "on")))))
            .then(ClientPlatform.literal("reload").executes(c -> reload(c.getSource())))
            .then(ClientPlatform.literal("clear").executes(c -> clear(c.getSource())))
            .then(ClientPlatform.literal("status").executes(c -> status(c.getSource()))));
  }

  private static int forward(FabricClientCommandSource source,String options) {
    String args=options.trim();
    if(args.equals("conflicts")) { ClientRuntime.get().openConflicts(); return 1; }
    if(args.equals("clear") || args.equals("preview off")) clear(source);
    // sendCommand 會再進 Fabric 的 client dispatcher；直接送無簽章文字參數的指令封包。
    source.getClient().getConnection().send(new ServerboundChatCommandPacket("wg"+(args.isEmpty() ? "" : " "+args)));
    return 1;
  }

  private static void say(FabricClientCommandSource s, Msg msg) {
    s.sendFeedback(ClientRuntime.get().text(msg));
  }

  private static int palette(FabricClientCommandSource s, String name) {
    var rt = ClientRuntime.get();
    if (!java.util.Set.of("auto", "default", "colorblind").contains(name)) {
      s.sendError(rt.text(Msg.of(MessageKeys.CLIENT_PALETTE_INVALID)));
      return 0;
    }
    rt.setConfig(rt.config().withPalette(name));
    say(s, Msg.of(MessageKeys.CLIENT_PALETTE, "palette", rt.paletteText(name)));
    return 1;
  }

  private static int seeThrough(FabricClientCommandSource s, boolean on) {
    var rt = ClientRuntime.get();
    rt.setConfig(rt.config().withSeeThrough(on));
    say(s, Msg.of(MessageKeys.CLIENT_SEE_THROUGH, "state", rt.booleanText(on)));
    return 1;
  }

  private static int reload(FabricClientCommandSource s) {
    var rt = ClientRuntime.get();
    try {
      rt.reload();
      say(s, Msg.of(MessageKeys.CLIENT_RELOADED));
      return 1;
    } catch (IOException | RuntimeException e) {
      s.sendError(rt.text(Msg.of(MessageKeys.CLIENT_RELOAD_FAILED, "message", String.valueOf(e.getMessage()))));
      return 0;
    }
  }

  private static int clear(FabricClientCommandSource s) {
    ClientRuntime.get().clear();
    say(s, Msg.of(MessageKeys.CLIENT_CLEARED));
    return 1;
  }

  private static int status(FabricClientCommandSource s) {
    var rt = ClientRuntime.get();
    say(s, Msg.of(MessageKeys.CLIENT_STATUS_TITLE));
    say(s, Msg.of(MessageKeys.CLIENT_STATUS_CONNECTION, "state", rt.value(rt.handshaken() ? MessageKeys.CLIENT_CONNECTED : MessageKeys.CLIENT_WAITING)));
    var scene = rt.currentScene();
    if (scene == null) say(s, Msg.of(MessageKeys.CLIENT_STATUS_NO_PREVIEW));
    else
      say(
          s,
          Msg.of(
              MessageKeys.CLIENT_STATUS_PREVIEW,
              "dimension", scene.published().dimension(),
              "sections", scene.sectionCount(),
              "cells", scene.cellCount(),
              "detail", scene.detailCount()));
    say(s, Msg.of(MessageKeys.CLIENT_STATUS_SETTINGS, "palette", rt.paletteText(rt.config().palette()), "seethrough", rt.booleanText(rt.config().seeThrough())));
    return 1;
  }
}
