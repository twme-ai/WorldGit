package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.capture.*;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.merge.MergeEngine;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

/** 多版本投影與決策 #169 的排程語意一致，包括不重新正規化的整世界 runtime 路徑。 */
class MultiversionTicksTest {
  @TempDir Path temp;
  private static final DimensionId NETHER = new DimensionId("minecraft:the_nether");

  private static Nbt.Compound tick(String id, int x, int delay) {
    return new Nbt.Compound().with("i", id).with("x", x).with("y", 64)
        .with("z", 0).with("p", 2).with("t", delay);
  }

  private static ChunkSnapshot snapshot(int version, String block, int delay) throws Exception {
    var ticks = new Nbt.Compound()
        .with("block_ticks", new Nbt.ListTag(10, List.of(
            tick("minecraft:stone", 0, delay), tick("minecraft:sand", 1, 12))))
        .with("fluid_ticks", new Nbt.ListTag(10, List.of(
            tick("minecraft:water", 0, delay), tick("minecraft:lava", 1, 9))));
    return new ChunkSnapshot(new ChunkPos(0, 0), version,
        new TreeMap<>(Map.of(4, new Section(
            Collections.nCopies(4096, new BlockState("minecraft:" + block)), Map.of()))),
        new TreeMap<>(Map.of(4, Collections.nCopies(64, "minecraft:plains"))),
        List.of(), Nbt.write(ticks), null);
  }

  private static void delays(byte[] bytes, int expired) throws java.io.IOException {
    var ticks = Nbt.read(bytes);
    for (String key : TickSemantics.KEYS) {
      var values = ticks.list(key).values();
      assertEquals(2, values.size());
      assertEquals(expired, ((Nbt.Compound) values.get(0)).integer("t", -1));
      assertEquals(key.equals("block_ticks") ? 12 : 9,
          ((Nbt.Compound) values.get(1)).integer("t", -1));
      assertEquals(2, ((Nbt.Compound) values.get(0)).integer("p", -1));
    }
  }

  private static final NbtUpgrader IDENTITY_DFU = (type, input, from, to) -> {
    if (type.equals("CHUNK")) delays(Nbt.write(input), 0);
    return Nbt.copy(input);
  };

  @Test
  void upgradeAndDowngradeClampBothTickKindsInHistoryAndRuntime() throws Exception {
    for (int[] versions : List.of(new int[] {3465, 5023}, new int[] {5023, 3465})) {
      for (boolean runtime : List.of(false, true)) {
        var source = snapshot(versions[0], "stone", -772);
        try (var converter = new SnapshotConverter(
            versions[0], versions[1], null, IDENTITY_DFU, runtime)) {
          var target = converter.convert(source);
          assertEquals(versions[1], target.dataVersion());
          delays(target.ticks(), 0);
          delays(source.ticks(), -772);
          var captured = new ChunkNormalizer(IgnoreRules.none(), EntitySemantics.OFFLINE)
              .normalize(target.pos(), SnapshotConverter.materialize(target), List.of());
          assertArrayEquals(target.ticks(), captured.ticks());
        }
      }
    }
  }

  private static String tree(JGitStore store, ChunkSnapshot snapshot) throws Exception {
    var editor = new TreeEditor(store, null);
    editor.replaceTree(snapshot.pos().treePath(), SnapshotCodec.chunkFiles(snapshot));
    String tree = editor.write();
    store.flush();
    return tree;
  }

  @Test
  void projectedTicksDoNotDirtyOrConflictButFollowChangedBlocks() throws Exception {
    try (var store = new JGitStore(temp.resolve("repo"), true)) {
      for (int[] versions : List.of(new int[] {3465, 5023}, new int[] {5023, 3465})) {
        var source = snapshot(versions[0], "stone", -772);
        String old = tree(store, source);
        String projected;
        try (var converter = new SnapshotConverter(versions[0], versions[1], null, IDENTITY_DFU)) {
          projected = converter.convert(store, old, NETHER).tree();
        }
        String base = tree(store, snapshot(versions[1], "stone", 20));
        String ours = tree(store, snapshot(versions[1], "stone", 3));
        var diff = new DiffEngine(store);
        assertTrue(diff.compare(NETHER, ours, projected, 2).empty());
        assertTrue(ApplyPlanner.plan(store, NETHER, ours, projected,
            Scope.all(), ApplyPlanner.Options.defaults()).empty());
        var merged = new MergeEngine(store, NETHER, base, ours, projected,
            List.of("ours"), List.of("theirs")).merge(1);
        assertTrue(merged.report().regions().isEmpty());
        var ticks = TreeEditor.find(store, merged.tree(), source.pos().treePath() + "/ticks.bin");
        assertArrayEquals(snapshot(versions[1], "stone", 3).ticks(),
            SnapshotCodec.nbt(4, store.readBlob(ticks.id()), true));
        String changed = tree(store, snapshot(versions[1], "gold_block", -99));
        assertFalse(diff.compare(NETHER, ours, changed, 2).empty());
        var plan = ApplyPlanner.plan(store, NETHER, ours, changed,
            Scope.all(), ApplyPlanner.Options.defaults());
        var op = plan.chunks().get(source.pos());
        assertTrue(op.setTicks());
        delays(op.ticks(), 0);
      }
    }
  }

  @Test
  void conversionCacheDoesNotReuseUnclampedV11Projection() throws Exception {
    String previous = System.getProperty("worldgit.versions");
    System.setProperty("worldgit.versions", temp.resolve("versions").toString());
    try (var store = new JGitStore(temp.resolve("repo"), true)) {
      String old = tree(store, snapshot(3465, "stone", -772));
      var rules = new ReverseRules(3465, 5023, null);
      String key = MojangRuntime.sha1((old + "\0" + NETHER + "\0" + 3465 + "\0" + 5023
          + "\0" + rules.fingerprint + "\0" + SavedData.NORMALIZATION
          + "\0multiversion-canonical-v11").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      Path cache = MojangRuntime.cache().resolve("conversions").resolve(key + ".nbt");
      Files.createDirectories(cache.getParent());
      Files.write(cache, Nbt.write(new Nbt.Compound().with("tree", old).with("report",
          new ConversionReport(3465, 5023, rules.fingerprint, List.of()).bytes())));
      var result = VersionConversions.convert(store, old, NETHER, 3465, 5023,
          null, false, IDENTITY_DFU);
      assertNotEquals(old, result.tree());
      var chunk = TreeEditor.find(store, result.tree(), new ChunkPos(0, 0).treePath());
      delays(SnapshotConverter.readChunk(store, chunk.id(), new ChunkPos(0, 0), 5023).ticks(), 0);
    } finally {
      if (previous == null) System.clearProperty("worldgit.versions");
      else System.setProperty("worldgit.versions", previous);
    }
  }

  @Test
  void conversionCacheSeparatesServerMetadataProjectionAndReusesMatchingBackend() throws Exception {
    String previous = System.getProperty("worldgit.versions");
    System.setProperty("worldgit.versions", temp.resolve("versions").toString());
    try (var store = new JGitStore(temp.resolve("repo"), true)) {
      var editor = new TreeEditor(store, null);
      editor.replaceTree("dimension-meta", Map.of("custom.nbt", Nbt.write(new Nbt.Compound().with("DataVersion", 3465))));
      String source = editor.write();
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      class Projection implements NbtUpgrader {
        private final String name;
        Projection(String name) { this.name = name; }
        public Nbt.Compound upgrade(String type, Nbt.Compound raw, int from, int to) { return Nbt.copy(raw); }
        public String projectionFingerprint() { return name; }
        public SortedMap<String, byte[]> projectMetadata(SortedMap<String, byte[]> input, int from, int to) throws java.io.IOException {
          calls.incrementAndGet();
          var output = new TreeMap<>(input);
          output.put("custom.nbt", Nbt.write(Nbt.read(input.get("custom.nbt")).with("backend", name)));
          return output;
        }
      }
      var first = VersionConversions.convert(store, source, NETHER, 3465, 3700, null, false, new Projection("paper-a"));
      var second = VersionConversions.convert(store, source, NETHER, 3465, 3700, null, false, new Projection("paper-b"));
      var cached = VersionConversions.convert(store, source, NETHER, 3465, 3700, null, false, new Projection("paper-a"));
      assertNotEquals(first.tree(), second.tree());
      assertEquals(first.tree(), cached.tree());
      assertEquals(2, calls.get());
      assertEquals("paper-b", Nbt.read(store.readBlob(TreeEditor.find(store, second.tree(), "dimension-meta/custom.nbt").id())).string("backend"));
    } finally {
      if (previous == null) System.clearProperty("worldgit.versions");
      else System.setProperty("worldgit.versions", previous);
    }
  }

  @Test
  void captureFingerprintsIncludeTickAndMultiversionNormalization() throws Exception {
    var live = new SnapshotSource() {
      public DimensionId dimension() { return DimensionId.OVERWORLD; }
      public int dataVersion() { return 3465; }
      public Scan scan(ScanIndex previous, boolean full) { throw new UnsupportedOperationException(); }
      public CompletionStage<Optional<ChunkSnapshot>> snapshot(ChunkPos pos, IgnoreRules rules) {
        throw new UnsupportedOperationException();
      }
    };
    assertEquals("normalize-v2:3465:" + VersionConversions.NORMALIZATION,
        live.normalizationFingerprint());
    Files.createDirectories(temp.resolve("region"));
    try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(temp.resolve("level.dat")))) {
      out.write(Nbt.write(new Nbt.Compound().with("Data",
          new Nbt.Compound().with("DataVersion", 3465))));
    }
    var layout = WorldLayout.discover(temp);
    try (var source = new OfflineSnapshotSource(layout, layout.dimensions().get(DimensionId.OVERWORLD))) {
      String fingerprint = source.normalizationFingerprint();
      assertTrue(fingerprint.startsWith("normalize-v2-ticks-clamp:3465:"));
      assertTrue(fingerprint.endsWith(":" + VersionConversions.NORMALIZATION));
      assertTrue(fingerprint.length() > ("normalize-v2-ticks-clamp:3465:"
          + VersionConversions.NORMALIZATION).length(), "保留 registry fingerprint");
    }
  }
}
