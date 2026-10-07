package org.worldgit.fabric.client;

import net.minecraft.client.gui.GuiGraphics;

/** 此版本的 GUI draw 名稱轉接。 */
final class IgnoreScreen extends IgnoreScreenBase {
  @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    paintUnder(ClientPlatform.canvas(graphics));
  }

  @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    super.render(graphics, mouseX, mouseY, delta);
    paintOver(ClientPlatform.canvas(graphics));
  }
}
