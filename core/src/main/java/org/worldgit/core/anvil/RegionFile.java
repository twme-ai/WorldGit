package org.worldgit.core.anvil;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.worldgit.core.model.ChunkPos;

/** 離線 Anvil IO，僅讀標頭時不載入 payload；損毀資料必須報錯，不能靜默少存 chunk。 */
public final class RegionFile implements AutoCloseable {
  private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
  private final Path path;
  private final FileChannel channel;
  private final int rx, rz;
  private final int[] locations = new int[1024], timestamps = new int[1024];

  public RegionFile(Path path) throws IOException {
    this.path = path;
    var m = NAME.matcher(path.getFileName().toString());
    if (!m.matches()) throw new IOException("region 檔名無效：" + path);
    rx = Integer.parseInt(m.group(1));
    rz = Integer.parseInt(m.group(2));
    channel = FileChannel.open(path, StandardOpenOption.READ);
    try {
      // Minecraft 在第一次寫入前會留下 0 byte 的 .mca（entities/、poi/ 常見）：視為沒有任何 chunk。
      if (channel.size() == 0) return;
      if (channel.size() < 8192) throw new IOException("region 標頭截斷：" + path);
      ByteBuffer header = ByteBuffer.allocate(8192);
      readFully(channel, header, 0);
      header.flip();
      BitSet sectors = new BitSet();
      sectors.set(0, 2);
      for (int i = 0; i < 1024; i++) {
        locations[i] = header.getInt(i * 4);
        timestamps[i] = header.getInt(4096 + i * 4);
        if (locations[i] == 0) continue;
        int off = locations[i] >>> 8, count = locations[i] & 255;
        if (off < 2
            || count == 0
            // 執行中的伺服器還沒關閉檔案時，最後一個 chunk 的尾端不一定已補滿 4096；只要求資料起點在檔內，
            // 實際長度由 payload() 的 n 與 readFully 檢查（截斷會報錯，不會靜默少存）。
            || (long) off * 4096 + 5 > channel.size()
            || sectors.nextSetBit(off) >= 0 && sectors.nextSetBit(off) < off + count)
          throw new IOException("region sector 無效或重疊：" + path + " index=" + i);
        sectors.set(off, off + count);
      }
    } catch (Throwable t) {
      channel.close();
      throw t;
    }
  }

  private static void readFully(FileChannel ch, ByteBuffer b, long off) throws IOException {
    while (b.hasRemaining()) {
      int n = ch.read(b, off + b.position());
      if (n < 0) throw new EOFException("region 截斷");
    }
  }

  public boolean has(int index) {
    return locations[index] != 0;
  }

  public int timestamp(int index) {
    return timestamps[index];
  }

  public int location(int index) {
    return locations[index];
  }

  public ChunkPos pos(int index) {
    return new ChunkPos(rx * 32 + (index & 31), rz * 32 + (index >>> 5));
  }

  public record Payload(int compression, byte[] bytes) {}

  public Payload payload(int index) throws IOException {
    if (!has(index)) return null;
    int loc = locations[index];
    long offset = (long) (loc >>> 8) * 4096;
    ByteBuffer h = ByteBuffer.allocate(5);
    readFully(channel, h, offset);
    h.flip();
    int n = h.getInt();
    int compression = h.get() & 255;
    if (n < 1 || n > (loc & 255) * 4096 - 4)
      throw new IOException("chunk 長度無效：" + path + " index=" + index);
    byte[] b;
    if ((compression & 128) != 0) {
      var p = pos(index);
      Path ext = path.resolveSibling("c." + p.x() + "." + p.z() + ".mcc");
      if (Files.size(ext) > Nbt.MAX_BYTES) throw new IOException("外部 chunk 太大：" + ext);
      b = Files.readAllBytes(ext);
    } else {
      ByteBuffer body = ByteBuffer.allocate(n - 1);
      readFully(channel, body, offset + 5);
      b = body.array();
    }
    return new Payload(compression & 127, b);
  }

  public Nbt.Compound read(int index) throws IOException {
    Payload p = payload(index);
    if (p == null) return null;
    return decode(p);
  }

  /** 解壓並解析 payload；支援 gzip/zlib/無壓縮/LZ4。 */
  public static Nbt.Compound decode(Payload p) throws IOException {
    InputStream raw = new ByteArrayInputStream(p.bytes);
    try (InputStream in =
        switch (p.compression) {
          case 1 -> new GZIPInputStream(raw);
          case 2 -> new InflaterInputStream(raw);
          case 3 -> raw;
          case 4 -> new net.jpountz.lz4.LZ4BlockInputStream(raw);
          default -> throw new IOException("未知 Anvil 壓縮：" + p.compression);
        }) {
      byte[] b = in.readNBytes(Nbt.MAX_BYTES + 1);
      if (b.length > Nbt.MAX_BYTES) throw new IOException("chunk 解壓超過限制");
      return Nbt.read(b);
    }
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  public static List<Path> list(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) return List.of();
    try (var s = Files.list(dir)) {
      return s.filter(p -> NAME.matcher(p.getFileName().toString()).matches()).sorted().toList();
    }
  }

  /** 就地更新 region（不整檔重寫）：只動被改的 chunk 的 sector；null 值表示刪除。限離線。 */
  public static void update(Path path, Map<Integer, Nbt.Compound> changes, int timestamp)
      throws IOException {
    for(int index:changes.keySet()) if(index<0 || index>=1024) throw new IOException("chunk index 無效");
    try (var writer = new RegionWriter(path)) {
      for (var e : new TreeMap<>(changes).entrySet()) {
        if (e.getKey() < 0 || e.getKey() >= 1024) throw new IOException("chunk index 無效");
        if (e.getValue() == null) writer.delete(e.getKey());
        else writer.write(e.getKey(), e.getValue(), timestamp);
      }
    }
  }

  public static void atomicWrite(Path path, byte[] bytes) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path tmp = Files.createTempFile(path.toAbsolutePath().getParent(), "worldgit-", ".tmp");
    try {
      try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      move(tmp, path);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  static void move(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
