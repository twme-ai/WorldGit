package org.worldgit.fabric.gametest;

final class GameTestScreens {
  static void play(net.minecraft.client.Minecraft client) { client.setScreen(null); }
  static void title(net.minecraft.client.Minecraft client) { client.setScreen(new net.minecraft.client.gui.screens.TitleScreen()); }
  static net.minecraft.client.gui.screens.Screen current(net.minecraft.client.Minecraft client) { return client.screen; }
  static net.minecraft.client.gui.screens.Overlay overlay(net.minecraft.client.Minecraft client) { return client.getOverlay(); }
  static net.minecraft.client.Camera camera(net.minecraft.client.Minecraft client) { return client.gameRenderer.getMainCamera(); }
  static boolean guiHidden(net.minecraft.client.Minecraft client) { return client.options.hideGui; }
  static void hideGui(net.minecraft.client.Minecraft client, boolean hidden) { client.options.hideGui=hidden; }
}
