package org.worldgit.core.version;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.SavedData;
import org.worldgit.core.model.DimensionId;

/** level.dat 與 shared／dimension saved-data 的格式、位置轉換。 */
final class MetadataConversion {
  private MetadataConversion() {}

  static SortedMap<String, byte[]> convert(
      Map<String, byte[]> input,
      DimensionId dimension,
      int from,
      int to,
      NbtUpgrader dfu,
      ReverseNbt reverse,
      ConversionReport.Collector loss)
      throws IOException {
    return convert(input, dimension, from, to, dfu, reverse, loss, false);
  }

  static SortedMap<String, byte[]> convert(
      Map<String, byte[]> input, DimensionId dimension, int from, int to,
      NbtUpgrader dfu, ReverseNbt reverse, ConversionReport.Collector loss,
      boolean runtime) throws IOException {
    if (!runtime) {
      var normalized = new TreeMap<String, byte[]>();
      for (var entry : input.entrySet()) {
        byte[] value = SavedData.normalize(entry.getKey(), entry.getValue());
        if (value != null) normalized.put(entry.getKey(), value);
      }
      input = normalized;
    }
    var out = new TreeMap<String, byte[]>();
    var level =
        input.containsKey("level.nbt") ? Nbt.read(input.get("level.nbt")) : new Nbt.Compound();
    String prefix = dimension.directoryName() + ".";
    // 26.x 的固定 saved-data 別名先折回 LEVEL dynamic，讓 DFU 使用正確的 schema。
    if (from >= MinecraftVersion.NAMESPACED_STORAGE) {
      if (to < MinecraftVersion.NAMESPACED_STORAGE
          && input.containsKey("custom_boss_events.dat.nbt"))
        level.put(
            "CustomBossEvents", Nbt.read(input.get("custom_boss_events.dat.nbt")).compound("data"));
      for (String key : List.of("game_rules", "world_gen_settings", "world_border")) {
        byte[] b = input.getOrDefault(prefix + key + ".dat.nbt", input.get(key + ".dat.nbt"));
        if (b != null) level.put(key, Nbt.read(b).compound("data"));
      }
      if (to < MinecraftVersion.NAMESPACED_STORAGE && dimension.equals(DimensionId.OVERWORLD))
        for (var e : input.entrySet()) {
          int saved = e.getKey().indexOf("saved.");
          if (saved < 0 || !e.getKey().endsWith(".nbt")) continue;
          String path =
              new String(
                  Base64.getUrlDecoder()
                      .decode(e.getKey().substring(saved + 6, e.getKey().length() - 4)),
                  StandardCharsets.UTF_8);
          var data = Nbt.read(e.getValue()).compound("data");
          switch (path) {
            case "data/minecraft/ender_dragon_fight.dat" -> {
              var dragon = new Nbt.Compound();
              for (var field :
                  Map.of(
                          "dragon_killed",
                          "DragonKilled",
                          "previously_killed",
                          "PreviouslyKilled",
                          "needs_state_scanning",
                          "NeedsStateScanning",
                          "dragon_uuid",
                          "Dragon",
                          "gateways",
                          "Gateways")
                      .entrySet())
                if (data.containsKey(field.getKey()))
                  dragon.put(field.getValue(), data.get(field.getKey()));
              if (data.get("exit_portal") instanceof int[] p && p.length == 3)
                dragon.put(
                    "ExitPortalLocation",
                    new Nbt.Compound().with("X", p[0]).with("Y", p[1]).with("Z", p[2]));
              level.put("DragonFight", dragon);
            }
            case "data/minecraft/weather.dat" -> {
              for (var field :
                  Map.of(
                          "clear_weather_time",
                          "clearWeatherTime",
                          "rain_time",
                          "rainTime",
                          "raining",
                          "raining",
                          "thunder_time",
                          "thunderTime",
                          "thundering",
                          "thundering")
                      .entrySet())
                if (data.containsKey(field.getKey()))
                  level.put(field.getValue(), data.get(field.getKey()));
            }
            case "data/minecraft/world_clocks.dat" -> {
              var clock = data.compound("minecraft:overworld");
              if (clock.containsKey("total_ticks")) level.put("DayTime", clock.get("total_ticks"));
            }
            case "data/minecraft/scheduled_events.dat" ->
                level.put("ScheduledEvents", data.list("events"));
            case "data/minecraft/wandering_trader.dat" -> {
              if (data.containsKey("spawn_delay"))
                level.put("WanderingTraderSpawnDelay", data.get("spawn_delay"));
              if (data.containsKey("spawn_chance"))
                level.put("WanderingTraderSpawnChance", data.get("spawn_chance"));
              if (data.containsKey("trader_uuid"))
                level.put("WanderingTraderId", data.get("trader_uuid"));
            }
            default -> {}
          }
        }
    }
    if (from < to && !level.isEmpty()) level = dfu.upgrade("LEVEL", level, from, to);
    if (from > to) {
      if (level.containsKey("world_gen_settings")) {
        var gen = level.compound("world_gen_settings");
        if (to < MinecraftVersion.NAMESPACED_STORAGE && gen.containsKey("generate_structures"))
          gen.put("generate_features", gen.remove("generate_structures"));
        level.put(
            to < MinecraftVersion.NAMESPACED_STORAGE ? "WorldGenSettings" : "world_gen_settings",
            gen);
        if (to < MinecraftVersion.NAMESPACED_STORAGE) level.remove("world_gen_settings");
      }
      for (String key:List.of("WorldGenSettings","world_gen_settings"))
        if(level.get(key) instanceof Nbt.Compound settings)level.put(key,reverse.worldGeneration(settings));
      if (to < MinecraftVersion.NAMESPACED_STORAGE && level.containsKey("difficulty_settings")) {
        var d = level.compound("difficulty_settings");
        int i = List.of("peaceful", "easy", "normal", "hard").indexOf(d.string("difficulty"));
        level.put("Difficulty", (byte) Math.max(0, i));
        level.put("DifficultyLocked", d.getOrDefault("locked", (byte) 0));
        level.put("hardcore", d.getOrDefault("hardcore", (byte) 0));
        level.remove("difficulty_settings");
      }
      if (to <= 4440 && level.containsKey("game_rules")) {
        var old = new Nbt.Compound();
        var names = VersionYaml.resource("gamerules.yml");
        for (var e : level.compound("game_rules").entrySet()) {
          Object oldName = names.get(e.getKey());
          if (oldName instanceof String key && reverse.known("gamerules", key)) {
            Object value = e.getValue();
            if (Set.of(
                        "minecraft:raids",
                        "minecraft:elytra_movement_check",
                        "minecraft:player_movement_check")
                    .contains(e.getKey())
                && value instanceof Number n) value = (byte) (n.intValue() == 0 ? 1 : 0);
            if (e.getKey().equals("minecraft:fire_spread_radius_around_player")
                && value instanceof Number n) {
              if (n.intValue() != -1 && n.intValue() != 0)
                loss.add("gamerule", e.getKey() + "=" + n, "doFireTick=true", "metadata");
              value = (byte) (n.intValue() == 0 ? 0 : 1);
            }
            old.put(
                key, value instanceof Byte b ? b == 0 ? "false" : "true" : String.valueOf(value));
          } else loss.add("gamerule", e.getKey(), "skip", "metadata");
        }
        level.put("GameRules", old);
        level.remove("game_rules");
      }
      if (to <= 4440 && level.containsKey("spawn")) {
        var s = level.compound("spawn");
        if (s.get("pos") instanceof int[] p && p.length == 3) {
          level.put("SpawnX", p[0]);
          level.put("SpawnY", p[1]);
          level.put("SpawnZ", p[2]);
          level.put("SpawnAngle", s.getOrDefault("yaw", 0f));
        }
        level.remove("spawn");
      }
      if (to < MinecraftVersion.NAMESPACED_STORAGE && level.containsKey("world_border")) {
        var b = level.compound("world_border");
        var names =
            Map.of(
                "center_x",
                "BorderCenterX",
                "center_z",
                "BorderCenterZ",
                "size",
                "BorderSize",
                "safe_zone",
                "BorderSafeZone",
                "warning_blocks",
                "BorderWarningBlocks",
                "warning_time",
                "BorderWarningTime",
                "damage_per_block",
                "BorderDamagePerBlock");
        for (var e : names.entrySet())
          if (b.containsKey(e.getKey())) level.put(e.getValue(), b.get(e.getKey()));
        level.remove("world_border");
      }
    }
    if (to >= MinecraftVersion.NAMESPACED_STORAGE) {
      for (String key : List.of("game_rules", "world_gen_settings", "world_border"))
        if (level.get(key) instanceof Nbt.Compound data) {
          byte[] b = Nbt.write(new Nbt.Compound().with("DataVersion", to).with("data", data));
          out.put(prefix + key + ".dat.nbt", b);
          if (dimension.equals(DimensionId.OVERWORLD)) out.put(key + ".dat.nbt", b);
          level.remove(key);
        }
      var split =
          Map.of(
              "dragon_fight",
              "ender_dragon_fight",
              "scheduled_events",
              "scheduled_events",
              "wandering_trader_migration_data",
              "wandering_trader",
              "weather_data",
              "weather",
              "world_clocks",
              "world_clocks");
      for (var e : split.entrySet())
        if (level.get(e.getKey()) instanceof Nbt.Compound data) {
          String p = "data/minecraft/" + e.getValue() + ".dat",
              name =
                  (e.getKey().equals("dragon_fight") ? "minecraft.the_end." : "")
                      + "saved."
                      + Base64.getUrlEncoder()
                          .withoutPadding()
                          .encodeToString(p.getBytes(StandardCharsets.UTF_8))
                      + ".nbt";
          out.put(name, Nbt.write(new Nbt.Compound().with("DataVersion", to).with("data", data)));
          level.remove(e.getKey());
        }
      if (level.get("CustomBossEvents") instanceof Nbt.Compound events) {
        out.put(
            "custom_boss_events.dat.nbt",
            Nbt.write(new Nbt.Compound().with("DataVersion", to).with("data", events)));
        level.remove("CustomBossEvents");
      }
    }
    // 僅追蹤原本追蹤的世界欄位；DFU 為缺值填入的時鐘／天氣不加入快照。
    level
        .keySet()
        .removeIf(
            k ->
                Set.of(
                        "weather_data",
                        "world_clocks",
                        "scheduled_events",
                        "wandering_trader_migration_data",
                        "version_history")
                    .contains(k));
    if (!runtime) stripRuntimeLevel(level);
    if (input.containsKey("level.nbt")) {
      level.put("DataVersion", to);
      out.put("level.nbt", Nbt.write(level));
    }
    for (var e : input.entrySet()) {
      String name = e.getKey();
      if (name.equals("level.nbt")
          || to < MinecraftVersion.NAMESPACED_STORAGE && name.equals("custom_boss_events.dat.nbt")
          || name.endsWith("game_rules.dat.nbt")
          || name.endsWith("world_gen_settings.dat.nbt")
          || name.endsWith("world_border.dat.nbt")) continue;
      if (!name.endsWith(".nbt")) {
        out.put(name, e.getValue());
        continue;
      }
      Nbt.Compound n = Nbt.read(e.getValue());
      String type = null;
      int saved = name.indexOf("saved.");
      String savedPath =
          saved < 0
              ? null
              : new String(
                  Base64.getUrlDecoder().decode(name.substring(saved + 6, name.length() - 4)),
                  StandardCharsets.UTF_8);
      if (savedPath != null
          && to < MinecraftVersion.NAMESPACED_STORAGE
          && from >= MinecraftVersion.NAMESPACED_STORAGE
          && dimension.equals(DimensionId.OVERWORLD)
          && Set.of(
                  "data/minecraft/ender_dragon_fight.dat",
                  "data/minecraft/weather.dat",
                  "data/minecraft/world_clocks.dat",
                  "data/minecraft/scheduled_events.dat",
                  "data/minecraft/wandering_trader.dat")
              .contains(savedPath)) continue;
      if (savedPath != null) type = savedType(savedPath);
      if (name.matches("map_\\d+\\.dat\\.nbt")) type = "SAVED_DATA_MAP_DATA";
      if (name.equals("scoreboard.dat.nbt")) type = "SAVED_DATA_SCOREBOARD";
      if (from > to && "SAVED_DATA_RANDOM_SEQUENCES".equals(type) && to <= 3465) {
        // 1.20.1 沒有 /random；僅使用者設定屬於損失，RNG 游標仍為暫態。
        if (SavedData.normalize(name, Nbt.write(n)) != null)
          loss.add("saved-data", savedPath, "skip", "metadata/" + name);
        continue;
      }
      if (from < to && type != null) n = dfu.upgrade(type, n, from, to);
      if (from > to && type != null) {
        var converted = reverse.convert(n, "saved-data", "metadata/" + name);
        if (converted != null) n = converted;
        SavedDataReverse.convert(n, type, from, to, loss);
      }
      if (n.containsKey("DataVersion") && (type != null || saved < 0)) n.put("DataVersion", to);
      if (saved >= 0
          && from < MinecraftVersion.NAMESPACED_STORAGE
          && to >= MinecraftVersion.NAMESPACED_STORAGE) {
        String next = modern(savedPath);
        if (next != null) {
          String owner = name.substring(0, saved);
          if (owner.isEmpty() && Set.of("SAVED_DATA_RAIDS", "SAVED_DATA_TICKETS",
              "SAVED_DATA_RANDOM_SEQUENCES").contains(type)) owner = prefix;
          name =
              owner
                  + "saved."
                  + Base64.getUrlEncoder()
                      .withoutPadding()
                      .encodeToString(next.getBytes(StandardCharsets.UTF_8))
                  + ".nbt";
        }
      }
      if (saved >= 0 && to < MinecraftVersion.NAMESPACED_STORAGE) {
        String path =
            new String(
                Base64.getUrlDecoder().decode(name.substring(saved + 6, name.length() - 4)),
                StandardCharsets.UTF_8);
        String oldPath = legacy(path);
        if (oldPath == null) {
          if (SavedData.normalize(name, Nbt.write(n)) == null) continue;
          loss.add("saved-data", path, "skip", "metadata/" + name);
          continue;
        }
        name =
            (dimension.equals(DimensionId.OVERWORLD) && name.startsWith(prefix)
                ? "" : name.substring(0, saved))
                + "saved."
                + Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(oldPath.getBytes(StandardCharsets.UTF_8))
                + ".nbt";
      }
      out.put(name, Nbt.write(n));
    }
    if (!runtime) {
      for (String key : new ArrayList<>(out.keySet())) {
        byte[] value = SavedData.normalize(key, out.get(key));
        if (value == null) out.remove(key); else out.put(key, value);
      }
    }
    return out;
  }

  static void stripRuntimeLevel(Nbt.Compound level) {
    for (String key : List.of("Time", "DayTime", "clearWeatherTime", "rainTime", "raining",
        "thunderTime", "thundering", "WanderingTraderSpawnDelay", "WanderingTraderSpawnChance",
        "WanderingTraderId", "weather_data", "world_clocks", "wandering_trader_migration_data",
        "version_history")) level.remove(key);
  }

  private static String legacy(String path) {
    if (path.matches("data/minecraft/maps/\\d+\\.dat"))
      return "data/map_" + path.substring("data/minecraft/maps/".length());
    if (path.equals("data/minecraft/maps/last_id.dat")) return "data/idcounts.dat";
    if (path.matches("data/[^/]+/command_storage.dat"))
      return "data/command_storage_" + path.split("/")[1] + ".dat";
    if (!path.startsWith("data/minecraft/")) return null;
    String base = path.substring(path.lastIndexOf('/') + 1);
    if (Set.of(
            "raids.dat",
            "random_sequences.dat",
            "scoreboard.dat",
            "ender_dragon_fight.dat",
            "chunk_tickets.dat")
        .contains(base)) return "data/" + (base.equals("chunk_tickets.dat") ? "chunks.dat" : base);
    return null;
  }

  private static String modern(String path) {
    if (!path.matches("data/[^/]+\\.dat")) return null;
    String file = path.substring(path.lastIndexOf('/') + 1);
    if (file.matches("map_\\d+\\.dat")) return "data/minecraft/maps/" + file.substring(4);
    if (file.equals("idcounts.dat")) return "data/minecraft/maps/last_id.dat";
    if (file.startsWith("command_storage_") && file.endsWith(".dat"))
      return "data/" + file.substring(16, file.length() - 4) + "/command_storage.dat";
    if (savedType(path) != null)
      return "data/minecraft/" + (file.equals("chunks.dat") ? "chunk_tickets.dat" : file);
    return null;
  }

  private static String savedType(String path) {
    if (!path.startsWith("data/minecraft/") && !path.matches("data/[^/]+\\.dat")
        && !path.matches("data/[^/]+/command_storage\\.dat")) return null;
    String file = path.substring(path.lastIndexOf('/') + 1);
    if (file.matches("(?:map_)?\\d+\\.dat")) return "SAVED_DATA_MAP_DATA";
    if (file.startsWith("command_storage")) return "SAVED_DATA_COMMAND_STORAGE";
    return switch (file) {
      case "idcounts.dat", "last_id.dat" -> "SAVED_DATA_MAP_INDEX";
      case "chunks.dat", "chunk_tickets.dat" -> "SAVED_DATA_TICKETS";
      case "raids.dat" -> "SAVED_DATA_RAIDS";
      case "random_sequences.dat" -> "SAVED_DATA_RANDOM_SEQUENCES";
      case "scoreboard.dat" -> "SAVED_DATA_SCOREBOARD";
      case "ender_dragon_fight.dat" -> "SAVED_DATA_ENDER_DRAGON_FIGHT";
      case "world_clocks.dat" -> "SAVED_DATA_WORLD_CLOCKS";
      case "weather.dat" -> "SAVED_DATA_WEATHER";
      case "scheduled_events.dat" -> "SAVED_DATA_SCHEDULED_EVENTS";
      case "custom_boss_events.dat" -> "SAVED_DATA_CUSTOM_BOSS_EVENTS";
      case "stopwatches.dat" -> "SAVED_DATA_STOPWATCHES";
      default -> null;
    };
  }
}
