package wgproto;

import java.io.*;
import java.util.*;

/** 最小 NBT 實作。型別對應：Byte Short Integer Long Float Double byte[] String NList NCompound int[] long[]。 */
public final class Nbt {
    private Nbt() {}

    public static final class NList {
        public byte type; // 0 = 空 list
        public final List<Object> items = new ArrayList<>();
        public NList(byte type) { this.type = type; }
        public NList add(Object o) { items.add(o); if (type == 0) type = typeOf(o); return this; }
        public int size() { return items.size(); }
    }

    public static final class NCompound extends LinkedHashMap<String, Object> {
        public NCompound put2(String k, Object v) { put(k, v); return this; }
        public NCompound comp(String k) { Object o = get(k); return o instanceof NCompound c ? c : null; }
        public NList list(String k) { Object o = get(k); return o instanceof NList c ? c : null; }
        public String str(String k) { Object o = get(k); return o instanceof String s ? s : null; }
        public int intv(String k, int d) { Object o = get(k); return o instanceof Number n ? n.intValue() : d; }
    }

    public static byte typeOf(Object o) {
        if (o instanceof Byte) return 1;
        if (o instanceof Short) return 2;
        if (o instanceof Integer) return 3;
        if (o instanceof Long) return 4;
        if (o instanceof Float) return 5;
        if (o instanceof Double) return 6;
        if (o instanceof byte[]) return 7;
        if (o instanceof String) return 8;
        if (o instanceof NList) return 9;
        if (o instanceof NCompound) return 10;
        if (o instanceof int[]) return 11;
        if (o instanceof long[]) return 12;
        throw new IllegalArgumentException("bad nbt value " + o.getClass());
    }

    // ---------- read ----------
    /** 讀取帶根名稱的 NBT（region chunk / level.dat 格式）。 */
    public static NCompound readRoot(DataInput in) throws IOException {
        byte t = in.readByte();
        if (t != 10) throw new IOException("root is not compound: " + t);
        in.readUTF();
        return (NCompound) readPayload(in, (byte) 10);
    }

    public static NCompound readRoot(byte[] data) throws IOException {
        return readRoot(new DataInputStream(new ByteArrayInputStream(data)));
    }

    static Object readPayload(DataInput in, byte t) throws IOException {
        switch (t) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                byte et = in.readByte(); int n = in.readInt();
                NList l = new NList(et);
                for (int i = 0; i < n; i++) l.items.add(readPayload(in, et));
                return l;
            }
            case 10: {
                NCompound c = new NCompound();
                while (true) {
                    byte ct = in.readByte();
                    if (ct == 0) break;
                    String k = in.readUTF();
                    c.put(k, readPayload(in, ct));
                }
                return c;
            }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("bad tag " + t);
        }
    }

    // ---------- write ----------
    /** sorted=true：compound 鍵依字串排序（正規化用）。 */
    public static void writeRoot(DataOutput out, NCompound c, boolean sorted) throws IOException {
        out.writeByte(10); out.writeUTF("");
        writePayload(out, c, sorted);
    }

    public static byte[] toBytes(NCompound c, boolean sorted) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            writeRoot(out, c, sorted);
            return bo.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static void writePayload(DataOutput out, Object v, boolean sorted) throws IOException {
        if (v instanceof Byte b) out.writeByte(b);
        else if (v instanceof Short s) out.writeShort(s);
        else if (v instanceof Integer i) out.writeInt(i);
        else if (v instanceof Long l) out.writeLong(l);
        else if (v instanceof Float f) out.writeFloat(f);
        else if (v instanceof Double d) out.writeDouble(d);
        else if (v instanceof byte[] a) { out.writeInt(a.length); out.write(a); }
        else if (v instanceof String s) out.writeUTF(s);
        else if (v instanceof NList l) {
            out.writeByte(l.items.isEmpty() ? 0 : l.type); out.writeInt(l.items.size());
            for (Object o : l.items) writePayload(out, o, sorted);
        } else if (v instanceof NCompound c) {
            Collection<String> keys = sorted ? new TreeSet<>(c.keySet()) : c.keySet();
            for (String k : keys) {
                Object o = c.get(k);
                out.writeByte(typeOf(o)); out.writeUTF(k);
                writePayload(out, o, sorted);
            }
            out.writeByte(0);
        } else if (v instanceof int[] a) { out.writeInt(a.length); for (int x : a) out.writeInt(x); }
        else if (v instanceof long[] a) { out.writeInt(a.length); for (long x : a) out.writeLong(x); }
        else throw new IOException("bad value " + v);
    }

    // ---------- helpers ----------
    public static NCompound deepCopy(NCompound c) {
        NCompound r = new NCompound();
        for (var e : c.entrySet()) r.put(e.getKey(), copyVal(e.getValue()));
        return r;
    }

    static Object copyVal(Object v) {
        if (v instanceof NCompound c) return deepCopy(c);
        if (v instanceof NList l) { NList r = new NList(l.type); for (Object o : l.items) r.items.add(copyVal(o)); return r; }
        if (v instanceof byte[] a) return a.clone();
        if (v instanceof int[] a) return a.clone();
        if (v instanceof long[] a) return a.clone();
        return v;
    }

    /** 簡短的除錯字串。 */
    public static String str(Object v) {
        if (v instanceof NCompound c) {
            StringBuilder sb = new StringBuilder("{");
            for (String k : new TreeSet<>(c.keySet())) sb.append(k).append(':').append(str(c.get(k))).append(',');
            return sb.append('}').toString();
        }
        if (v instanceof NList l) { StringBuilder sb = new StringBuilder("["); for (Object o : l.items) sb.append(str(o)).append(','); return sb.append(']').toString(); }
        if (v instanceof int[] a) return Arrays.toString(a);
        if (v instanceof long[] a) return "long[" + a.length + "]";
        if (v instanceof byte[] a) return "byte[" + a.length + "]";
        if (v instanceof Byte b) return b + "b";
        if (v instanceof Short b) return b + "s";
        if (v instanceof Long b) return b + "L";
        if (v instanceof Float b) return b + "f";
        if (v instanceof Double b) return b + "d";
        return String.valueOf(v);
    }
}
