package org.worldgit.fabric.gametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector2f;

/** 遍歷原版當前聊天的文字幾何一次，再用 vanilla hit test 驗證可點選片段的真座標。 */
final class ChatProbe {
  private ChatProbe() {}

  static double[] locate(Minecraft client, String action) {
    var collector = new ActiveTextCollector() {
      private Parameters parameters = new ActiveTextCollector.ClickableStyleFinder(client.font, 0, 0).defaultParameters();
      private double[] point;
      @Override public Parameters defaultParameters() { return parameters; }
      @Override public void defaultParameters(Parameters value) { parameters = value; }
      @Override public void accept(TextAlignment alignment, int x, int y, Parameters value, FormattedCharSequence text) {
        if (point != null) return;
        int left = alignment.calculateLeft(x, client.font, text);
        int[] advance = {0};
        text.accept((index, style, codePoint) -> {
          var click = style.getClickEvent();
          if (click != null && click.action().getSerializedName().equals(action)) {
            var position = value.pose().transformPosition(new Vector2f(left + advance[0] + 1, y + 4));
            var actual = GameTestScreens.chatStyle(client, (int) position.x, (int) position.y);
            if (actual != null && click.equals(actual.getClickEvent())) {
              double scale = client.getWindow().getGuiScale();
              point = new double[] {position.x * scale, position.y * scale};
              return false;
            }
          }
          advance[0] += client.font.width(new String(Character.toChars(codePoint)));
          return true;
        });
      }
      @Override public void acceptScrolling(Component text, int x1, int x2, int y1, int y2, int center, Parameters value) {
        defaultScrollingHelper(text, x1, x2, y1, y2, center, client.font.width(text), client.font.lineHeight, value);
      }
    };
    GameTestScreens.captureChat(client, collector);
    return collector.point;
  }
}
