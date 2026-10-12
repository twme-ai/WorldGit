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
    String fingerprint = "paper-metadata-v3:" + target + ":" + packFormat + "." + packMinor + ":" + experiments
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
        projectEmptyMarker(output, packFormat, packMinor, to >= 4671);
        return output;
      }
    };
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
    if (!root.isObject() || root.size() != 1 || !pack.isObject()
        || !DESCRIPTION.equals(pack.path("description").asText())) return;
    boolean legacy = pack.size() == 2 && pack.path("pack_format").isIntegralNumber();
    boolean nativeRange = pack.size() == 3 && nativeFormat(pack.path("min_format"))
        && nativeFormat(pack.path("max_format")) && pack.get("min_format").equals(pack.get("max_format"));
    if (!legacy && !nativeRange) return;
    if (modern || nativeRange) {
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

  private static boolean nativeFormat(com.fasterxml.jackson.databind.JsonNode format) {
    return format.isArray() && format.size() == 2
        && format.get(0).isIntegralNumber() && format.get(0).canConvertToInt() && format.get(0).intValue() >= 0
        && format.get(1).isIntegralNumber() && format.get(1).canConvertToInt() && format.get(1).intValue() >= 0;
  }
}
