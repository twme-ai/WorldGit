package org.worldgit.core.apply;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.version.NbtUpgrader;

class UnloadedChunkUpgradeTest {
  @TempDir Path world;
  private static final int TARGET = 5023;
  private static final ChunkPos POS = new ChunkPos(0, 0);

  private WorldLayout layout() throws Exception {
    Files.createDirectories(world.resolve("region"));
    try (var out = new GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))) {
      out.write(Nbt.write(new Nbt.Compound().with("Data", new Nbt.Compound().with("DataVersion", TARGET))));
    }
    return WorldLayout.discover(world);
  }

  private ApplyPlan terrainPlan() throws Exception {
    long[] mask = new long[64]; mask[0] = 1;
    var section = new Section(Collections.nCopies(4096, new BlockState("minecraft:gold_block")), Map.of());
    var patches = new TreeMap<Integer, ApplyPlan.SectionOp>();
    patches.put(4, new ApplyPlan.SectionOp(4, SnapshotCodec.section(section), mask));
    return new ApplyPlan(DimensionId.OVERWORLD, null, null, TARGET, Scope.all(),
        List.of(new ApplyPlan.ChunkOp(POS, false, patches, new TreeMap<>(), false, null, false, null)),
        List.of(), Map.of(), List.of());
  }

  @Test void partialPatchUpgradesUnchangedBlockEntitiesBeforePublishingTargetVersion() throws Exception {
    var layout = layout();
    var banner = new Nbt.Compound().with("id", "minecraft:banner")
        .with("x", 2).with("y", -48).with("z", 0).with("Patterns", new Nbt.Compound().with("old", 1));
    var chunk = new Nbt.Compound().with("DataVersion", 3700)
        .with("block_entities", new Nbt.ListTag(10, List.of(banner)));
    RegionFile.update(world.resolve("region/r.0.0.mca"), Map.of(0, chunk), 1);
    var calls = new ArrayList<String>();
    NbtUpgrader dfu = (type, input, from, to) -> {
      calls.add(type); assertEquals(3700, from); assertEquals(TARGET, to);
      var upgraded = Nbt.copy(input);
      var be = (Nbt.Compound) upgraded.list("block_entities").values().getFirst();
      be.remove("Patterns"); be.put("patterns", new Nbt.Compound().with("converted", 1));
      return upgraded;
    };
    new OfflineApplier(layout, null, dfu).apply(terrainPlan());
    try (var region = new RegionFile(world.resolve("region/r.0.0.mca"))) {
      var result = region.read(0);
      assertEquals(TARGET, result.integer("DataVersion", 0));
      var be = (Nbt.Compound) result.list("block_entities").values().getFirst();
      assertFalse(be.containsKey("Patterns"));
      assertEquals(1, be.compound("patterns").integer("converted", 0));
      assertEquals(-48, be.integer("y", 0));
      assertEquals("minecraft:gold_block", ChunkNbt.blocks((Nbt.Compound)
          result.list("sections").values().getFirst(), TARGET).block(0).name());
    }
    assertEquals(List.of("CHUNK"), calls);
  }

  private Nbt.Compound entity(int id) {
    return new Nbt.Compound().with("id", "minecraft:armor_stand").with("UUID", new int[] {0,0,0,id})
        .with("Pos", new Nbt.ListTag(6, List.of(1d,64d,1d))).with("old_field", id);
  }

  @Test void entityReplacementUpgradesUnrelatedEntitiesInTheSameStorageRecord() throws Exception {
    var layout = layout();
    Path path = world.resolve("entities/r.0.0.mca");
    var root = new Nbt.Compound().with("DataVersion", 3700).with("Position", new int[] {0,0})
        .with("Entities", new Nbt.ListTag(10, List.of(entity(1), entity(2))));
    RegionFile.update(path, Map.of(0, root), 1);
    NbtUpgrader dfu = (type, input, from, to) -> {
      assertEquals("ENTITY_CHUNK", type);
      var upgraded = Nbt.copy(input);
      for (var value : upgraded.list("Entities").values()) {
        var e = (Nbt.Compound) value;
        e.put("new_field", e.remove("old_field"));
      }
      return upgraded;
    };
    var target = entity(1); target.remove("old_field"); target.put("new_field", 9);
    var snapshot = new EntitySnapshot(EntityNormalizer.uuid(target), target);
    var plan = new ApplyPlan(DimensionId.OVERWORLD, null, null, TARGET, Scope.all(), List.of(),
        List.of(new ApplyPlan.EntityOp(snapshot.uuid(), POS, snapshot)), Map.of(), List.of());
    new OfflineApplier(layout, null, dfu).apply(plan);
    try (var region = new RegionFile(path)) {
      var result = region.read(0); assertEquals(TARGET, result.integer("DataVersion", 0));
      assertEquals(2, result.list("Entities").values().size());
      for (var value : result.list("Entities").values()) {
        var e = (Nbt.Compound) value; assertFalse(e.containsKey("old_field"));
        assertEquals(EntityNormalizer.uuid(entity(2)).equals(EntityNormalizer.uuid(e)) ? 2 : 9,
            e.integer("new_field", 0));
      }
    }
  }

  @Test void sameVersionPatchDoesNotInvokeDfu() throws Exception {
    var layout = layout();
    RegionFile.update(world.resolve("region/r.0.0.mca"),
        Map.of(0, new Nbt.Compound().with("DataVersion", TARGET)), 1);
    new OfflineApplier(layout, null, (type, raw, from, to) -> {
      fail("同版本不得啟動 DFU"); return raw;
    }).apply(terrainPlan());
  }
}
