package org.worldgit.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.apply.ApplyPlan;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.remote.WorldRemotes;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.i18n.MessageCatalog;

/** 完成摘要必須是人類可讀文字，不得洩漏 Java record 的 toString。 */
class ResultSummaryTest {
  private static final MessageCatalog CATALOG = MessageCatalog.bundled();

  private static WorldDiff diff(long added, int sections) {
    // 方塊計數由各 section 的 counts 加總；以最少欄位建出 section 清單。
    var changes = new ArrayList<WorldDiff.SectionChange>();
    for (int i = 0; i < sections; i++)
      changes.add(new WorldDiff.SectionChange(new org.worldgit.core.model.ChunkPos(i, 0), 0, org.worldgit.core.diff.ChangeKind.ADDED, List.of(),
          new WorldDiff.Counts(i == 0 ? added : 0, 0, 0, 0)));
    return new WorldDiff(DimensionId.OVERWORLD, changes, List.of(), List.of(), List.of());
  }

  @Test
  void commitSummaryUsesThousandsSeparatorsAndNoRecordText() {
    var status = new DimensionRepository.Status(diff(42_467_328L, 10_368), 10_368, 0, false, List.of());
    var result = new DimensionRepository.CommitResult("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", status);
    var batch = new WorldRepositories.Batch<>(UUID.randomUUID(), new TreeMap<>(Map.of(DimensionId.OVERWORLD, new WorldRepositories.Outcome<>(result, null))));
    String zh = new ResultSummary(CATALOG, "zh_tw").describe(batch);
    assertTrue(zh.contains("新增 42,467,328 方塊"), zh);
    assertTrue(zh.contains("10,368 sections"), zh);
    assertTrue(zh.contains("a1b2c3d4"), zh);
    String en = new ResultSummary(CATALOG, "en_us").describe(batch);
    assertTrue(en.contains("added 42,467,328 blocks") && en.contains("10,368 sections"), en);
    for (String text : List.of(zh, en)) {
      assertFalse(text.contains("Counts["), text);
      assertFalse(text.contains("added=") || text.contains("{head="), text);
    }
  }

  @Test
  void unchangedAndFailedDimensionsAreDescribed() {
    var clean = new DimensionRepository.Status(diff(0, 0), 0, 0, false, List.of());
    var rows = new TreeMap<DimensionId, WorldRepositories.Outcome<DimensionRepository.CommitResult>>();
    rows.put(DimensionId.OVERWORLD, new WorldRepositories.Outcome<>(new DimensionRepository.CommitResult(null, clean), null));
    rows.put(new DimensionId("minecraft:the_nether"), new WorldRepositories.Outcome<>(null, "boom"));
    String text = new ResultSummary(CATALOG, "en_us").describe(new WorldRepositories.Batch<>(UUID.randomUUID(), rows));
    assertTrue(text.contains("no changes") && text.contains("minecraft:the_nether failed"), text);
  }

  @Test
  void appliedMergeAndTransferResultsAreReadable() {
    var stats = new ApplyPlan.Stats(5, 12, 0, 3, 1, 0, 0, 0);
    var applied = new WorldOperations.Result(WorldOperations.State.COMPLETE, new TreeMap<>(Map.of(DimensionId.OVERWORLD, stats)), null);
    String text = new ResultSummary(CATALOG, "zh_tw").describe(applied);
    assertEquals("minecraft:overworld：12 sections、5 chunks、4 個實體操作", text);
    String dry = new ResultSummary(CATALOG, "en_us").describe(new WorldOperations.Result(WorldOperations.State.DRY_RUN, new TreeMap<>(Map.of(DimensionId.OVERWORLD, stats)), null));
    assertTrue(dry.startsWith("dry run"), dry);
    var merge = new WorldOperations.MergeResult("COMPLETE", null, new TreeMap<>(), new TreeMap<>(), new TreeMap<>(Map.of(DimensionId.OVERWORLD, "0123456789abcdef")), null);
    String mergeText = new ResultSummary(CATALOG, "en_us").describe(merge);
    assertTrue(mergeText.contains("minecraft:overworld 01234567") && mergeText.contains("no unresolved conflicts"), mergeText);
    var transfer = new WorldRemotes.TransferResult("COMPLETE", "push", new TreeMap<>(Map.of(DimensionId.OVERWORLD, "fedcba9876543210")), new TreeMap<>(), null);
    assertTrue(new ResultSummary(CATALOG, "en_us").describe(transfer).contains("fedcba98"));
    assertEquals("main @ 01234567", new ResultSummary(CATALOG, "en_us").head("main", "0123456789abcdef"));
  }

  @Test
  void unknownValuesAreNeverStringified() {
    assertNull(new ResultSummary(CATALOG, "en_us").describe(new Object() {}));
    assertNull(new ResultSummary(CATALOG, "en_us").describe(Map.of("head", "main")));
  }
}
