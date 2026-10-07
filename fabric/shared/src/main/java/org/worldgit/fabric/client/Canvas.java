package org.worldgit.fabric.client;

import net.minecraft.network.chat.Component;

/** 兩個 Minecraft 版本的 GUI 繪製名稱不同（GuiGraphics／GuiGraphicsExtractor）；自訂繪製只用這三個原語。 */
interface Canvas {
  void fill(int x1, int y1, int x2, int y2, int argb);

  void text(Component text, int x, int y, int argb);

  int width(Component text);

  /** 保持單行內容在面板內；完整內容仍可在聊天／選取詳情讀取。 */
  default void textLimited(Component value, int x, int y, int argb, int maxWidth) {
    if (width(value) <= maxWidth) { text(value, x, y, argb); return; }
    String plain = value.getString();
    while (!plain.isEmpty() && width(Component.literal(plain + "…")) > maxWidth)
      plain = plain.substring(0, plain.offsetByCodePoints(plain.length(), -1));
    text(Component.literal(plain + "…"), x, y, argb);
  }

  /** 一像素寬的直線（只支援水平、垂直與斜線），供分支圖連線使用。 */
  default void line(int x1, int y1, int x2, int y2, int argb) {
    int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), steps = Math.max(dx, dy);
    if (steps == 0) {
      fill(x1, y1, x1 + 1, y1 + 1, argb);
      return;
    }
    for (int i = 0; i <= steps; i++) {
      int x = x1 + Math.round((float) (x2 - x1) * i / steps), y = y1 + Math.round((float) (y2 - y1) * i / steps);
      fill(x, y, x + 1, y + 1, argb);
    }
  }
}
