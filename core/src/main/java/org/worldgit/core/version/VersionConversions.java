package org.worldgit.core.version;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.store.*;

/** 一致入口；同版本不下載／啟動 DFU。快取鍵包含輸入 tree、版本、規則與設定。 */
public final class VersionConversions {
  private VersionConversions() {}
  // Resolve at runtime so incremental builds cannot retain an inlined previous version.
  public static final String NORMALIZATION = normalizationVersion();
  private static String normalizationVersion() { return "multiversion-canonical-v27-namespaced-dragon-gateways"; }

  /** Saved data may retain its older file version until it becomes dirty after a server upgrade. */
  public static Map<String, byte[]> capturedSavedData(Map<String, byte[]> input, int target,
      NbtUpgrader upgrader) throws IOException {
    var output = new TreeMap<>(input);
    for (var entry : input.entrySet()) {
      String path = org.worldgit.core.anvil.SavedData.relativePath(entry.getKey());
      String type = path == null
          ? entry.getKey().matches("map_\\d+\\.dat\\.nbt") ? "SAVED_DATA_MAP_DATA"
              : entry.getKey().equals("scoreboard.dat.nbt") ? "SAVED_DATA_SCOREBOARD"
              : entry.getKey().endsWith("world_border.dat.nbt") && target >= 4671 ? "SAVED_DATA_WORLD_BORDER"
              : entry.getKey().endsWith("game_rules.dat.nbt") && target >= MinecraftVersion.NAMESPACED_STORAGE ? "SAVED_DATA_GAME_RULES"
              : entry.getKey().endsWith("world_gen_settings.dat.nbt") && target >= MinecraftVersion.NAMESPACED_STORAGE ? "SAVED_DATA_WORLD_GEN_SETTINGS"
              : entry.getKey().equals("custom_boss_events.dat.nbt") && target >= MinecraftVersion.NAMESPACED_STORAGE ? "SAVED_DATA_CUSTOM_BOSS_EVENTS" : null
          : MetadataConversion.savedType(path);
      if (type == null) continue;
      var raw = org.worldgit.core.anvil.Nbt.read(entry.getValue());
      int from = raw.integer("DataVersion", target);
      if (from > target) throw new IOException("Saved-data DataVersion=" + from + " exceeds target=" + target);
      if (from == target) continue;
      var fixed = upgrader.upgrade(type, raw, from, target);
      fixed.put("DataVersion", target);
      byte[] value = org.worldgit.core.anvil.SavedData.normalize(entry.getKey(),
          org.worldgit.core.anvil.Nbt.write(fixed));
      if (value == null) output.remove(entry.getKey()); else output.put(entry.getKey(), value);
    }
    return output;
  }

  public static SnapshotConverter.Result convert(
      ObjectStore store,
      String tree,
      DimensionId dimension,
      int from,
      int to,
      Path config,
      boolean accepted)
      throws IOException {
    return convert(store, tree, dimension, from, to, config, accepted, null);
  }

  public static SnapshotConverter.Result convert(
      ObjectStore store,
      String tree,
      DimensionId dimension,
      int from,
      int to,
      Path config,
      boolean accepted,
      NbtUpgrader upgrader)
      throws IOException {
    if (tree == null || from == to)
      return new SnapshotConverter.Result(
          tree, new ConversionReport(from, to, "same-version", List.of()));
    var rules = new ReverseRules(from, to, config);
    String key =
        MojangRuntime.sha1(
            (tree + "\0" + dimension + "\0" + from + "\0" + to + "\0" + rules.fingerprint
                + "\0" + org.worldgit.core.anvil.SavedData.NORMALIZATION + "\0" + NORMALIZATION
                + "\0" + (upgrader==null ? "vanilla" : upgrader.projectionFingerprint()))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Path cache = MojangRuntime.cache().resolve("conversions").resolve(key + ".nbt");
    if (Files.isRegularFile(cache))
      try {
        var n = org.worldgit.core.anvil.Nbt.read(Files.readAllBytes(cache));
        String result = n.string("tree");
        store.readTree(result);
        var report = ConversionReport.read((byte[]) n.get("report"));
        report.requireAccepted(accepted);
        return new SnapshotConverter.Result(result, report);
      } catch (ConversionReport.LossRequired e) {
        throw e;
      } catch (IOException | RuntimeException stale) {
        /* objects may be pruned or belong to another store */
      }
    SnapshotConverter.Result result;
    try (var converter = new SnapshotConverter(from, to, config, upgrader)) {
      result = converter.convert(store, tree, dimension);
    }
    Files.createDirectories(cache.getParent());
    prune(cache.getParent());
    org.worldgit.core.anvil.RegionFile.atomicWrite(
        cache,
        org.worldgit.core.anvil.Nbt.write(
            new org.worldgit.core.anvil.Nbt.Compound()
                .with("tree", result.tree())
                .with("report", result.report().bytes())));
    result.report().requireAccepted(accepted);
    return result;
  }

  public static String comparisonTree(ObjectStore store, RefStore.Commit commit, int target)
      throws IOException {
    return commit == null
        ? null
        : convert(
                store,
                commit.tree(),
                commit.metadata().dimension(),
                commit.metadata().mcDataVersion(),
                target,
                null,
                false)
            .tree();
  }

  private static synchronized void prune(Path directory) throws IOException {
    try (var files = Files.list(directory)) {
      var ordered =
          files
              .filter(p -> p.toString().endsWith(".nbt"))
              .sorted(
                  Comparator.comparingLong(
                      p -> {
                        try {
                          return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                          return 0L;
                        }
                      }))
              .toList();
      long total = 0;
      for (Path p : ordered) total += Files.size(p);
      int remaining = ordered.size();
      for (Path p : ordered) {
        if (remaining < 2048 && total < 64L * 1024 * 1024) break;
        total -= Files.size(p);
        remaining--;
        Files.deleteIfExists(p);
      }
    }
  }
}
