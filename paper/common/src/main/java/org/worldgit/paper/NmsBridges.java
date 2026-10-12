package org.worldgit.paper;

import java.util.Map;

/** 依執行中的 Minecraft 版本選擇轉接層；同一個 jar 內含所有版本的轉接層，只載入符合的那一個。 */
public final class NmsBridges {
  private NmsBridges() {}

  /** Minecraft 版本 → 轉接層類別名稱。新增版本只需在此加一列並新增模組。 */
  private static final Map<String, String> ADAPTERS =
      Map.of(
          "1.20.1", "org.worldgit.paper.v1_20_1.Bridge_1_20_1",
          "1.20.4", "org.worldgit.paper.v1_20_4.Bridge_1_20_4",
          "1.20.6", "org.worldgit.paper.v1_20_6.Bridge_1_20_6",
          "1.21.1", "org.worldgit.paper.v1_21_1.Bridge_1_21_1",
          "1.21.4", "org.worldgit.paper.v1_21_4.Bridge_1_21_4",
          "1.21.8", "org.worldgit.paper.v1_21_8.Bridge_1_21_8",
          "1.21.11", "org.worldgit.paper.v1_21_11.Bridge_1_21_11",
          "26.2", "org.worldgit.paper.v26_2.Bridge_26_2");

  public static NmsBridge select(String minecraftVersion, ClassLoader loader) {
    return select(minecraftVersion,loader,false);
  }

  public static NmsBridge select(String minecraftVersion, ClassLoader loader, boolean folia) {
    if(folia && java.util.Set.of("1.21.1","26.3").contains(minecraftVersion))
      throw new UnsupportedOperationException(Messages.plain(Messages.text("paper.version.folia-unavailable","version",minecraftVersion)));
    try { org.worldgit.core.version.MinecraftVersion.byId(minecraftVersion); }
    catch(java.io.IOException error) { throw new UnsupportedOperationException(Messages.plain(Messages.text("paper.version.unsupported","version",minecraftVersion,"supported",String.join(", ",new java.util.TreeSet<>(ADAPTERS.keySet())))),error); }
    String className = ADAPTERS.get(minecraftVersion);
    if(className==null)throw new UnsupportedOperationException(Messages.plain(Messages.text("paper.version.unsupported","version",minecraftVersion,"supported",String.join(", ",new java.util.TreeSet<>(ADAPTERS.keySet())))));
    try {
      var bridge=(NmsBridge) Class.forName(className, true, loader).getDeclaredConstructor().newInstance();
      var catalog=org.worldgit.core.version.MinecraftVersion.byId(minecraftVersion);
      if(!minecraftVersion.equals(bridge.minecraftVersion()) || catalog.dataVersion()!=bridge.dataVersion())throw new IllegalStateException(Messages.plain(Messages.text("paper.version.mismatch","version",minecraftVersion,"expected",catalog.dataVersion(),"actual",bridge.dataVersion())));
      return bridge;
    } catch (ReflectiveOperationException | LinkageError | java.io.IOException e) {
      throw new IllegalStateException(
          Messages.plain(Messages.text("paper.version.load-failed","version",minecraftVersion,"adapter",className,"error",e.toString())), e);
    }
  }

  public static java.util.Set<String> supported() {
    return ADAPTERS.keySet();
  }
}
