package org.worldgit.validation;

import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/** 最小 Java 21 bytecode／API 探針；不代替 WorldGit NMS adapter 驗收。 */
public final class Java21PluginProbe extends JavaPlugin {
  @Override
  public void onEnable() {
    if (Runtime.version().feature() != 21) throw new IllegalStateException("requires Java 21 probe");
    getLogger().info(List.of("WG_JAVA21_PLUGIN_READY").getFirst());
  }
}
