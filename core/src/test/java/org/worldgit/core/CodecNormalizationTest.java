package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;

class CodecNormalizationTest {
  @Test
  void nbtCanonicalAndMalformed() throws Exception {
    var a = new Nbt.Compound().with("b", 2).with("a", new long[] {1, 2});
    var b = new Nbt.Compound().with("a", new long[] {1, 2}).with("b", 2);
    assertArrayEquals(Nbt.write(a), Nbt.write(b));
    assertArrayEquals(Nbt.write(a), Nbt.write(Nbt.read(Nbt.write(a))));
    assertThrows(
        IOException.class, () -> Nbt.read(new byte[] {10, 0, 0, 7, 0, 1, 97, -1, -1, -1, -1, 0}));
    assertThrows(IOException.class, () -> Nbt.read(new byte[] {10, 0, 0, 0, 0}));
  }

  @Test
  void sectionRoundTripAndDeterminism() throws Exception {
    var blocks = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
    for (int i = 0; i < 4096; i += 7)
      blocks.set(
          i,
          new BlockState(
              "minecraft:oak_stairs", new TreeMap<>(Map.of("half", "bottom", "facing", "east"))));
    var section =
        new Section(
            blocks,
            Map.of(
                7,
                Nbt.write(
                    new Nbt.Compound()
                        .with("id", "minecraft:chest")
                        .with("Items", new Nbt.ListTag(10, List.of())))));
    byte[] blob = SnapshotCodec.section(section);
    var decoded = SnapshotCodec.section(blob);
    assertEquals(section.blocks(), decoded.blocks());
    assertArrayEquals(blob, SnapshotCodec.section(decoded));
    assertArrayEquals(section.blockEntities().get(7), decoded.blockEntities().get(7));
    assertThrows(IOException.class, () -> SnapshotCodec.section(new byte[] {1, 2, 3}));
    assertThrows(
        IOException.class,
        () -> SnapshotCodec.section(SnapshotCodec.nbt(4, Nbt.write(new Nbt.Compound()))));
    byte[] bytes = section.blockEntities().get(7);
    bytes[0] = 0;
    assertNotEquals(0, section.blockEntities().get(7)[0]);
  }

  @Test
  void entitiesStripSortStickyAndExact() throws Exception {
    UUID id = UUID.randomUUID();
    var a = TestWorlds.entity(id, 15.8, false);
    a.with("Health", 20f)
        .with("Motion", new Nbt.ListTag(6, List.of(0.0, 0.0, 0.0)))
        .with("Paper.Test", 1);
    var attr = new Nbt.Compound().with("id", "minecraft:scale").with("base", 1.0);
    a.with(
        "attributes",
        new Nbt.ListTag(
            10,
            List.of(
                attr,
                new Nbt.Compound().with("id", "minecraft:movement_speed").with("base", 0.7))));
    var normalized = EntityNormalizer.normalize(a, IgnoreRules.none());
    assertFalse(normalized.data().containsKey("Health"));
    assertFalse(normalized.data().containsKey("Paper.Test"));
    assertEquals(1, normalized.data().list("attributes").values().size());
    var b = Nbt.copy(a);
    b.put("Pos", new Nbt.ListTag(6, List.of(17.0, 70.0, 0.0)));
    assertTrue(
        EntityNormalizer.stickyEqual(
            normalized, EntityNormalizer.normalize(b, IgnoreRules.none()), 2));
    b.put("Pos", new Nbt.ListTag(6, List.of(18.0, 70.0, 0.0)));
    assertFalse(
        EntityNormalizer.stickyEqual(
            normalized, EntityNormalizer.normalize(b, IgnoreRules.none()), 2));
    var exact = TestWorlds.entity(id, 15.8, true);
    assertFalse(
        EntityNormalizer.stickyEqual(
            EntityNormalizer.normalize(exact, IgnoreRules.none()),
            EntityNormalizer.normalize(TestWorlds.entity(id, 16, true), IgnoreRules.none()),
            2));
    var rules = IgnoreRules.parse("!field minecraft:zombie Health");
    assertTrue(EntityNormalizer.normalize(a, rules).data().containsKey("Health"));
  }

  @Test
  void realFixturesNormalizeIdenticallyAndIgnoreLighting() throws Exception {
    for (String version : List.of("1.21.11", "26.2")) {
      var layout = WorldLayout.discover(TestWorlds.fixture(version));
      assertEquals(3, layout.dimensions().size());
      assertEquals(version.equals("26.2") ? 4903 : 4671, layout.dataVersion());
      for (var dim : layout.dimensions().values())
        for (Path file : RegionFile.list(dim.region()))
          try (var region = new RegionFile(file)) {
            for (int i = 0; i < 1024; i++)
              if (region.has(i)) {
                var raw = region.read(i);
                var normalizer = new ChunkNormalizer(IgnoreRules.none(), EntitySemantics.OFFLINE);
                var before =
                    SnapshotCodec.chunkFiles(normalizer.normalize(region.pos(i), raw, List.of()));
                raw.with("LastUpdate", 1234567L)
                    .with("Heightmaps", new Nbt.Compound().with("fake", new long[] {0}));
                for (Object o : raw.list("sections").values())
                  ((Nbt.Compound) o)
                      .with("BlockLight", new byte[2048])
                      .with("starlight.light_version", 99);
                var after =
                    SnapshotCodec.chunkFiles(normalizer.normalize(region.pos(i), raw, List.of()));
                assertEquals(before.keySet(), after.keySet());
                for (String name : before.keySet())
                  assertArrayEquals(before.get(name), after.get(name));
              }
          }
    }
  }

  @Test
  void unorderedStructureReferencesAndTicksHaveStableBytes() throws Exception {
    var raw = new Nbt.Compound().with("Status", "minecraft:full").with("DataVersion", 4903);
    raw.with(
        "structures",
        new Nbt.Compound()
            .with(
                "References",
                new Nbt.Compound().with("minecraft:nether_fossil", new long[] {3, 1, 2}))
            .with("starts", new Nbt.Compound()));
    var first =
        new Nbt.Compound()
            .with("x", 0)
            .with("y", -3)
            .with("z", 0)
            .with("i", "minecraft:stone")
            .with("p", 0)
            .with("t", 3);
    var second =
        new Nbt.Compound()
            .with("x", 0)
            .with("y", 2)
            .with("z", 0)
            .with("i", "minecraft:stone")
            .with("p", 0)
            .with("t", 1);
    raw.with("block_ticks", new Nbt.ListTag(10, List.of(second, first)));
    var normalizer = new ChunkNormalizer(IgnoreRules.none(), EntitySemantics.OFFLINE);
    var a = normalizer.normalize(new ChunkPos(0, 0), raw, List.of());
    raw.compound("structures")
        .compound("References")
        .put("minecraft:nether_fossil", new long[] {2, 3, 1});
    raw.put("block_ticks", new Nbt.ListTag(10, List.of(first, second)));
    var b = normalizer.normalize(new ChunkPos(0, 0), raw, List.of());
    assertArrayEquals(a.structures(), b.structures());
    assertArrayEquals(a.ticks(), b.ticks());
    assertEquals(
        -3,
        ((Nbt.Compound) Nbt.read(a.ticks()).list("block_ticks").values().getFirst())
            .integer("y", 0));
  }

  @Test
  void regionLz4ExternalAndCorruption() throws Exception {
    var raw = new Nbt.Compound().with("test", "value");
    Path dir =
        Files.createTempDirectory(
            Path.of(System.getProperty("worldgit.projectRoot"), ".work"), "region-test-");
    Path file = dir.resolve("r.-1.0.mca");
    RegionFile.update(file, Map.of(31, raw), 17);
    try (var r = new RegionFile(file)) {
      assertEquals(new ChunkPos(-1, 0), r.pos(31));
      assertArrayEquals(Nbt.write(raw), Nbt.write(r.read(31)));
    }
    // 載入與 writer 均支援外部 .mcc；隨機 bytes 阻止壓縮成小 chunk。
    byte[] huge = new byte[1_100_000];
    new Random(7).nextBytes(huge);
    RegionFile.update(file, Map.of(31, new Nbt.Compound().with("data", huge)), 18);
    assertTrue(Files.exists(dir.resolve("c.-1.0.mcc")));
    try (var r = new RegionFile(file)) {
      assertArrayEquals(huge, (byte[]) r.read(31).get("data"));
    }
    var bytes = new ByteArrayOutputStream();
    try (var out = new net.jpountz.lz4.LZ4BlockOutputStream(bytes)) {
      out.write(Nbt.write(raw));
    }
    byte[] lz = bytes.toByteArray();
    var region = java.nio.ByteBuffer.allocate(12288);
    region.putInt(0, (2 << 8) | 1);
    region.putInt(8192, lz.length + 1);
    region.put(8196, (byte) 4);
    region.position(8197);
    region.put(lz);
    Files.write(file, region.array());
    try (var r = new RegionFile(file)) {
      assertArrayEquals(Nbt.write(raw), Nbt.write(r.read(0)));
    }
    Files.write(file, new byte[5]);
    assertThrows(IOException.class, () -> new RegionFile(file));
  }

  @Test
  void regionOfRunningServerEmptyAndUnpaddedTail() throws Exception {
    Path dir =
        Files.createTempDirectory(
            Path.of(System.getProperty("worldgit.projectRoot"), ".work"), "region-test-");
    // Minecraft 第一次寫入前會留下 0 byte 的 .mca：視為沒有任何 chunk，不是損毀。
    Path empty = dir.resolve("r.0.0.mca");
    Files.write(empty, new byte[0]);
    try (var r = new RegionFile(empty)) {
      for (int i = 0; i < 1024; i++) assertFalse(r.has(i));
    }
    // 執行中的伺服器尚未關閉檔案時，最後一個 chunk 不一定補滿 4096 byte：仍可讀。
    Path live = dir.resolve("r.0.1.mca");
    var raw = new Nbt.Compound().with("test", "tail");
    RegionFile.update(live, Map.of(5, raw), 9);
    byte[] all = Files.readAllBytes(live);
    int length = java.nio.ByteBuffer.wrap(all).getInt(8192) + 4;
    Files.write(live, java.util.Arrays.copyOf(all, 8192 + length));
    try (var r = new RegionFile(live)) {
      assertArrayEquals(Nbt.write(raw), Nbt.write(r.read(5)));
    }
    // 真的被截斷（payload 不完整）仍要報錯。
    Files.write(live, java.util.Arrays.copyOf(all, 8192 + length - 3));
    try (var r = new RegionFile(live)) {
      assertThrows(IOException.class, () -> r.read(5));
    }
    // 資料起點在檔案之外是損毀。
    Files.write(live, java.util.Arrays.copyOf(all, 8192));
    assertThrows(IOException.class, () -> new RegionFile(live));
  }
}
