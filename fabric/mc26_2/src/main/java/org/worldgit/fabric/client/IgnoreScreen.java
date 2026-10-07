package org.worldgit.fabric.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 此版本的 GUI draw/extract 名稱轉接。 */
final class IgnoreScreen extends IgnoreScreenBase {
  @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    paintUnder(ClientPlatform.canvas(graphics));
  }

  @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    super.extractRenderState(graphics, mouseX, mouseY, delta);
    paintOver(ClientPlatform.canvas(graphics));
  }
}
