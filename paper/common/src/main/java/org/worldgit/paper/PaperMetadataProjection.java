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
  private static final Set<String> EXPERIMENTS = Set.of("bundle", "update_1_20", "update_1_21", "trade_rebalance");
  private static final String MARKER = "datapacks/bukkit/pack.mcmeta";
  private static final String DESCRIPTION = "Data pack for resources provided by Bukkit plugins";
  private static final Pattern FORMAT = Pattern.compile("(\"pack_format\"\\s*:\\s*)[0-9]+");
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public static NbtUpgrader wrap(NbtUpgrader vanilla, int target, Nbt.Compound defaults,
      int packFormat, Collection<String> available) throws IOException {
    var nativeRules = Nbt.copy(defaults);
    var experiments = new TreeSet<String>(available);
    experiments.retainAll(EXPERIMENTS);
    String fingerprint = "paper-metadata-v1:" + target + ":" + packFormat + ":" + experiments
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
          if (level.containsKey("GameRules")) {
            var rules = level.compound("GameRules");
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
        projectEmptyMarker(output, packFormat);
        return output;
      }
    };
  }

  private static void projectEmptyMarker(SortedMap<String, byte[]> metadata, int format) throws IOException {
    String marker = null;
    for (String key : metadata.keySet()) if (key.startsWith("asset.")) {
      String path = new String(Base64.getUrlDecoder().decode(key.substring(6)), StandardCharsets.UTF_8);
      if (path.equals(MARKER)) marker = key;
      else if (path.startsWith("datapacks/bukkit/")) return;
    }
    if (marker == null || metadata.get(marker).length > 16_384) return;
    var root = JSON.readTree(metadata.get(marker));
    var pack = root.path("pack");
    if (!root.isObject() || root.size() != 1 || !pack.isObject() || pack.size() != 2
        || !DESCRIPTION.equals(pack.path("description").asText())
        || !pack.path("pack_format").isIntegralNumber()) return;
    String text = new String(metadata.get(marker), StandardCharsets.UTF_8);
    metadata.put(marker, FORMAT.matcher(text).replaceFirst(match -> match.group(1) + format)
        .getBytes(StandardCharsets.UTF_8));
  }
}
