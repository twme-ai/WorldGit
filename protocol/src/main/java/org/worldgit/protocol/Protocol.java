package org.worldgit.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.DimensionId;

/** v2：hello/diff/status/clear。最外層是獨立 payload，禁止未驗證長度或未完成批次進入渲染。 */
public final class Protocol {
  private Protocol() {}

  public static final int VERSION = 2,
      MAX_PAYLOAD = 28_000,
      MAX_ENTRIES = 100_000,
      MAX_PARTS = 8192;
  public static final String HELLO = "worldgit:hello",
      DIFF = "worldgit:diff",
      STATUS = "worldgit:status",
      CLEAR = "worldgit:clear";
  public static final List<String> CAPABILITIES =
      List.of("ghost-render", "outline", "status-outline", "section-palette-v2");

  public sealed interface Message permits Hello, Clear, DiffPart, StatusPart {}

  public record Hello(int version, long nonce, List<String> capabilities, DiffPalette palette)
      implements Message {
    public Hello {
      capabilities = List.copyOf(capabilities);
      if (version < 1 || version > 255 || capabilities.size() > 16)
        throw new IllegalArgumentException("hello limits");
      Objects.requireNonNull(palette);
    }
  }

  public record Clear(long preview) implements Message {
    public Clear {
      if (preview < 0) throw new IllegalArgumentException("preview id");
    }
  }

  public record Cell(int x, int y, int z, ChangeKind kind, String before, String after) {
    public Cell {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(before);
      Objects.requireNonNull(after);
      coordinate(x, y, z);
    }
  }

  public record Outline(
      int x1,
      int y1,
      int z1,
      int x2,
      int y2,
      int z2,
      ChangeKind kind,
      long added,
      long removed,
      long modified,
      long conflicts) {
    public Outline {
      coordinate(x1, y1, z1);
      coordinate(x2, y2, z2);
      Objects.requireNonNull(kind);
      if (x1 > x2
          || y1 > y2
          || z1 > z2
          || added < 0
          || removed < 0
          || modified < 0
          || conflicts < 0) throw new IllegalArgumentException("outline bounds/counts");
    }
  }

  public record Header(
      long preview, int sequence, int parts, int totalEntries, DimensionId dimension) {
    public Header {
      if (preview < 0
          || parts < 1
          || parts > MAX_PARTS
          || sequence < 0
          || sequence >= parts
          || totalEntries < 1
          || totalEntries > MAX_ENTRIES) throw new IllegalArgumentException("batch header limits");
      Objects.requireNonNull(dimension);
    }
  }

  public record DiffPart(Header header, List<Cell> cells) implements Message {
    public DiffPart {
      cells = List.copyOf(cells);
      if (cells.isEmpty() || cells.size() > header.totalEntries)
        throw new IllegalArgumentException("cells count");
    }
  }

  public record StatusPart(Header header, List<Outline> outlines) implements Message {
    public StatusPart {
      outlines = List.copyOf(outlines);
      if (outlines.isEmpty() || outlines.size() > header.totalEntries)
        throw new IllegalArgumentException("outlines count");
    }
  }

  private record Section(int x, int y, int z) {}

  private static void coordinate(int x, int y, int z) {
    if (Math.abs((long) x) > 30_000_000
        || Math.abs((long) z) > 30_000_000
        || Math.abs((long) y) > 32_768) throw new IllegalArgumentException("coordinate limit");
  }

  public static byte[] encode(Message message) throws IOException {
    byte[] bytes = encodeUnchecked(message);
    if (bytes.length > MAX_PAYLOAD) throw new IOException("payload 超過 28000 bytes");
    return bytes;
  }

  private static byte[] encodeUnchecked(Message message) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeByte(VERSION);
    switch (message) {
      case Hello h -> {
        out.writeByte(0);
        out.writeByte(h.version);
        out.writeLong(h.nonce);
        varint(out, h.capabilities.size());
        for (String c : h.capabilities) string(out, c);
        for (var kind : ChangeKind.values()) out.writeInt(h.palette.rgb(kind));
      }
      case Clear c -> {
        out.writeByte(1);
        out.writeLong(c.preview);
      }
      case DiffPart p -> {
        out.writeByte(2);
        header(out, p.header);
        var grouped = new LinkedHashMap<Section, List<Cell>>();
        for (var c : p.cells)
          grouped
              .computeIfAbsent(
                  new Section(
                      Math.floorDiv(c.x, 16), Math.floorDiv(c.y, 16), Math.floorDiv(c.z, 16)),
                  k -> new ArrayList<>())
              .add(c);
        varint(out, grouped.size());
        for (var e : grouped.entrySet()) {
          out.writeInt(e.getKey().x);
          out.writeInt(e.getKey().y);
          out.writeInt(e.getKey().z);
          var palette = new LinkedHashMap<String, Integer>();
          for (var c : e.getValue()) {
            palette.computeIfAbsent(c.before, k -> palette.size());
            palette.computeIfAbsent(c.after, k -> palette.size());
          }
          varint(out, palette.size());
          for (String s : palette.keySet()) string(out, s);
          varint(out, e.getValue().size());
          for (var c : e.getValue()) {
            out.writeShort(
                (c.x & 15) | ((c.z & 15) << 4) | ((c.y & 15) << 8) | (c.kind.ordinal() << 12));
            varint(out, palette.get(c.before));
            varint(out, palette.get(c.after));
          }
        }
      }
      case StatusPart p -> {
        out.writeByte(3);
        header(out, p.header);
        varint(out, p.outlines.size());
        for (var o : p.outlines) {
          out.writeInt(o.x1);
          out.writeInt(o.y1);
          out.writeInt(o.z1);
          out.writeInt(o.x2);
          out.writeInt(o.y2);
          out.writeInt(o.z2);
          out.writeByte(o.kind.ordinal());
          out.writeLong(o.added);
          out.writeLong(o.removed);
          out.writeLong(o.modified);
          out.writeLong(o.conflicts);
        }
      }
    }
    return bytes.toByteArray();
  }

  public static Message decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_PAYLOAD) throw new IOException("payload 太大");
    var in = new DataInputStream(new ByteArrayInputStream(bytes));
    int version = in.readUnsignedByte(), type = in.readUnsignedByte();
    if (version != VERSION) throw new IOException("不支援協定版本：" + version);
    try {
      Message message =
          switch (type) {
            case 0 -> {
              int peer = in.readUnsignedByte();
              long nonce = in.readLong();
              int n = varint(in, 16);
              var capabilities = new ArrayList<String>();
              for (int i = 0; i < n; i++) capabilities.add(string(in));
              var colors = new EnumMap<ChangeKind, Integer>(ChangeKind.class);
              for (var k : ChangeKind.values()) colors.put(k, in.readInt());
              yield new Hello(peer, nonce, capabilities, new DiffPalette(colors));
            }
            case 1 -> new Clear(in.readLong());
            case 2 -> {
              Header h = header(in);
              int n = varint(in, 4096);
              var cells = new ArrayList<Cell>();
              for (int s = 0; s < n; s++) {
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                if (Math.abs((long) x) > 1_875_000
                    || Math.abs((long) z) > 1_875_000
                    || Math.abs((long) y) > 2048) throw new IOException("section coordinate limit");
                int np = varint(in, 8192);
                if (np < 1) throw new IOException("empty palette");
                var palette = new ArrayList<String>();
                for (int i = 0; i < np; i++) palette.add(string(in));
                int nc = varint(in, 4096);
                if (cells.size() + nc > h.totalEntries) throw new IOException("entry count limit");
                for (int i = 0; i < nc; i++) {
                  int packed = in.readUnsignedShort();
                  if ((packed & 0xC000) != 0) throw new IOException("reserved cell bits");
                  int a = varint(in, np - 1), b = varint(in, np - 1);
                  cells.add(
                      new Cell(
                          x * 16 + (packed & 15),
                          y * 16 + ((packed >> 8) & 15),
                          z * 16 + ((packed >> 4) & 15),
                          ChangeKind.values()[packed >> 12],
                          palette.get(a),
                          palette.get(b)));
                }
              }
              yield new DiffPart(h, cells);
            }
            case 3 -> {
              Header h = header(in);
              int n = varint(in, h.totalEntries);
              var outlines = new ArrayList<Outline>();
              for (int i = 0; i < n; i++) {
                int x1 = in.readInt(),
                    y1 = in.readInt(),
                    z1 = in.readInt(),
                    x2 = in.readInt(),
                    y2 = in.readInt(),
                    z2 = in.readInt(),
                    k = in.readUnsignedByte();
                if (k > 3) throw new IOException("outline kind");
                outlines.add(
                    new Outline(
                        x1,
                        y1,
                        z1,
                        x2,
                        y2,
                        z2,
                        ChangeKind.values()[k],
                        in.readLong(),
                        in.readLong(),
                        in.readLong(),
                        in.readLong()));
              }
              yield new StatusPart(h, outlines);
            }
            default -> throw new IOException("未知 message type：" + type);
          };
      if (in.available() != 0) throw new IOException("payload 多餘資料");
      return message;
    } catch (IllegalArgumentException e) {
      throw new IOException("payload 格式無效：" + e.getMessage(), e);
    }
  }

  private static void header(DataOutputStream out, Header h) throws IOException {
    out.writeLong(h.preview);
    varint(out, h.sequence);
    varint(out, h.parts);
    varint(out, h.totalEntries);
    string(out, h.dimension.value());
  }

  private static Header header(DataInputStream in) throws IOException {
    return new Header(
        in.readLong(),
        varint(in, MAX_PARTS - 1),
        varint(in, MAX_PARTS),
        varint(in, MAX_ENTRIES),
        new DimensionId(string(in)));
  }

  private static void varint(DataOutputStream out, int value) throws IOException {
    if (value < 0) throw new IOException("negative varint");
    do {
      int b = value & 127;
      value >>>= 7;
      out.writeByte(b | (value == 0 ? 0 : 128));
    } while (value != 0);
  }

  private static int varint(DataInputStream in, int max) throws IOException {
    long n = 0;
    for (int i = 0; i < 5; i++) {
      int b = in.readUnsignedByte();
      n |= (long) (b & 127) << (i * 7);
      if ((b & 128) == 0) {
        if (n > max) throw new IOException("varint 超過限制");
        return (int) n;
      }
    }
    throw new IOException("varint 過長");
  }

  private static void string(DataOutputStream out, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    if (b.length > 2048) throw new IOException("string 太長");
    varint(out, b.length);
    out.write(b);
  }

  private static String string(DataInputStream in) throws IOException {
    int n = varint(in, 2048);
    byte[] b = in.readNBytes(n);
    if (b.length != n) throw new EOFException();
    try {
      return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new IOException("UTF-8 無效", e);
    }
  }

  public static List<byte[]> diff(long id, WorldDiff diff) throws IOException {
    if (diff.sections().stream()
        .anyMatch(
            s ->
                s.blocks().isEmpty()
                    && (s.counts().added()
                            + s.counts().removed()
                            + s.counts().modified()
                            + s.counts().conflict()
                        > 0)))
      throw new IOException("鬼影 diff 需要 DiffEngine.Detail.BLOCKS；摘要請使用 Protocol.status");
    List<Cell> cells =
        diff.sections().stream()
            .flatMap(s -> s.blocks().stream())
            .map(
                b ->
                    new Cell(
                        b.pos().x(),
                        b.pos().y(),
                        b.pos().z(),
                        b.kind(),
                        b.before().canonical(),
                        b.after().canonical()))
            .toList();
    return split(id, diff.dimension(), cells, true);
  }

  public static List<byte[]> status(long id, WorldDiff diff) throws IOException {
    return status(id, diff, -64, 319);
  }

  public static List<byte[]> status(long id, WorldDiff diff, int minY, int maxY)
      throws IOException {
    var outlines = new ArrayList<Outline>();
    for (var s : diff.sections()) {
      var c = s.counts();
      outlines.add(
          new Outline(
              s.chunk().x() * 16,
              s.sectionY() * 16,
              s.chunk().z() * 16,
              s.chunk().x() * 16 + 15,
              s.sectionY() * 16 + 15,
              s.chunk().z() * 16 + 15,
              priority(c),
              c.added(),
              c.removed(),
              c.modified(),
              c.conflict()));
    }
    var represented = new HashSet<org.worldgit.core.model.ChunkPos>();
    diff.sections().forEach(s -> represented.add(s.chunk()));
    for (var chunk : diff.chunks())
      if (!represented.contains(chunk))
        outlines.add(
            new Outline(
                chunk.x() * 16,
                minY,
                chunk.z() * 16,
                chunk.x() * 16 + 15,
                maxY,
                chunk.z() * 16 + 15,
                ChangeKind.MODIFIED,
                0,
                0,
                0,
                0));
    return split(id, diff.dimension(), outlines, false);
  }

  private static ChangeKind priority(WorldDiff.Counts c) {
    return c.conflict() > 0
        ? ChangeKind.CONFLICT
        : c.modified() > 0
            ? ChangeKind.MODIFIED
            : c.removed() > 0 ? ChangeKind.REMOVED : ChangeKind.ADDED;
  }

  @SuppressWarnings("unchecked")
  private static List<byte[]> split(long id, DimensionId dimension, List<?> entries, boolean diff)
      throws IOException {
    if (entries.isEmpty()) return List.of(encode(new Clear(id)));
    if (entries.size() > MAX_ENTRIES)
      throw new IOException("超過 100000 entries；請改用 status 包圍盒或依區域分批");
    var groups = new ArrayList<List<?>>();
    int start = 0;
    // 二分搜尋每包可容納數量，依實際編碼大小決定，不假設 palette 大小。
    while (start < entries.size()) {
      int low = 1, high = Math.min(4096, entries.size() - start), fit = 0;
      while (low <= high) {
        int n = (low + high) >>> 1;
        var h = new Header(id, 0, MAX_PARTS, entries.size(), dimension);
        Message m =
            diff
                ? new DiffPart(h, (List<Cell>) entries.subList(start, start + n))
                : new StatusPart(h, (List<Outline>) entries.subList(start, start + n));
        if (encodeUnchecked(m).length <= MAX_PAYLOAD) {
          fit = n;
          low = n + 1;
        } else high = n - 1;
      }
      if (fit == 0) throw new IOException("單筆 entry 太大");
      groups.add(List.copyOf(entries.subList(start, start + fit)));
      start += fit;
    }
    if (groups.size() > MAX_PARTS) throw new IOException("parts 超過限制");
    var packets = new ArrayList<byte[]>();
    for (int i = 0; i < groups.size(); i++) {
      var h = new Header(id, i, groups.size(), entries.size(), dimension);
      packets.add(
          encode(
              diff
                  ? new DiffPart(h, (List<Cell>) groups.get(i))
                  : new StatusPart(h, (List<Outline>) groups.get(i))));
    }
    return List.copyOf(packets);
  }
}
