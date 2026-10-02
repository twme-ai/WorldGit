package org.worldgit.fabric.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 此版本的 GUI draw/extract 名称轉接。 */
final class ConflictScreen extends ConflictScreenBase {
  @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta) {
    graphics.fill(0,0,width,height,0xDA10121C);
    super.extractRenderState(graphics,mouseX,mouseY,delta);
  }
}
