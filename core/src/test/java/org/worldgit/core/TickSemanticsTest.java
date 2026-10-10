package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

/** 決策 #169：排程 tick 的剩餘延遲夾到 max(t,0)，且不單獨構成差異；驗證失敗訊息列出 chunk 與種類。 */
class TickSemanticsTest {
  @TempDir Path temp;
  JGitStore store;

  @BeforeEach
  void open() throws Exception {
    store = new JGitStore(temp.resolve("repo"), true);
  }

  @AfterEach
  void close() throws Exception {
    store.close();
  }

  static Nbt.Compound tick(String type, int x, int y, int z, int t) {
    return new Nbt.Compound().with("i", type).with("x", x).with("y", y).with("z", z).with("p", 0).with("t", t);
  }

  static byte[] ticks(Nbt.Compound... fluids) throws Exception {
    var c = new Nbt.Compound();
    c.put("fluid_ticks", new Nbt.ListTag(10, List.<Object>of((Object[]) fluids)));
    return SnapshotCodec.nbt(4, Nbt.write(c));
  }

  static byte[] section(int index, String block) throws Exception {
    var list = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
    list.set(index, new BlockState("minecraft:" + block));
    return SnapshotCodec.section(new Section(list, Map.of()));
  }

  String chunk(int cx, int cz, byte[] section, byte[] ticks) throws Exception {
    var t = new TreeEditor(store, null);
    String base = new ChunkPos(cx, cz).treePath();
    if (section != null) t.putBlob(base + "/s.4.bin", section);
    if (ticks != null) t.putBlob(base + "/ticks.bin", ticks);
    return t.write();
  }

  static long t(Nbt.Compound ticks, String key, int index) {
    return ((Nbt.Compound) ticks.list(key).values().get(index)).integer("t", Integer.MIN_VALUE);
  }

  @Test
  void negativeRemainingDelayClampsToZeroAndStaysStable() throws Exception {
    var raw = new Nbt.Compound().with("DataVersion", 1);
    raw.put("fluid_ticks", new Nbt.ListTag(10, List.of(tick("minecraft:water", 1, 2, 3, -772), tick("minecraft:lava", 2, 2, 3, 12))));
    var normalizer = new ChunkNormalizer(IgnoreRules.none(), EntitySemantics.OFFLINE);
    var a = normalizer.normalize(new ChunkPos(0, 0), raw, List.of());
    // 同一批排程在稍後的遊戲時間擷取：已到期的延遲更負，結果必須逐位元組相同。
    raw.put("fluid_ticks", new Nbt.ListTag(10, List.of(tick("minecraft:water", 1, 2, 3, -99999), tick("minecraft:lava", 2, 2, 3, 12))));
    var b = normalizer.normalize(new ChunkPos(0, 0), raw, List.of());
    assertArrayEquals(a.ticks(), b.ticks());
    var read = Nbt.read(a.ticks());
    assertEquals(0, t(read, "fluid_ticks", 0), "已到期的 water 為 0（x=1 排在前）");
    assertEquals(12, t(read, "fluid_ticks", 1), "未到期的延遲保留");
    for (Object v : read.list("fluid_ticks").values()) assertTrue(((Nbt.Compound) v).integer("t", -1) >= 0);
  }

  @Test
  void clampBytesLeavesNonNegativeUntouched() throws Exception {
    var ok = Nbt.write(new Nbt.Compound().with("fluid_ticks", new Nbt.ListTag(10, List.of(tick("minecraft:water", 0, 0, 0, 5)))));
    assertSame(ok, TickSemantics.clamp(ok));
    var bad = Nbt.write(new Nbt.Compound().with("block_ticks", new Nbt.ListTag(10, List.of(tick("minecraft:stone", 0, 0, 0, -3)))));
    assertEquals(0, t(Nbt.read(TickSemantics.clamp(bad)), "block_ticks", 0));
    assertNull(TickSemantics.clamp((byte[]) null));
  }

  @Test
  void ticksOnlyDifferencesAreNotChangesAndOldHistoryMatches() throws Exception {
    byte[] blocks = section(0, "stone");
    // 舊歷史：負延遲、流體排程；新擷取：夾住後的 0 或根本沒有排程。
    String old = chunk(0, 0, blocks, ticks(tick("minecraft:flowing_lava", 1, 1, 1, -772)));
    String fresh = chunk(0, 0, blocks, ticks(tick("minecraft:flowing_lava", 1, 1, 1, 0)));
    String none = chunk(0, 0, blocks, null);
    store.flush();
    var engine = new DiffEngine(store);
    for (var pair : List.of(List.of(old, fresh), List.of(fresh, old), List.of(old, none), List.of(none, old))) {
      var diff = engine.compare(DimensionId.OVERWORLD, pair.get(0), pair.get(1), 2);
      assertTrue(diff.empty(), "只有排程不同不應是差異：" + diff.metadata());
      var plan = ApplyPlanner.plan(store, DimensionId.OVERWORLD, pair.get(0), pair.get(1), Scope.all(), ApplyPlanner.Options.defaults());
      assertTrue(plan.empty(), plan.stats().toString());
    }
  }

  @Test
  void blocksStillDiffAndTicksFollowTheirChunkWithClampedDelay() throws Exception {
    String a = chunk(0, 0, section(0, "stone"), ticks(tick("minecraft:water", 1, 1, 1, 7)));
    String b = chunk(0, 0, section(1, "gold_block"), ticks(tick("minecraft:flowing_lava", 1, 1, 1, -500)));
    store.flush();
    assertFalse(new DiffEngine(store).compare(DimensionId.OVERWORLD, a, b, 2).empty(), "方塊差異必須仍看得到");
    var plan = ApplyPlanner.plan(store, DimensionId.OVERWORLD, a, b, Scope.all(), ApplyPlanner.Options.defaults());
    assertFalse(plan.empty());
    var op = plan.chunks().get(new ChunkPos(0, 0));
    assertEquals(1, op.sections().size());
    assertTrue(op.setTicks(), "有方塊改寫的 chunk，排程隨之還原");
    assertEquals(0, t(Nbt.read(op.ticks()), "fluid_ticks", 0), "套用給平台的延遲不得為負");
  }

  @Test
  void mergeNeverConflictsOnTicks() throws Exception {
    byte[] blocks = section(0, "stone");
    String base = chunk(0, 0, blocks, ticks(tick("minecraft:water", 1, 1, 1, 20)));
    String ours = chunk(0, 0, blocks, ticks(tick("minecraft:water", 1, 1, 1, 3)));
    String theirs = chunk(0, 0, blocks, ticks(tick("minecraft:water", 1, 1, 1, -40), tick("minecraft:lava", 2, 2, 2, 0)));
    store.flush();
    var result = new MergeEngine(store, DimensionId.OVERWORLD, base, ours, theirs, List.of("A"), List.of("B")).merge(1);
    assertTrue(result.report().regions().isEmpty(), "排程是暫態，不得產生 conflict");
  }

  @Test
  void verificationFailureListsChunksAndKindsWithLimit() throws Exception {
    // 20 個 chunk：偶數 chunk 方塊不同，奇數 chunk 方塊與排程都不同。
    var observed = new TreeEditor(store, null);
    var target = new TreeEditor(store, null);
    for (int i = 0; i < 20; i++) {
      String base = new ChunkPos(i, -i).treePath();
      observed.putBlob(base + "/s.4.bin", section(0, "stone"));
      target.putBlob(base + "/s.4.bin", section(1, "stone"));
      if (i % 2 == 1) {
        observed.putBlob(base + "/ticks.bin", ticks(tick("minecraft:water", 1, 1, 1, 24)));
        target.putBlob(base + "/ticks.bin", ticks(tick("minecraft:water", 1, 1, 1, 0)));
      }
    }
    String w = observed.write(), g = target.write();
    store.flush();
    var check = ApplyPlanner.plan(store, DimensionId.OVERWORLD, w, g, Scope.all(), ApplyPlanner.Options.defaults());
    assertFalse(check.empty());
    var error = ApplyVerificationException.of(store, "minecraft:overworld", check);
    assertEquals(20, error.total());
    assertEquals(ApplyVerificationException.LIMIT, error.chunks().size());
    var first = error.chunks().get(0);
    assertEquals(new ChunkPos(0, 0), first.chunk());
    assertEquals(1, first.sections());
    var odd = error.chunks().get(1);
    assertEquals(1, odd.fluidTicks());
    assertTrue(odd.samples().stream().anyMatch(v -> v.contains("t 24→0")), odd.samples().toString());
    assertTrue(odd.samples().stream().anyMatch(v -> v.contains("minecraft:stone→")), "方塊差異帶座標與前後方塊：" + odd.samples());
    String message = error.getMessage();
    assertTrue(message.contains("[0,0] 方塊 section 1"), message);
    assertTrue(message.contains("[1,-1] 方塊 section 1，排程 tick（流體 1 筆）"), message);
    assertTrue(message.contains("另 12 個 chunk 未列出"), message);
    assertTrue(message.contains("switch <目標> --force") && message.contains("reset --hard"), message);
    assertFalse(message.contains("Stats["), message);
  }
}
