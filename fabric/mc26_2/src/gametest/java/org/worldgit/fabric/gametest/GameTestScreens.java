package org.worldgit.fabric.gametest;

final class GameTestScreens {
  static void title(net.minecraft.client.Minecraft client) { client.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()); }
  static net.minecraft.client.gui.screens.Screen current(net.minecraft.client.Minecraft client) { return client.gui.screen(); }
}
