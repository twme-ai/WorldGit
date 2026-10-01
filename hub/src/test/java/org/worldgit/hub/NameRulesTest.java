package org.worldgit.hub;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.worldgit.core.model.DimensionId;
import org.worldgit.hub.storage.NameRules;

class NameRulesTest {
  @Test
  void slugs() {
    assertTrue(NameRules.validSlug("my-world_1"));
    assertTrue(NameRules.validSlug("a"));
    assertFalse(NameRules.validSlug("-bad"));
    assertFalse(NameRules.validSlug("Upper"));
    assertFalse(NameRules.validSlug("a/b"));
    assertFalse(NameRules.validSlug("api"), "保留字");
    assertFalse(NameRules.validSlug("x".repeat(41)));
    assertThrows(IllegalArgumentException.class, () -> NameRules.requireSlug("../x"));
  }

  @Test
  void dimensionDirectoryRoundTrip() {
    for (String id : new String[] {"minecraft:overworld", "minecraft:the_nether", "my.mod:deep/dim", "a:b.c"}) {
      var d = new DimensionId(id);
      assertEquals(d, NameRules.dimensionFromDirectory(d.directoryName()), id);
    }
    assertNull(NameRules.dimensionFromDirectory("nodot"));
    assertNull(NameRules.dimensionFromDirectory(".x"));
    assertNull(NameRules.dimensionFromDirectory("x."));
  }
}
