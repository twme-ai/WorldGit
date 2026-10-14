package org.worldgit.fabric.gametest;
import net.minecraft.client.Minecraft;
final class ChatProbe {
  static double[] locate(Minecraft client,String action) {
    int width=client.getWindow().getGuiScaledWidth(),height=client.getWindow().getGuiScaledHeight();
    for(int y=height-1;y>=Math.max(0,height-180);y--)for(int x=0;x<Math.min(width,600);x++) {
      var style=GameTestScreens.chatStyle(client,x,y);var click=style==null?null:style.getClickEvent();
      if(click!=null && LegacyTestPlatform.action(click.getAction()).equals(action)) {double scale=client.getWindow().getGuiScale();return new double[]{x*scale,y*scale};}
    }
    return null;
  }
}
