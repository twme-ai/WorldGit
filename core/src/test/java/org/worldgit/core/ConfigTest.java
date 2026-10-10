package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.worldgit.core.config.*;

class ConfigTest {
  @Test
  void allIgnoreSyntaxAndPrecedence() throws Exception {
    var rules =
        IgnoreRules.parse(
            "area -20 0 -20 20 100 20\n"
                + "!area 0 0 0 1 1 1\n"
                + "entity #minecraft:arrows\n"
                + "entity minecraft:zombie !persistent in area 100 0 0 200 100 100\n"
                + "!entity minecraft:arrow in area 0 50 0 10 90 10\n"
                + "field * Brain Paper.*\n"
                + "!field minecraft:zombie Brain\n");
    assertTrue(rules.ignoredBlock(-10, 80, -10));
    assertFalse(rules.ignoredBlock(0, 0, 0));
    var arrow = TestWorlds.entity(UUID.randomUUID(), 3, false);
    arrow.put("id", "minecraft:arrow");
    assertFalse(rules.ignoredEntity(arrow, EntitySemantics.OFFLINE));
    var zombie = TestWorlds.entity(UUID.randomUUID(), 130, false);
    assertTrue(rules.ignoredEntity(zombie, EntitySemantics.OFFLINE));
    zombie.put("PersistenceRequired", (byte) 1);
    assertFalse(rules.ignoredEntity(zombie, EntitySemantics.OFFLINE));
    assertFalse(rules.ignoredField("minecraft:zombie", "Brain", true));
    assertTrue(rules.ignoredField("minecraft:zombie", "Paper.foo", false));
    var player = TestWorlds.entity(UUID.randomUUID(), 300, false);
    player.put("id", "minecraft:player");
    assertTrue(IgnoreRules.parse("!entity *").ignoredEntity(player, EntitySemantics.OFFLINE));
  }

  @Test
  void invalidLinesAndYaml() throws Exception {
    assertTrue(
        assertThrows(IOException.class, () -> IgnoreRules.parse("\nentity * what"))
            .getMessage()
            .contains("第 2 行"));
    assertThrows(IOException.class, () -> IgnoreRules.parse("dimension minecraft:overworld"));
    assertThrows(IOException.class, () -> IgnoreRules.parse("area 1 2 3"));
    assertEquals(WorldGitConfig.Track.ALL, WorldGitConfig.readRepo("", "test").track());
    assertEquals(
        WorldGitConfig.Track.MODIFIED_ONLY,
        WorldGitConfig.readRepo(
                WorldGitConfig.write(new WorldGitConfig.Repo(WorldGitConfig.Track.MODIFIED_ONLY)),
                "test")
            .track());
    for (String text :
        List.of(
            "track: ALL",
            "track: true",
            "track: all\ntrack: all",
            "unknown: 1",
            "[]",
            "!!java.lang.Runtime {}"))
      assertThrows(IOException.class, () -> WorldGitConfig.readRepo(text, "bad.yml"));
    assertEquals(
        "colorblind",
        WorldGitConfig.readLocal("palette: colorblind\nentity-tolerance: 2.5", "test").palette());
    assertThrows(IOException.class, () -> WorldGitConfig.readLocal("entity-tolerance: -1", "test"));
  }

  @Test
  void templatesExactFromSpec() throws Exception {
    var creative = IgnoreRules.parse(IgnoreTemplates.load("creative"));
    var survival = IgnoreRules.parse(IgnoreTemplates.load("survival"));
    var e = TestWorlds.entity(UUID.randomUUID(), 0, false);
    assertTrue(creative.ignoredEntity(e, EntitySemantics.OFFLINE));
    assertTrue(survival.ignoredEntity(e, EntitySemantics.OFFLINE));
    e.put("CustomName", "display");
    assertFalse(creative.ignoredEntity(e, EntitySemantics.OFFLINE));
    assertFalse(survival.ignoredEntity(e, EntitySemantics.OFFLINE));
    e.put("id", "minecraft:item");
    assertTrue(creative.ignoredEntity(e, EntitySemantics.OFFLINE));
    assertTrue(survival.ignoredEntity(e, EntitySemantics.OFFLINE));
  }
}
