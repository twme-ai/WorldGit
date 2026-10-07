package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.apply.ApplyPlan;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;

/** Phase 5：Paper 的完成摘要是人類可讀文字，不輸出 Java record 的 toString（Counts[...]、{head=...}）。 */
class OperationSummaryTest {
  @Test
  void commitAndApplySummariesAreReadable() {
    var diff = new WorldDiff(DimensionId.OVERWORLD, List.of(new WorldDiff.SectionChange(new ChunkPos(0, 0), 0, ChangeKind.ADDED, List.of(), new WorldDiff.Counts(42_467_328L, 0, 0, 0))),
        List.of(), List.of(), List.of());
    var commit = new DimensionRepository.CommitResult("0123456789abcdef0123456789abcdef01234567", new DimensionRepository.Status(diff, 1, 0, false, List.of()));
    var batch = new WorldRepositories.Batch<>(UUID.randomUUID(), new TreeMap<>(Map.of(DimensionId.OVERWORLD, new WorldRepositories.Outcome<>(commit, null))));
    var summary = new TreeMap<String, Object>();
    summary.put("batch.0", batch);
    summary.put("head", new OperationUi.Head("main", "0123456789abcdef"));
    summary.put("changes", new PaperOperations.Result(PaperOperations.State.COMPLETE, new TreeMap<>(Map.of(DimensionId.OVERWORLD, new ApplyPlan.Stats(1, 2, 0, 0, 0, 0, 0, 0))), null));
    String text = OperationUi.describe(summary);
    assertTrue(text.contains("added 42,467,328 blocks"), text);
    assertTrue(text.contains("main @ 01234567"), text);
    assertTrue(text.contains("2 sections"), text);
    assertFalse(text.contains("Counts[") || text.contains("{head=") || text.contains("added="), text);
  }
}
