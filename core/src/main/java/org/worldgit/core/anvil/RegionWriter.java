package org.worldgit.core.anvil;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.worldgit.core.model.ChunkPos;

/**
 * 離線就地寫入 region 檔：只改被寫入的 chunk 的 sector，其他 chunk 的位元組完全不動。
 *
 * <ul>
 *   <li>新 payload 先寫到（原位或首個足夠的空 run 或檔尾），再更新標頭，最後才釋放舊 sector；
 *   <li>超過 255 sector（約 1 MiB）的 chunk 寫到 {@code c.X.Z.mcc}，標頭壓縮欄位設 128；
 *   <li>寫入一律用 zlib（壓縮 2）；讀取支援 gzip/zlib/無壓縮/LZ4；
 *   <li>尾端的空 sector 在 close 時截掉，檔案長度補到 4096 的倍數。
 * </ul>
 */
public final class RegionWriter implements AutoCloseable {
  private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
  private final Path path;
  private final int rx, rz;
  private FileChannel channel;
  private final int[] locations = new int[1024], timestamps = new int[1024];
  private final BitSet used = new BitSet();
  private boolean dirty;

  public RegionWriter(Path path) throws IOException {
    this.path = path;
    var m = NAME.matcher(path.getFileName().toString());
    if (!m.matches()) throw new IOException("region 檔名無效：" + path);
    rx = Integer.parseInt(m.group(1));
    rz = Integer.parseInt(m.group(2));
    used.set(0, 2);
    if (Files.exists(path) && Files.size(path) > 0) {
      channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
      try {
        loadHeader();
      } catch (Throwable t) {
        channel.close();
        throw t;
      }
    }
  }

  private void loadHeader() throws IOException {
    if (channel.size() < 8192) throw new IOException("region 標頭截斷：" + path);
    ByteBuffer header = ByteBuffer.allocate(8192);
    while (header.hasRemaining()) if (channel.read(header, header.position()) < 0) throw new EOFException();
    header.flip();
    for (int i = 0; i < 1024; i++) {
      locations[i] = header.getInt(i * 4);
      timestamps[i] = header.getInt(4096 + i * 4);
      if (locations[i] == 0) continue;
      int off = locations[i] >>> 8, count = locations[i] & 255;
      if (off < 2 || count == 0 || (long)off*4096+5 > channel.size() || used.nextSetBit(off) >= 0 && used.nextSetBit(off) < off + count)
        throw new IOException("region sector 無效或重疊：" + path + " index=" + i);
      used.set(off, off + count);
    }
  }

  private void ensureOpen() throws IOException {
    if (channel != null) return;
    Files.createDirectories(path.toAbsolutePath().getParent());
    channel =
        FileChannel.open(
            path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    if (channel.size() < 8192) writeFully(ByteBuffer.allocate(8192), 0);
  }

  private static void checkIndex(int index) {
    if (index < 0 || index >= 1024) throw new IllegalArgumentException("chunk index 無效");
  }

  public boolean has(int index) {
    checkIndex(index);
    return locations[index] != 0;
  }

  public ChunkPos pos(int index) {
    return new ChunkPos(rx * 32 + (index & 31), rz * 32 + (index >>> 5));
  }

  public int timestamp(int index) {
    return timestamps[index];
  }

  private Path external(int index) {
    var p = pos(index);
    return path.resolveSibling("c." + p.x() + "." + p.z() + ".mcc");
  }

  public RegionFile.Payload payload(int index) throws IOException {
    if (!has(index)) return null;
    int loc = locations[index];
    long offset = (long) (loc >>> 8) * 4096;
    ByteBuffer h = ByteBuffer.allocate(5);
    while (h.hasRemaining()) if (channel.read(h, offset + h.position()) < 0) throw new EOFException();
    h.flip();
    int n = h.getInt();
    int compression = h.get() & 255;
    if (n < 1 || n > (loc & 255) * 4096 - 4) throw new IOException("chunk 長度無效：" + path + " index=" + index);
    byte[] b;
    if ((compression & 128) != 0) {
      Path ext = external(index);
      if (Files.size(ext) > Nbt.MAX_BYTES) throw new IOException("外部 chunk 太大：" + ext);
      b = Files.readAllBytes(ext);
    } else {
      ByteBuffer body = ByteBuffer.allocate(n - 1);
      while (body.hasRemaining())
        if (channel.read(body, offset + 5 + body.position()) < 0) throw new EOFException();
      b = body.array();
    }
    return new RegionFile.Payload(compression & 127, b);
  }

  public Nbt.Compound read(int index) throws IOException {
    var p = payload(index);
    return p == null ? null : RegionFile.decode(p);
  }

  /** 以 zlib 壓縮後寫入。 */
  public void write(int index, Nbt.Compound chunk, int timestamp) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DeflaterOutputStream(bytes)) {
      out.write(Nbt.write(chunk));
    }
    writePayload(index, 2, bytes.toByteArray(), timestamp);
  }

  public void writePayload(int index, int compression, byte[] data, int timestamp)
      throws IOException {
    checkIndex(index);
    if (compression < 1 || compression > 4 || data.length > Nbt.MAX_BYTES)
      throw new IOException("壓縮或 payload 長度無效");
    ensureOpen();
    int oldLoc = locations[index];
    boolean oldExternal = false;
    if (oldLoc != 0) {
      ByteBuffer h = ByteBuffer.allocate(5);
      channel.read(h, (long) (oldLoc >>> 8) * 4096);
      oldExternal = (h.get(4) & 128) != 0;
    }
    int count = (data.length + 5 + 4095) / 4096;
    boolean external = count > 255;
    if (external) {
      RegionFile.atomicWrite(external(index), data);
      count = 1;
    }
    int oldOff = oldLoc >>> 8, oldCount = oldLoc & 255;
    int offset = oldLoc != 0 && oldCount == count ? oldOff : allocate(count);
    if (offset > 0xffffff) throw new IOException("region offset 超過限制");
    ByteBuffer body = ByteBuffer.allocate(count * 4096);
    body.putInt(external ? 1 : data.length + 1).put((byte) (compression | (external ? 128 : 0)));
    if (!external) body.put(data);
    body.position(0);
    long at = (long) offset * 4096;
    while (body.hasRemaining()) at += channel.write(body, at);
    int loc = (offset << 8) | count;
    if (offset > 0xffffff) throw new IOException("region offset 超過限制");
    channel.force(false);
    used.set(offset, offset + count);
    setHeader(index, loc, timestamp);
    channel.force(false);
    if (oldLoc != 0 && offset != oldOff) used.clear(oldOff, oldOff + oldCount);
    if (oldExternal && !external) Files.deleteIfExists(external(index));
    dirty = true;
  }

  public void delete(int index) throws IOException {
    if (!has(index)) return;
    ensureOpen();
    int oldLoc = locations[index];
    ByteBuffer h = ByteBuffer.allocate(5);
    channel.read(h, (long) (oldLoc >>> 8) * 4096);
    boolean oldExternal = (h.get(4) & 128) != 0;
    setHeader(index, 0, 0);
    channel.force(false);
    used.clear(oldLoc >>> 8, (oldLoc >>> 8) + (oldLoc & 255));
    if (oldExternal) Files.deleteIfExists(external(index));
    dirty = true;
  }

  private void setHeader(int index, int loc, int timestamp) throws IOException {
    locations[index] = loc;
    timestamps[index] = timestamp;
    ByteBuffer b = ByteBuffer.allocate(4).putInt(0, loc);
    writeFully(b, index * 4L);
    b = ByteBuffer.allocate(4).putInt(0, timestamp);
    writeFully(b, 4096L + index * 4L);
  }

  private void writeFully(ByteBuffer buffer, long offset) throws IOException {
    while (buffer.hasRemaining()) offset += channel.write(buffer, offset);
  }

  private int allocate(int count) {
    int start = 2;
    while (true) {
      int firstUsed = used.nextSetBit(start);
      if (firstUsed < 0 || firstUsed >= start + count) return start;
      start = used.nextClearBit(firstUsed);
    }
  }

  @Override
  public void close() throws IOException {
    if (channel == null) return;
    try {
      if (dirty) {
        int end = Math.max(2, used.length());
        channel.truncate((long) end * 4096L);
        if (channel.size() < (long) end * 4096L) channel.write(ByteBuffer.allocate(1), (long) end * 4096L - 1);
        channel.force(true);
      }
    } finally {
      channel.close();
    }
  }
}
