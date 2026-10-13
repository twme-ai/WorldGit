package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.DimensionId;

class Paper26BoundariesTest {
  @Test void brewing263ProjectionMatchesNativeSaveAndKeepsCustomValues() throws Exception {
    var source = new Nbt.Compound().with("id", "minecraft:brewing_stand")
        .with("x", 10).with("y", 64).with("z", 4).with("Fuel", (byte) 0)
        .with("BrewTime", (short) 0).with("custom_data", new Nbt.Compound().with("total_fuel", 99));
    var nativeSave = Nbt.copy(source);
    nativeSave.put("Fuel", 0);nativeSave.put("BrewTime", 0);
    nativeSave.put("total_brew_time", 400);nativeSave.put("total_fuel", 20);
    nativeSave.put("speed_multiplier", 1f);
    assertArrayEquals(Nbt.write(normalizeBrewing(source, 5023)), Nbt.write(normalizeBrewing(nativeSave, 5023)));
    assertFalse(source.containsKey("total_fuel"));
    assertFalse(normalizeBrewing(source, 4903).containsKey("total_fuel"));
    nativeSave.put("total_brew_time", 237);nativeSave.put("total_fuel", 7);
    nativeSave.put("speed_multiplier", 2f);
    var normalized = normalizeBrewing(nativeSave, 5023);
    assertEquals(237, normalized.get("total_brew_time"));assertEquals(7, normalized.get("total_fuel"));
    assertEquals(2f, normalized.get("speed_multiplier"));assertEquals(99, normalized.compound("custom_data").get("total_fuel"));
    var loss = new ConversionReport.Collector();
    var reversed = new ReverseNbt(new ReverseRules(5023, 4903, null), loss, 5023, 4903)
        .convert(normalized, "block-entity", "brewing");
    assertEquals((byte) 0, reversed.get("Fuel"));
    assertTrue(loss.report(5023, 4903, "test").losses().stream().anyMatch(x -> x.source().contains("total_brew_time")));
    assertTrue(loss.report(5023, 4903, "test").losses().stream().anyMatch(x -> x.source().contains("total_fuel")));
    assertTrue(loss.report(5023, 4903, "test").losses().stream().anyMatch(x -> x.source().contains("speed_multiplier")));
    assertFalse(normalizeBrewing(new Nbt.Compound().with("id", "custom:brewing_stand"), 5023).containsKey("total_fuel"));
  }

  private static Nbt.Compound normalizeBrewing(Nbt.Compound blockEntity, int version) throws Exception {
    var snapshot = new org.worldgit.core.normalize.ChunkNormalizer(org.worldgit.core.config.IgnoreRules.none(),
        org.worldgit.core.config.EntitySemantics.OFFLINE).normalize(new org.worldgit.core.model.ChunkPos(0, 0),
        new Nbt.Compound().with("DataVersion", version).with("block_entities", new Nbt.ListTag(10, List.of(blockEntity))), List.of());
    return Nbt.read(snapshot.sections().get(blockEntity.integer("y", 0) >> 4).blockEntities().values().iterator().next());
  }

  @Test void native12111BorderStorageIsEquivalentButConflictingOrCustomWrappersStayVisible() throws Exception {
    var border=new Nbt.Compound().with("warning_time",300).with("center_x",12d).with("custom:preserve",42);
    var files=new TreeMap<String,byte[]>();files.put("level.nbt",Nbt.write(new Nbt.Compound().with("DataVersion",4671).with("world_border",border)));
    var nativeForm=org.worldgit.core.normalize.MetadataNormalizer.nativeLayout(files);
    assertFalse(Nbt.read(nativeForm.get("level.nbt")).containsKey("world_border"));
    assertArrayEquals(Nbt.write(border),Nbt.write(Nbt.read(nativeForm.get("world_border.dat.nbt")).compound("data")));
    assertArrayEquals(nativeForm.get("level.nbt"),org.worldgit.core.normalize.MetadataNormalizer.nativeLayout(nativeForm).get("level.nbt"));
    var other=Nbt.copy(border);other.put("warning_time",301);
    files.put("world_border.dat.nbt",Nbt.write(new Nbt.Compound().with("DataVersion",4671).with("data",other)));
    assertArrayEquals(files.get("level.nbt"),org.worldgit.core.normalize.MetadataNormalizer.nativeLayout(files).get("level.nbt"));
    files.put("world_border.dat.nbt",Nbt.write(new Nbt.Compound().with("DataVersion",4671).with("data",border).with("custom",42)));
    assertArrayEquals(files.get("level.nbt"),org.worldgit.core.normalize.MetadataNormalizer.nativeLayout(files).get("level.nbt"));
    files.remove("world_border.dat.nbt");files.put("level.nbt",Nbt.write(new Nbt.Compound().with("DataVersion",4790).with("world_border",border)));
    assertEquals(files.keySet(),org.worldgit.core.normalize.MetadataNormalizer.nativeLayout(files).keySet());
  }
  @Test void farmAnimalSoundDefaultsMatchNative261WithoutChangingExistingOrOpaqueValues() {
    for (String id : List.of("minecraft:pig","minecraft:cow","minecraft:chicken")) {
      var source=new Nbt.Compound().with("id",id).with("custom_data",new Nbt.Compound().with("sound_variant","opaque"));
      assertFalse(NativeNbtForms.entity(source,4671).containsKey("sound_variant"));
      var nativeForm=NativeNbtForms.entity(source,4790);
      assertEquals("minecraft:classic",nativeForm.string("sound_variant"));
      assertFalse(source.containsKey("sound_variant"));
      assertEquals("opaque",nativeForm.compound("custom_data").string("sound_variant"));
      source.put("sound_variant","custom:preserve");
      assertEquals("custom:preserve",NativeNbtForms.entity(source,5023).string("sound_variant"));
    }
    assertFalse(NativeNbtForms.entity(new Nbt.Compound().with("id","custom:pig"),5023).containsKey("sound_variant"));
  }
  @Test void namespacedLayoutStartsAt4790AndKeepsDifficultyAcross26x() throws Exception {
    var difficulty = new Nbt.Compound().with("difficulty", "hard")
        .with("locked", (byte) 1).with("hardcore", (byte) 0);
    var border = new Nbt.Compound().with("center_x", 12d).with("warning_time", 17);
    for (int source : List.of(4790, 4903, 5023)) {
      var input = Map.of("level.nbt", Nbt.write(new Nbt.Compound().with("DataVersion", source)
              .with("difficulty_settings", difficulty)),
          "world_border.dat.nbt", Nbt.write(new Nbt.Compound().with("DataVersion", source).with("data", border)));
      var loss = new ConversionReport.Collector();
      var reverse = new ReverseNbt(new ReverseRules(source, 4671, null), loss, source, 4671);
      var output = MetadataConversion.convert(input, DimensionId.OVERWORLD, source, 4671, null, reverse, loss);
      var level = Nbt.read(output.get("level.nbt"));
      assertEquals((byte) 3, level.get("Difficulty"));assertEquals((byte) 1, level.get("DifficultyLocked"));
      assertFalse(level.containsKey("difficulty_settings"));assertFalse(output.containsKey("world_border.dat.nbt"));
      assertArrayEquals(Nbt.write(border), Nbt.write(level.compound("world_border")));
      assertFalse(loss.report(source, 4671, "test").lossy());
      if (source > 4790) {
        loss = new ConversionReport.Collector();
        reverse = new ReverseNbt(new ReverseRules(source, 4790, null), loss, source, 4790);
        output = MetadataConversion.convert(input, DimensionId.OVERWORLD, source, 4790, null, reverse, loss);
        assertArrayEquals(Nbt.write(difficulty), Nbt.write(Nbt.read(output.get("level.nbt")).compound("difficulty_settings")));
        assertArrayEquals(Nbt.write(border), Nbt.write(Nbt.read(output.get("world_border.dat.nbt")).compound("data")));
      }
    }
  }

  @Test void compactAndExplicit263StatesReverseToFullNative262Form() throws Exception {
    for (Object state : List.of("minecraft:oak_log", new Nbt.Compound().with("id", "minecraft:oak_log"),
        new Nbt.Compound().with("id", "minecraft:oak_log").with("properties", new Nbt.Compound().with("axis", "x")))) {
      var source = new Nbt.Compound().with("id", "minecraft:block_display").with("block_state", state);
      var loss = new ConversionReport.Collector();
      var result = new ReverseNbt(new ReverseRules(5023, 4903, null), loss, 5023, 4903)
          .convert(source, "entity", "display");
      var expected = BlockStateNbt.read(state, 5023);
      assertArrayEquals(Nbt.write(BlockStateNbt.write(expected, 4903)), Nbt.write(result.compound("block_state")));
      assertEquals("minecraft:oak_log", result.compound("block_state").string("Name"));
      assertFalse(loss.report(5023, 4903, "test").lossy());assertEquals(state, source.get("block_state"));
    }
  }
}
