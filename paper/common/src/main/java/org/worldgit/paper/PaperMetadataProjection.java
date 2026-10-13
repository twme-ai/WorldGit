package org.worldgit.paper;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.version.NbtUpgrader;

/** 原版載入預設及空 Bukkit 標記包的版本投影；完整自訂資料包仍由原預檢處理。 */
public final class PaperMetadataProjection {
  private PaperMetadataProjection() {}
  private static final Set<String> EXPERIMENTS = Set.of("bundle", "update_1_20", "update_1_21",
      "trade_rebalance", "redstone_experiments", "minecart_improvements");
  private static final String MARKER = "datapacks/bukkit/pack.mcmeta";
  private static final String DESCRIPTION = "Data pack for resources provided by Bukkit plugins";
  private static final String CURRENT_DESCRIPTION = "Persistent resources provided by plugins";
  private static final Pattern FORMAT = Pattern.compile("(\"pack_format\"\\s*:\\s*)[0-9]+");
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static Nbt.Compound liveLevel(Nbt.Compound stored, Nbt.Compound active, int target,
      NbtUpgrader upgrader) throws IOException {
    int version = stored.integer("DataVersion", target);
    if (version > target) throw new IOException("Level DataVersion exceeds server DataVersion");
    var level = version < target ? upgrader.upgrade("LEVEL", Nbt.copy(stored), version, target)
        : Nbt.copy(stored);
    level.put("DataVersion", target);
    active.forEach((key, value) -> level.put(key, Nbt.copy(value)));
    return level;
  }

  public static NbtUpgrader wrap(NbtUpgrader vanilla, int target, Nbt.Compound defaults,
      int packFormat, Collection<String> available) throws IOException {
    return wrap(vanilla, target, defaults, packFormat, 0, available);
  }

  public static NbtUpgrader wrap(NbtUpgrader vanilla, int target, Nbt.Compound defaults,
      int packFormat, int packMinor, Collection<String> available) throws IOException {
    var nativeRules = Nbt.copy(defaults);
    var experiments = new TreeSet<String>(available);
    experiments.retainAll(EXPERIMENTS);
    String fingerprint = "paper-metadata-v6:" + target + ":" + packFormat + "." + packMinor + ":" + experiments
        + ":" + Base64.getEncoder().encodeToString(Nbt.write(nativeRules));
    return new NbtUpgrader() {
      public Nbt.Compound upgrade(String type, Nbt.Compound input, int from, int to) throws IOException {
        return vanilla.upgrade(type, input, from, to);
      }
      public String projectionFingerprint() { return fingerprint; }
      public SortedMap<String, byte[]> projectMetadata(SortedMap<String, byte[]> input,
          int from, int to) throws IOException {
        if (to != target) throw new IOException("Metadata target does not match server DataVersion");
        var output = new TreeMap<>(input);
        if (input.containsKey("level.nbt")) {
          var level = Nbt.read(input.get("level.nbt"));
          String ruleKey = to < 4671 ? "GameRules" : "game_rules";
          if (level.containsKey(ruleKey)) {
            var rules = level.compound(ruleKey);
            nativeRules.forEach((key, value) -> rules.putIfAbsent(key, Nbt.copy(value)));
          }
          if (level.containsKey("DataPacks")) {
            var packs = level.compound("DataPacks");
            var disabled = packs.list("Disabled").values();
            // 只投影純原版可用實驗包名單；自訂 Disabled 名單與 Enabled 的順序完整保留。
            if (disabled.stream().allMatch(EXPERIMENTS::contains)) {
              var enabled = packs.list("Enabled").values();
              packs.put("Disabled", new Nbt.ListTag(8,
                  experiments.stream().filter(name -> !enabled.contains(name)).map(name -> (Object) name).toList()));
            }
          }
          output.put("level.nbt", Nbt.write(level));
        }
        // 26.1 的 gamerule 從 level 拆到維度 SavedData；套用相同原生缺省值。
        // 僅補既有原版別名，不能憑空建立其他維度或覆蓋使用者設定。
        if (to >= org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE)
          for (var entry : input.entrySet())
            if (entry.getKey().equals("game_rules.dat.nbt")
                || entry.getKey().endsWith(".game_rules.dat.nbt")) {
              var saved = Nbt.read(entry.getValue());
              var rules = saved.compound("data");
              nativeRules.forEach((key, value) -> rules.putIfAbsent(key, Nbt.copy(value)));
              saved.put("data", rules);
              output.put(entry.getKey(), Nbt.write(saved));
            }
        projectEmptyMarker(output, packFormat, packMinor, to >= 4671);
        if (to < org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE)
          legacyPaperData(output, output.containsKey("level.nbt") ? Nbt.read(output.get("level.nbt")) : null);
        return output;
      }
    };
  }

  /** 固定世界身分；新增平台檔只補原生等價表示，未知內容仍交由嚴格 metadata 預檢。 */
  public static NbtUpgrader forWorld(NbtUpgrader delegate, String dimension, UUID uuid,
      java.nio.file.Path folder) {
    final Nbt.Compound fallback;
    final byte[] marker;
    try {
      var file = folder.resolve("data/paper/level_overrides.dat");
      if (java.nio.file.Files.isRegularFile(file)) {
        var saved = org.worldgit.core.anvil.WorldLayout.readGzip(file);
        fallback = saved.compound("data");fallback.remove("game_time");
      } else if (java.nio.file.Files.isRegularFile(folder.resolve("level.dat")))
        fallback = override(org.worldgit.core.anvil.WorldLayout.readGzip(folder.resolve("level.dat")).compound("Data"));
      else fallback = null;
      var root = WorldMapper.worldRoot(folder);
      var path = root.resolve(MARKER);
      if (java.nio.file.Files.isRegularFile(path) && java.nio.file.Files.size(path) <= 16_384
          && nativeMarker(JSON.readTree(java.nio.file.Files.readAllBytes(path)))) marker = java.nio.file.Files.readAllBytes(path);
      else marker = null;
    } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    String context = delegate.projectionFingerprint() + ":world:" + dimension + ":" + uuid
        + ":" + (fallback == null ? "absent" : Base64.getEncoder().encodeToString(Nbt.write(fallback)))
        + ":marker:" + (marker == null ? "absent" : Base64.getEncoder().encodeToString(marker));
    return new NbtUpgrader() {
      public Nbt.Compound upgrade(String type, Nbt.Compound input, int from, int to) throws IOException {
        return delegate.upgrade(type, input, from, to);
      }
      public String projectionFingerprint() { return context; }
      public SortedMap<String, byte[]> projectMetadata(SortedMap<String, byte[]> input, int from, int to) throws IOException {
        var out = delegate.projectMetadata(input, from, to);
        if (marker != null) {
          String name = null;boolean assets = false;
          for (String key : out.keySet()) if (key.startsWith("asset.")) {
            String path = new String(Base64.getUrlDecoder().decode(key.substring(6)),StandardCharsets.UTF_8);
            if (path.equals(MARKER)) name=key;
            else if (path.startsWith("datapacks/bukkit/")) assets=true;
          }
          if (name != null && !assets && out.get(name).length <= 16_384 && nativeMarker(JSON.readTree(out.get(name)))) out.put(name,marker.clone());
        }
        var level = out.containsKey("level.nbt") ? Nbt.read(out.get("level.nbt")) : null;
        if (to < org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE) {
          legacyPaperData(out, level == null ? fallback : override(level));
          return out;
        }
        String prefix = dimension + ".";
        // Vanilla 的全域 scheduled_events 在 Paper 由相應維度保存。
        String global = saved("", "data/minecraft/scheduled_events.dat");
        if (out.containsKey(global)) {
          String owner = saved(prefix, "data/minecraft/scheduled_events.dat");
          if (!out.containsKey(owner) || Arrays.equals(out.get(owner), out.get(global))) {
            out.put(owner, out.remove(global));
          }
        }
        if (from < org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE) {
          var identity = new Nbt.Compound().with("uuid", new int[] {(int)(uuid.getMostSignificantBits() >> 32),
              (int)uuid.getMostSignificantBits(), (int)(uuid.getLeastSignificantBits() >> 32), (int)uuid.getLeastSignificantBits()});
          putDefault(out, saved(prefix, "data/paper/metadata.dat"), identity, to);
          putDefault(out, saved(prefix, "data/paper/persistent_data_container.dat"), new Nbt.Compound(), to);
          var overrides = level == null ? fallback : override(level);
          if (overrides != null) putDefault(out, saved(prefix, "data/paper/level_overrides.dat"), overrides, to);
        }
        for (var name : new ArrayList<>(out.keySet())) {
          String path = org.worldgit.core.anvil.SavedData.relativePath(name);
          if (path != null && path.startsWith("data/paper/")) {
            byte[] canonical = org.worldgit.core.anvil.SavedData.normalize(name, out.get(name));
            if (canonical == null) out.remove(name);else out.put(name, canonical);
          }
        }
        return out;
      }
    };
  }

  private static String saved(String prefix, String path) {
    return prefix + "saved." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(path.getBytes(StandardCharsets.UTF_8)) + ".nbt";
  }
  private static void putDefault(Map<String, byte[]> out, String name, Nbt.Compound data, int version) {
    out.putIfAbsent(name, Nbt.write(new Nbt.Compound().with("DataVersion", version).with("data", Nbt.copy(data))));
  }
  private static Nbt.Compound override(Nbt.Compound level) {
    var data = new Nbt.Compound().with("initialized", (byte) 1);
    if (level.containsKey("GameType")) data.put("game_type", Nbt.copy(level.get("GameType")));
    else if (level.containsKey("game_type")) data.put("game_type", Nbt.copy(level.get("game_type")));
    if (level.containsKey("difficulty_settings")) data.put("difficulty_settings", Nbt.copy(level.get("difficulty_settings")));
    else if (level.get("Difficulty") instanceof Number difficulty) data.put("difficulty_settings",
        new Nbt.Compound().with("difficulty", List.of("peaceful", "easy", "normal", "hard").get(difficulty.intValue()))
            .with("hardcore", level.getOrDefault("hardcore", (byte) 0)).with("locked", level.getOrDefault("DifficultyLocked", (byte) 0)));
    if (level.get("spawn") instanceof Nbt.Compound spawn) {
      var copy = Nbt.copy(spawn);copy.remove("dimension");data.put("spawn", copy);
    } else if (level.containsKey("SpawnX")) data.put("spawn", new Nbt.Compound().with("pos", new int[] {
        level.integer("SpawnX",0),level.integer("SpawnY",0),level.integer("SpawnZ",0)})
        .with("yaw",level.getOrDefault("SpawnAngle",0f)).with("pitch",0f));
    return data;
  }
  private static void legacyPaperData(SortedMap<String, byte[]> out, Nbt.Compound level) throws IOException {
    Nbt.Compound expected = level == null ? null : (level.containsKey("initialized") ? level : override(level));
    for (var name : new ArrayList<>(out.keySet())) {
      String path = org.worldgit.core.anvil.SavedData.relativePath(name);
      if (path == null || !Set.of("data/paper/metadata.dat", "data/paper/persistent_data_container.dat",
          "data/paper/level_overrides.dat").contains(path)) continue;
      var root = Nbt.read(out.get(name));
      if (!Set.of("data", "DataVersion").containsAll(root.keySet())) continue;
      var data = root.compound("data");
      boolean equivalent = path.equals("data/paper/metadata.dat") && data.size() == 1
          && data.get("uuid") instanceof int[] id && id.length == 4
          || path.equals("data/paper/persistent_data_container.dat") && data.isEmpty()
          || path.equals("data/paper/level_overrides.dat") && expected != null
              && Arrays.equals(Nbt.write(data), Nbt.write(expected));
      if (equivalent) out.remove(name);
    }
  }

  private static void projectEmptyMarker(SortedMap<String, byte[]> metadata, int format,
      int minor, boolean modern) throws IOException {
    String marker = null;
    for (String key : metadata.keySet()) if (key.startsWith("asset.")) {
      String path = new String(Base64.getUrlDecoder().decode(key.substring(6)), StandardCharsets.UTF_8);
      if (path.equals(MARKER)) marker = key;
      else if (path.startsWith("datapacks/bukkit/")) return;
    }
    if (marker == null || metadata.get(marker).length > 16_384) return;
    var root = JSON.readTree(metadata.get(marker));
    var pack = root.path("pack");
    if (!nativeMarker(root)) return;
    boolean legacy = pack.size() == 2 && pack.path("pack_format").isIntegralNumber();
    boolean nativeRange = pack.size() == 3 && nativeFormat(pack.path("min_format"))
        && nativeFormat(pack.path("max_format")) && pack.get("min_format").equals(pack.get("max_format"));
    if (modern || nativeRange || CURRENT_DESCRIPTION.equals(pack.path("description").asText())) {
      String fields = modern ? "\"min_format\": [" + format + ", " + minor
          + "],\n        \"max_format\": [" + format + ", " + minor + "]"
          : "\"pack_format\": " + format;
      String text = "{\n    \"pack\": {\n        \"description\": \"" + DESCRIPTION
          + "\",\n        " + fields + "\n    }\n}\n";
      metadata.put(marker, text.getBytes(StandardCharsets.UTF_8));
      return;
    }
    String text = new String(metadata.get(marker), StandardCharsets.UTF_8);
    metadata.put(marker, FORMAT.matcher(text).replaceFirst(match -> match.group(1) + format)
        .getBytes(StandardCharsets.UTF_8));
  }

  private static boolean nativeMarker(com.fasterxml.jackson.databind.JsonNode root) {
    var pack=root.path("pack");
    if (!root.isObject() || root.size()!=1 || !pack.isObject()) return false;
    String description=pack.path("description").asText();
    if (DESCRIPTION.equals(description))
      return pack.size()==2 && pack.path("pack_format").isIntegralNumber()
          || pack.size()==3 && nativeFormat(pack.path("min_format")) && nativeFormat(pack.path("max_format"))
              && pack.get("min_format").equals(pack.get("max_format"));
    return CURRENT_DESCRIPTION.equals(description) && pack.size()==3 && nativeFormat(pack.path("min_format"))
        && pack.path("max_format").isIntegralNumber() && pack.path("max_format").intValue()==pack.path("min_format").get(0).intValue();
  }

  private static boolean nativeFormat(com.fasterxml.jackson.databind.JsonNode format) {
    return format.isArray() && format.size() == 2
        && format.get(0).isIntegralNumber() && format.get(0).canConvertToInt() && format.get(0).intValue() >= 0
        && format.get(1).isIntegralNumber() && format.get(1).canConvertToInt() && format.get(1).intValue() >= 0;
  }
}
