package org.worldgit.core.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.version.ConversionReport;

class ConversionJournalTest {
  @TempDir Path temp;

  private Map<?, ?> roundtrip(ConversionReport report) throws Exception {
    Path path = temp.resolve("apply-state.yml");
    var conversions = WorldOperations.conversionJournal(Map.of("base", report, "theirs", report));
    OperationState.write(path, Map.of("state", "COMPLETE", "version-conversions", conversions));
    assertFalse(OperationState.partial(temp));
    return (Map<?, ?>) OperationState.read(path).get("version-conversions");
  }

  @Test void multipleLosslessConversionsRemainReadableByTheAliasFreeLoader() throws Exception {
    var report = new ConversionReport(3700, 5023, "fixture", List.of());
    var restored = roundtrip(report);
    assertEquals(2, restored.size());
    for (var value : restored.values()) {
      var row = (Map<?, ?>) value;
      assertEquals(3700, row.get("from")); assertEquals(5023, row.get("to"));
      assertTrue(((List<?>) row.get("losses")).isEmpty());
    }
  }

  @Test void sharedLossSamplesAreRepeatedWithoutYamlAliases() throws Exception {
    var loss = new ConversionReport.Loss("block", "minecraft:new", "minecraft:stone", 3,
        List.of("0,64,0"));
    var restored = roundtrip(new ConversionReport(5023, 3700, "fixture", List.of(loss, loss)));
    for (var value : restored.values()) {
      var losses = (List<?>) ((Map<?, ?>) value).get("losses");
      assertEquals(2, losses.size());
      for (var item : losses) {
        var row = (Map<?, ?>) item;
        assertEquals(3L, ((Number) row.get("count")).longValue());
        assertEquals(List.of("0,64,0"), row.get("locations"));
      }
    }
  }
}
