package org.worldgit.fabric.logic;

import java.util.*;

/**
 * 一則待翻譯的訊息：語言鍵加參數（參數在 MiniMessage 樣板中是 {@code <name>} 標籤，內容一律當純文字插入，
 * 所以玩家名稱、方塊狀態之類不會被誤解析成標籤）。與 Minecraft／Adventure 無關，由平台層依玩家語言與色票渲染。
 * prefixed 為真時在前面加 {@code common.prefix}。
 */
public record Msg(String key, Map<String, String> args, boolean prefixed) {
  public Msg {
    args = Map.copyOf(args);
    MessageKeys.validate(key, args.keySet());
  }

  /** 參數以 名稱, 值, 名稱, 值… 給。 */
  public static Msg of(String key, Object... nameValue) {
    return new Msg(key, pairs(nameValue), false);
  }

  public static Msg prefixed(String key, Object... nameValue) {
    return new Msg(key, pairs(nameValue), true);
  }

  private static Map<String, String> pairs(Object[] nameValue) {
    if (nameValue.length % 2 != 0) throw new IllegalArgumentException("參數必須成對");
    var map = new LinkedHashMap<String, String>();
    for (int i = 0; i < nameValue.length; i += 2) map.put(String.valueOf(nameValue[i]), String.valueOf(nameValue[i + 1]));
    return map;
  }
}
