package org.worldgit.fabric.gametest;
final class LegacyTestPlatform {
  static String action(net.minecraft.network.chat.ClickEvent.Action value) { return value.getName(); }
  static void disconnect(net.minecraft.client.Minecraft client) { if (client.level != null) client.level.disconnect(); client.clearLevel(new net.minecraft.client.gui.screens.TitleScreen()); }
  static void loadWorld(net.minecraft.client.Minecraft client,String name) { client.createWorldOpenFlows().loadLevel(new net.minecraft.client.gui.screens.TitleScreen(),name); }

  static String json(net.minecraft.network.chat.Component component) { return net.minecraft.network.chat.Component.Serializer.toJson(component); }
  static void connect(net.minecraft.client.Minecraft client,String address) {
    net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(),client,
        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),new net.minecraft.client.multiplayer.ServerData("WorldGit",address,false),false);
  }
}
