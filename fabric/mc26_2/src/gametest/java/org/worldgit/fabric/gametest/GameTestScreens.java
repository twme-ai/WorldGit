package org.worldgit.fabric.gametest;

final class GameTestScreens {
  static void play(net.minecraft.client.Minecraft client) { client.gui.setScreen(null); }
  static void title(net.minecraft.client.Minecraft client) { client.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()); }
  static net.minecraft.client.gui.screens.Screen current(net.minecraft.client.Minecraft client) { return client.gui.screen(); }
  static net.minecraft.client.gui.screens.Overlay overlay(net.minecraft.client.Minecraft client) { return client.gui.overlay(); }
  static net.minecraft.client.Camera camera(net.minecraft.client.Minecraft client) { return client.gameRenderer.mainCamera(); }
  static boolean guiHidden(net.minecraft.client.Minecraft client) { return client.gui.hud.isHidden(); }
  static void hideGui(net.minecraft.client.Minecraft client, boolean hidden) { if(client.gui.hud.isHidden()!=hidden)client.gui.hud.toggle(); }
}
