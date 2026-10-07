package org.worldgit.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.worldgit.fabric.Net;
import org.worldgit.fabric.logic.ClientConnections;

/** 客戶端進入點：握手、接收預覽封包、渲染外框／鬼影、/wgc 指令。 */
public final class WorldGitClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    Net.register();
    ClientRuntime.init(FabricLoader.getInstance().getConfigDir());
    var connections = new ClientConnections<net.minecraft.client.multiplayer.ClientPacketListener>();
    ClientPlayNetworking.registerGlobalReceiver(Net.HELLO.type(), (payload, context) -> ClientRuntime.get().onHello(payload.bytes()));
    for (var channel : new Net.Channel[] {Net.DIFF, Net.STATUS, Net.CLEAR})
      ClientPlayNetworking.registerGlobalReceiver(channel.type(), (payload, context) -> ClientRuntime.get().onPreview(payload.bytes()));
    ClientPlayNetworking.registerGlobalReceiver(Net.CONFLICTS.type(), (payload, context) -> ClientRuntime.get().onMerge(org.worldgit.protocol.MergeProtocol.REGIONS,payload.bytes()));
    ClientPlayNetworking.registerGlobalReceiver(Net.CONFLICT_PREVIEW.type(), (payload, context) -> ClientRuntime.get().onMerge(org.worldgit.protocol.MergeProtocol.PREVIEW,payload.bytes()));
    ClientPlayNetworking.registerGlobalReceiver(Net.UI.type(), (payload, context) -> ClientRuntime.get().onUi(payload.bytes()));
    ClientPlayNetworking.registerGlobalReceiver(Net.COMMENTS.type(),(payload,context)->ClientRuntime.get().onComments(payload.bytes()));
    var conflictsKey=ClientPlatform.registerKey("key.worldgit.conflicts", org.lwjgl.glfw.GLFW.GLFW_KEY_G);
    var graphKey=ClientPlatform.registerKey("key.worldgit.graph", org.lwjgl.glfw.GLFW.GLFW_KEY_H);
    var ignoreKey=ClientPlatform.registerKey("key.worldgit.ignore", org.lwjgl.glfw.GLFW.GLFW_KEY_K);
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
      if (connections.shouldReset(client.getConnection())) ClientRuntime.get().onDisconnect();
      ClientRuntime.get().tickComments();
      while(conflictsKey.consumeClick()) ClientRuntime.get().openConflicts();
      while(graphKey.consumeClick()) {
        var runtime=ClientRuntime.get();
        runtime.command(runtime.uiCapable() ? "wg graph" : "wg log --graph");
      }
      while(ignoreKey.consumeClick()) ClientRuntime.get().command("wg ignore gui");
    });
    ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
      ClientRuntime.get().onDisconnect();
      connections.join(handler);
    });
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> connections.disconnected(handler));
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientRuntime.get().close());
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> ClientCommands.register(dispatcher));
    ClientPlatform.registerRender((view, cx, cy, cz) -> ClientRuntime.get().render(view, cx, cy, cz));
    ClientPlatform.registerCommentHud();
    ClientPlatform.registerProgressHud();
    ClientRuntime.LOG.info("WORLDGIT CLIENT_LOADED adapter={}", ClientPlatform.VERSION);
  }
}
