package org.worldgit.paper;

import java.util.Map;

/** 依執行中的 Minecraft 版本選擇轉接層；同一個 jar 內含所有版本的轉接層，只載入符合的那一個。 */
public final class NmsBridges {
  private NmsBridges() {}

  /** Minecraft 版本 → 轉接層類別名稱。新增版本只需在此加一列並新增模組。 */
  private static final Map<String, String> ADAPTERS =
      Map.of(
          "1.21.11", "org.worldgit.paper.v1_21_11.Bridge_1_21_11",
          "26.2", "org.worldgit.paper.v26_2.Bridge_26_2");

  public static NmsBridge select(String minecraftVersion, ClassLoader loader) {
    String className = ADAPTERS.get(minecraftVersion);
    if (className == null)
      throw new UnsupportedOperationException(
          "WorldGit 尚未支援 Minecraft " + minecraftVersion + "；支援：" + String.join("、", ADAPTERS.keySet()));
    try {
      return (NmsBridge) Class.forName(className, true, loader).getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalStateException(
          "無法載入 " + minecraftVersion + " 的 NMS 轉接層（" + className + "）：" + e, e);
    }
  }

  public static java.util.Set<String> supported() {
    return ADAPTERS.keySet();
  }
}
