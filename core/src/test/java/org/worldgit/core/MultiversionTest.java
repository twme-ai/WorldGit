package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.*;
import org.worldgit.core.version.*;

class MultiversionTest {
  @TempDir Path temp;

  @Test
  void entityAttributesUseTheNativeShapeAcrossThe1206To1211Boundary() throws Exception {
    for (int source : List.of(3955, 5023)) {
      for (int target : List.of(3839, 3955)) {
        if (source <= target) continue;
        var loss = new ConversionReport.Collector();
        var reverse = new ReverseNbt(new ReverseRules(source, target, null), loss, source, target);
        var input = new Nbt.Compound().with("id", "minecraft:armor_stand")
            .with("attributes", new Nbt.ListTag(10, List.of(new Nbt.Compound()
                .with("id", source == 3955 ? "minecraft:generic.max_health" : "minecraft:max_health")
                .with("base", 37.5d))));
        var output = reverse.convert(input, "entity", "armor_stand");
        String key = target == 3839 ? "Attributes" : "attributes";
        assertFalse(output.containsKey(target == 3839 ? "attributes" : "Attributes"));
        var attribute = (Nbt.Compound) output.list(key).values().getFirst();
        assertEquals("minecraft:generic.max_health", attribute.string(target == 3839 ? "Name" : "id"));
        assertEquals(37.5d, attribute.get(target == 3839 ? "Base" : "base"));
        assertFalse(attribute.containsKey(target == 3839 ? "id" : "Name"));
        assertFalse(loss.report(source, target, "test").lossy());
      }
    }
  }

  @Test
  void itemFallbackOmitsAnEmptyNativePatchAndKeepsOpaqueCustomData() throws Exception {
    var loss = new ConversionReport.Collector();
    var reverse = new ReverseNbt(new ReverseRules(3955, 3839, null), loss, 3955, 3839);
    var item = reverse.convert(new Nbt.Compound().with("id", "minecraft:mace").with("count", 1), "item", "Items[1]");
    assertEquals("minecraft:barrier", item.string("id"));
    assertEquals(1, item.integer("count", 0));
    assertFalse(item.containsKey("components"));
    assertTrue(loss.report(3955, 3839, "test").lossy());
    var opaque = new Nbt.Compound().with("components", new Nbt.Compound()).with("Count", 7);
    var sword = reverse.convert(new Nbt.Compound().with("id", "minecraft:diamond_sword").with("count", 1)
        .with("components", new Nbt.Compound().with("minecraft:custom_data", opaque)), "item", "Items[0]");
    assertArrayEquals(Nbt.write(opaque), Nbt.write(sword.compound("components").compound("minecraft:custom_data")));
  }

  @Test
  void oldComponentVersionsOmitEmptyBlockEntityPatchButPreserveNonemptyPatch() throws Exception {
    for (int target : List.of(3839, 3955, 4189, 4440)) {
      var loss = new ConversionReport.Collector();
      var reverse = new ReverseNbt(new ReverseRules(5023, target, null), loss, 5023, target);
      var chest = new Nbt.Compound().with("id", "minecraft:chest")
          .with("components", new Nbt.Compound());
      assertEquals(target > 4189,
          reverse.convert(chest, "block-entity", "chest").containsKey("components"));
      chest.compound("components").put("minecraft:custom_data", new Nbt.Compound().with("test", 42));
      assertEquals(42, reverse.convert(chest, "block-entity", "chest")
          .compound("components").compound("minecraft:custom_data").integer("test", 0));
      assertFalse(loss.report(5023, target, "test").lossy());
    }
  }

  @Test
  void legacyMaterializationKeepsPassengerHealthAndChecksEntityType() throws Exception {
    var passenger = new Nbt.Compound().with("id", "minecraft:pig").with("UUID", new int[] {5,6,7,8});
    var target = new Nbt.Compound().with("id", "minecraft:cow").with("UUID", new int[] {1,2,3,4})
        .with("Passengers", new Nbt.ListTag(10, List.of(passenger)));
    var old = new HashMap<UUID, Nbt.Compound>();
    old.put(org.worldgit.core.normalize.EntityNormalizer.uuid(target), Nbt.copy(target).with("Health", 7f));
    old.put(org.worldgit.core.normalize.EntityNormalizer.uuid(passenger), Nbt.copy(passenger).with("Health", 4f));
    VanillaEntityDefaults.materialize(target, 3465, old);
    assertEquals(7f, target.get("Health"));
    assertEquals(4f, passenger.get("Health"));
    passenger.remove("Health");
    passenger.put("id", "minecraft:armor_stand");
    VanillaEntityDefaults.materialize(passenger, 3465, old);
    assertEquals(20f, passenger.get("Health"));
  }

  @Test
  void downgradedEntityDefaultsStayOutOfHistoryButRemainSafeToMaterialize() throws Exception {
    var data = new Nbt.Compound().with("id", "minecraft:creeper")
        .with("UUID", new int[] {1, 2, 3, 4}).with("NoAI", (byte) 1)
        .with("Pos", new Nbt.ListTag(6, List.of(4.5d, 64d, 4.5d)));
    var source = new ChunkSnapshot(new ChunkPos(0, 0), 5023, new TreeMap<>(),
        new TreeMap<>(), List.of(new EntitySnapshot(
            org.worldgit.core.normalize.EntityNormalizer.uuid(data), data)), null, null);
    try (var converter = new SnapshotConverter(5023, 4189, null, null)) {
      var target = converter.convert(source);
      var entity = target.entities().getFirst();
      assertFalse(entity.data().containsKey("Health"));
      assertFalse(entity.data().containsKey("Fuse"));
      var captured = new org.worldgit.core.normalize.ChunkNormalizer(
          org.worldgit.core.config.IgnoreRules.none(),
          org.worldgit.core.config.EntitySemantics.OFFLINE)
          .normalize(target.pos(), SnapshotConverter.materialize(target), List.of(entity.data()));
      assertArrayEquals(entity.bytes(), captured.entities().getFirst().bytes());
      var physical = entity.data();
      VanillaEntityDefaults.materialize(physical, 4189);
      assertEquals(20f, physical.get("Health"));
      assertFalse(entity.data().containsKey("Health"));
      physical.put("Health", 5f);
      VanillaEntityDefaults.materialize(physical, 4189);
      assertEquals(5f, physical.get("Health"));
    }
  }

  @Test
  void downgradedTicksMatchRecapturedCanonicalBytes() throws Exception {
    var ticks = new Nbt.Compound().with("block_ticks", new Nbt.ListTag(10, List.of(
        new Nbt.Compound().with("i", "minecraft:sand").with("x", 0).with("y", 64)
            .with("z", 0).with("p", 0).with("t", 5),
        new Nbt.Compound().with("i", "minecraft:short_grass").with("x", 0).with("y", 64)
            .with("z", 0).with("p", 0).with("t", 5))));
    var source = new ChunkSnapshot(new ChunkPos(0, 0), 5023, new TreeMap<>(),
        new TreeMap<>(), List.of(), Nbt.write(ticks), null);
    try (var converter = new SnapshotConverter(5023, 3465, null, null)) {
      var target = converter.convert(source);
      var captured = new org.worldgit.core.normalize.ChunkNormalizer(
          org.worldgit.core.config.IgnoreRules.none(),
          org.worldgit.core.config.EntitySemantics.OFFLINE)
          .normalize(target.pos(), SnapshotConverter.materialize(target), List.of());
      assertArrayEquals(captured.ticks(), target.ticks());
      assertEquals("minecraft:grass", ((Nbt.Compound) Nbt.read(target.ticks())
          .list("block_ticks").values().getFirst()).string("i"));
      assertFalse(Nbt.read(target.ticks()).containsKey("fluid_ticks"));
    }
  }

  @Test
  void bambooRaftsKeepTheirWoodTypeAcrossBoatIdBoundary() throws Exception {
    var losses = new ConversionReport.Collector();
    var reverse = new ReverseNbt(new ReverseRules(5023, 3955, null), losses, 5023, 3955);
    for (String suffix : List.of("raft", "chest_raft")) {
      var boat = reverse.convert(new Nbt.Compound().with("id", "minecraft:bamboo_" + suffix),
          "entity", "boat");
      assertEquals(suffix.equals("raft") ? "minecraft:boat" : "minecraft:chest_boat",
          boat.string("id"));
      assertEquals("bamboo", boat.string("Type"));
    }
    assertFalse(losses.report(5023, 3955, "test").lossy());
  }

  @Test
  void modelDataUsesExactTargetSchemaAndReportsUnrepresentableValues() throws Exception {
    var model = new Nbt.Compound().with("floats", new Nbt.ListTag(5, List.of(42f)))
        .with("strings", new Nbt.ListTag(8, List.of("fixture")));
    for (int target : List.of(4189, 3955, 3700)) {
      var losses = new ConversionReport.Collector();
      var reverse = new ReverseNbt(new ReverseRules(5023, target, null), losses, 5023, target);
      var chest = new Nbt.Compound().with("id", "minecraft:chest").with("Items",
          new Nbt.ListTag(10, List.of(new Nbt.Compound().with("id", "minecraft:diamond_sword")
              .with("count", 1).with("components", new Nbt.Compound()
                  .with("minecraft:custom_model_data", model)))));
      var item = (Nbt.Compound) reverse.convert(chest, "block-entity", "chest")
          .list("Items").values().getFirst();
      if (target == 4189) {
        assertArrayEquals(Nbt.write(model),
            Nbt.write(item.compound("components").compound("minecraft:custom_model_data")));
        assertFalse(losses.report(5023, target, "test").lossy());
      } else {
        assertFalse(item.compound("components").containsKey("minecraft:custom_model_data"));
        assertFalse(item.compound("tag").containsKey("CustomModelData"));
        assertTrue(losses.report(5023, target, "test").losses().stream()
            .anyMatch(l -> l.source().equals("minecraft:custom_model_data")));
      }
    }
    for (float value : List.of(42f, 42.5f)) {
      var losses = new ConversionReport.Collector();
      var reverse = new ReverseNbt(new ReverseRules(5023, 3955, null), losses, 5023, 3955);
      var chest = new Nbt.Compound().with("id", "minecraft:chest").with("Items",
          new Nbt.ListTag(10, List.of(new Nbt.Compound().with("id", "minecraft:diamond_sword")
              .with("count", 1).with("components", new Nbt.Compound()
                  .with("minecraft:custom_model_data", new Nbt.Compound()
                      .with("floats", new Nbt.ListTag(5, List.of(value))))))));
      var item = (Nbt.Compound) reverse.convert(chest, "block-entity", "chest")
          .list("Items").values().getFirst();
      if (value == 42f) assertEquals(42, item.compound("components").get("minecraft:custom_model_data"));
      assertEquals(value != 42f, losses.report(5023, 3955, "test").lossy());
    }
  }

  @Test
  void legacyJsonLoreIsPreservedBetweenComponentVersions() throws Exception {
    String line = "{\"text\":\"WorldGit lore\",\"italic\":false}";
    var chest = new Nbt.Compound().with("id", "minecraft:chest").with("Items",
        new Nbt.ListTag(10, List.of(new Nbt.Compound().with("id", "minecraft:diamond_sword")
            .with("count", 1).with("components", new Nbt.Compound()
                .with("minecraft:lore", new Nbt.ListTag(8, List.of(line)))))));
    var losses = new ConversionReport.Collector();
    var reverse = new ReverseNbt(new ReverseRules(4189, 3839, null), losses, 4189, 3839);
    var item = (Nbt.Compound) reverse.convert(chest, "block-entity", "chest")
        .list("Items").values().getFirst();
    assertEquals(line, item.compound("components").list("minecraft:lore").values().getFirst());
    assertFalse(losses.report(4189, 3839, "test").lossy());
  }

  @Test
  void legacyPlayerEquipmentAndRespawnAndLivingDefaultsArePreserved() throws Exception {
    var loss = new ConversionReport.Collector();
    var reverse = new ReverseNbt(new ReverseRules(5023, 3465, null), loss, 5023, 3465);
    var item = new Nbt.Compound().with("id", "minecraft:diamond_boots").with("count", 1);
    var player =
        new Nbt.Compound()
            .with("equipment", new Nbt.Compound().with("feet", item))
            .with("Inventory", new Nbt.ListTag(10, List.of()))
            .with(
                "respawn",
                new Nbt.Compound()
                    .with("pos", new int[] {1, 64, 2})
                    .with("dimension", "minecraft:the_nether")
                    .with("forced", (byte) 1));
    var old = reverse.convert(player, "player", "player");
    var boots = (Nbt.Compound) old.list("Inventory").values().getFirst();
    assertEquals((byte) 100, boots.get("Slot"));
    assertEquals((byte) 1, boots.get("Count"));
    assertEquals(64, old.integer("SpawnY", 0));
    assertEquals("minecraft:the_nether", old.string("SpawnDimension"));
    assertEquals(
        10f,
        reverse
            .convert(new Nbt.Compound().with("id", "minecraft:cow"), "entity", "cow")
            .get("Health"));
    assertFalse(loss.report(5023, 3465, "test").lossy());
  }

  @Test
  void recoverVersionMigrationWithoutLevelDatAndRejectForeignPaths() throws Exception {
    String property = System.getProperty("worldgit.versions");
    try {
      Path cache = temp.resolve("cache"),
          stage = cache.resolve("migration-00000000-0000-0000-0000-000000000000"),
          world = temp.resolve("world"),
          backup = stage.resolve("backup/level.dat");
      System.setProperty("worldgit.versions", cache.toString());
      Files.createDirectories(backup.getParent());
      Files.createDirectories(world.resolve(".worldgit"));
      Files.writeString(backup, "original");
      var journal = new LinkedHashMap<String, Object>();
      journal.put("state", "PREPARED");
      journal.put("stage", stage.toString());
      journal.put(
          "moves",
          List.of(Map.of("from", world.resolve("level.dat").toString(), "to", backup.toString())));
      journal.put("before", List.of());
      journal.put("commits", Map.of());
      Path path = world.resolve(".worldgit/version-state.yml");
      org.worldgit.core.service.OperationState.write(path, journal);
      assertTrue(WorldVersionMigration.recover(world));
      assertEquals("original", Files.readString(world.resolve("level.dat")));
      assertFalse(WorldVersionMigration.recover(world));
      journal.put(
          "moves",
          List.of(Map.of("from", temp.resolve("foreign").toString(), "to", backup.toString())));
      org.worldgit.core.service.OperationState.write(path, journal);
      assertThrows(IOException.class, () -> WorldVersionMigration.recover(world));
      assertEquals("original", Files.readString(world.resolve("level.dat")));
    } finally {
      if (property == null) System.clearProperty("worldgit.versions");
      else System.setProperty("worldgit.versions", property);
    }
  }

  @Test
  void booksFireworksEffectsAndAttributesUseLegacyCodecs() throws Exception {
    var components =
        new Nbt.Compound()
            .with(
                "minecraft:written_book_content",
                new Nbt.Compound()
                    .with("title", new Nbt.Compound().with("raw", "Title"))
                    .with("author", "Author")
                    .with(
                        "pages",
                        new Nbt.ListTag(
                            10,
                            List.of(
                                new Nbt.Compound()
                                    .with("raw", new Nbt.Compound().with("text", "Page"))))))
            .with(
                "minecraft:fireworks",
                new Nbt.Compound()
                    .with("flight_duration", 2)
                    .with(
                        "explosions",
                        new Nbt.ListTag(
                            10,
                            List.of(
                                new Nbt.Compound()
                                    .with("shape", "star")
                                    .with("colors", new int[] {123})
                                    .with("has_trail", (byte) 1)))))
            .with(
                "minecraft:attribute_modifiers",
                new Nbt.ListTag(
                    10,
                    List.of(
                        new Nbt.Compound()
                            .with("id", "test:damage")
                            .with("type", "minecraft:attack_damage")
                            .with("amount", 4d)
                            .with("operation", "add_value")
                            .with("slot", "mainhand"))))
            .with(
                "minecraft:potion_contents",
                new Nbt.Compound()
                    .with(
                        "custom_effects",
                        new Nbt.ListTag(
                            10,
                            List.of(
                                new Nbt.Compound()
                                    .with("id", "minecraft:speed")
                                    .with("duration", 100)
                                    .with("amplifier", (byte) 2)))));
    var loss = new ConversionReport.Collector();
    var reverse = new ReverseNbt(new ReverseRules(5023, 3465, null), loss, 5023, 3465);
    var item =
        new Nbt.Compound()
            .with("id", "minecraft:diamond_sword")
            .with("count", 1)
            .with("components", components);
    var chest =
        new Nbt.Compound()
            .with("id", "minecraft:chest")
            .with("components", new Nbt.Compound())
            .with("Items", new Nbt.ListTag(10, List.of(item)));
    var tag =
        ((Nbt.Compound)
                reverse.convert(chest, "block-entity", "test").list("Items").values().getFirst())
            .compound("tag");
    assertEquals("{\"text\":\"Page\"}", tag.list("pages").values().getFirst());
    assertEquals("Title", tag.string("title"));
    assertEquals((byte) 2, tag.compound("Fireworks").get("Flight"));
    assertEquals(
        (byte) 2,
        ((Nbt.Compound) tag.compound("Fireworks").list("Explosions").values().getFirst())
            .get("Type"));
    assertEquals(
        "minecraft:generic.attack_damage",
        ((Nbt.Compound) tag.list("AttributeModifiers").values().getFirst())
            .string("AttributeName"));
    assertEquals(
        (byte) 1, ((Nbt.Compound) tag.list("CustomPotionEffects").values().getFirst()).get("Id"));
    assertFalse(loss.report(5023, 3465, "test").lossy());
  }

  @Test
  void compact263StatesPreserveRegistryDefaultsAndExplicitProperties() throws Exception {
    var compact = BlockStateNbt.read("minecraft:oak_log", 5023);
    assertEquals("y", compact.properties().get("axis"));
    assertEquals(
        "x",
        BlockStateNbt.read(
                new Nbt.Compound()
                    .with("id", "minecraft:oak_log")
                    .with("properties", new Nbt.Compound().with("axis", "x")),
                5023)
            .properties()
            .get("axis"));
    assertEquals(
        compact, BlockStateNbt.read(new Nbt.Compound().with("", "minecraft:oak_log"), 5023));
    assertThrows(IOException.class, () -> BlockStateNbt.read("mod:unknown", 5023));
  }

  @Test
  void biomeOnly263PatchWritesValidAirStateInUntrackedSections() throws Exception {
    var op =
        new org.worldgit.core.apply.ApplyPlan.ChunkOp(
            new ChunkPos(0, 0),
            false,
            new TreeMap<>(),
            new TreeMap<>(
                Map.of(
                    19,
                    new org.worldgit.core.apply.ApplyPlan.BiomeOp(
                        19, Collections.nCopies(64, "minecraft:forest")))),
            false,
            null,
            false,
            null);
    var raw =
        org.worldgit.core.apply.ChunkNbt.apply(
            null, op, 5023, org.worldgit.core.config.IgnoreRules.none());
    var section = (Nbt.Compound) raw.list("sections").values().getFirst();
    var air = (Nbt.Compound) section.compound("block_states").list("palette").values().getFirst();
    assertEquals("minecraft:air", air.string("id"));
    assertFalse(air.containsKey("Name"));
    assertTrue(
        new org.worldgit.core.normalize.ChunkNormalizer(
                org.worldgit.core.config.IgnoreRules.none(),
                org.worldgit.core.config.EntitySemantics.OFFLINE)
            .normalize(new ChunkPos(0, 0), raw, List.of())
            .sections()
            .isEmpty());
  }

  @Test
  void catalogUsesReleaseDataVersionsAndCorrectJava() throws Exception {
    assertEquals(10, MinecraftVersion.SUPPORTED.size());
    assertEquals(5023, MinecraftVersion.byId("26.3").dataVersion());
    assertEquals(25, MinecraftVersion.byId("26.1.2").javaVersion());
    assertThrows(IOException.class, () -> MinecraftVersion.byId("1.21.10"));
  }

  @Test
  void lossConfirmationIncludesUnsupportedRegistryEntriesAndValidFallback() throws Exception {
    var blocks = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
    blocks.set(0, new BlockState("minecraft:copper_chest", new TreeMap<>()));
    blocks.set(1, new BlockState("minecraft:short_grass", new TreeMap<>()));
    var chest = new Nbt.Compound().with("id", "minecraft:copper_chest");
    var snapshot =
        new ChunkSnapshot(
            new ChunkPos(0, 0),
            5023,
            new TreeMap<>(Map.of(4, new Section(blocks, Map.of(0, Nbt.write(chest))))),
            new TreeMap<>(Map.of(4, Collections.nCopies(64, "minecraft:pale_garden"))),
            List.of(),
            null,
            null);
    try (var converter = new SnapshotConverter(5023, 3465, null, null)) {
      var result = converter.convert(snapshot);
      assertEquals("minecraft:stone", result.sections().get(4).block(0).name());
      assertEquals("minecraft:grass", result.sections().get(4).block(1).name());
      assertTrue(result.sections().get(4).blockEntities().isEmpty());
      assertEquals("minecraft:plains", result.biomes().get(4).getFirst());
    }
    var report =
        new ConversionReport(
            5023,
            3465,
            "test",
            List.of(
                new ConversionReport.Loss(
                    "block", "minecraft:copper_chest", "minecraft:stone", 1, List.of("0,64,0"))));
    assertThrows(ConversionReport.LossRequired.class, () -> report.requireAccepted(false));
    assertDoesNotThrow(() -> report.requireAccepted(true));
    assertEquals(report, ConversionReport.read(report.bytes()));
  }

  @Test
  void legacyDamageDefaultsAreRestoredOnlyForDamageableItems() throws Exception {
    for (int target : List.of(3465, 3700)) {
      var loss = new ConversionReport.Collector();
      var reverse = new ReverseNbt(new ReverseRules(5023, target, null), loss, 5023, target);
      var items = new ArrayList<Object>();
      for (String id : List.of("minecraft:diamond_sword", "minecraft:chest"))
        items.add(new Nbt.Compound().with("id", id).with("count", 1));
      items.add(new Nbt.Compound().with("id", "minecraft:diamond_sword").with("count", 1)
          .with("components", new Nbt.Compound().with("minecraft:damage", 123)));
      var converted = reverse.convert(new Nbt.Compound().with("id", "minecraft:chest")
          .with("Items", new Nbt.ListTag(10, items)), "block-entity", "chest");
      var values = converted.list("Items").values();
      assertEquals(0, ((Nbt.Compound) values.get(0)).compound("tag").get("Damage"));
      assertFalse(((Nbt.Compound) values.get(1)).compound("tag").containsKey("Damage"));
      assertEquals(123, ((Nbt.Compound) values.get(2)).compound("tag").get("Damage"));
      assertFalse(loss.report(5023, target, "test").lossy());
    }
  }

  @Test
  void componentsPreserveNameEnchantmentsAndOpaqueCustomData() throws Exception {
    var item =
        new Nbt.Compound()
            .with("id", "minecraft:diamond_sword")
            .with("count", 1)
            .with("Slot", (byte) 0)
            .with(
                "components",
                new Nbt.Compound()
                    .with(
                        "minecraft:custom_name", new Nbt.Compound().with("text", "WorldGit sword"))
                    .with(
                        "minecraft:enchantments", new Nbt.Compound().with("minecraft:sharpness", 3))
                    .with(
                        "minecraft:custom_data",
                        new Nbt.Compound()
                            .with("WorldGitFixture", new Nbt.Compound().with("number", 42))));
    var chest =
        new Nbt.Compound()
            .with("id", "minecraft:chest")
            .with("components", new Nbt.Compound())
            .with("Items", new Nbt.ListTag(10, List.of(item)));
    var loss = new ConversionReport.Collector();
    var reverse = new ReverseNbt(new ReverseRules(5023, 3465, null), loss, 5023, 3465);
    var converted = reverse.convert(chest, "block-entity", "0,64,0");
    var old = (Nbt.Compound) converted.list("Items").values().getFirst();
    assertEquals((byte) 1, old.get("Count"));
    assertEquals(
        "{\"text\":\"WorldGit sword\"}", old.compound("tag").compound("display").string("Name"));
    assertEquals(42, old.compound("tag").compound("WorldGitFixture").integer("number", 0));
    assertEquals(
        "minecraft:sharpness",
        ((Nbt.Compound) old.compound("tag").list("Enchantments").values().getFirst()).string("id"));
    assertFalse(loss.report(5023, 3465, "test").lossy());
  }

  @Test
  void experimentalBlocksAreUnavailableInNormal1206() throws Exception {
    var rules = new ReverseRules(5023, 3839, null);
    assertFalse(rules.known("block", "minecraft:crafter"));
    assertFalse(rules.known("entity", "minecraft:breeze"));
    assertTrue(rules.known("entity", "minecraft:armadillo"));
  }

  @Test
  void configurableReplacementIsValidatedAgainstTargetRegistry() throws Exception {
    Path config = temp.resolve("worldgit.yml");
    Files.writeString(config, "conversion:\n  fallbacks:\n    block: minecraft:diamond_block\n");
    var losses = new ConversionReport.Collector();
    var rules = new ReverseRules(5023, 3465, config);
    assertEquals(
        "minecraft:diamond_block",
        rules
            .block(new BlockState("minecraft:copper_chest", new TreeMap<>()), "0,64,0", losses)
            .name());
    Files.writeString(config, "conversion:\n  fallbacks:\n    block: minecraft:not_real\n");
    var invalid = new ReverseRules(5023, 3465, config);
    assertThrows(
        IOException.class,
        () ->
            invalid.block(
                new BlockState("minecraft:copper_chest", new TreeMap<>()), "0,64,0", losses));
  }
}
