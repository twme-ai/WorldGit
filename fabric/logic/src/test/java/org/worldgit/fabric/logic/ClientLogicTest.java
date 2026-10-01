package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;
import org.worldgit.protocol.*;

class ClientLogicTest {
  private static WorldDiff diff(DimensionId dim, int sections, int perSection) {
    var list = new ArrayList<WorldDiff.SectionChange>();
    for (int s = 0; s < sections; s++) {
      var blocks = new ArrayList<WorldDiff.BlockChange>();
      for (int i = 0; i < perSection; i++)
        blocks.add(
            new WorldDiff.BlockChange(
                new WorldDiff.BlockPos(s * 16 + (i & 15), 64 + (i >> 8), (i >> 4) & 15),
                ChangeKind.values()[i % 3],
                new BlockState(i % 3 == 0 ? "minecraft:air" : "minecraft:stone"),
                new BlockState(i % 3 == 1 ? "minecraft:air" : "minecraft:gold_block"),
                null,
                null));
      list.add(new WorldDiff.SectionChange(new ChunkPos(s, 0), 4, ChangeKind.MODIFIED, blocks, WorldDiff.Counts.of(blocks)));
    }
    return new WorldDiff(dim, list, List.of(), List.of(), List.of());
  }

  @Test
  void publishesOnlyCompleteBatchesPerDimension() throws Exception {
    var previews = new ClientPreviews();
    var packets = Protocol.diff(5, diff(DimensionId.OVERWORLD, 3, 3000));
    assertTrue(packets.size() > 1);
    ClientPreviews.Published published = null;
    for (int i = packets.size() - 1; i >= 0; i--) { // 亂序
      var r = previews.accept(Protocol.decode(packets.get(i)), 1000);
      if (i > 0) assertNull(r.published());
      else published = r.published();
    }
    assertNotNull(published);
    assertEquals(9000, published.cells().size());
    assertTrue(published.ghost());
    assertEquals(DimensionId.OVERWORLD, published.dimension());
    assertEquals(5, previews.currentPreview(DimensionId.OVERWORLD).getAsLong());
    assertTrue(previews.currentPreview(new DimensionId("minecraft:the_nether")).isEmpty());
  }

  @Test
  void clearDropsOlderPreviewsAndIgnoresStalePackets() throws Exception {
    var previews = new ClientPreviews();
    for (var p : Protocol.diff(3, diff(DimensionId.OVERWORLD, 1, 10))) previews.accept(Protocol.decode(p), 0);
    var cleared = previews.accept(new Protocol.Clear(3), 1);
    assertEquals(3, cleared.clearedUpTo());
    assertTrue(previews.currentPreview(DimensionId.OVERWORLD).isEmpty());
    for (var p : Protocol.diff(2, diff(DimensionId.OVERWORLD, 1, 10)))
      assertNull(previews.accept(Protocol.decode(p), 2).published());
    var r = previews.accept(Protocol.decode(Protocol.diff(4, diff(DimensionId.OVERWORLD, 1, 10)).getFirst()), 3);
    assertNotNull(r.published());
  }

  @Test
  void statusOutlinesPublishAsOutlines() throws Exception {
    var previews = new ClientPreviews();
    ClientPreviews.Published p = null;
    for (var bytes : Protocol.status(1, diff(DimensionId.OVERWORLD, 4, 5), -64, 319))
      p = previews.accept(Protocol.decode(bytes), 0).published();
    assertNotNull(p);
    assertFalse(p.ghost());
    assertEquals(4, p.outlines().size());
  }

  @Test
  void rejectsInconsistentHeadersAndResets() throws Exception {
    var previews = new ClientPreviews();
    var cell = new Protocol.Cell(0, 64, 0, ChangeKind.ADDED, "minecraft:air", "minecraft:stone");
    var other = new Protocol.Cell(1, 64, 0, ChangeKind.ADDED, "minecraft:air", "minecraft:stone");
    var dim = DimensionId.OVERWORLD;
    previews.accept(new Protocol.DiffPart(new Protocol.Header(1, 0, 2, 2, dim), List.of(cell)), 0);
    // 同一個 preview 的 parts 數不一致
    assertThrows(
        IOException.class,
        () -> previews.accept(new Protocol.DiffPart(new Protocol.Header(1, 1, 3, 2, dim), List.of(other)), 0));
    // 失敗後 assembler 已重置，完整的新批次可正常發布
    ClientPreviews.Published p = null;
    p = previews.accept(new Protocol.DiffPart(new Protocol.Header(2, 0, 2, 2, dim), List.of(cell)), 0).published();
    assertNull(p);
    p = previews.accept(new Protocol.DiffPart(new Protocol.Header(2, 1, 2, 2, dim), List.of(other)), 0).published();
    assertNotNull(p);
    assertEquals(2, p.cells().size());
    // 預覽只接受 diff/status/clear
    assertThrows(IOException.class, () -> previews.accept(new Protocol.Hello(Protocol.VERSION, 1, List.of(), DiffPalette.DEFAULT), 0));
    previews.reset();
    assertTrue(previews.currentPreview(dim).isEmpty());
  }

  @Test
  void helloReplyChecksVersionAndPalette() {
    var server = new Protocol.Hello(Protocol.VERSION, 99L, List.of(), DiffPalette.COLORBLIND);
    var reply = ClientHandshake.reply(server, ClientConfig.defaults()).orElseThrow();
    assertEquals(99L, reply.nonce());
    assertEquals(DiffPalette.COLORBLIND, reply.palette());
    assertEquals(Protocol.CAPABILITIES, reply.capabilities());
    assertTrue(ClientHandshake.reply(new Protocol.Hello(1, 1, List.of(), DiffPalette.DEFAULT), ClientConfig.defaults()).isEmpty());
    assertEquals(DiffPalette.DEFAULT, ClientHandshake.reply(server, ClientConfig.defaults().withPalette("default")).orElseThrow().palette());
  }

  private static List<Protocol.Cell> cells(int sx, int sz, int n, ChangeKind kind) {
    var list = new ArrayList<Protocol.Cell>();
    for (int i = 0; i < n; i++) list.add(new Protocol.Cell(sx * 16 + (i & 15), 64 + (i >> 8), sz * 16 + ((i >> 4) & 15), kind, "minecraft:stone", "minecraft:gold_block"));
    return list;
  }

  @Test
  void sectionPlanGroupsBoundsAndPriority() {
    var all = new ArrayList<Protocol.Cell>();
    all.addAll(cells(0, 0, 5, ChangeKind.ADDED));
    all.addAll(cells(0, 0, 2, ChangeKind.MODIFIED));
    all.addAll(cells(-1, 3, 4, ChangeKind.REMOVED));
    var plans = SectionPlan.ofCells(all);
    assertEquals(2, plans.size());
    var first = plans.get(0);
    assertEquals(7, first.cellCount());
    assertEquals(ChangeKind.MODIFIED, first.priority());
    assertArrayEquals(new int[] {0, 64, 0, 5, 65, 1}, first.bounds());
    assertEquals(ChangeKind.REMOVED, plans.get(1).priority());
    assertEquals(new SectionPlan.Key(-1, 4, 3), plans.get(1).key());
    assertEquals(0, first.distance(2, 64, 1));
    assertEquals(10, first.distance(15, 64, 0), 1e-9);
    var outlines = SectionPlan.ofOutlines(List.of(
        new Protocol.Outline(0, 0, 0, 15, 15, 15, ChangeKind.ADDED, 1, 0, 0, 0),
        new Protocol.Outline(16, 0, 0, 31, 15, 15, ChangeKind.CONFLICT, 0, 0, 0, 1),
        new Protocol.Outline(300, 0, 0, 315, 15, 15, ChangeKind.REMOVED, 0, 1, 0, 0)));
    assertEquals(2, outlines.size());
    assertEquals(ChangeKind.CONFLICT, outlines.get(0).priority());
  }

  @Test
  void frustumCullsBehindTheCamera() {
    // 看向 -Z（OpenGL 預設），單位旋轉
    float[] identity = new float[16];
    identity[0] = identity[5] = identity[10] = identity[15] = 1;
    var f = Frustum.perspective(identity, 80, 16.0 / 9.0, 0.05, 1000);
    assertTrue(f.intersects(-1, -1, -11, 1, 1, -9), "正前方");
    assertFalse(f.intersects(-1, -1, 9, 1, 1, 11), "背後");
    assertFalse(f.intersects(500, -1, -11, 502, 1, -9), "遠在右側");
    assertTrue(f.intersects(-1, -1, -1, 1, 1, 1), "包含相機");
  }

  @Test
  void lodSelectsDetailNearBoxFarAndBudgetsBuilds() {
    var all = new ArrayList<Protocol.Cell>();
    for (int i = 0; i < 20; i++) all.addAll(cells(i, 0, 4, ChangeKind.ADDED));
    var plans = SectionPlan.ofCells(all);
    var cfg = new ClientConfig("auto", false, 48, 384, 192, 2); // detail 48, max 384, 每畫面建 2 個
    var lod = new LodSelector(plans, cfg, true);
    var frame = lod.select(0, 64, 0, null);
    // 距離 ≤ 48 的 section：x 起點 0,32 與 ... 
    var near = new ArrayList<Integer>();
    for (int i = 0; i < plans.size(); i++) if (plans.get(i).distance(0, 64, 0) <= 48) near.add(i);
    assertEquals(4, near.size());
    assertEquals(2, frame.build().size(), "每個畫面最多 2 個新明細");
    assertEquals(2, lod.residentCount());
    assertTrue(frame.build().stream().allMatch(near::contains));
    for (int i = 0; i < plans.size(); i++) {
      var level = frame.levels()[i];
      if (frame.build().contains(i)) assertEquals(LodSelector.Level.DETAIL, level);
      else assertEquals(LodSelector.Level.BBOX, level);
    }
    // 之後幾個畫面補齊
    for (int k = 0; k < 3; k++) lod.select(0, 64, 0, null);
    assertEquals(near.size(), lod.residentCount());
    // 相機遠離：全部釋放，遠處只剩框（超過 maxDistance 則隱藏）
    var away = lod.select(1000, 64, 0, null);
    assertEquals(near.size(), away.release().size());
    assertEquals(0, lod.residentCount());
    assertTrue(Arrays.stream(away.levels()).allMatch(l -> l == LodSelector.Level.HIDDEN));
  }

  @Test
  void lodRespectsResidentCapAndOutlineOnlyMode() {
    var all = new ArrayList<Protocol.Cell>();
    for (int i = 0; i < 10; i++) all.addAll(cells(i, 0, 2, ChangeKind.REMOVED));
    var plans = SectionPlan.ofCells(all);
    var capped = new ClientConfig("auto", false, 48, 384, 3, 100);
    var lod = new LodSelector(plans, capped, true);
    lod.select(0, 64, 0, null);
    assertEquals(3, lod.residentCount());
    var statusOnly = new LodSelector(plans, ClientConfig.defaults(), false);
    var frame = statusOnly.select(0, 64, 0, null);
    assertTrue(frame.build().isEmpty());
    assertTrue(Arrays.stream(frame.levels()).allMatch(l -> l == LodSelector.Level.BBOX));
  }
}
