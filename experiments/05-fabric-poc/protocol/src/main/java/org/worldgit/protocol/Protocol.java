package org.worldgit.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Java 21、零 MC 依賴。所有長度在配置記憶體前驗證。見 REPORT 的 wire format。 */
public final class Protocol {
    private Protocol() {}
    public static final int VERSION = 1, MAX_PAYLOAD = 28_000, MAX_ENTRIES = 100_000, MAX_PARTS = 8192;
    public static final String HELLO = "worldgit:hello", DIFF = "worldgit:diff", CLEAR = "worldgit:clear";
    public static final List<String> CAPABILITIES = List.of("ghost-render", "outline", "section-palette-v1");
    public record Hello(int version, long nonce, List<String> capabilities) {
        public Hello { capabilities = List.copyOf(capabilities); if (capabilities.size() > 16) throw new IllegalArgumentException("capabilities"); }
    }
    public record Entry(int x, int y, int z, DiffType type, String otherState) {
        public Entry {
            Objects.requireNonNull(type); Objects.requireNonNull(otherState);
            if ((type == DiffType.REMOVED || type == DiffType.MODIFIED) && otherState.isEmpty()) throw new IllegalArgumentException("missing otherState");
        }
    }
    public record Part(long preview, int sequence, int parts, int totalEntries, String dimension, List<Entry> entries) {
        public Part { entries = List.copyOf(entries); }
    }
    private record Section(int x, int y, int z) {}
    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] encode(Writer writer) {
        try { var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); writer.write(out); out.flush(); return bytes.toByteArray(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static DataInputStream input(byte[] bytes) throws IOException {
        if (bytes.length > MAX_PAYLOAD) throw new IOException("payload too large");
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
    private static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("trailing bytes"); }
    private static void version(DataInputStream in) throws IOException { if (varint(in, 255) != VERSION) throw new IOException("protocol version"); }
    private static void varint(DataOutputStream out, int v) throws IOException {
        if (v < 0) throw new IOException("negative varint");
        do { int b = v & 127; v >>>= 7; out.writeByte(b | (v == 0 ? 0 : 128)); } while (v != 0);
    }
    private static int varint(DataInputStream in, int max) throws IOException {
        long value = 0;
        for (int n = 0; n < 5; n++) { int b = in.readUnsignedByte(); value |= (long)(b & 127) << (n * 7);
            if ((b & 128) == 0) { if (value > max) throw new IOException("varint limit " + max); return (int)value; } }
        throw new IOException("overlong varint");
    }
    private static void string(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8); if (bytes.length > 512) throw new IOException("string limit");
        varint(out, bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int n = varint(in, 512); byte[] b = in.readNBytes(n); if (b.length != n) throw new EOFException();
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IOException("invalid UTF-8", e); }
    }
    public static byte[] hello(Hello hello) {
        return encode(out -> { varint(out, hello.version); out.writeLong(hello.nonce); varint(out, hello.capabilities.size()); for (String c : hello.capabilities) string(out, c); });
    }
    public static Hello hello(byte[] bytes) throws IOException {
        var in = input(bytes); int v = varint(in, 255); long nonce = in.readLong(); int n = varint(in, 16);
        var caps = new ArrayList<String>(); for (int i = 0; i < n; i++) caps.add(string(in)); end(in); return new Hello(v, nonce, caps);
    }
    public static byte[] clear(long preview) { return encode(out -> { varint(out, VERSION); out.writeLong(preview); }); }
    public static long clear(byte[] bytes) throws IOException { var in = input(bytes); version(in); long id = in.readLong(); end(in); return id; }

    private static Map<Section,List<Entry>> sections(List<Entry> entries) {
        var result = new LinkedHashMap<Section,List<Entry>>();
        for (var e : entries) result.computeIfAbsent(new Section(e.x >> 4, e.y >> 4, e.z >> 4), k -> new ArrayList<>()).add(e);
        return result;
    }
    public static byte[] part(Part part) {
        return encode(out -> {
            varint(out, VERSION); out.writeLong(part.preview); varint(out, part.sequence); varint(out, part.parts);
            varint(out, part.totalEntries); string(out, part.dimension);
            var sections = sections(part.entries); varint(out, sections.size());
            for (var s : sections.entrySet()) {
                out.writeInt(s.getKey().x); out.writeInt(s.getKey().y); out.writeInt(s.getKey().z);
                var palette = new LinkedHashMap<String,Integer>();
                for (var e : s.getValue()) if (!e.otherState.isEmpty()) palette.computeIfAbsent(e.otherState, k -> palette.size());
                varint(out, palette.size()); for (String state : palette.keySet()) string(out, state);
                varint(out, s.getValue().size());
                for (var e : s.getValue()) {
                    int local = (e.x & 15) | ((e.z & 15) << 4) | ((e.y & 15) << 8);
                    out.writeShort(local | (e.type.ordinal() << 12)); varint(out, e.otherState.isEmpty() ? 0 : palette.get(e.otherState) + 1);
                }
            }
        });
    }
    public static Part part(byte[] bytes) throws IOException {
        var in = input(bytes); version(in); long id = in.readLong(); int seq = varint(in, MAX_PARTS - 1);
        int parts = varint(in, MAX_PARTS), total = varint(in, MAX_ENTRIES); String dim = string(in);
        if (parts == 0 || seq >= parts || total == 0 || !dim.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IOException("header");
        int ns = varint(in, 4096); var entries = new ArrayList<Entry>();
        for (int si = 0; si < ns; si++) {
            int x = in.readInt(), y = in.readInt(), z = in.readInt();
            if (Math.abs((long)x) > 1_875_000 || Math.abs((long)z) > 1_875_000 || Math.abs((long)y) > 2048) throw new IOException("section coordinate");
            int np = varint(in, 4096); var palette = new ArrayList<String>(); for (int pi = 0; pi < np; pi++) palette.add(string(in));
            int ne = varint(in, 4096); if (entries.size() + ne > total) throw new IOException("entry count");
            for (int i = 0; i < ne; i++) {
                int packed = in.readUnsignedShort(); if ((packed & 0xc000) != 0) throw new IOException("reserved bits");
                int p = varint(in, np); var type = DiffType.values()[packed >> 12];
                if ((type == DiffType.REMOVED || type == DiffType.MODIFIED) && p == 0) throw new IOException("missing state");
                entries.add(new Entry(x * 16 + (packed & 15), y * 16 + ((packed >> 8) & 15), z * 16 + ((packed >> 4) & 15), type, p == 0 ? "" : palette.get(p - 1)));
            }
        }
        end(in); return new Part(id, seq, parts, total, dim, entries);
    }
    /** 每 section 先切成最多 128 格的小段，再依實際編碼長度打包；不假設 palette 大小。 */
    public static List<byte[]> split(long preview, String dimension, List<Entry> entries) {
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("entries 1..100000");
        var pieces = new ArrayList<List<Entry>>();
        for (var section : sections(entries).values()) {
            var piece = new ArrayList<Entry>();
            for (var e : section) {
                piece.add(e);
                if (part(new Part(preview, 0, MAX_PARTS, entries.size(), dimension, piece)).length > MAX_PAYLOAD) {
                    piece.removeLast(); pieces.add(piece); piece = new ArrayList<>(); piece.add(e);
                }
                if (piece.size() == 128) { pieces.add(piece); piece = new ArrayList<>(); }
            }
            if (!piece.isEmpty()) pieces.add(piece);
        }
        var groups = new ArrayList<List<Entry>>(); var current = new ArrayList<Entry>();
        for (var piece : pieces) {
            var next = new ArrayList<>(current); next.addAll(piece);
            if (part(new Part(preview, 0, MAX_PARTS, entries.size(), dimension, next)).length > MAX_PAYLOAD) { groups.add(current); current = new ArrayList<>(piece); }
            else current = next;
        }
        if (!current.isEmpty()) groups.add(current);
        if (groups.size() > MAX_PARTS) throw new IllegalArgumentException("parts limit");
        var result = new ArrayList<byte[]>();
        for (int i = 0; i < groups.size(); i++) {
            byte[] b = part(new Part(preview, i, groups.size(), entries.size(), dimension, groups.get(i)));
            if (b.length > MAX_PAYLOAD) throw new IllegalStateException("packet limit"); result.add(b);
        }
        return List.copyOf(result);
    }
}
