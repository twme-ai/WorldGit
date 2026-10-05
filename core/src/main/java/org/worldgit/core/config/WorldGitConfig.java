package org.worldgit.core.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.RegionFile;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** YAML 使用 safe constructor；拒絕未知鍵、重複鍵、型別錯誤，錯誤附來源。 */
public final class WorldGitConfig {
  private WorldGitConfig() {}

  public enum Track {
    ALL("all"),
    MODIFIED_ONLY("modified-only");
    public final String yaml;

    Track(String s) {
      yaml = s;
    }
  }

  public enum Entities { ALL("all"), PLAYER_TOUCHED("player-touched");
    public final String yaml; Entities(String yaml) { this.yaml = yaml; }
  }

  public record Repo(Track track, Entities entities) {
    public Repo(Track track) { this(track, Entities.ALL); }
    public Repo {
      Objects.requireNonNull(track); Objects.requireNonNull(entities);
    }
  }

  public static Repo legacy(Track track) { return new Repo(track); }

  public record Local(String palette, double entityTolerance) {
    public Local {
      if (!Set.of("default", "colorblind").contains(palette)
          || !Double.isFinite(entityTolerance)
          || entityTolerance < 0
          || entityTolerance > 64)
        throw new IllegalArgumentException(
            "palette 必須是 default|colorblind；entity-tolerance 必須介於 0–64");
    }
  }

  public static Repo readRepo(String text, String source) throws IOException {
    var m = load(text, source, Set.of("track", "entities"));
    String t = string(m, "track", "all", source);
    return new Repo(
        switch (t) {
          case "all" -> Track.ALL;
          case "modified-only" -> Track.MODIFIED_ONLY;
          default -> throw error(source, "track 必須是 all|modified-only");
        }, switch (string(m, "entities", "all", source)) {
          case "all" -> Entities.ALL;
          case "player-touched" -> Entities.PLAYER_TOUCHED;
          default -> throw error(source, "entities 必須是 all|player-touched");
        });
  }

  public static Local readLocal(String text, String source) throws IOException {
    var m = load(text, source, Set.of("palette", "entity-tolerance"));
    String p = string(m, "palette", "default", source);
    Object v = m.getOrDefault("entity-tolerance", 2);
    if (!(v instanceof Number n)) throw error(source, "entity-tolerance 必須是數字");
    try {
      return new Local(p, n.doubleValue());
    } catch (IllegalArgumentException e) {
      throw error(source, e.getMessage());
    }
  }

  public static Repo readRepo(Path path) throws IOException {
    return readRepo(Files.exists(path) ? Files.readString(path) : "", path.toString());
  }

  public static Local readLocal(Path path) throws IOException {
    return readLocal(Files.exists(path) ? Files.readString(path) : "", path.toString());
  }

  public static String write(Repo config) {
    return "track: " + config.track.yaml + "\nentities: " + config.entities.yaml + "\n";
  }

  public static String write(Local config) {
    return "palette: " + config.palette + "\nentity-tolerance: " + config.entityTolerance + "\n";
  }

  public static void write(Path p, String text) throws IOException {
    RegionFile.atomicWrite(p, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static Map<String, Object> load(String text, String source, Set<String> keys)
      throws IOException {
    if (text.length() > 64 * 1024) throw error(source, "設定檔超過 64 KiB");
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(8);
    options.setCodePointLimit(64 * 1024);
    Object root;
    try {
      root = new Yaml(new SafeConstructor(options)).load(text);
    } catch (RuntimeException e) {
      throw error(source, "YAML 語法錯誤：" + e.getMessage());
    }
    if (root == null) return Map.of();
    if (!(root instanceof Map<?, ?> m)) throw error(source, "根節點必須是 mapping");
    var result = new LinkedHashMap<String, Object>();
    for (var e : m.entrySet()) {
      if (!(e.getKey() instanceof String s) || !keys.contains(s))
        throw error(source, "未知設定鍵：" + e.getKey());
      result.put(s, e.getValue());
    }
    return result;
  }

  private static String string(Map<String, Object> m, String key, String fallback, String source)
      throws IOException {
    Object v = m.getOrDefault(key, fallback);
    if (!(v instanceof String s)) throw error(source, key + " 必須是字串");
    return s;
  }

  private static IOException error(String source, String detail) {
    return new IOException(source + "：" + detail);
  }
}
