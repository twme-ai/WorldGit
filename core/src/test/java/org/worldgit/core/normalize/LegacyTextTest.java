package org.worldgit.core.normalize;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;

class LegacyTextTest {
  private Nbt.Compound chest(String name, String lore) {
    return new Nbt.Compound().with("id", "minecraft:chest").with("Items",
        new Nbt.ListTag(10, List.of(new Nbt.Compound().with("id", "minecraft:diamond_sword")
            .with("Count", (byte) 1).with("tag", new Nbt.Compound().with("display",
                new Nbt.Compound().with("Name", name)
                    .with("Lore", new Nbt.ListTag(8, List.of(lore))))))));
  }

  @Test
  void equivalentTextSerializationsHaveEqualBytesWithoutDroppingStyle() throws Exception {
    var before = chest("{\"text\":\"WorldGit 劍\"}",
        "{\"italic\":false,\"text\":\"Lore\",\"extra\":[{\"text\":\"!\"}]}");
    var after = chest("\"WorldGit 劍\"",
        "{\"extra\":[\"!\"],\"text\":\"Lore\",\"italic\":false}");
    assertArrayEquals(Nbt.write(LegacyText.normalize(before)), Nbt.write(LegacyText.normalize(after)));
    assertNotEquals(Arrays.toString(Nbt.write(LegacyText.normalize(before))),
        Arrays.toString(Nbt.write(LegacyText.normalize(chest("\"WorldGit 劍\"", "\"Lore!\"")))));
    assertEquals("{\"text\":\"WorldGit 劍\"}", ((Nbt.Compound) before.list("Items").values().getFirst())
        .compound("tag").compound("display").string("Name"));
  }

  @Test
  void canonicalizationPreservesOpaqueDataAndInvalidText() throws Exception {
    var custom = chest("{\"text\":\"opaque\"}", "\"opaque lore\"");
    var input = new Nbt.Compound().with("id", "minecraft:armor_stand")
        .with("CustomName", "{\"text\":\"hello\"} trailing").with("data", custom)
        .with("equipment", new Nbt.Compound().with("head",
            new Nbt.Compound().with("id", "minecraft:stone").with("count", 1)
                .with("components", new Nbt.Compound().with("minecraft:custom_data", custom)
                    .with("minecraft:custom_name", "{\"text\":\"head\"}"))));
    var output = LegacyText.normalize(input);
    assertEquals(input.string("CustomName"), output.string("CustomName"));
    assertArrayEquals(Nbt.write(custom), Nbt.write(output.compound("data")));
    var components = output.compound("equipment").compound("head").compound("components");
    assertArrayEquals(Nbt.write(custom), Nbt.write(components.compound("minecraft:custom_data")));
    assertEquals("\"head\"", components.string("minecraft:custom_name"));
  }

  @Test
  void signAndPassengerNamesSurviveNativeReserialization() throws Exception {
    var before = new Nbt.Compound().with("id", "minecraft:sign").with("front_text",
        new Nbt.Compound().with("messages", new Nbt.ListTag(8, List.of("{\"text\":\"\"}"))))
        .with("Passengers", new Nbt.ListTag(10, List.of(new Nbt.Compound()
            .with("id", "minecraft:armor_stand").with("CustomName", "{\"text\":\"name\"}"))));
    var after = Nbt.copy(before);
    after.compound("front_text").put("messages", new Nbt.ListTag(8, List.of("\"\"")));
    ((Nbt.Compound) after.list("Passengers").values().getFirst()).put("CustomName", "\"name\"");
    assertArrayEquals(Nbt.write(LegacyText.normalize(before)), Nbt.write(LegacyText.normalize(after)));
  }
}
