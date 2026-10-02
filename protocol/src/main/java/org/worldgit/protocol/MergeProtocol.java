package org.worldgit.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.BlockBox;
import org.worldgit.core.merge.MergeReport;
import org.worldgit.core.merge.MergeReport.*;
import org.worldgit.core.model.DimensionId;

/** 可選新 channel，獨立 v1 envelope；未註冊 channel 的 v2 客戶端忽略。不得送給未宣告能力的 peer。 */
public final class MergeProtocol {
  private MergeProtocol() {}

  public static final int VERSION = 1,
      MAX_BATCH = 8 * 1024 * 1024,
      MAX_PARTS = 8192,
      FRAGMENT = 27_800;
  public static final String REGIONS = "worldgit:conflicts",
      PREVIEW = "worldgit:conflict_preview",
      CAPABILITY = "merge-regions-v1";

  public enum Type {
    REGIONS,
    PREVIEW
  }

  public record RegionInfo(
      int id, BlockBox bounds, int blockCount, Choice choice, boolean resolved, boolean redstone) {
    public RegionInfo {
      if (id < 1 || blockCount < 0) throw new IllegalArgumentException("region id/count");
      Objects.requireNonNull(choice);
      if (bounds != null) {
        coordinate(bounds.minX(), bounds.minY(), bounds.minZ());
        coordinate(bounds.maxX(), bounds.maxY(), bounds.maxZ());
        if (bounds.minX() > bounds.maxX()
            || bounds.minY() > bounds.maxY()
            || bounds.minZ() > bounds.maxZ()) throw new IllegalArgumentException("region bounds");
      }
    }
  }

  public record PreviewCell(Cell position, String state, byte[] blockEntity) {
    public PreviewCell {
      Objects.requireNonNull(position);
      Objects.requireNonNull(state);
      coordinate(position.x(), position.y(), position.z());
      blockEntity = blockEntity == null ? null : blockEntity.clone();
    }

    @Override
    public byte[] blockEntity() {
      return blockEntity == null ? null : blockEntity.clone();
    }
  }

  public record Part(
      int version,
      Type type,
      long preview,
      int sequence,
      int parts,
      int totalBytes,
      DimensionId dimension,
      byte[] data) {
    public Part {
      if (version != VERSION
          || preview < 0
          || parts < 1
          || parts > MAX_PARTS
          || sequence < 0
          || sequence >= parts
          || totalBytes < 1
          || totalBytes > MAX_BATCH
          || data.length < 1
          || data.length > FRAGMENT
          || data.length > totalBytes) throw new IllegalArgumentException("merge part limits");
      Objects.requireNonNull(type);
      Objects.requireNonNull(dimension);
      data = data.clone();
    }

    @Override
    public byte[] data() {
      return data.clone();
    }
  }

  public record Completed(
      Type type,
      long preview,
      DimensionId dimension,
      List<RegionInfo> regions,
      int region,
      Choice choice,
      List<PreviewCell> cells) {
    public Completed {
      regions = List.copyOf(regions);
      cells = List.copyOf(cells);
    }
  }

  public static List<byte[]> regions(
      long preview, DimensionId dimension, List<MergeReport.Region> regions) throws IOException {
    if (regions.size() > Protocol.MAX_ENTRIES) throw new IOException("衝突區域超過 100000");
    var list = new ArrayList<Object>();
    var ids = new HashSet<Integer>();
    for (var r : regions) {
      if (!r.dimension().equals(dimension) || !ids.add(r.id()))
        throw new IOException("衝突區域維度／id 不一致");
      var row =
          new Nbt.Compound()
              .with("id", r.id())
              .with("count", r.blockCount())
              .with("choice", r.choice().name())
              .with("resolved", (byte) (r.resolved() ? 1 : 0))
              .with("redstone", (byte) (r.redstone() ? 1 : 0));
      if (r.bounds() != null) {
        var b = r.bounds();
        row.put("bounds", new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()});
      }
      list.add(row);
    }
    return split(
        Type.REGIONS,
        preview,
        dimension,
        Nbt.write(new Nbt.Compound().with("regions", new Nbt.ListTag(10, list))));
  }

  public static List<byte[]> preview(
      long preview, DimensionId dimension, int region, Choice choice, List<PreviewCell> cells)
      throws IOException {
    if (region < 1 || choice == Choice.MANUAL || cells.size() > Protocol.MAX_ENTRIES)
      throw new IOException("preview region／choice／count 無效");
    var list = new ArrayList<Object>();
    var positions = new HashSet<Cell>();
    long size = 0;
    for (var c : cells) {
      if (!positions.add(c.position())) throw new IOException("重複 preview 座標");
      size += c.state().length() * 3L + 64 + (c.blockEntity == null ? 0 : c.blockEntity.length);
      if (size > MAX_BATCH) throw new IOException("preview 超過 8 MiB");
      var p = c.position();
      var row =
          new Nbt.Compound().with("pos", new int[] {p.x(), p.y(), p.z()}).with("state", c.state());
      if (c.blockEntity != null) {
        Nbt.read(c.blockEntity);
        row.put("be", c.blockEntity);
      }
      list.add(row);
    }
    return split(
        Type.PREVIEW,
        preview,
        dimension,
        Nbt.write(
            new Nbt.Compound()
                .with("region", region)
                .with("choice", choice.name())
                .with("cells", new Nbt.ListTag(10, list))));
  }

  private static List<byte[]> split(Type type, long preview, DimensionId dimension, byte[] body)
      throws IOException {
    if (body.length > MAX_BATCH) throw new IOException("merge batch 超過 8 MiB");
    int parts = (body.length + FRAGMENT - 1) / FRAGMENT;
    var result = new ArrayList<byte[]>();
    for (int i = 0; i < parts; i++)
      result.add(
          encode(
              new Part(
                  VERSION,
                  type,
                  preview,
                  i,
                  parts,
                  body.length,
                  dimension,
                  Arrays.copyOfRange(
                      body, i * FRAGMENT, Math.min(body.length, (i + 1) * FRAGMENT)))));
    return List.copyOf(result);
  }

  public static byte[] encode(Part p) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeByte(p.version);
    out.writeByte(p.type.ordinal());
    out.writeLong(p.preview);
    out.writeInt(p.sequence);
    out.writeInt(p.parts);
    out.writeInt(p.totalBytes);
    byte[] dim = p.dimension.value().getBytes(StandardCharsets.UTF_8);
    if (dim.length > 128) throw new IOException("維度 id 過長");
    out.writeByte(dim.length);
    out.write(dim);
    out.writeInt(p.data.length);
    out.write(p.data);
    if (bytes.size() > Protocol.MAX_PAYLOAD) throw new IOException("merge payload 超過 28000");
    return bytes.toByteArray();
  }

  public static Part decode(byte[] bytes) throws IOException {
    if (bytes.length > Protocol.MAX_PAYLOAD) throw new IOException("merge payload 過大");
    var in = new DataInputStream(new ByteArrayInputStream(bytes));
    try {
      int v = in.readUnsignedByte(), type = in.readUnsignedByte();
      if (type >= Type.values().length) throw new IOException("merge message type");
      long id = in.readLong();
      int seq = in.readInt(), parts = in.readInt(), total = in.readInt(), n = in.readUnsignedByte();
      if (n > 128) throw new IOException("dimension length");
      byte[] dim = in.readNBytes(n);
      if (dim.length != n) throw new EOFException();
      String dimension =
          StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(dim)).toString();
      int length = in.readInt();
      if (length < 1 || length > FRAGMENT || length != in.available())
        throw new IOException("merge fragment length");
      return new Part(
          v,
          Type.values()[type],
          id,
          seq,
          parts,
          total,
          new DimensionId(dimension),
          in.readNBytes(length));
    } catch (IllegalArgumentException ex) {
      throw new IOException("merge payload 無效", ex);
    }
  }

  private static Completed complete(Part p, byte[] bytes) throws IOException {
    var root = Nbt.read(bytes);
    try {
      if (p.type == Type.REGIONS) {
        var rs = new ArrayList<RegionInfo>();
        var ids = new HashSet<Integer>();
        if (root.list("regions").values().size() > Protocol.MAX_ENTRIES)
          throw new IOException("regions count");
        for (Object v : root.list("regions").values()) {
          var r = (Nbt.Compound) v;
          BlockBox b = null;
          if (r.containsKey("bounds")) {
            int[] a = (int[]) r.get("bounds");
            if (a.length != 6) throw new IOException("bounds length");
            b = new BlockBox(a[0], a[1], a[2], a[3], a[4], a[5]);
          }
          int id = r.integer("id", 0);
          if (!ids.add(id)) throw new IOException("重複 region id");
          rs.add(
              new RegionInfo(
                  id,
                  b,
                  r.integer("count", 0),
                  Choice.valueOf(r.string("choice")),
                  flag(r, "resolved"),
                  flag(r, "redstone")));
        }
        return new Completed(p.type, p.preview, p.dimension, rs, 0, null, List.of());
      }
      var cells = new ArrayList<PreviewCell>();
      var positions = new HashSet<Cell>();
      if (root.list("cells").values().size() > Protocol.MAX_ENTRIES)
        throw new IOException("preview count");
      for (Object v : root.list("cells").values()) {
        var c = (Nbt.Compound) v;
        int[] a = (int[]) c.get("pos");
        if (a.length != 3) throw new IOException("pos length");
        var pos = new Cell(a[0], a[1], a[2]);
        if (!positions.add(pos)) throw new IOException("重複 preview 座標");
        byte[] be = c.containsKey("be") ? (byte[]) c.get("be") : null;
        if (be != null) Nbt.read(be);
        cells.add(new PreviewCell(pos, c.string("state"), be));
      }
      int id = root.integer("region", 0);
      var choice = Choice.valueOf(root.string("choice"));
      if (id < 1 || choice == Choice.MANUAL) throw new IOException("preview region/choice");
      return new Completed(p.type, p.preview, p.dimension, List.of(), id, choice, cells);
    } catch (RuntimeException ex) {
      throw new IOException("merge body 無效", ex);
    }
  }

  private static boolean flag(Nbt.Compound r, String key) throws IOException {
    int value = r.integer(key, -1);
    if (value < 0 || value > 1) throw new IOException("merge flag 無效");
    return value == 1;
  }

  private static void coordinate(int x, int y, int z) {
    if (Math.abs((long) x) > 30_000_000
        || Math.abs((long) z) > 30_000_000
        || Math.abs((long) y) > 32_768) throw new IllegalArgumentException("coordinate limit");
  }

  /** 每連線／channel／維度各自建立；只在完整且有界的批次才發布。 */
  public static final class Assembler {
    private Part header;
    private final Map<Integer, byte[]> data = new HashMap<>();
    private long floor = -1, started;
    private int received;

    public void reset() {
      floor = -1;
      discard();
    }

    public void clear(long id) {
      floor = Math.max(floor, id);
      if (header != null && header.preview <= floor) discard();
    }

    private void discard() {
      header = null;
      data.clear();
      received = 0;
    }

    public Optional<Completed> accept(Part part, long now) throws IOException {
      if (part.preview <= floor) return Optional.empty();
      if (header != null && now - started > 30_000) discard();
      if (header == null || part.preview > header.preview) {
        discard();
        header = part;
        started = now;
      } else if (part.preview < header.preview) return Optional.empty();
      if (part.type != header.type
          || part.parts != header.parts
          || part.totalBytes != header.totalBytes
          || !part.dimension.equals(header.dimension))
        throw new IOException("merge batch header 不一致");
      byte[] prior = data.get(part.sequence);
      if (prior != null) {
        if (!Arrays.equals(prior, part.data)) throw new IOException("merge 重傳內容不同");
        return Optional.empty();
      }
      if (received + part.data.length > header.totalBytes)
        throw new IOException("merge batch 長度超過 header");
      data.put(part.sequence, part.data());
      received += part.data.length;
      if (data.size() != header.parts) return Optional.empty();
      if (received != header.totalBytes) throw new IOException("merge batch 長度不符");
      var out = new ByteArrayOutputStream(received);
      for (int i = 0; i < header.parts; i++) out.write(data.get(i));
      var result = complete(header, out.toByteArray());
      floor = header.preview;
      discard();
      return Optional.of(result);
    }
  }
}
