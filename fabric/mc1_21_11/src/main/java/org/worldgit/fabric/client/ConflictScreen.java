package org.worldgit.fabric.client;

import net.minecraft.client.gui.GuiGraphics;

/** 此版本的 GUI draw/extract 名称轉接。 */
final class ConflictScreen extends ConflictScreenBase {
  @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
    graphics.fill(0,0,width,height,0xDA10121C);
    super.render(graphics,mouseX,mouseY,delta);
  }
}
