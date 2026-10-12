package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;

class Fields263Test {
  @Test
  void dfuNumericTagsNormalizeToTheSame263BytesAsNativeSerialization() throws Exception {
    var furnace = new Nbt.Compound().with("id", "minecraft:furnace")
        .with("x", 0).with("y", 64).with("z", 0).with("cooking_total_time", (short) 200);
    var chunk = new Nbt.Compound().with("DataVersion", 5023)
        .with("block_entities", new Nbt.ListTag(10, List.of(furnace)));
    var normalizer = new org.worldgit.core.normalize.ChunkNormalizer(
        org.worldgit.core.config.IgnoreRules.none(), org.worldgit.core.config.EntitySemantics.OFFLINE);
    var before = normalizer.normalize(new org.worldgit.core.model.ChunkPos(0, 0), chunk, List.of());
    furnace.put("cooking_total_time", 200);
    furnace.put("lit_total_time", 0);
    furnace.put("speed_multiplier", 1f);
    furnace.put("components", new Nbt.Compound());
    var nativeOutput = normalizer.normalize(new org.worldgit.core.model.ChunkPos(0, 0), chunk, List.of());
    assertArrayEquals(before.sections().get(4).blockEntities().get(0),
        nativeOutput.sections().get(4).blockEntities().get(0));
    var ignored = new org.worldgit.core.normalize.ChunkNormalizer(
        org.worldgit.core.config.IgnoreRules.parse("field minecraft:furnace components\n"
            + "field minecraft:furnace speed_multiplier\nfield minecraft:furnace lit_total_time\n"),
        org.worldgit.core.config.EntitySemantics.OFFLINE)
        .normalize(new org.worldgit.core.model.ChunkPos(0, 0), chunk, List.of());
    var data = Nbt.read(ignored.sections().get(4).blockEntities().get(0));
    assertFalse(data.containsKey("components"));
    assertFalse(data.containsKey("speed_multiplier"));
    assertFalse(data.containsKey("lit_total_time"));
  }

  @Test
  void temporaryInvulnerabilityRespectsIgnoreOverridesAndIsAuditedDuringPhysicalDowngrade()
      throws Exception {
    var entity = new Nbt.Compound().with("id", "minecraft:cow").with("UUID", new int[] {1,2,3,4})
        .with("Pos", new Nbt.ListTag(6, List.of(1d, 64d, 1d))).with("invulnerable_time", 30);
    assertFalse(org.worldgit.core.normalize.EntityNormalizer.normalize(entity,
        org.worldgit.core.config.IgnoreRules.none()).data().containsKey("invulnerable_time"));
    assertEquals(30, org.worldgit.core.normalize.EntityNormalizer.normalize(entity,
        org.worldgit.core.config.IgnoreRules.parse("!field minecraft:cow invulnerable_time\n"))
        .data().get("invulnerable_time"));
    var loss = new ConversionReport.Collector();
    var converted = new ReverseNbt(new ReverseRules(5023, 4903, null), loss, 5023, 4903)
        .convert(entity, "entity", "cow");
    assertFalse(converted.containsKey("invulnerable_time"));
    assertTrue(loss.report(5023, 4903, "test").lossy());
    var named = new ReverseNbt(new ReverseRules(5023, 3465, null),
        new ConversionReport.Collector(), 5023, 3465).convert(
        new Nbt.Compound().with("id", "minecraft:chest")
            .with("components", new Nbt.Compound().with("minecraft:custom_name", "Named chest")),
        "block-entity", "named-chest");
    assertEquals("\"Named chest\"", named.get("CustomName"));
    assertFalse(named.containsKey("components"));
  }

  @Test
  void oldSignBehaviorIsEquivalentButDisabledOpFeaturesRequireLossConfirmation() throws Exception {
    for (String id : List.of("minecraft:sign", "minecraft:hanging_sign"))
      for (byte flag : new byte[] {0, 1}) {
        var loss = new ConversionReport.Collector();
        var reverse = new ReverseNbt(new ReverseRules(5023, 4671, null), loss, 5023, 4671);
        var sign = reverse.convert(new Nbt.Compound().with("id", id)
            .with("allow_op_features", flag), "block-entity", "sign/8,64,5");
        assertFalse(sign.containsKey("allow_op_features"));
        var report = loss.report(5023, 4671, "test");
        assertEquals(flag == 0, report.lossy());
        if (flag == 0) {
          assertTrue(report.losses().getFirst().source().contains("allow_op_features=0"));
          assertThrows(ConversionReport.LossRequired.class, () -> report.requireAccepted(false));
        }
      }
    var loss = new ConversionReport.Collector();
    new ReverseNbt(new ReverseRules(5023, 4903, null), loss, 5023, 4903)
        .convert(new Nbt.Compound().with("id", "minecraft:sign"), "block-entity", "default-sign");
    assertTrue(loss.report(5023, 4903, "test").lossy());
  }

  @Test
  void signComponentsAndEmbeddedBlockEntityDataUseTheSameReverseCodec() throws Exception {
    var text = new Nbt.Compound().with("messages", new Nbt.ListTag(8, List.of("hello", "", "", "")));
    for (int target : List.of(4903, 4671, 4189, 3700, 3465)) {
      var loss = new ConversionReport.Collector();
      var reverse = new ReverseNbt(new ReverseRules(5023, target, null), loss, 5023, target);
      var item = new Nbt.Compound().with("id", "minecraft:oak_sign").with("count", 1)
          .with("components", new Nbt.Compound().with("minecraft:sign_text_front", text)
              .with("minecraft:waxed", new Nbt.Compound()));
      var chest = new Nbt.Compound().with("id", "minecraft:chest")
          .with("Items", new Nbt.ListTag(10, List.of(item)));
      var result = (Nbt.Compound) reverse.convert(chest, "block-entity", "chest")
          .list("Items").values().getFirst();
      var be = target <= 3700 ? result.compound("tag").compound("BlockEntityTag")
          : result.compound("components").compound("minecraft:block_entity_data");
      assertEquals("minecraft:sign", be.string("id"));
      assertEquals((byte) 1, be.get("is_waxed"));
      assertEquals(target <= 4189 ? "\"hello\"" : "hello",
          be.compound("front_text").list("messages").values().getFirst());
      assertFalse(be.containsKey("allow_op_features"));
      assertTrue(loss.report(5023, target, "test").losses().stream()
          .anyMatch(l -> l.source().contains("allow_op_features")));
    }
  }

  @Test
  void furnaceFieldBoundaryPreservesNonzeroValuesIn1214() throws Exception {
    for (int target : List.of(3955, 4189, 4440)) {
      var loss = new ConversionReport.Collector();
      var reverse = new ReverseNbt(new ReverseRules(5023, target, null), loss, 5023, target);
      var furnace = reverse.convert(new Nbt.Compound().with("id", "minecraft:furnace")
          .with("cooking_total_time", 327).with("cooking_time_spent", 21)
          .with("lit_time_remaining", 1234).with("lit_total_time", 1600), "block-entity", "furnace");
      if (target == 3955) {
        assertEquals((short) 327, furnace.get("CookTimeTotal"));
        assertEquals((short) 21, furnace.get("CookTime"));
        assertEquals((short) 1234, furnace.get("BurnTime"));
        assertFalse(furnace.containsKey("lit_total_time"));
      } else {
        assertEquals((short) 327, furnace.get("cooking_total_time"));
        assertEquals((short) 21, furnace.get("cooking_time_spent"));
        assertEquals((short) 1234, furnace.get("lit_time_remaining"));
        assertEquals((short) 1600, furnace.get("lit_total_time"));
        assertFalse(furnace.containsKey("CookTimeTotal"));
      }
      var report = loss.report(5023, target, "test");
      if (target == 3955) {
        assertEquals(1, report.losses().size());
        assertEquals("block-entity-field", report.losses().getFirst().kind());
        assertEquals("lit_total_time=1600", report.losses().getFirst().source());
        assertEquals("recalculate from fuel", report.losses().getFirst().replacement());
      } else assertFalse(report.lossy());
    }
  }

  @Test
  void numericWidthsAreRestoredAndOverflowIsReported() throws Exception {
    for (int target : List.of(4903, 4671, 4189, 3955, 3839, 3465)) {
      var loss = new ConversionReport.Collector();
      var reverse = new ReverseNbt(new ReverseRules(5023, target, null), loss, 5023, target);
      var furnace = reverse.convert(new Nbt.Compound().with("id", "minecraft:furnace")
          .with("cooking_total_time", 200).with("speed_multiplier", 1f), "block-entity", "furnace");
      assertEquals((short) 200, furnace.get(target <= 3955 ? "CookTimeTotal" : "cooking_total_time"));
      assertFalse(furnace.containsKey("speed_multiplier"));
      var stand = reverse.convert(new Nbt.Compound().with("id", "minecraft:brewing_stand")
          .with("Fuel", 20).with("BrewTime", 200).with("total_brew_time", 400)
          .with("total_fuel", 20), "block-entity", "stand");
      assertEquals((byte) 20, stand.get("Fuel"));
      assertEquals((short) 200, stand.get("BrewTime"));
      assertFalse(stand.containsKey("total_brew_time"));
      assertFalse(loss.report(5023, target, "test").lossy());
      var overflowing = reverse.convert(new Nbt.Compound().with("id", "minecraft:furnace")
          .with("cooking_total_time", 40000).with("speed_multiplier", 2f), "block-entity", "overflow");
      assertEquals(Short.MAX_VALUE,
          overflowing.get(target <= 3955 ? "CookTimeTotal" : "cooking_total_time"));
      assertTrue(loss.report(5023, target, "test").lossy());
    }
  }
}
