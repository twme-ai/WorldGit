package wgproto;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Anvil region 檔（.mca）讀寫。支援壓縮 1=gzip 2=zlib 3=無 4=LZ4（lz4-java block stream）與外部 .mcc。 */
public final class Region {
    public static final class Entry {
        public int timestamp;
        public int compression; // 含 128 旗標時表示外部檔
        public byte[] payload;  // 壓縮後的資料（不含 5 byte 標頭）
    }

    public final Path path;
    public final int rx, rz;
    /** idx = (cz&31)*32 + (cx&31) → entry；未載入 payload 之前只有 timestamp。 */
    private final Entry[] entries = new Entry[1024];
    private final byte[] raw;

    public Region(Path path) throws IOException {
        this.path = path;
        String[] p = path.getFileName().toString().split("\\.");
        rx = Integer.parseInt(p[1]); rz = Integer.parseInt(p[2]);
        raw = Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
        if (raw.length >= 8192) {
            ByteBuffer bb = ByteBuffer.wrap(raw);
            for (int i = 0; i < 1024; i++) {
                int loc = bb.getInt(i * 4);
                int off = loc >>> 8;
                if (off == 0) continue;
                Entry e = new Entry();
                e.timestamp = bb.getInt(4096 + i * 4);
                int pos = off * 4096;
                int len = bb.getInt(pos);
                e.compression = raw[pos + 4] & 0xFF;
                if (len - 1 < 0 || pos + 5 + len - 1 > raw.length) continue; // 損毀，略過
                e.payload = Arrays.copyOfRange(raw, pos + 5, pos + 4 + len);
                entries[i] = e;
            }
        }
    }

    public static int idx(int cx, int cz) { return (cz & 31) * 32 + (cx & 31); }
    public boolean has(int idx) { return entries[idx] != null; }
    public int timestamp(int idx) { return entries[idx] == null ? 0 : entries[idx].timestamp; }
    public Entry entry(int idx) { return entries[idx]; }

    public Nbt.NCompound read(int idx) throws IOException {
        Entry e = entries[idx];
        if (e == null) return null;
        byte[] data = e.payload;
        int comp = e.compression;
        if ((comp & 128) != 0) {
            int cx = rx * 32 + (idx & 31), cz = rz * 32 + (idx >> 5);
            data = Files.readAllBytes(path.resolveSibling("c." + cx + "." + cz + ".mcc"));
            comp &= 127;
        }
        InputStream in = new ByteArrayInputStream(data);
        switch (comp) {
            case 1 -> in = new GZIPInputStream(in);
            case 2 -> in = new InflaterInputStream(in);
            case 3 -> {}
            case 4 -> in = new net.jpountz.lz4.LZ4BlockInputStream(in);
            default -> throw new IOException("unknown compression " + comp);
        }
        return Nbt.readRoot(new DataInputStream(new BufferedInputStream(in)));
    }

    /** 設定/取代某 chunk 的內容（以 zlib 壓縮）。nbt=null 表示移除。 */
    public void put(int idx, Nbt.NCompound nbt, int timestamp) throws IOException {
        if (nbt == null) { entries[idx] = null; return; }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bo))) {
            Nbt.writeRoot(out, nbt, false);
        }
        Entry e = new Entry();
        e.timestamp = timestamp; e.compression = 2; e.payload = bo.toByteArray();
        entries[idx] = e;
    }

    /** 整檔重寫（未改動的 chunk 沿用原壓縮資料）。全部移除則刪除檔案。 */
    public void save() throws IOException {
        boolean any = false;
        for (Entry e : entries) if (e != null) { any = true; break; }
        if (!any) { Files.deleteIfExists(path); return; }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] loc = new int[1024];
        int sector = 2;
        for (int i = 0; i < 1024; i++) {
            Entry e = entries[i];
            if (e == null) continue;
            int total = 5 + e.payload.length;
            int sectors = (total + 4095) / 4096;
            if (sectors > 255) throw new IOException("chunk too large for inline storage (.mcc write unsupported)");
            ByteBuffer bb = ByteBuffer.allocate(sectors * 4096);
            bb.putInt(e.payload.length + 1);
            bb.put((byte) e.compression);
            bb.put(e.payload);
            body.write(bb.array());
            loc[i] = (sector << 8) | sectors;
            sector += sectors;
        }
        ByteBuffer hdr = ByteBuffer.allocate(8192);
        for (int i = 0; i < 1024; i++) hdr.putInt(i * 4, loc[i]);
        for (int i = 0; i < 1024; i++) hdr.putInt(4096 + i * 4, entries[i] == null ? 0 : entries[i].timestamp);
        Path tmp = path.resolveSibling(path.getFileName() + ".wgtmp");
        try (OutputStream out = Files.newOutputStream(tmp)) { out.write(hdr.array()); body.writeTo(out); }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 列出目錄下所有 r.X.Z.mca。 */
    public static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList();
        }
    }
}
