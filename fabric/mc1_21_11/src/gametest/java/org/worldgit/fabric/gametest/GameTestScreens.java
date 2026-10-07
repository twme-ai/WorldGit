package org.worldgit.fabric.gametest;

final class GameTestScreens {
  static net.minecraft.client.gui.components.BossHealthOverlay bossOverlay(net.minecraft.client.Minecraft client) { return client.gui.getBossOverlay(); }
  static void play(net.minecraft.client.Minecraft client) { client.setScreen(null); }
  static void title(net.minecraft.client.Minecraft client) { client.setScreen(new net.minecraft.client.gui.screens.TitleScreen()); }
  static net.minecraft.client.gui.screens.Screen current(net.minecraft.client.Minecraft client) { return client.screen; }
  static net.minecraft.client.gui.screens.Overlay overlay(net.minecraft.client.Minecraft client) { return client.getOverlay(); }
  static net.minecraft.client.Camera camera(net.minecraft.client.Minecraft client) { return client.gameRenderer.getMainCamera(); }
  static boolean guiHidden(net.minecraft.client.Minecraft client) { return client.options.hideGui; }
  static void hideGui(net.minecraft.client.Minecraft client, boolean hidden) { client.options.hideGui=hidden; }

  static boolean interact(net.minecraft.client.Minecraft client, net.minecraft.world.entity.Entity entity) {
    var hit = new net.minecraft.world.phys.EntityHitResult(entity);
    var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
    return client.gameMode.interactAt(client.player, entity, hit, hand).consumesAction() | client.gameMode.interact(client.player, entity, hand).consumesAction();
  }
  static net.minecraft.network.chat.Style chatStyle(net.minecraft.client.Minecraft client, int x, int y) {
    var finder = new net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder(client.font, x, y);
    captureChat(client, finder);
    return finder.result();
  }
  static void captureChat(net.minecraft.client.Minecraft client, net.minecraft.client.gui.ActiveTextCollector collector) {
    client.gui.getChat().captureClickableText(collector, client.getWindow().getGuiScaledHeight(), client.gui.getGuiTicks(), true);
  }
}
