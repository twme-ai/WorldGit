package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.worldgit.core.merge.MergeReport.*;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

class MergeEngineTest {
  @TempDir Path temp;
  JGitStore store;
  static final String PATH = "r.0.0/c.0.0/s.4.bin";

  @BeforeEach
  void open() throws Exception {
    store = new JGitStore(temp.resolve("repo"), true);
  }

  @AfterEach
  void close() throws Exception {
    store.close();
  }

  String tree(Map<String, byte[]> leaves) throws Exception {
    var t = new TreeEditor(store, null);
    for (var e : leaves.entrySet()) t.putBlob(e.getKey(), e.getValue());
    return t.write();
  }

  Section section(Map<Integer, BlockState> blocks, Map<Integer, byte[]> bes) {
    var list = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
    blocks.forEach(list::set);
    return new Section(list, bes);
  }

  String blocks(Map<Integer, BlockState> values) throws Exception {
    return tree(Map.of(PATH, SnapshotCodec.section(section(values, Map.of()))));
  }

  Section read(String tree) throws Exception {
    var e = TreeEditor.find(store, tree, PATH);
    return e == null ? Section.air() : SnapshotCodec.section(store.readBlob(e.id()));
  }

  MergeEngine.Result merge(String b, String o, String t, int k) throws Exception {
    return new MergeEngine(store, DimensionId.OVERWORLD, b, o, t, List.of("Alice"), List.of("Bob"))
        .merge(k);
  }

  static BlockState block(String n) {
    return new BlockState("minecraft:" + n);
  }

  @Test
  void hierarchicalShortcutAndDisjointBlocks() throws Exception {
    String base = blocks(Map.of()),
        ours = blocks(Map.of(0, block("stone"))),
        theirs = blocks(Map.of(15, block("gold_block")));
    assertEquals(ours, merge(base, ours, base, 1).tree());
    assertEquals(ours, merge(base, ours, ours, 1).tree());
    var r = merge(base, ours, theirs, 1);
    assertTrue(r.report().regions().isEmpty());
    assertEquals(1, r.report().automaticallyMergedSections());
    assertEquals(block("stone"), read(r.tree()).block(0));
    assertEquals(block("gold_block"), read(r.tree()).block(15));
  }

  @Test
  void beIsAtomicAndFollowsBlockCell() throws Exception {
    var chest = block("chest");
    String b =
        tree(
            Map.of(
                PATH,
                SnapshotCodec.section(
                    section(
                        Map.of(0, chest),
                        Map.of(
                            0,
                            Nbt.write(
                                new Nbt.Compound()
                                    .with("id", "minecraft:chest")
                                    .with("a", 1)
                                    .with("b", 1)))))));
    String o =
        tree(
            Map.of(
                PATH,
                SnapshotCodec.section(
                    section(
                        Map.of(0, chest),
                        Map.of(
                            0,
                            Nbt.write(
                                new Nbt.Compound()
                                    .with("id", "minecraft:chest")
                                    .with("a", 2)
                                    .with("b", 1)))))));
    String t =
        tree(
            Map.of(
                PATH,
                SnapshotCodec.section(
                    section(
                        Map.of(0, chest),
                        Map.of(
                            0,
                            Nbt.write(
                                new Nbt.Compound()
                                    .with("id", "minecraft:chest")
                                    .with("a", 1)
                                    .with("b", 2)))))));
    var r = merge(b, o, t, 1);
    assertEquals(1, r.report().regions().getFirst().blockCount());
    assertEquals(o, r.tree());
    String selected = MergeEngine.select(store, r.tree(), t, r.report().regions().getFirst());
    assertArrayEquals(read(t).blockEntities().get(0), read(selected).blockEntities().get(0));
  }

  @Test
  void uuidMoveDeleteModifyAndBothModifyConflictEvenSameResult() throws Exception {
    UUID id = UUID.randomUUID();
    var base = new EntitySnapshot(id, TestWorlds.entity(id, 1, true));
    var moved = new EntitySnapshot(id, TestWorlds.entity(id, 33, true));
    var modified = new EntitySnapshot(id, TestWorlds.entity(id, 1, true).with("Silent", (byte) 1));
    String b = tree(Map.of("r.0.0/c.0.0/entities.bin", SnapshotCodec.entities(List.of(base))));
    String o = tree(Map.of("r.0.0/c.2.0/entities.bin", SnapshotCodec.entities(List.of(moved))));
    String t = tree(Map.of("r.0.0/c.0.0/entities.bin", SnapshotCodec.entities(List.of(modified))));
    String empty = tree(Map.of());
    var r = merge(b, o, t, 1);
    assertEquals(1, r.report().regions().size());
    assertEquals(Kind.ENTITY, r.report().regions().getFirst().atoms().getFirst().kind());
    assertEquals(33, new DiffEngine(store).entities(r.tree()).get(id).entity().position()[0]);
    assertEquals(1, merge(b, empty, t, 1).report().regions().size());
    assertTrue(new DiffEngine(store).entities(merge(b, empty, b, 1).tree()).isEmpty());
    assertEquals(1, merge(b, t, t, 1).report().regions().size());
    assertEquals(
        1,
        new DiffEngine(store)
            .entities(MergeEngine.select(store, r.tree(), t, r.report().regions().getFirst()))
            .size());
  }

  @Test
  void movedEntityInsideBlockRegionJoinsDespiteBasePositionOutside() throws Exception {
    UUID id = UUID.randomUUID();
    var original =
        TestWorlds.entity(id, -20, true)
            .with("Pos", new Nbt.ListTag(6, List.of(-20.0, 64.0, -20.0)));
    var moved = Nbt.copy(original).with("Pos", new Nbt.ListTag(6, List.of(1.0, 64.0, 9.0)));
    var a = new TreeMap<Integer, BlockState>();
    var c = new TreeMap<Integer, BlockState>();
    // L 形衝突的 bbox 包含實體，但實體離最近的衝突格超過 k=1。
    for (int i = 0; i <= 10; i++) {
      for (int cell : List.of(i, 10 | (i << 4))) {
        a.put(cell, block("stone"));
        c.put(cell, block("gold_block"));
      }
    }
    String oldPath = "r.-1.-1/c.-2.-2/entities.bin";
    String b =
        tree(Map.of(oldPath, SnapshotCodec.entities(List.of(new EntitySnapshot(id, original)))));
    String o =
        tree(
            Map.of(
                PATH,
                SnapshotCodec.section(section(a, Map.of())),
                "r.0.0/c.0.0/entities.bin",
                SnapshotCodec.entities(List.of(new EntitySnapshot(id, moved)))));
    String t =
        tree(
            Map.of(
                PATH,
                SnapshotCodec.section(section(c, Map.of())),
                oldPath,
                SnapshotCodec.entities(
                    List.of(new EntitySnapshot(id, Nbt.copy(original).with("Silent", (byte) 1))))));
    var result = merge(b, o, t, 1);
    assertEquals(1, result.report().regions().size());
    var region = result.report().regions().getFirst();
    assertEquals(21, region.blockCount());
    String selected = MergeEngine.select(store, result.tree(), t, region);
    var entities = new DiffEngine(store).entities(selected);
    assertEquals(1, entities.size());
    assertEquals(-20.0, entities.get(id).entity().position()[0]);
  }

  @Test
  void biomeSampleMergeAndConflict() throws Exception {
    var base = new ArrayList<>(Collections.nCopies(64, "minecraft:plains"));
    var a = new ArrayList<>(base);
    a.set(0, "minecraft:desert");
    var c = new ArrayList<>(base);
    c.set(1, "minecraft:forest");
    String b = tree(Map.of("r.0.0/c.0.0/biomes.bin", SnapshotCodec.biomes(Map.of(4, base)))),
        o = tree(Map.of("r.0.0/c.0.0/biomes.bin", SnapshotCodec.biomes(Map.of(4, a)))),
        t = tree(Map.of("r.0.0/c.0.0/biomes.bin", SnapshotCodec.biomes(Map.of(4, c))));
    var r = merge(b, o, t, 1);
    var bio =
        SnapshotCodec.biomes(
                store.readBlob(TreeEditor.find(store, r.tree(), "r.0.0/c.0.0/biomes.bin").id()))
            .get(4);
    assertEquals("minecraft:desert", bio.get(0));
    assertEquals("minecraft:forest", bio.get(1));
    assertTrue(r.report().regions().isEmpty());
    c.set(0, "minecraft:forest");
    t = tree(Map.of("r.0.0/c.0.0/biomes.bin", SnapshotCodec.biomes(Map.of(4, c))));
    assertEquals(
        Kind.BIOME, merge(b, o, t, 1).report().regions().getFirst().atoms().getFirst().kind());
  }

  @Test
  void metadataRecursivelyMergesKeysAndSwitchKeepsOtherKeys() throws Exception {
    String path = "world-meta/level.nbt";
    var rules = new Nbt.Compound().with("a", "true").with("b", "true");
    var br = new Nbt.Compound().with("GameRules", rules);
    var or = Nbt.copy(br);
    or.compound("GameRules").put("a", "false");
    var tr = Nbt.copy(br);
    tr.compound("GameRules").put("b", "false");
    String b = tree(Map.of(path, Nbt.write(br))),
        o = tree(Map.of(path, Nbt.write(or))),
        t = tree(Map.of(path, Nbt.write(tr)));
    var r = merge(b, o, t, 1);
    assertTrue(r.report().regions().isEmpty());
    var merged =
        Nbt.read(store.readBlob(TreeEditor.find(store, r.tree(), path).id())).compound("GameRules");
    assertEquals("false", merged.string("a"));
    assertEquals("false", merged.string("b"));
    tr.compound("GameRules").put("a", "other");
    t = tree(Map.of(path, Nbt.write(tr)));
    r = merge(b, o, t, 1);
    assertNull(r.report().regions().getFirst().bounds());
    var chosen = MergeEngine.select(store, r.tree(), t, r.report().regions().getFirst());
    assertEquals(
        "false",
        Nbt.read(store.readBlob(TreeEditor.find(store, chosen, path).id()))
            .compound("GameRules")
            .string("b"));
  }

  @Test
  void threeDimensionalDistanceDoorsBedsPlantsAndPistonsAreBound() throws Exception {
    var lower = new BlockState("minecraft:oak_door", new TreeMap<>(Map.of("half", "lower")));
    var upper = new BlockState("minecraft:oak_door", new TreeMap<>(Map.of("half", "upper")));
    String b = blocks(Map.of(0, lower, 256, upper)),
        o = blocks(Map.of(0, block("gold_block"), 256, upper)),
        t = blocks(Map.of(0, block("diamond_block"), 256, upper));
    var r = merge(b, o, t, 0);
    assertEquals(1, r.report().regions().size());
    assertEquals(2, r.report().regions().getFirst().blockCount());
    assertEquals(65, r.report().regions().getFirst().bounds().maxY());
    var foot =
        new BlockState(
            "minecraft:red_bed", new TreeMap<>(Map.of("part", "foot", "facing", "east")));
    var head =
        new BlockState(
            "minecraft:red_bed", new TreeMap<>(Map.of("part", "head", "facing", "east")));
    b = blocks(Map.of(0, foot, 1, head));
    o = blocks(Map.of(0, block("stone"), 1, head));
    t = blocks(Map.of(0, block("dirt"), 1, head));
    assertEquals(2, merge(b, o, t, 0).report().regions().getFirst().blockCount());
    var plant = new BlockState("minecraft:sunflower", new TreeMap<>(Map.of("half", "lower")));
    b = blocks(Map.of(0, plant, 256, upper));
    assertEquals(2, merge(b, o, t, 0).report().regions().getFirst().blockCount());
    var piston =
        new BlockState(
            "minecraft:piston", new TreeMap<>(Map.of("extended", "true", "facing", "east")));
    b =
        blocks(
            Map.of(
                0,
                piston,
                1,
                new BlockState("minecraft:piston_head", new TreeMap<>(Map.of("facing", "east")))));
    assertEquals(2, merge(b, o, t, 0).report().regions().getFirst().blockCount());
    b = blocks(Map.of());
    o = blocks(Map.of(0, block("stone"), 2, block("stone")));
    t = blocks(Map.of(0, block("dirt"), 2, block("dirt")));
    assertEquals(2, merge(b, o, t, 1).report().regions().size());
    assertEquals(1, merge(b, o, t, 2).report().regions().size());
  }

  @Test
  void redstoneAndShapeBoundaryAcrossChunk() throws Exception {
    var fence =
        new BlockState(
            "minecraft:oak_fence", new TreeMap<>(Map.of("east", "false", "west", "false")));
    String b = blocks(Map.of(15, fence)), o = blocks(Map.of(15, fence, 0, block("stone")));
    String t =
        tree(
            Map.of(
                PATH,
                SnapshotCodec.section(section(Map.of(15, fence), Map.of())),
                "r.0.0/c.1.0/s.4.bin",
                SnapshotCodec.section(section(Map.of(0, block("stone")), Map.of()))));
    var r = merge(b, o, t, 1);
    assertTrue(r.report().updateShapes().contains(new Cell(15, 64, 0)));
    assertFalse(r.report().warnings().isEmpty());
    b = blocks(Map.of());
    o = blocks(Map.of(0, block("redstone_wire")));
    t = blocks(Map.of(0, block("stone")));
    assertTrue(merge(b, o, t, 1).report().regions().getFirst().redstone());
  }

  @Test
  void ticksStructuresAreAtomicAndRuleTextMergeRespectsOrder() throws Exception {
    for (String name : List.of("ticks", "structures")) {
      int kind = name.equals("ticks") ? 4 : 5;
      String p = "r.0.0/c.0.0/" + name + ".bin";
      String
          b = tree(Map.of(p, SnapshotCodec.nbt(kind, Nbt.write(new Nbt.Compound().with("a", 1))))),
          o = tree(Map.of(p, SnapshotCodec.nbt(kind, Nbt.write(new Nbt.Compound().with("a", 2))))),
          t = tree(Map.of(p, SnapshotCodec.nbt(kind, Nbt.write(new Nbt.Compound().with("b", 2)))));
      assertEquals(1, merge(b, o, t, 1).report().regions().size());
    }
    assertEquals(
        "entity minecraft:item\n", IgnoreRuleMerge.merge("", "entity minecraft:item\n", ""));
    assertThrows(
        IOException.class,
        () -> IgnoreRuleMerge.merge("", "entity minecraft:item\n", "entity minecraft:zombie\n"));
    String base = "entity minecraft:item\n# middle\nentity minecraft:zombie\n";
    String ours = base.replace("minecraft:item", "minecraft:arrow"),
        theirs = base.replace("minecraft:zombie", "minecraft:cow");
    assertEquals(
        base.replace("minecraft:item", "minecraft:arrow")
            .replace("minecraft:zombie", "minecraft:cow"),
        IgnoreRuleMerge.merge(base, ours, theirs));
  }

  @Test
  void mergedIgnoreFiltersBlockBiomeBeEntityAndMetadata() throws Exception {
    UUID id = UUID.randomUUID();
    String t =
        tree(
            Map.of(
                PATH,
                SnapshotCodec.section(
                    section(
                        Map.of(0, block("chest"), 1, block("stone")),
                        Map.of(
                            0,
                            Nbt.write(
                                new Nbt.Compound()
                                    .with("id", "minecraft:chest")
                                    .with("LootTable", "x"))))),
                "r.0.0/c.0.0/entities.bin",
                SnapshotCodec.entities(
                    List.of(new EntitySnapshot(id, TestWorlds.entity(id, 1, true)))),
                "world-meta/level.nbt",
                Nbt.write(new Nbt.Compound().with("LevelName", "A").with("hardcore", (byte) 0))));
    String rules =
        "area 1 64 0 1 64 0\n"
            + "field minecraft:chest LootTable\n"
            + "entity minecraft:zombie\n"
            + "field worldgit:level LevelName\n";
    String f = TreeFilter.filter(store, t, rules, EntitySemantics.OFFLINE);
    assertTrue(read(f).block(1).air());
    assertFalse(Nbt.read(read(f).blockEntities().get(0)).containsKey("LootTable"));
    assertTrue(new DiffEngine(store).entities(f).isEmpty());
    assertFalse(
        Nbt.read(store.readBlob(TreeEditor.find(store, f, "world-meta/level.nbt").id()))
            .containsKey("LevelName"));
  }

  CommitMetadata meta() {
    var a = new CommitMetadata.Identity("test", "t@example.com");
    return new CommitMetadata(
        a,
        a,
        "test",
        Instant.now(),
        4903,
        DimensionId.OVERWORLD,
        CommitMetadata.Source.CLI,
        false,
        UUID.randomUUID(),
        List.of());
  }

  @Test
  void bestBasesNoCommonAndCrissCrossRefusal() throws Exception {
    String tree = blocks(Map.of()),
        a = store.createCommit(tree, (String) null, meta()),
        b = store.createCommit(tree, a, meta()),
        c = store.createCommit(tree, a, meta());
    assertEquals(a, MergeBases.unique(store, b, c));
    String unrelated = store.createCommit(tree, (String) null, meta());
    assertNull(MergeBases.unique(store, b, unrelated));
    String d = store.createCommit(tree, List.of(b, c), meta(), Map.of()),
        e = store.createCommit(tree, List.of(c, b), meta(), Map.of());
    assertEquals(Set.of(b, c), new HashSet<>(MergeBases.best(store, d, e)));
    assertThrows(IOException.class, () -> MergeBases.unique(store, d, e));
  }

  @Test
  void redstoneInsideRegionBoxIsReportedEvenIfNotConflictAtom() throws Exception {
    var common = block("redstone_wire");
    String b = blocks(Map.of(17, common));
    String o = blocks(Map.of(17, common, 0, block("stone"), 2, block("stone"), 34, block("stone")));
    String t = blocks(Map.of(17, common, 0, block("dirt"), 2, block("dirt"), 34, block("dirt")));
    var r = merge(b, o, t, 2);
    assertEquals(1, r.report().regions().size());
    assertTrue(r.report().regions().getFirst().redstone());
  }
}
