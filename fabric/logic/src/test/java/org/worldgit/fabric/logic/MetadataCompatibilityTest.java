package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;

class MetadataCompatibilityTest {
  private static Nbt.Compound packs(String... enabled) {
    return new Nbt.Compound().with("Enabled", new Nbt.ListTag(8, new ArrayList<>(List.of(enabled))))
        .with("Disabled", new Nbt.ListTag(8, List.of()));
  }

  @Test void platformMarkersDoNotRejectPortableSnapshots() {
    var target = packs("vanilla", "file/building");
    for (String marker : List.of("paper", "fabric-convention-tags-v2")) {
      var current = packs("vanilla", marker, "file/building");
      assertTrue(MetadataCompatibility.equivalent("DataPacks", target, current));
      assertTrue(MetadataCompatibility.equivalent("DataPacks", current, target));
      assertEquals(3, current.list("Enabled").values().size());
    }
  }

  @Test void realPackChangesOrderAndOtherMetadataStillDiffer() {
    var target = packs("vanilla", "file/building");
    assertFalse(MetadataCompatibility.equivalent("DataPacks", target, packs("vanilla")));
    assertFalse(MetadataCompatibility.equivalent("DataPacks", target, packs("file/building", "vanilla")));
    var disabled = packs("vanilla", "file/building");
    disabled.put("Disabled", new Nbt.ListTag(8, List.of("file/other")));
    assertFalse(MetadataCompatibility.equivalent("DataPacks", target, disabled));
    assertFalse(MetadataCompatibility.equivalent("DataPacks", target, null));
    assertFalse(MetadataCompatibility.equivalent("Other", target, packs("vanilla", "paper", "file/building")));
  }
}
