package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.SavedData;

class SavedDataReverseTest {
  @Test void forcedTicketsKeepPositionsAndAuditLevelsThatLegacyCannotRepresent() throws Exception {
    var ticket = new Nbt.Compound().with("chunk_pos", new int[] {-1, 2})
        .with("type", "minecraft:forced").with("level", 30).with("ticks_left", Long.MIN_VALUE);
    var root = new Nbt.Compound().with("data", new Nbt.Compound().with("tickets", new Nbt.ListTag(10, List.of(ticket))));
    var loss = new ConversionReport.Collector();
    SavedDataReverse.convert(root, "SAVED_DATA_TICKETS", 4440, 4189, loss);
    assertArrayEquals(new long[] {(2L << 32) | 0xffffffffL}, (long[]) root.compound("data").get("Forced"));
    var report = loss.report(4440, 4189, "test");
    assertEquals(1, report.losses().size());
    assertEquals("chunk-ticket-level", report.losses().getFirst().kind());
    assertEquals("level=30", report.losses().getFirst().source());
    assertEquals("31", report.losses().getFirst().replacement());
  }
  @Test
  void raidsAndForcedChunksRemainReadableByLegacyCodecs() throws Exception {
    var raid =
        new Nbt.Compound()
            .with("id", 4)
            .with("center", new int[] {2, 64, -3})
            .with("started", (byte) 1)
            .with("total_health", 12f);
    var root =
        new Nbt.Compound()
            .with(
                "data",
                new Nbt.Compound()
                    .with("next_id", 5)
                    .with("raids", new Nbt.ListTag(10, List.of(raid))));
    var loss = new ConversionReport.Collector();
    SavedDataReverse.convert(root, "SAVED_DATA_RAIDS", 5023, 3465, loss);
    assertEquals(5, root.compound("data").integer("NextAvailableID", 0));
    var old = (Nbt.Compound) root.compound("data").list("Raids").values().getFirst();
    assertEquals(4, old.integer("Id", 0));
    assertEquals(-3, old.integer("CZ", 0));
    assertEquals(12f, old.get("TotalHealth"));
    assertFalse(old.containsKey("center"));
    var ticket =
        new Nbt.Compound().with("chunk_pos", new int[] {-1, 2}).with("type", "minecraft:forced");
    var chunks =
        new Nbt.Compound()
            .with("data", new Nbt.Compound().with("tickets", new Nbt.ListTag(10, List.of(ticket))));
    SavedDataReverse.convert(chunks, "SAVED_DATA_TICKETS", 5023, 3465, loss);
    assertArrayEquals(
        new long[] {(2L << 32) | 0xffffffffL}, (long[]) chunks.compound("data").get("Forced"));
    assertFalse(loss.report(5023, 3465, "test").lossy());
  }

  @Test
  void freshRaidFilesSupplyRequiredRuntimeTickAndPreserveExistingTick() {
    for (int version : List.of(3465, 4189, 4671, 5023)) {
      String field = version <= 4189 ? "Tick" : "tick";
      var target = new Nbt.Compound().with("DataVersion", version).with("data", new Nbt.Compound());
      var current = new Nbt.Compound().with("data", new Nbt.Compound().with(field, 100));
      assertEquals(
          100,
          SavedData.materialize(Path.of("world/data/minecraft/raids.dat"), target, current)
              .compound("data")
              .integer(field, -1));
    }
  }
}
