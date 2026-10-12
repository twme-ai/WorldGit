package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;

class Paper121BoundariesTest {
  private static Object parse(String json) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
  }

  private final ConversionReport.Collector losses = new ConversionReport.Collector();

  private Nbt.Compound convert(int from, int to, Nbt.Compound input, String kind) throws Exception {
    return new ReverseNbt(new ReverseRules(from, to, null), losses, from, to)
        .convert(input, kind, "chunk 0,0/fixture");
  }

  private Nbt.Compound item(int from, int to, Nbt.Compound components) throws Exception {
    var chest = new Nbt.Compound().with("id", "minecraft:chest").with("Items",
        new Nbt.ListTag(10, List.of(new Nbt.Compound().with("id", "minecraft:diamond_sword")
            .with("count", 1).with("components", components))));
    return ((Nbt.Compound) convert(from, to, chest, "block-entity")
        .list("Items").values().getFirst()).compound("components");
  }

  @Test void boatSplitAndAttributesKeepExact1211And1214Forms() throws Exception {
    for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
        "mangrove", "cherry", "bamboo")) {
      for (boolean chest : List.of(false, true)) {
        String suffix = wood.equals("bamboo") ? "raft" : "boat";
        String id = "minecraft:" + wood + "_" + (chest ? "chest_" : "") + suffix;
        var input = new Nbt.Compound().with("id", id);
        var older = convert(4189, 3955, input, "entity");
        assertEquals(chest ? "minecraft:chest_boat" : "minecraft:boat", older.string("id"));
        assertEquals(wood, older.string("Type"));
        var modern = convert(4440, 4189, input, "entity");
        assertEquals(id, modern.string("id"));
        assertFalse(modern.containsKey("Type"));
      }
    }
    var attribute = new Nbt.Compound().with("id", "minecraft:max_health").with("base", 37.5d);
    var input = new Nbt.Compound().with("id", "minecraft:armor_stand")
        .with("attributes", new Nbt.ListTag(10, List.of(attribute)));
    for (int target : List.of(3955, 4189)) {
      var output = convert(4440, target, input, "entity");
      var result = (Nbt.Compound) output.list("attributes").values().getFirst();
      assertEquals(target == 3955 ? "minecraft:generic.max_health" : "minecraft:max_health",
          result.string("id"));
      assertEquals(37.5d, result.get("base"));
      assertFalse(output.containsKey("Attributes"));
    }
    assertFalse(losses.report(4440, 3955, "test").lossy());
  }

  @Test void wrappedAttributeModifiersPreserveTooltipAndUseTargetRegistry() throws Exception {
    var modifier = new Nbt.Compound().with("type", "minecraft:attack_damage")
        .with("id", "worldgit:fixture").with("amount", 3d).with("operation", "add_value")
        .with("slot", "mainhand");
    var wrapped = new Nbt.Compound().with("modifiers", new Nbt.ListTag(10, List.of(modifier)))
        .with("show_in_tooltip", (byte) 0);
    var output = item(4189, 3955, new Nbt.Compound().with("minecraft:attribute_modifiers", wrapped))
        .compound("minecraft:attribute_modifiers");
    assertEquals((byte) 0, output.get("show_in_tooltip"));
    var result = (Nbt.Compound) output.list("modifiers").values().getFirst();
    assertEquals("minecraft:generic.attack_damage", result.string("type"));
    assertEquals("worldgit:fixture", result.string("id"));
    assertEquals(3d, result.get("amount"));
    assertFalse(losses.report(4189, 3955, "test").lossy());
  }

  @Test void equipmentSlotsAndDefaultDropChancesSurviveNativeReserialization() throws Exception {
    var sword = new Nbt.Compound().with("id", "minecraft:diamond_sword").with("count", 1);
    var armor = new Nbt.Compound().with("id", "minecraft:iron_chestplate").with("count", 1);
    var input = new Nbt.Compound().with("id", "minecraft:zombie")
        .with("equipment", new Nbt.Compound().with("mainhand", sword).with("chest", armor))
        .with("drop_chances", new Nbt.Compound().with("mainhand", .75f).with("chest", .5f));
    var output = convert(4440, 4189, input, "entity");
    assertFalse(output.containsKey("equipment"));
    assertFalse(output.containsKey("drop_chances"));
    assertEquals(2, output.list("HandItems").values().size());
    assertEquals(4, output.list("ArmorItems").values().size());
    assertEquals("minecraft:diamond_sword", ((Nbt.Compound) output.list("HandItems").values().get(0)).string("id"));
    assertEquals("minecraft:iron_chestplate", ((Nbt.Compound) output.list("ArmorItems").values().get(2)).string("id"));
    assertEquals(List.of(.75f, .085f), output.list("HandDropChances").values());
    assertEquals(List.of(.085f, .085f, .5f, .085f), output.list("ArmorDropChances").values());
    var defaults = convert(4440, 4189, new Nbt.Compound().with("id", "minecraft:zombie"), "entity");
    assertEquals(List.of(.085f, .085f), defaults.list("HandDropChances").values());
    var stand = convert(4440, 4189, new Nbt.Compound().with("id", "minecraft:armor_stand"), "entity");
    assertEquals(4, stand.list("ArmorItems").values().size());
    assertFalse(stand.containsKey("HandDropChances"));
    var boat = convert(4440, 4189, new Nbt.Compound().with("id", "minecraft:oak_boat"), "entity");
    assertFalse(boat.containsKey("HandItems"));
    assertFalse(losses.report(4440, 4189, "test").lossy());
    assertTrue(input.containsKey("equipment"));
  }

  @Test void saddleAndBodySlotsPreserveDataAndReportUnrepresentableFields() throws Exception {
    var saddle = new Nbt.Compound().with("id", "minecraft:saddle").with("count", 1);
    for (String id : List.of("minecraft:pig", "minecraft:strider", "minecraft:horse")) {
      var output = convert(4440, 4189, new Nbt.Compound().with("id", id)
          .with("equipment", new Nbt.Compound().with("saddle", saddle))
          .with("drop_chances", new Nbt.Compound().with("saddle", 2f)), "entity");
      if (id.equals("minecraft:horse")) assertEquals("minecraft:saddle", output.compound("SaddleItem").string("id"));
      else { assertEquals((byte) 1, output.get("Saddle")); assertFalse(output.containsKey("SaddleItem")); }
    }
    var body = new Nbt.Compound().with("id", "minecraft:wolf_armor").with("count", 1);
    var wolf = convert(4440, 4189, new Nbt.Compound().with("id", "minecraft:wolf")
        .with("equipment", new Nbt.Compound().with("body", body))
        .with("drop_chances", new Nbt.Compound().with("body", .5f)), "entity");
    assertEquals("minecraft:wolf_armor", wolf.compound("body_armor_item").string("id"));
    assertEquals(.5f, wolf.get("body_armor_drop_chance"));
    assertFalse(losses.report(4440, 4189, "test").lossy());
    saddle.put("components", new Nbt.Compound().with("minecraft:custom_data", new Nbt.Compound().with("opaque", 7)));
    convert(4440, 4189, new Nbt.Compound().with("id", "minecraft:pig")
        .with("equipment", new Nbt.Compound().with("saddle", saddle).with("unknown", body))
        .with("drop_chances", new Nbt.Compound().with("saddle", .5f)), "entity");
    var report = losses.report(4440, 4189, "test");
    assertEquals(Set.of("equipment", "equipment-slot", "equipment-drop-chance"),
        report.losses().stream().map(ConversionReport.Loss::kind).collect(java.util.stream.Collectors.toSet()));
  }

  @Test void absentModernSaddleDropChanceUsesNativeDefaultAndRequiresLossApproval() throws Exception {
    var input = new Nbt.Compound().with("id", "minecraft:pig").with("equipment",
        new Nbt.Compound().with("saddle", new Nbt.Compound().with("id", "minecraft:saddle").with("count", 1)));
    convert(4440, 4189, input, "entity");
    var report = losses.report(4440, 4189, "test");
    assertEquals(1, report.losses().size());
    assertEquals("equipment-drop-chance", report.losses().getFirst().kind());
    assertEquals("saddle=0.085", report.losses().getFirst().source());
    assertEquals("1.0", report.losses().getFirst().replacement());
  }

  @Test void nativeTextConvertsNamesSignsLoreAndFilteredBookPagesWithoutTouchingCustomData() throws Exception {
    var text = new Nbt.Compound().with("text", "WorldGit").with("color", "gold").with("italic", (byte) 0);
    var page = new Nbt.Compound().with("raw", text).with("filtered", "filtered text");
    var opaque = new Nbt.Compound().with("text", text).with("equipment", new Nbt.Compound().with("untouched", 7));
    var input = new Nbt.Compound().with("minecraft:custom_name", text)
        .with("minecraft:lore", new Nbt.ListTag(10, List.of(text)))
        .with("minecraft:written_book_content", new Nbt.Compound().with("pages", new Nbt.ListTag(10, List.of(page))))
        .with("minecraft:custom_data", opaque);
    var output = item(4440, 4189, input);
    var json = parse((String) output.get("minecraft:custom_name"));
    assertEquals(Map.of("text", "WorldGit", "color", "gold", "italic", false), json);
    assertEquals(8, output.list("minecraft:lore").type());
    var convertedPage = (Nbt.Compound) output.compound("minecraft:written_book_content").list("pages").values().getFirst();
    assertEquals(json, parse(convertedPage.string("raw")));
    assertEquals("filtered text", parse(convertedPage.string("filtered")));
    assertArrayEquals(Nbt.write(opaque), Nbt.write(output.compound("minecraft:custom_data")));
    var sign = convert(4440, 4189, new Nbt.Compound().with("id", "minecraft:sign")
        .with("front_text", new Nbt.Compound().with("messages", new Nbt.ListTag(10, List.of(text)))), "block-entity");
    assertEquals(json, parse((String) sign.compound("front_text").list("messages").values().getFirst()));
    assertFalse(losses.report(4440, 4189, "test").lossy());
  }

  @Test void furnaceBoundaryAndTntFuseUse1214Schema() throws Exception {
    var furnace = new Nbt.Compound().with("id", "minecraft:furnace")
        .with("lit_time_remaining", (short) 0).with("lit_total_time", (short) 200)
        .with("cooking_time_spent", (short) 0).with("cooking_total_time", (short) 100);
    var sameSchema = convert(4440, 4189, furnace, "block-entity");
    assertEquals((short) 200, sameSchema.get("lit_total_time"));
    assertEquals((short) 100, sameSchema.get("cooking_total_time"));
    assertFalse(sameSchema.containsKey("CookTimeTotal"));
    assertFalse(losses.report(4440, 4189, "test").lossy());
    var older = convert(4189, 3955, furnace, "block-entity");
    assertEquals((short) 100, older.get("CookTimeTotal"));
    assertEquals((short) 0, older.get("BurnTime"));
    assertFalse(older.containsKey("lit_total_time"));
    assertTrue(losses.report(4189, 3955, "test").losses().stream()
        .anyMatch(l -> l.source().equals("lit_total_time=200")));
    var minecart = new Nbt.Compound().with("id", "minecraft:tnt_minecart").with("fuse", 80);
    assertEquals(80, convert(4440, 4189, minecart, "entity").get("fuse"));
    assertEquals(80, convert(4189, 3955, minecart, "entity").get("TNTFuse"));
  }

  private static Nbt.Compound boat(String id, String wood) {
    return new Nbt.Compound().with("id", id).with("Type", wood).with("UUID", new int[] {1, 2, 3, 4})
        .with("Pos", new Nbt.ListTag(6, List.of(12.5d, 70d, 12.5d)));
  }

  private static Nbt.Compound normalized(int version, Nbt.Compound entity) throws Exception {
    return new org.worldgit.core.normalize.ChunkNormalizer(org.worldgit.core.config.IgnoreRules.none(),
        org.worldgit.core.config.EntitySemantics.OFFLINE)
        .normalize(new org.worldgit.core.model.ChunkPos(0, 0),
            new Nbt.Compound().with("DataVersion", version), List.of(entity))
        .entities().getFirst().data();
  }

  @Test void modernNativeEquipmentRemovesOnlyMatchingDfuRemnants() throws Exception {
    var sword = new Nbt.Compound().with("id", "minecraft:diamond_sword").with("count", 1);
    var helmet = new Nbt.Compound().with("id", "minecraft:diamond_helmet").with("count", 1);
    var input = boat("minecraft:armor_stand", "ignored"); input.remove("Type");
    input.put("equipment", new Nbt.Compound().with("mainhand", sword).with("head", helmet));
    input.put("HandItems", new Nbt.ListTag(10, List.of(sword, new Nbt.Compound())));
    input.put("ArmorItems", new Nbt.ListTag(10, List.of(new Nbt.Compound(), new Nbt.Compound(), new Nbt.Compound(), helmet)));
    input.put("custom_data", new Nbt.Compound().with("HandItems", input.get("HandItems")));
    var nativeForm = Nbt.copy(input); nativeForm.remove("HandItems"); nativeForm.remove("ArmorItems");
    assertArrayEquals(Nbt.write(normalized(4440, nativeForm)), Nbt.write(normalized(4440, input)));
    assertTrue(input.containsKey("HandItems"));
    assertTrue(normalized(4189, input).containsKey("HandItems"));
    assertTrue(normalized(4440, input.with("id", "mod:armor_stand")).containsKey("ArmorItems"));
    input.put("id", "minecraft:armor_stand");
    input.put("HandItems", new Nbt.ListTag(10, List.of(helmet, new Nbt.Compound())));
    assertTrue(normalized(4440, input).containsKey("HandItems"));
    assertTrue(normalized(4440, nativeForm).compound("custom_data").containsKey("HandItems"));
  }

  @Test void modernSaddleAndPigVariantDefaultsAreNamespaceScopedAndRecursive() throws Exception {
    var saddle = new Nbt.Compound().with("id", "minecraft:saddle").with("count", 1);
    var pig = boat("minecraft:pig", "ignored"); pig.remove("Type");
    pig.put("equipment", new Nbt.Compound().with("saddle", saddle));
    pig.put("saddle", saddle);
    pig.put("HandItems", new Nbt.ListTag(10, List.of(new Nbt.Compound(), new Nbt.Compound())));
    var nativeForm = Nbt.copy(pig); nativeForm.remove("saddle"); nativeForm.remove("HandItems");
    nativeForm.put("variant", "minecraft:temperate");
    assertArrayEquals(Nbt.write(normalized(4440, nativeForm)), Nbt.write(normalized(4440, pig)));
    var changed = Nbt.copy(nativeForm).with("variant", "minecraft:warm");
    assertEquals("minecraft:warm", normalized(4440, changed).string("variant"));
    var custom = Nbt.copy(nativeForm).with("id", "mod:pig");
    assertEquals("minecraft:temperate", normalized(4440, custom).string("variant"));
    var mismatch = Nbt.copy(pig).with("saddle", Nbt.copy(saddle).with("count", 2));
    assertEquals(2, normalized(4440, mismatch).compound("saddle").integer("count", 0));
    var root = boat("minecraft:oak_boat", "oak")
        .with("Passengers", new Nbt.ListTag(10, List.of(pig)));
    var passenger = (Nbt.Compound) normalized(4440, root).list("Passengers").values().getFirst();
    assertFalse(passenger.containsKey("saddle"));
    assertFalse(passenger.containsKey("HandItems"));
    assertTrue(pig.containsKey("saddle"));
  }

  @Test void modernBlockEntityEmptyComponentPatchMatchesNativeSaveWithoutDroppingContent() {
    for (String id : List.of("minecraft:chest", "minecraft:sign")) {
      var input = new Nbt.Compound().with("id", id).with("components", new Nbt.Compound());
      var canonical = NativeNbtForms.blockEntity(input, 4440);
      var missing = new Nbt.Compound().with("id", id);
      assertArrayEquals(Nbt.write(canonical), Nbt.write(NativeNbtForms.blockEntity(missing, 4440)));
      assertTrue(canonical.compound("components").isEmpty());
      assertTrue(input.containsKey("components"));
      assertFalse(NativeNbtForms.blockEntity(missing, 4189).containsKey("components"));
      input.put("components", new Nbt.Compound().with("minecraft:custom_name", "preserve"));
      assertEquals("preserve", NativeNbtForms.blockEntity(input, 4440).compound("components").string("minecraft:custom_name"));
    }
    var custom = new Nbt.Compound().with("id", "mod:chest").with("components", new Nbt.Compound());
    assertTrue(NativeNbtForms.blockEntity(custom, 4440).containsKey("components"));
    custom.remove("components");
    assertFalse(NativeNbtForms.blockEntity(custom, 4440).containsKey("components"));
  }

  @Test void fixedWoodBoatNativeCodecOmitsOnlyItsRedundantTypeIncludingPassengers() throws Exception {
    for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
        "mangrove", "cherry", "bamboo", "pale_oak")) {
      for (boolean chest : List.of(false, true)) {
        String id = "minecraft:" + wood + "_" + (chest ? "chest_" : "") + (wood.equals("bamboo") ? "raft" : "boat");
        var input = boat(id, wood);
        var nativeForm = Nbt.copy(input); nativeForm.remove("Type");
        assertArrayEquals(Nbt.write(normalized(4189, nativeForm)), Nbt.write(normalized(4189, input)));
        assertEquals(wood, input.string("Type"));
        assertEquals(wood, normalized(3955, input).string("Type"));
      }
    }
    assertEquals("oak", normalized(4189, boat("minecraft:boat", "oak")).string("Type"));
    assertEquals("oak", normalized(4189, boat("mod:oak_boat", "oak")).string("Type"));
    assertEquals("spruce", normalized(4189, boat("minecraft:oak_boat", "spruce")).string("Type"));
    var passenger = boat("minecraft:oak_chest_boat", "oak").with("UUID", new int[] {5, 6, 7, 8});
    var root = boat("minecraft:oak_boat", "oak")
        .with("Passengers", new Nbt.ListTag(10, List.of(passenger)))
        .with("custom_data", new Nbt.Compound().with("Type", "opaque"));
    var result = normalized(4440, root);
    assertFalse(result.containsKey("Type"));
    assertFalse(((Nbt.Compound) result.list("Passengers").values().getFirst()).containsKey("Type"));
    assertEquals("opaque", result.compound("custom_data").string("Type"));
    assertEquals("oak", passenger.string("Type"));
  }

  @Test void projectedBoatMatchesNativeResaveWhenVanillaDfuKeepsLegacyType() throws Exception {
    var data = boat("minecraft:boat", "oak");
    var source = new org.worldgit.core.model.ChunkSnapshot(new org.worldgit.core.model.ChunkPos(0, 0),
        3955, new TreeMap<>(), new TreeMap<>(), List.of(new org.worldgit.core.model.EntitySnapshot(
            org.worldgit.core.normalize.EntityNormalizer.uuid(data), data)), null, null);
    NbtUpgrader dfu = (type, input, from, to) -> {
      var output = Nbt.copy(input);
      if (type.equals("ENTITY_CHUNK"))
        for (Object value : output.list("Entities").values())
          ((Nbt.Compound) value).put("id", "minecraft:oak_boat");
      return output;
    };
    try (var converter = new SnapshotConverter(3955, 4189, null, dfu)) {
      var projected = converter.convert(source).entities().getFirst().data();
      assertFalse(projected.containsKey("Type"));
      var nativeForm = Nbt.copy(data).with("id", "minecraft:oak_boat"); nativeForm.remove("Type");
      assertArrayEquals(Nbt.write(normalized(4189, nativeForm)), Nbt.write(projected));
      assertEquals("oak", data.string("Type"));
    }
  }
}
