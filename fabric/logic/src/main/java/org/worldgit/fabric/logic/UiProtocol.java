package org.worldgit.fabric.logic;

import java.io.*;
import java.util.*;
import org.worldgit.core.graph.CommitGraph;
import org.worldgit.protocol.Protocol;

/**
 * Fabric 伺服器與 Fabric 客戶端之間的 UI 封包（進度 HUD、分支圖畫面、.wgignore 編輯畫面）。
 * 宣告 {@link #CAPABILITY} 的雙方才使用；Paper 伺服器不宣告，客戶端不會送也不會收。
 * 每個 payload ≤ {@link Protocol#MAX_PAYLOAD}，所有長度與數量在解碼時驗證；客戶端送來的內容一律由伺服器重新驗證。
 */
public final class UiProtocol {
  private UiProtocol() {}

  public static final String CAPABILITY = "fabric-ui-v1", CHANNEL = "worldgit:ui";
  public static final int VERSION = 1, MAX_NODES_PER_PART = 40, MAX_GRAPH_NODES = 400, MAX_GRAPH_PARTS = 20,
      MAX_LINES = 4096, MAX_LINE_CHARS = 240, MAX_TEXT = 600, MAX_EXAMPLES = 8;
  private static final int BUDGET = 24_000;

  public enum Status { RUNNING, SUCCESS, NO_OP, PARTIAL, FAILED, CANCELLED }

  public sealed interface Message permits Progress, GraphRequest, GraphPart, IgnoreView, IgnorePreview, IgnoreResult, IgnoreEdit {}

  /** total=-1 表示不定進度；eta=-1 表示未知。status 非 RUNNING 時 text 是終態摘要。 */
  public record Progress(UUID operation, String name, String dimension, String phase, int unit, long completed, long total,
      long etaMillis, Status status, String text, long elapsedMillis, boolean cancellable) implements Message {}

  public record GraphRequest(long request, String dimension, boolean all, int limit, boolean open) implements Message {
    public GraphRequest {
      if (limit < 1 || limit > MAX_GRAPH_NODES) throw new IllegalArgumentException("graph limit");
    }
  }

  /** request 是伺服器遞增 generation；命令與客戶端 refresh 共用，同一 generation 的所有 part 綁定同一 envelope。 */
  public record GraphPart(long request, String dimension, int index, int parts, boolean truncated, int lanes, int total,
      boolean open, List<CommitGraph.Node> nodes) implements Message {
    public GraphPart { nodes = List.copyOf(nodes); }
  }

  public record Line(String text, boolean rule, boolean enabled, boolean cut) {}

  public record IgnoreView(long request, String dimension, String token, boolean editable, boolean merging, int total,
      int offset, boolean open, List<Line> lines) implements Message {
    public IgnoreView { lines = List.copyOf(lines); }
  }

  public record IgnorePreview(long request, String dimension, String code, String change, long blocks, long blockEntities,
      long entities, long biomes, long fields, List<String> examples, int expiresSeconds) implements Message {
    public IgnorePreview { examples = List.copyOf(examples); }
  }

  public record IgnoreResult(long request, String dimension, boolean ok, String kind, String text) implements Message {}

  /** op：view add remove move enable disable preview confirm cancel test。 */
  public record IgnoreEdit(long request, String dimension, String op, int line, int destination, String text, String code,
      String token, int offset) implements Message {}

  // ---------------------------------------------------------------- 編碼

  private static String bounded(String value, int max) {
    if (value == null) return "";
    if (value.codePointCount(0, value.length()) <= max) return value;
    int end = value.offsetByCodePoints(0, max - 1);
    return value.substring(0, end) + "…";
  }

  private static void utf(DataOutputStream out, String value, int max) throws IOException {
    out.writeUTF(bounded(value, max));
  }

  private static String utf(DataInputStream in, int max) throws IOException {
    String value = in.readUTF();
    if (value.codePointCount(0, value.length()) > max) throw new IOException("ui string limit");
    return value;
  }

  private static DataOutputStream begin(ByteArrayOutputStream bytes, int kind) throws IOException {
    var out = new DataOutputStream(bytes);
    out.writeByte(VERSION);
    out.writeByte(kind);
    return out;
  }

  private static byte[] finish(ByteArrayOutputStream bytes) throws IOException {
    if (bytes.size() > Protocol.MAX_PAYLOAD) throw new IOException("ui payload limit");
    return bytes.toByteArray();
  }

  public static byte[] encode(Message message) throws IOException {
    var bytes = new ByteArrayOutputStream();
    switch (message) {
      case Progress p -> {
        var out = begin(bytes, 1);
        out.writeLong(p.operation().getMostSignificantBits()); out.writeLong(p.operation().getLeastSignificantBits());
        utf(out, p.name(), 64); utf(out, p.dimension(), 128); utf(out, p.phase(), 64);
        out.writeByte(p.unit()); out.writeLong(p.completed()); out.writeLong(p.total()); out.writeLong(p.etaMillis());
        out.writeByte(p.status().ordinal()); utf(out, p.text(), 400); out.writeLong(p.elapsedMillis()); out.writeBoolean(p.cancellable());
      }
      case GraphRequest r -> {
        var out = begin(bytes, 2);
        out.writeLong(r.request()); utf(out, r.dimension(), 128); out.writeBoolean(r.all()); out.writeInt(r.limit()); out.writeBoolean(r.open());
      }
      case GraphPart g -> writeGraph(begin(bytes, 3), g);
      case IgnoreView v -> {
        var out = begin(bytes, 4);
        out.writeLong(v.request()); utf(out, v.dimension(), 128); utf(out, v.token(), 80); out.writeBoolean(v.editable());
        out.writeBoolean(v.merging()); out.writeInt(v.total()); out.writeInt(v.offset()); out.writeBoolean(v.open());
        out.writeShort(v.lines().size());
        for (var line : v.lines()) { utf(out, line.text(), MAX_LINE_CHARS); out.writeBoolean(line.rule()); out.writeBoolean(line.enabled()); out.writeBoolean(line.cut()); }
      }
      case IgnorePreview p -> {
        var out = begin(bytes, 5);
        out.writeLong(p.request()); utf(out, p.dimension(), 128); utf(out, p.code(), 16); utf(out, p.change(), 300);
        out.writeLong(p.blocks()); out.writeLong(p.blockEntities()); out.writeLong(p.entities()); out.writeLong(p.biomes()); out.writeLong(p.fields());
        out.writeByte(Math.min(MAX_EXAMPLES, p.examples().size()));
        for (var example : p.examples().stream().limit(MAX_EXAMPLES).toList()) utf(out, example, 160);
        out.writeShort(p.expiresSeconds());
      }
      case IgnoreResult r -> {
        var out = begin(bytes, 6);
        out.writeLong(r.request()); utf(out, r.dimension(), 128); out.writeBoolean(r.ok()); utf(out, r.kind(), 24); utf(out, r.text(), MAX_TEXT);
      }
      case IgnoreEdit e -> {
        var out = begin(bytes, 7);
        out.writeLong(e.request()); utf(out, e.dimension(), 128); utf(out, e.op(), 16); out.writeInt(e.line()); out.writeInt(e.destination());
        if (e.text() != null && e.text().codePointCount(0, e.text().length()) > MAX_LINE_CHARS) throw new IOException("ignore edit text limit");
        utf(out, e.text(), MAX_LINE_CHARS); utf(out, e.code(), 16); utf(out, e.token(), 80); out.writeInt(e.offset());
      }
    }
    return finish(bytes);
  }

  private static void writeGraph(DataOutputStream out, GraphPart g) throws IOException {
    out.writeLong(g.request()); utf(out, g.dimension(), 128); out.writeByte(g.index()); out.writeByte(g.parts());
    out.writeBoolean(g.truncated()); out.writeShort(g.lanes()); out.writeInt(g.total()); out.writeBoolean(g.open());
    var table = new LinkedHashMap<String, Integer>();
    for (var node : g.nodes()) {
      table.putIfAbsent(node.id(), table.size());
      node.parents().forEach(p -> table.putIfAbsent(p, table.size()));
      node.before().forEach(p -> table.putIfAbsent(p, table.size()));
      node.after().forEach(p -> table.putIfAbsent(p, table.size()));
    }
    out.writeShort(table.size());
    for (var id : table.keySet()) utf(out, id, 80);
    out.writeByte(g.nodes().size());
    for (var node : g.nodes()) {
      out.writeShort(table.get(node.id()));
      writeIndexes(out, table, node.parents());
      out.writeShort(node.lane()); writeIndexes(out, table, node.before()); writeIndexes(out, table, node.after());
      out.writeShort(node.edges().size());
      for (var edge : node.edges()) { out.writeShort(table.get(edge.parent())); out.writeShort(edge.fromLane()); out.writeShort(edge.toLane() + 1); }
      out.writeShort(node.labels().size());
      for (var label : node.labels()) { utf(out, label.kind(), 12); utf(out, label.name(), 64); }
      utf(out, node.time(), 40); utf(out, node.author(), 64); utf(out, node.message(), 120);
    }
  }

  private static void writeIndexes(DataOutputStream out, Map<String, Integer> table, List<String> ids) throws IOException {
    out.writeShort(ids.size());
    for (var id : ids) out.writeShort(table.get(id));
  }

  /** 依 payload 大小與節點數切成多個 part；每個 part 都是獨立可解的封包。 */
  public static List<byte[]> encodeGraph(long request, String dimension, CommitGraph graph, boolean open) throws IOException {
    var nodes = graph.nodes().subList(0, Math.min(graph.nodes().size(), MAX_GRAPH_NODES));
    var groups = new ArrayList<List<CommitGraph.Node>>();
    var current = new ArrayList<CommitGraph.Node>();
    for (var node : nodes) {
      var candidate = new ArrayList<>(current);
      candidate.add(node);
      if (!current.isEmpty() && (candidate.size() > MAX_NODES_PER_PART || partSize(request, dimension, graph, candidate) > BUDGET)) {
        groups.add(List.copyOf(current));
        current = new ArrayList<>(List.of(node));
      } else current = candidate;
    }
    if (!current.isEmpty() || groups.isEmpty()) groups.add(List.copyOf(current));
    if (groups.size() > MAX_GRAPH_PARTS) throw new IOException("graph parts");
    var out = new ArrayList<byte[]>();
    for (int i = 0; i < groups.size(); i++)
      out.add(encode(new GraphPart(request, dimension, i, groups.size(), graph.truncated() || nodes.size() < graph.nodes().size(),
          graph.lanes(), nodes.size(), open, groups.get(i))));
    return List.copyOf(out);
  }

  private static int partSize(long request, String dimension, CommitGraph graph, List<CommitGraph.Node> nodes) throws IOException {
    var bytes = new ByteArrayOutputStream();
    writeGraph(begin(bytes, 3), new GraphPart(request, dimension, 0, 1, false, graph.lanes(), nodes.size(), false, nodes));
    return bytes.size();
  }

  /** 把 view 切成可放入單一 payload 的行數（由呼叫端決定 offset）。 */
  public static IgnoreView fitView(long request, String dimension, String token, boolean editable, boolean merging, int total,
      int offset, boolean open, List<Line> lines) throws IOException {
    var kept = new ArrayList<Line>();
    for (var line : lines) {
      kept.add(line);
      if (kept.size() > 120 || encode(new IgnoreView(request, dimension, token, editable, merging, total, offset, open, kept)).length > BUDGET) {
        kept.removeLast(); break;
      }
    }
    return new IgnoreView(request, dimension, token, editable, merging, total, offset, open, kept);
  }

  // ---------------------------------------------------------------- 解碼

  public static Message decode(byte[] bytes) throws IOException {
    if (bytes.length > Protocol.MAX_PAYLOAD || bytes.length < 2) throw new IOException("ui payload size");
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (in.readUnsignedByte() != VERSION) throw new IOException("ui version");
      int kind = in.readUnsignedByte();
      Message message = switch (kind) {
        case 1 -> {
          var id = new UUID(in.readLong(), in.readLong());
          String name = utf(in, 64), dimension = utf(in, 128), phase = utf(in, 64);
          int unit = in.readUnsignedByte(); long completed = in.readLong(), total = in.readLong(), eta = in.readLong();
          int status = in.readUnsignedByte();
          if (status >= Status.values().length || unit > 4 || completed < 0 || total < -1) throw new IOException("progress fields");
          yield new Progress(id, name, dimension, phase, unit, completed, total, eta, Status.values()[status], utf(in, 400), in.readLong(), in.readBoolean());
        }
        case 2 -> {
          long request = in.readLong(); String dimension = utf(in, 128); boolean all = in.readBoolean(); int limit = in.readInt();
          yield new GraphRequest(request, dimension, all, limit, in.readBoolean());
        }
        case 3 -> readGraph(in);
        case 4 -> {
          long request = in.readLong(); String dimension = utf(in, 128), token = utf(in, 80); boolean editable = in.readBoolean(), merging = in.readBoolean();
          int total = in.readInt(), offset = in.readInt(); boolean open = in.readBoolean(); int count = in.readUnsignedShort();
          if (total < 0 || total > MAX_LINES || offset < 0 || offset > total || count > 200 || offset + count > total) throw new IOException("ignore view fields");
          var lines = new ArrayList<Line>();
          for (int i = 0; i < count; i++) lines.add(new Line(utf(in, MAX_LINE_CHARS), in.readBoolean(), in.readBoolean(), in.readBoolean()));
          yield new IgnoreView(request, dimension, token, editable, merging, total, offset, open, lines);
        }
        case 5 -> {
          long request = in.readLong(); String dimension = utf(in, 128), code = utf(in, 16), change = utf(in, 300);
          long blocks = in.readLong(), bes = in.readLong(), entities = in.readLong(), biomes = in.readLong(), fields = in.readLong();
          int count = in.readUnsignedByte();
          if (count > MAX_EXAMPLES || blocks < 0 || bes < 0 || entities < 0 || biomes < 0 || fields < 0) throw new IOException("ignore preview fields");
          var examples = new ArrayList<String>();
          for (int i = 0; i < count; i++) examples.add(utf(in, 160));
          yield new IgnorePreview(request, dimension, code, change, blocks, bes, entities, biomes, fields, examples, in.readUnsignedShort());
        }
        case 6 -> new IgnoreResult(in.readLong(), utf(in, 128), in.readBoolean(), utf(in, 24), utf(in, MAX_TEXT));
        case 7 -> {
          long request = in.readLong(); String dimension = utf(in, 128), op = utf(in, 16); int line = in.readInt(), destination = in.readInt();
          String text = utf(in, MAX_LINE_CHARS), code = utf(in, 16), token = utf(in, 80); int offset = in.readInt();
          if (!Set.of("view", "add", "remove", "move", "enable", "disable", "preview", "confirm", "cancel", "test").contains(op)
              || line < 0 || line > MAX_LINES || destination < 0 || destination > MAX_LINES || offset < 0 || offset > MAX_LINES) throw new IOException("ignore edit fields");
          yield new IgnoreEdit(request, dimension, op, line, destination, text, code, token, offset);
        }
        default -> throw new IOException("unknown ui message " + kind);
      };
      if (in.available() != 0) throw new IOException("trailing ui bytes");
      return message;
    } catch (IllegalArgumentException | EOFException ex) {
      throw new IOException("ui payload malformed", ex);
    }
  }

  private static GraphPart readGraph(DataInputStream in) throws IOException {
    long request = in.readLong(); String dimension = utf(in, 128); int index = in.readUnsignedByte(), parts = in.readUnsignedByte();
    boolean truncated = in.readBoolean(); int lanes = in.readUnsignedShort(), total = in.readInt(); boolean open = in.readBoolean();
    int tableSize = in.readUnsignedShort();
    if (parts < 1 || parts > MAX_GRAPH_PARTS || index >= parts || total < 0 || total > MAX_GRAPH_NODES || tableSize > 4096 || lanes > 512) throw new IOException("graph envelope");
    var table = new ArrayList<String>();
    for (int i = 0; i < tableSize; i++) table.add(utf(in, 80));
    int count = in.readUnsignedByte();
    if (count > MAX_NODES_PER_PART) throw new IOException("graph part nodes");
    var nodes = new ArrayList<CommitGraph.Node>();
    for (int i = 0; i < count; i++) {
      String id = table(table, in.readUnsignedShort());
      var parents = readIndexes(in, table);
      int lane = in.readUnsignedShort(); var before = readIndexes(in, table); var after = readIndexes(in, table);
      int edgeCount = in.readUnsignedShort();
      if (edgeCount > 512 || lane >= lanes || before.size() > lanes || after.size() > lanes) throw new IOException("graph lanes"); var edges = new ArrayList<CommitGraph.Edge>();
      for (int e = 0; e < edgeCount; e++) edges.add(new CommitGraph.Edge(table(table, in.readUnsignedShort()), in.readUnsignedShort(), in.readUnsignedShort() - 1));
      for (var edge : edges) if (edge.fromLane() >= lanes || edge.toLane() >= lanes) throw new IOException("graph edge lane");
      int labelCount = in.readUnsignedShort();
      if (labelCount > 4096) throw new IOException("graph labels"); var labels = new ArrayList<CommitGraph.Label>();
      for (int l = 0; l < labelCount; l++) labels.add(new CommitGraph.Label(utf(in, 12), utf(in, 64)));
      String time = utf(in, 40), author = utf(in, 64), message = utf(in, 120);
      nodes.add(new CommitGraph.Node(id, parents, "", time, message, author, lane, before, after, edges, labels));
    }
    return new GraphPart(request, dimension, index, parts, truncated, lanes, total, open, nodes);
  }

  private static String table(List<String> table, int index) throws IOException {
    if (index >= table.size()) throw new IOException("graph table index");
    return table.get(index);
  }

  private static List<String> readIndexes(DataInputStream in, List<String> table) throws IOException {
    int count = in.readUnsignedShort();
    if (count > 512) throw new IOException("graph index count");
    var result = new ArrayList<String>();
    for (int i = 0; i < count; i++) result.add(table(table, in.readUnsignedShort()));
    return result;
  }

  /** 圖畫面接收端：依 request 收齊所有 part 才發布；較新的 request 取代舊的。 */
  public static final class GraphAssembler {
    private long request = -1; private int parts; private boolean published; private final SortedMap<Integer, GraphPart> received = new TreeMap<>();

    public Optional<Completed> accept(GraphPart part) {
      if (part.request() < request || part.request() == request && published) return Optional.empty();
      if (part.parts() < 1 || part.parts() > MAX_GRAPH_PARTS || part.index() < 0 || part.index() >= part.parts()
          || part.total() < 0 || part.total() > MAX_GRAPH_NODES || part.nodes().size() > MAX_NODES_PER_PART)
        throw new IllegalArgumentException("graph envelope");
      if (part.request() > request) { request = part.request(); parts = part.parts(); received.clear(); published = false; }
      if (!received.isEmpty()) {
        var envelope = received.get(received.firstKey());
        if (part.parts() != parts || !part.dimension().equals(envelope.dimension()) || part.total() != envelope.total()
            || part.lanes() != envelope.lanes() || part.open() != envelope.open() || part.truncated() != envelope.truncated())
          throw new IllegalArgumentException("inconsistent graph parts");
      }
      var previous = received.putIfAbsent(part.index(), part);
      if (previous != null && !previous.equals(part)) throw new IllegalArgumentException("conflicting graph part");
      if (received.size() != parts) return Optional.empty();
      var nodes = new ArrayList<CommitGraph.Node>();
      received.values().forEach(p -> nodes.addAll(p.nodes()));
      var first = received.get(0);
      if (nodes.size() != first.total() || nodes.stream().map(CommitGraph.Node::id).distinct().count() != nodes.size())
        throw new IllegalArgumentException("graph total");
      var done = new Completed(request, first.dimension(), first.truncated(), first.lanes(), first.open(), List.copyOf(nodes));
      received.clear(); published = true;
      return Optional.of(done);
    }
    public record Completed(long request, String dimension, boolean truncated, int lanes, boolean open, List<CommitGraph.Node> nodes) {}
    public void clear() { request = -1; parts = 0; published = false; received.clear(); }
  }
}
