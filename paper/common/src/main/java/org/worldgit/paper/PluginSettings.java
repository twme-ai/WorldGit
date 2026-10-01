package org.worldgit.paper;

import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/**
 * plugins/WorldGit/config.yml 的型別化設定。所有值都驗證範圍，錯誤時丟出 IllegalArgumentException 並指出鍵名，
 * 由 plugin 在啟用時以明確訊息停用，不使用默默改回預設值的寬鬆行為。
 */
public record PluginSettings(
    int pollIntervalTicks,
    int chunksPerTick,
    int snapshotWindow,
    int commitTimeoutSeconds,
    boolean autoEnabled,
    int autoIntervalMinutes,
    int autoMaxWaitMinutes,
    int autoMinChangedSections,
    boolean autoEntityOnlyTriggers,
    boolean autoOnQuit,
    int autoQuitDelaySeconds,
    boolean autoOnShutdown,
    int showRadiusChunks,
    int showMaxCells,
    String language,
    String serverName,
    String serverEmail) {

  public PluginSettings {
    check(pollIntervalTicks, 5, 12_000, "dirty-poll-interval-ticks");
    check(chunksPerTick, 1, 512, "commit.chunks-per-tick");
    check(snapshotWindow, 16, 8192, "commit.snapshot-window");
    check(commitTimeoutSeconds, 10, 3600, "commit.timeout-seconds");
    check(autoIntervalMinutes, 1, 10_080, "auto-commit.interval-minutes");
    check(autoMaxWaitMinutes, autoIntervalMinutes, 100_800, "auto-commit.max-wait-minutes");
    check(autoMinChangedSections, 1, 1_000_000, "auto-commit.min-changed-sections");
    check(autoQuitDelaySeconds, 0, 600, "auto-commit.quit-delay-seconds");
    check(showRadiusChunks, 1, 32, "show.radius-chunks");
    check(showMaxCells, 1, 100_000, "show.max-cells");
    Objects.requireNonNull(serverName);
    Objects.requireNonNull(serverEmail);
    Objects.requireNonNull(language);
    if (!language.matches("[A-Za-z]{2,8}([_-][A-Za-z0-9]{2,8})*"))
      throw new IllegalArgumentException("language 無效");
    if (serverName.isBlank() || serverName.matches("(?s).*[\\r\\n<>].*"))
      throw new IllegalArgumentException("server-identity.name 無效");
    if (serverEmail.isBlank() || serverEmail.matches("(?s).*[\\r\\n<>].*"))
      throw new IllegalArgumentException("server-identity.email 無效");
  }

  private static void check(int value, int min, int max, String key) {
    if (value < min || value > max)
      throw new IllegalArgumentException(key + " 必須介於 " + min + " 與 " + max + "，目前是 " + value);
  }

  public static PluginSettings defaults() {
    return from(new org.bukkit.configuration.file.YamlConfiguration());
  }

  public static PluginSettings from(ConfigurationSection c) {
    validateTypes(c, Integer.class, "dirty-poll-interval-ticks", "commit.chunks-per-tick", "commit.snapshot-window", "commit.timeout-seconds",
        "auto-commit.interval-minutes", "auto-commit.max-wait-minutes", "auto-commit.min-changed-sections", "auto-commit.quit-delay-seconds", "show.radius-chunks", "show.max-cells");
    validateTypes(c, Boolean.class, "auto-commit.enabled", "auto-commit.entity-only-triggers", "auto-commit.on-quit", "auto-commit.on-shutdown");
    validateTypes(c, String.class, "language", "server-identity.name", "server-identity.email");
    return new PluginSettings(
        c.getInt("dirty-poll-interval-ticks", 40),
        c.getInt("commit.chunks-per-tick", 8),
        c.getInt("commit.snapshot-window", 256),
        c.getInt("commit.timeout-seconds", 600),
        c.getBoolean("auto-commit.enabled", true),
        c.getInt("auto-commit.interval-minutes", 15),
        c.getInt("auto-commit.max-wait-minutes", 30),
        c.getInt("auto-commit.min-changed-sections", 1),
        c.getBoolean("auto-commit.entity-only-triggers", false),
        c.getBoolean("auto-commit.on-quit", true),
        c.getInt("auto-commit.quit-delay-seconds", 10),
        c.getBoolean("auto-commit.on-shutdown", true),
        c.getInt("show.radius-chunks", 6),
        c.getInt("show.max-cells", 100_000),
        c.getString("language", "zh_tw"),
        c.getString("server-identity.name", "WorldGit Server"),
        c.getString("server-identity.email", "worldgit@server.invalid"));
  }

  private static void validateTypes(ConfigurationSection c, Class<?> type, String... keys) {
    for (String key : keys)
      if (c.contains(key) && !type.isInstance(c.get(key)))
        throw new IllegalArgumentException(key + " 必須是 " + type.getSimpleName());
  }
}
