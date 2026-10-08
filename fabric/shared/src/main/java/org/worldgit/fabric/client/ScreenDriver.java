package org.worldgit.fabric.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * 真客戶端驗收用：對目前 WorldGit 畫面做與玩家相同的輸入（以座標點選按鈕、對輸入框輸入文字）。
 * 只讀寫目前畫面，不繞過伺服器：所有修改仍走 worldgit:ui 封包與伺服器端驗證。
 */
public final class ScreenDriver {
  private ScreenDriver() {}

  public static String screenName() {
    var screen = ClientPlatform.currentScreen();
    return screen == null ? "none" : screen.getClass().getSimpleName();
  }

  /** 從畫面的實際繪製路徑讀取可見列，驗收註解與狀態標記的組合。 */
  public static java.util.List<String> ignoreRows() {
    var rows = new java.util.TreeMap<Integer, String>();
    if (ClientPlatform.currentScreen() instanceof IgnoreScreenBase ignore) {
      ignore.paintUnder(new Canvas() {
        public void fill(int x1, int y1, int x2, int y2, int argb) {}
        public int width(net.minecraft.network.chat.Component text) { return 0; }
        public void text(net.minecraft.network.chat.Component text, int x, int y, int argb) {
          if (x == 48 || x == 62) rows.merge(y, text.getString(), (marker, raw) -> marker + " " + raw);
        }
      });
    }
    return java.util.List.copyOf(rows.values());
  }

  /** 以按鈕文字（包含比對）點選；找不到或未啟用回傳 false。 */
  public static boolean click(String label) {
    var screen = ClientPlatform.currentScreen();
    if (screen == null) return false;
    for (var child : screen.children()) {
      if (child instanceof Button button && button.visible && button.active && button.getMessage().getString().contains(label)) {
        return screen.mouseClicked(new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
      }
    }
    return false;
  }

  /** 列出目前畫面的按鈕（label|active），供測試判斷哪些操作可用。 */
  public static java.util.List<String> buttons() {
    var result = new java.util.ArrayList<String>();
    var screen = ClientPlatform.currentScreen();
    if (screen != null)
      for (var child : screen.children())
        if (child instanceof AbstractWidget widget && child instanceof Button button && button.visible)
          result.add(button.getMessage().getString() + "|" + button.active);
    return result;
  }

  /** 設定第一個輸入框的文字（觸發即時語法檢查的 responder）。 */
  public static boolean type(String text) {
    var screen = ClientPlatform.currentScreen();
    if (screen == null) return false;
    for (var child : screen.children())
      if (child instanceof EditBox box) {
        box.setValue(text);
        return true;
      }
    return false;
  }

  public static String inputValue() {
    var screen = ClientPlatform.currentScreen();
    if (screen != null) for (var child : screen.children()) if (child instanceof EditBox box) return box.getValue();
    return "";
  }

  /** 分支圖：選取第 index 個 commit；ignore：選取第 index 行（1-based 行號）。 */
  public static int select(int index) {
    var screen = ClientPlatform.currentScreen();
    if (screen instanceof GraphScreenBase graph) {
      graph.select(index);
      return graph.nodeCount();
    }
    if (screen instanceof IgnoreScreenBase ignore) {
      ignore.select(index);
      return ignore.selectedLine();
    }
    return -1;
  }

  public static int graphNodes() {
    return ClientPlatform.currentScreen() instanceof GraphScreenBase graph ? graph.nodeCount() : -1;
  }

  /** 開啟（空白）聊天輸入畫面：聊天訊息與其 hover 在此狀態下可被滑鼠指到。 */
  public static void openChat(String text) {
    ClientPlatform.openChat(text);
  }

  public static void close() {
    var screen = ClientPlatform.currentScreen();
    if (screen != null) screen.onClose();
  }
}
