package org.worldgit.fabric.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.*;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** 1.20.1／1.20.4 的原生 networking、VertexBuffer、模型與 Screen 轉接。 */
final class ClientPlatform {
  private ClientPlatform() {}

  static void sendCommand(net.minecraft.client.multiplayer.ClientPacketListener connection, String command) {
    // sendCommand／sendUnsignedCommand 都會再次進 Fabric 的 client dispatcher。
    // WorldGit 的字串參數不要求簽章，但仍沿原版 tracker 更新聊天確認序列。
    connection.send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(command,
        java.time.Instant.now(), 0L, net.minecraft.commands.arguments.ArgumentSignatures.EMPTY,
        connection.lastSeenMessages.generateAndApplyUpdate().update()));
  }


  static final String VERSION = org.worldgit.fabric.Platform.MINECRAFT;

  /** 客戶端指令建構器（Fabric API 在 26.2 把 ClientCommandManager 改名為 ClientCommands）。 */
  static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
    return ClientCommandManager.literal(name);
  }

  static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
    return ClientCommandManager.argument(name, type);
  }

  static void registerRender(RenderCallback callback) {
    net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.LAST.register(ctx -> {
      var cam = ctx.camera().getPosition(); callback.render(ctx.matrixStack().last().pose(), cam.x, cam.y, cam.z);
    });
  }
  static List<Geometry.Quad> model(String serialized) {
    try {
      var state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), new StringReader(serialized), false).blockState();
      var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
      var result = new ArrayList<Geometry.Quad>(); var directions = new ArrayList<Direction>(Arrays.asList(Direction.values())); directions.add(null);
      for (var direction : directions) for (var quad : model.getQuads(state, direction, RandomSource.create(42))) {
        var raw = quad.getVertices(); int stride = raw.length / 4; float[] points = new float[20];
        for (int v = 0; v < 4; v++) { int k = v * 5, at = v * stride;
          for (int i = 0; i < 3; i++) points[k+i] = Float.intBitsToFloat(raw[at+i]);
          points[k+3] = Float.intBitsToFloat(raw[at+4]); points[k+4] = Float.intBitsToFloat(raw[at+5]);
        }
        result.add(new Geometry.Quad(points));
      }
      return List.copyOf(result);
    } catch (Exception error) { throw new IllegalArgumentException("model state " + serialized, error); }
  }
  static void draw(com.mojang.blaze3d.vertex.VertexBuffer buffer, int vertices, boolean textured, boolean seeThrough, Matrix4f transform, float alpha) {
    var color = RenderSystem.getShaderColor().clone();
    RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull(); RenderSystem.depthMask(false);
    if (seeThrough) RenderSystem.disableDepthTest(); else RenderSystem.enableDepthTest();
    RenderSystem.setShaderColor(1, 1, 1, alpha);
    if (textured) RenderSystem.setShaderTexture(0, net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
    var shader = textured ? net.minecraft.client.renderer.GameRenderer.getPositionTexColorShader() : net.minecraft.client.renderer.GameRenderer.getPositionColorShader();
    try { buffer.bind(); buffer.drawWithShader(transform, RenderSystem.getProjectionMatrix(), shader); }
    finally { com.mojang.blaze3d.vertex.VertexBuffer.unbind(); RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
      RenderSystem.depthMask(true); RenderSystem.enableDepthTest(); RenderSystem.enableCull(); RenderSystem.disableBlend(); }
  }
  static void setScreen(net.minecraft.client.gui.screens.Screen screen) { Minecraft.getInstance().setScreen(screen); }
  static net.minecraft.client.gui.screens.Screen currentScreen() { return Minecraft.getInstance().screen; }
  static boolean guiHidden() { return Minecraft.getInstance().options.hideGui; }

  /** 開啟聊天輸入框並預填文字（不送出）。 */
  static void openChat(String text) { Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.ChatScreen(text)); }

  static Canvas canvas(net.minecraft.client.gui.GuiGraphics g) {
    var font = Minecraft.getInstance().font;
    return new Canvas() {
      @Override public void fill(int x1, int y1, int x2, int y2, int argb) { g.fill(x1, y1, x2, y2, argb); }
      @Override public void text(net.minecraft.network.chat.Component text, int x, int y, int argb) { g.drawString(font, text.getVisualOrderText(), x, y, argb, false); }
      @Override public int width(net.minecraft.network.chat.Component text) { return font.width(text); }
    };
  }

  /** 進度 overlay（模組客戶端）：階段、維度、百分比或不定進度、ETA，完成後顯示終態。 */
  static void registerProgressHud() {
    net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((g, delta) -> {
      var mc = Minecraft.getInstance(); if (mc.level == null || guiHidden()) return;
      var items = ClientRuntime.get().progressItems();
      if (!items.isEmpty()) ProgressHud.render(canvas(g), items, g.guiWidth(), System.currentTimeMillis());
    });
  }
  static net.minecraft.client.KeyMapping registerKey(String name, int key) {
    return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(new net.minecraft.client.KeyMapping(name, key, "key.categories.worldgit"));
  }
  static void registerCommentHud() {
    net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((g, delta) -> {
      var mc = Minecraft.getInstance(); if (mc.level == null || guiHidden()) return; int y = 12, width = Math.min(600, g.guiWidth()-24);
      for (var line : ClientRuntime.get().commentHud()) {
        for (var part : mc.font.split(line, width)) {
          if (y > g.guiHeight()-70) return; g.fill(8,y-2,12+width,y+10,0xCC101820); g.drawString(mc.font,part,12,y,0xFFFFFFFF,true); y+=11;
        }
        y+=3;
      }
    });
  }
  static net.minecraft.network.chat.Component text(net.kyori.adventure.text.Component component) {
    return net.minecraft.network.chat.Component.Serializer.fromJson(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.builder().options(net.kyori.adventure.text.serializer.json.JSONOptions.byDataVersion().at(net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion())).build().serialize(component));
  }
  static void send(org.worldgit.fabric.Net.Channel channel, byte[] bytes) {
    org.worldgit.fabric.Net.check(bytes); var buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create(); buf.writeBytes(bytes);
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new net.minecraft.resources.ResourceLocation(channel.id()), buf);
  }
  static void registerClient() {
    for (var channel : java.util.List.of(org.worldgit.fabric.Net.HELLO, org.worldgit.fabric.Net.UI, org.worldgit.fabric.Net.DIFF,
        org.worldgit.fabric.Net.STATUS, org.worldgit.fabric.Net.CLEAR, org.worldgit.fabric.Net.COMMENTS, org.worldgit.fabric.Net.CONFLICTS, org.worldgit.fabric.Net.CONFLICT_PREVIEW)) {
      net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(new net.minecraft.resources.ResourceLocation(channel.id()), (client, handler, buf, sender) -> {
        if (buf.readableBytes() > org.worldgit.protocol.Protocol.MAX_PAYLOAD) return;
        byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes); client.execute(() -> receive(channel, bytes));
      });
    }
  }
  static void receive(org.worldgit.fabric.Net.Channel channel, byte[] bytes) {
    var rt = ClientRuntime.get();
    if (channel.equals(org.worldgit.fabric.Net.HELLO)) rt.onHello(bytes);
    else if (channel.equals(org.worldgit.fabric.Net.UI)) rt.onUi(bytes);
    else if (channel.equals(org.worldgit.fabric.Net.COMMENTS)) rt.onComments(bytes);
    else if (channel.equals(org.worldgit.fabric.Net.CONFLICTS)) rt.onMerge(org.worldgit.protocol.MergeProtocol.REGIONS, bytes);
    else if (channel.equals(org.worldgit.fabric.Net.CONFLICT_PREVIEW)) rt.onMerge(org.worldgit.protocol.MergeProtocol.PREVIEW, bytes);
    else rt.onPreview(bytes);
  }
}
