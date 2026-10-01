package org.worldgit.core.normalize;

import com.github.luben.zstd.Zstd;
import java.io.*;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.*;

/** 正式 blob envelope：WG + kind + version 1，zstd level 6；不是 Phase 0 的 wire format。 */
public final class SnapshotCodec {
  private SnapshotCodec() {}

  private static byte[] encode(int kind, byte[] raw) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeShort(0x5747);
    out.writeByte(kind);
    out.writeByte(1);
    out.write(raw);
    return Zstd.compress(bytes.toByteArray(), 6);
  }

  private static byte[] decode(int kind, byte[] blob) throws IOException {
    long size = Zstd.getFrameContentSize(blob);
    if (size < 4 || size > Nbt.MAX_BYTES) throw new IOException("blob zstd 長度無效");
    DecodeBudget.objects(1);
    DecodeBudget.decoded(size); // 在配置及解壓縮之前扣除跨物件預算
    byte[] raw = new byte[(int) size];
    long n = Zstd.decompress(raw, blob);
    if (Zstd.isError(n) || n != size) throw new IOException("blob zstd 解碼失敗");
    var in = new DataInputStream(new ByteArrayInputStream(raw));
    if (in.readUnsignedShort() != 0x5747
        || in.readUnsignedByte() != kind
        || in.readUnsignedByte() != 1) throw new IOException("blob 型別或版本不支援");
    return in.readAllBytes();
  }

  public static byte[] section(Section section) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    var palette = new LinkedHashMap<BlockState, Integer>();
    for (BlockState s : section.blocks()) palette.computeIfAbsent(s, k -> palette.size());
    out.writeShort(palette.size());
    for (var state : palette.keySet()) {
      out.writeUTF(state.name());
      out.writeShort(state.properties().size());
      for (var p : state.properties().entrySet()) {
        out.writeUTF(p.getKey());
        out.writeUTF(p.getValue());
      }
    }
    int bits = ChunkNormalizer.ceilLog2(palette.size());
    out.writeByte(bits);
    byte[] packed = new byte[(4096 * bits + 7) / 8];
    for (int i = 0; i < 4096; i++) {
      int index = palette.get(section.block(i));
      for (int bit = 0; bit < bits; bit++)
        if ((index & (1 << bit)) != 0) {
          int off = i * bits + bit;
          packed[off >>> 3] |= (byte) (1 << (off & 7));
        }
    }
    out.write(packed);
    var entities = section.blockEntities();
    out.writeShort(entities.size());
    for (var e : entities.entrySet()) {
      out.writeShort(e.getKey());
      out.writeInt(e.getValue().length);
      out.write(e.getValue());
    }
    return encode(1, bytes.toByteArray());
  }

  public static Section section(byte[] blob) throws IOException {
    var in = new DataInputStream(new ByteArrayInputStream(decode(1, blob)));
    DecodeBudget.work(4096);
    int count = in.readUnsignedShort();
    if (count < 1 || count > 4096) throw new IOException("section palette 大小無效");
    DecodeBudget.decoded((long) count * 128 + 4096 * 8L); // 調色盤物件及展開後 block 引用
    var palette = new ArrayList<BlockState>();
    for (int i = 0; i < count; i++) {
      String name = in.readUTF();
      int n = in.readUnsignedShort();
      if (n > 128) throw new IOException("block properties 超過限制");
      var properties = new TreeMap<String, String>();
      for (int p = 0; p < n; p++) {
        String k = in.readUTF();
        if (properties.put(k, in.readUTF()) != null) throw new IOException("重複 block property");
      }
      palette.add(new BlockState(name, properties));
    }
    int bits = in.readUnsignedByte();
    if (bits != ChunkNormalizer.ceilLog2(count)) throw new IOException("section bits 無效");
    DecodeBudget.work(4096L * Math.max(1, bits));
    byte[] packed = in.readNBytes((4096 * bits + 7) / 8);
    if (packed.length != (4096 * bits + 7) / 8) throw new EOFException();
    var blocks = new ArrayList<BlockState>();
    for (int i = 0; i < 4096; i++) {
      int index = 0;
      for (int bit = 0; bit < bits; bit++) {
        int off = i * bits + bit;
        if ((packed[off >>> 3] & (1 << (off & 7))) != 0) index |= 1 << bit;
      }
      if (index >= count) throw new IOException("section 索引越界");
      blocks.add(palette.get(index));
    }
    int n = in.readUnsignedShort();
    if (n > 4096) throw new IOException("BE 數量無效");
    var entities = new TreeMap<Integer, byte[]>();
    for (int i = 0; i < n; i++) {
      int pos = in.readUnsignedShort();
      int len = in.readInt();
      if (pos >= 4096 || len < 0 || len > in.available()) throw new IOException("BE 長度或座標無效");
      byte[] b = in.readNBytes(len);
      Nbt.read(b);
      if (entities.put(pos, b) != null) throw new IOException("BE 座標重複");
    }
    if (in.available() != 0) throw new IOException("section 有多餘位元組");
    return new Section(blocks, entities);
  }

  public static byte[] nbt(int kind, byte[] nbt) throws IOException {
    return encode(kind, nbt);
  }

  public static byte[] nbt(int kind, byte[] blob, boolean decode) throws IOException {
    return decode(kind, blob);
  }

  public static byte[] biomes(Map<Integer, List<String>> biomes) throws IOException {
    var root = new Nbt.Compound();
    for (var e : biomes.entrySet())
      root.put(e.getKey().toString(), new Nbt.ListTag(8, new ArrayList<>(e.getValue())));
    return encode(2, Nbt.write(root));
  }

  public static SortedMap<Integer, List<String>> biomes(byte[] blob) throws IOException {
    var root = Nbt.read(decode(2, blob));
    var result = new TreeMap<Integer, List<String>>();
    try {
      for (var e : root.entrySet()) {
        var list = (Nbt.ListTag) e.getValue();
        if (list.values().size() != 64 || list.type() != 8)
          throw new IOException("biome 必須有 64 個 sample");
        result.put(
            Integer.parseInt(e.getKey()), list.values().stream().map(String.class::cast).toList());
      }
    } catch (RuntimeException e) {
      throw new IOException("biome 格式無效", e);
    }
    return result;
  }

  public static byte[] entities(List<EntitySnapshot> entities) throws IOException {
    var sorted = new ArrayList<>(entities);
    sorted.sort(Comparator.comparing(e -> e.uuid().toString()));
    var root = new Nbt.Compound();
    root.put("entities", new Nbt.ListTag(10, sorted.stream().map(e -> (Object) e.data()).toList()));
    return encode(3, Nbt.write(root));
  }

  public static List<EntitySnapshot> entities(byte[] blob) throws IOException {
    var list = new ArrayList<EntitySnapshot>();
    try {
      for (Object value : Nbt.read(decode(3, blob)).list("entities").values()) {
        var e = (Nbt.Compound) value;
        list.add(new EntitySnapshot(EntityNormalizer.uuid(e), e));
      }
    } catch (RuntimeException e) {
      throw new IOException("entity blob 無效", e);
    }
    return List.copyOf(list);
  }

  public static Map<String, byte[]> chunkFiles(ChunkSnapshot snapshot) throws IOException {
    var files = new TreeMap<String, byte[]>();
    for (var e : snapshot.sections().entrySet())
      if (!e.getValue().empty()) files.put("s." + e.getKey() + ".bin", section(e.getValue()));
    if (!snapshot.biomes().isEmpty()) files.put("biomes.bin", biomes(snapshot.biomes()));
    if (!snapshot.entities().isEmpty()) files.put("entities.bin", entities(snapshot.entities()));
    if (snapshot.ticks() != null) files.put("ticks.bin", nbt(4, snapshot.ticks()));
    if (snapshot.structures() != null) files.put("structures.bin", nbt(5, snapshot.structures()));
    return files;
  }
}
