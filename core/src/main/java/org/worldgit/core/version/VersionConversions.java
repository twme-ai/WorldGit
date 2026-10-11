package org.worldgit.core.version;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.store.*;

/** 一致入口；同版本不下載／啟動 DFU。快取鍵包含輸入 tree、版本、規則與設定。 */
public final class VersionConversions {
  private VersionConversions() {}
  public static final String NORMALIZATION = "multiversion-canonical-v12-ticks-clamp";

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
                + "\0" + org.worldgit.core.anvil.SavedData.NORMALIZATION + "\0" + NORMALIZATION)
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
