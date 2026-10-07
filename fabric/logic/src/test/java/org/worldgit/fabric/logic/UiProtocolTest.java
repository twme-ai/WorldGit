package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.graph.CommitGraph;
import org.worldgit.protocol.Protocol;

class UiProtocolTest {
  private static CommitGraph.Node node(int i, int lane, List<String> before, List<String> after, List<String> parents, List<CommitGraph.Label> labels) {
    String id = String.format("%040x", i);
    var edges = new ArrayList<CommitGraph.Edge>();
    for (String parent : parents) edges.add(new CommitGraph.Edge(parent, lane, after.indexOf(parent)));
    return new CommitGraph.Node(id, parents, "", "2026-10-06T12:00:" + String.format("%02d", i % 60) + "Z", "commit " + i + " 新通道", "Alice <a@example.org>", lane,
        before, after, edges, labels);
  }

  private static CommitGraph graph(int count) {
    var nodes = new ArrayList<CommitGraph.Node>();
    for (int i = count; i >= 1; i--) {
      String id = String.format("%040x", i), parent = String.format("%040x", i - 1);
      var labels = i == count ? List.of(new CommitGraph.Label("HEAD", "HEAD"), new CommitGraph.Label("branch", "main")) : List.<CommitGraph.Label>of();
      nodes.add(node(i, 0, List.of(id), i > 1 ? List.of(parent) : List.of(), i > 1 ? List.of(parent) : List.of(), labels));
    }
    return new CommitGraph(nodes, false, 1);
  }

  @Test
  void progressRoundTrips() throws Exception {
    var progress = new UiProtocol.Progress(UUID.randomUUID(), "commit", "minecraft:the_nether", "capture", 0, 12, 40, 3500,
        UiProtocol.Status.RUNNING, "", 1200, true);
    assertEquals(progress, UiProtocol.decode(UiProtocol.encode(progress)));
    var done = new UiProtocol.Progress(UUID.randomUUID(), "init", "", "complete", 1, 40, -1, 3000, UiProtocol.Status.SUCCESS, "新增 42,467,328 方塊", 9000, false);
    assertEquals(done, UiProtocol.decode(UiProtocol.encode(done)));
  }

  @Test
  void graphIsChunkedAndReassembledWithinPayloadLimit() throws Exception {
    var source = graph(150);
    var parts = UiProtocol.encodeGraph(7, "minecraft:overworld", source, true);
    assertTrue(parts.size() > 1, "150 個節點必須分片");
    parts.forEach(part -> assertTrue(part.length <= Protocol.MAX_PAYLOAD));
    var assembler = new UiProtocol.GraphAssembler();
    Optional<UiProtocol.GraphAssembler.Completed> done = Optional.empty();
    // 亂序到達也能收齊；缺一片不發布。
    var shuffled = new ArrayList<>(parts);
    Collections.reverse(shuffled);
    for (int i = 0; i < shuffled.size(); i++) {
      done = assembler.accept((UiProtocol.GraphPart) UiProtocol.decode(shuffled.get(i)));
      assertEquals(i == shuffled.size() - 1, done.isPresent());
    }
    var graph = done.orElseThrow();
    assertTrue(graph.open());
    assertEquals("minecraft:overworld", graph.dimension());
    assertEquals(150, graph.nodes().size());
    for (int i = 0; i < 150; i++) {
      var expected = source.nodes().get(i);
      var actual = graph.nodes().get(i);
      assertEquals(expected.id(), actual.id());
      assertEquals(expected.parents(), actual.parents());
      assertEquals(expected.before(), actual.before());
      assertEquals(expected.after(), actual.after());
      assertEquals(expected.lane(), actual.lane());
      assertEquals(expected.labels(), actual.labels());
      assertEquals(expected.message(), actual.message());
      assertEquals(expected.edges(), actual.edges());
    }
  }

  @Test
  void graphHandlesBranchesAndEdgesToNowhere() throws Exception {
    String a = "a".repeat(40), b = "b".repeat(40), c = "c".repeat(40);
    var merge = new CommitGraph.Node(a, List.of(b, c), "", "2026-10-06T00:00:00Z", "merge", "x", 0, List.of(a), List.of(b, c),
        List.of(new CommitGraph.Edge(b, 0, 0), new CommitGraph.Edge(c, 0, 1)), List.of(new CommitGraph.Label("tag", "v1")));
    var root = new CommitGraph.Node(b, List.of(), "", "2026-10-06T00:00:00Z", "root", "x", 0, List.of(b, c), List.of(c), List.of(), List.of());
    var graph = new CommitGraph(List.of(merge, root), true, 2);
    var parts = UiProtocol.encodeGraph(1, "minecraft:the_end", graph, false);
    var done = new UiProtocol.GraphAssembler().accept((UiProtocol.GraphPart) UiProtocol.decode(parts.getFirst())).orElseThrow();
    assertTrue(done.truncated());
    assertFalse(done.open());
    assertEquals(graph.nodes().get(0).edges(), done.nodes().get(0).edges());
    assertEquals(List.of(), done.nodes().get(1).edges());
  }

  @Test
  void newerGraphRequestReplacesPartialOldOne() throws Exception {
    var assembler = new UiProtocol.GraphAssembler();
    var old = UiProtocol.encodeGraph(1, "d", graph(150), false);
    assertTrue(assembler.accept((UiProtocol.GraphPart) UiProtocol.decode(old.getFirst())).isEmpty());
    var fresh = UiProtocol.encodeGraph(2, "d", graph(3), false);
    assertTrue(assembler.accept((UiProtocol.GraphPart) UiProtocol.decode(fresh.getFirst())).isPresent());
    assertTrue(assembler.accept((UiProtocol.GraphPart) UiProtocol.decode(old.get(1))).isEmpty(), "較舊的請求不能發布");
  }

  @Test
  void ignoreMessagesRoundTrip() throws Exception {
    var lines = List.of(new UiProtocol.Line("entity minecraft:item", true, true, false), new UiProtocol.Line("# comment", false, true, false),
        new UiProtocol.Line("block 0,0,0", true, false, false));
    var view = new UiProtocol.IgnoreView(5, "minecraft:overworld", "abcdef0123456789", true, false, 3, 0, true, lines);
    assertEquals(view, UiProtocol.decode(UiProtocol.encode(view)));
    var preview = new UiProtocol.IgnorePreview(5, "minecraft:overworld", "ab12cd34", "add entity minecraft:cow", 10, 2, 3, 4, 5, List.of("entity 1", "entity 2"), 120);
    assertEquals(preview, UiProtocol.decode(UiProtocol.encode(preview)));
    var result = new UiProtocol.IgnoreResult(5, "minecraft:overworld", false, "error", "MERGING 期間禁止修改 .wgignore");
    assertEquals(result, UiProtocol.decode(UiProtocol.encode(result)));
    var edit = new UiProtocol.IgnoreEdit(6, "minecraft:overworld", "move", 4, 2, "", "", "abcdef0123456789", 0);
    assertEquals(edit, UiProtocol.decode(UiProtocol.encode(edit)));
  }

  @Test
  void fitViewKeepsLargeDocumentsInsideOnePayload() throws Exception {
    var lines = new ArrayList<UiProtocol.Line>();
    for (int i = 0; i < 2000; i++) lines.add(new UiProtocol.Line("entity minecraft:item_" + "x".repeat(200) + i, true, true, false));
    var view = UiProtocol.fitView(1, "d", "t", true, false, 2000, 0, false, lines);
    assertTrue(view.lines().size() > 10 && view.lines().size() < 2000);
    assertTrue(UiProtocol.encode(view).length <= Protocol.MAX_PAYLOAD);
    assertEquals(view, UiProtocol.decode(UiProtocol.encode(view)));
  }

  @Test
  void clientRequestsAreValidatedByTheServerDecoder() throws Exception {
    // 惡意 op／行號／長度不被接受。
    var bad = UiProtocol.encode(new UiProtocol.IgnoreEdit(1, "d", "view", 0, 0, "", "", "", 0));
    bad[bad.length - 1] = 1; // 破壞尾端 offset 的最低位不影響；改 op 字串更直接：重建一個未知 op。
    var unknown = new ByteArrayBuilder().build("format-disk");
    assertThrows(IOException.class, () -> UiProtocol.decode(unknown));
    assertThrows(IOException.class, () -> UiProtocol.decode(new byte[] {1}));
    assertThrows(IOException.class, () -> UiProtocol.decode(new byte[Protocol.MAX_PAYLOAD + 1]));
    assertThrows(IOException.class, () -> UiProtocol.decode(new byte[] {9, 1, 0, 0}));
    assertThrows(IllegalArgumentException.class, () -> new UiProtocol.GraphRequest(1, "d", false, 0, false));
    assertThrows(IllegalArgumentException.class, () -> new UiProtocol.GraphRequest(1, "d", false, UiProtocol.MAX_GRAPH_NODES + 1, false));
  }

  /** 組出任意 op 的 IgnoreEdit 封包位元組（編碼器不允許未知 op，所以直接寫）。 */
  private static final class ByteArrayBuilder {
    byte[] build(String op) throws IOException {
      var bytes = new java.io.ByteArrayOutputStream();
      var out = new java.io.DataOutputStream(bytes);
      out.writeByte(UiProtocol.VERSION);
      out.writeByte(7);
      out.writeLong(1);
      out.writeUTF("d");
      out.writeUTF(op);
      out.writeInt(0);
      out.writeInt(0);
      out.writeUTF("");
      out.writeUTF("");
      out.writeUTF("");
      out.writeInt(0);
      return bytes.toByteArray();
    }
  }

  @Test
  void wideGraphsDoNotWrapLaneOrIndexCountsAt256() throws Exception {
    var ids = new ArrayList<String>();
    for (int i = 1; i <= 300; i++) ids.add(String.format("%040x", i));
    var node = node(300, 299, ids, ids.subList(0, 299), List.of(ids.getFirst()), List.of());
    var part = new UiProtocol.GraphPart(9, "d", 0, 1, false, 300, 1, false, List.of(node));
    assertEquals(part, UiProtocol.decode(UiProtocol.encode(part)));
  }

  @Test
  void assemblerRejectsMixedDimensionsAndCannotPublishACompletedRequestTwice() throws Exception {
    var wire = UiProtocol.encodeGraph(7, "a", graph(60), false);
    var first = (UiProtocol.GraphPart) UiProtocol.decode(wire.getFirst());
    var last = (UiProtocol.GraphPart) UiProtocol.decode(wire.getLast());
    var assembler = new UiProtocol.GraphAssembler();
    assertTrue(assembler.accept(first).isEmpty());
    var mixed = new UiProtocol.GraphPart(last.request(), "b", last.index(), last.parts(), last.truncated(), last.lanes(), last.total(), last.open(), last.nodes());
    assertThrows(IllegalArgumentException.class, () -> assembler.accept(mixed));
    assertTrue(assembler.accept(last).isPresent());
    assertTrue(assembler.accept(first).isEmpty());
    assertTrue(assembler.accept(last).isEmpty());
  }

  @Test
  void editableTextIsRejectedRatherThanSilentlyTruncated() {
    var request = new UiProtocol.IgnoreEdit(1, "d", "add", 0, 0, "字".repeat(UiProtocol.MAX_LINE_CHARS + 1), "", "", 0);
    assertThrows(IOException.class, () -> UiProtocol.encode(request));
  }

  @Test
  void longStringsAreBoundedBeforeTheyReachTheWire() throws Exception {
    var result = new UiProtocol.IgnoreResult(1, "d", true, "test", "字".repeat(5000));
    var decoded = (UiProtocol.IgnoreResult) UiProtocol.decode(UiProtocol.encode(result));
    assertTrue(decoded.text().codePointCount(0, decoded.text().length()) <= UiProtocol.MAX_TEXT);
    assertTrue(decoded.text().endsWith("…"));
  }
}
