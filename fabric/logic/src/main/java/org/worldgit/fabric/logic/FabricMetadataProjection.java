package org.worldgit.fabric.logic;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.version.NbtUpgrader;

/** 目標 vanilla 的 gamerule defaults／未啟用實驗包；不改自訂資料包與既有設定。 */
public final class FabricMetadataProjection {
  private FabricMetadataProjection() {}
  private static final Set<String> EXPERIMENTS = Set.of("bundle", "update_1_20", "update_1_21",
      "trade_rebalance", "redstone_experiments", "minecart_improvements");
  private static final List<String> DEFAULT_PACKS = List.of("vanilla", "fabric",
      "fabric-convention-tags-v1", "fabric-gametest-api-v1");
  private static boolean defaultsOnly(List<?> enabled, List<?> disabled) {
    return !enabled.isEmpty() && enabled.getFirst().equals("vanilla")
        && enabled.equals(DEFAULT_PACKS.stream().filter(enabled::contains).toList())
        && (enabled.size()==1 || enabled.contains("fabric"))
        && disabled.stream().noneMatch(DEFAULT_PACKS::contains);
  }
  public static NbtUpgrader wrap(NbtUpgrader backend, int target, Nbt.Compound defaults,
      Collection<String> available) throws IOException {
    return wrap(backend,target,defaults,available,List.of("vanilla"),List.of());
  }
  public static NbtUpgrader wrap(NbtUpgrader backend, int target, Nbt.Compound defaults,
      Collection<String> available, Collection<String> enabled, Collection<String> disabled) throws IOException {
    var nativeRules = Nbt.copy(defaults);
    var nativeEnabled=List.copyOf(enabled);var nativeDisabled=List.copyOf(disabled);
    var experiments = new TreeSet<String>(available); experiments.retainAll(EXPERIMENTS);
    String fingerprint = "fabric-metadata-v2:" + target + ":" + experiments + ":" + nativeEnabled + ":" + nativeDisabled + ":"
        + Base64.getEncoder().encodeToString(Nbt.write(nativeRules));
    return new NbtUpgrader() {
      public Nbt.Compound upgrade(String type, Nbt.Compound input, int from, int to) throws IOException {
        return backend.upgrade(type, input, from, to);
      }
      public String projectionFingerprint() { return fingerprint; }
      public SortedMap<String, byte[]> projectMetadata(SortedMap<String, byte[]> input, int from, int to) throws IOException {
        if (to != target) throw new IOException("Metadata target does not match server DataVersion");
        var output = new TreeMap<>(input);
        if (input.containsKey("level.nbt")) {
          var level = Nbt.read(input.get("level.nbt"));
          String ruleKey = to < 4671 ? "GameRules" : "game_rules";
          if (level.containsKey(ruleKey)) nativeRules.forEach((key, value) -> level.compound(ruleKey).putIfAbsent(key, Nbt.copy(value)));
          if (level.containsKey("DataPacks")) {
            var packs = level.compound("DataPacks");
            // Fabric API 在版本／client fixture 模式間自動拆分預設 pack。
            // 只投影原版預設順序；自訂、重排或明確停用保持原值並由既有預檢判斷。
            if(defaultsOnly(packs.list("Enabled").values(),packs.list("Disabled").values())
                && defaultsOnly(nativeEnabled,nativeDisabled))
              packs.put("Enabled",new Nbt.ListTag(8,nativeEnabled.stream().map(value->(Object)value).toList()));
            if (packs.list("Disabled").values().stream().allMatch(EXPERIMENTS::contains))
              packs.put("Disabled", new Nbt.ListTag(8, experiments.stream()
                  .filter(name -> !packs.list("Enabled").values().contains(name)).map(name -> (Object) name).toList()));
          }
          output.put("level.nbt", Nbt.write(level));
        }
        if (to >= 4790) for (var entry : input.entrySet())
          if (entry.getKey().equals("game_rules.dat.nbt") || entry.getKey().endsWith(".game_rules.dat.nbt")) {
            var saved = Nbt.read(entry.getValue()); var rules = saved.compound("data");
            nativeRules.forEach((key, value) -> rules.putIfAbsent(key, Nbt.copy(value)));
            saved.put("data", rules); output.put(entry.getKey(), Nbt.write(saved));
          }
        return output;
      }
    };
  }
}
