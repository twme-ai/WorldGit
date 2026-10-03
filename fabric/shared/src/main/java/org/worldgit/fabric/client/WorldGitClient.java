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
    var conflictsKey=ClientPlatform.registerKey("key.worldgit.conflicts", org.lwjgl.glfw.GLFW.GLFW_KEY_G);
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
      if (connections.shouldReset(client.getConnection())) ClientRuntime.get().onDisconnect();
      while(conflictsKey.consumeClick()) ClientRuntime.get().openConflicts();
    });
    ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
      ClientRuntime.get().onDisconnect();
      connections.join(handler);
    });
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> connections.disconnected(handler));
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientRuntime.get().close());
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> ClientCommands.register(dispatcher));
    ClientPlatform.registerRender((view, cx, cy, cz) -> ClientRuntime.get().render(view, cx, cy, cz));
    ClientRuntime.LOG.info("WORLDGIT CLIENT_LOADED adapter={}", ClientPlatform.VERSION);
  }
}
