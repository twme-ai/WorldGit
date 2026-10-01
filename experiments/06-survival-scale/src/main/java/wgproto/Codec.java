package wgproto;

import com.github.luben.zstd.Zstd;
import wgproto.Nbt.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 正規化 + 自訂二進位編碼（section / biomes / ticks / structures / entities）。 */
public final class Codec {
    private Codec() {}
    public static final int ZSTD_LEVEL = 6;

    // ================= 小工具 =================
    static final class Out {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        void b(int v) { bo.write(v); }
        void varint(int v) { while ((v & ~0x7F) != 0) { bo.write((v & 0x7F) | 0x80); v >>>= 7; } bo.write(v); }
        void str(String s) { byte[] x = s.getBytes(StandardCharsets.UTF_8); varint(x.length); bo.write(x, 0, x.length); }
        void bytes(byte[] x) { varint(x.length); bo.write(x, 0, x.length); }
        byte[] done() { return bo.toByteArray(); }
    }

    static final class In {
        final byte[] d; int p;
        In(byte[] d) { this.d = d; }
        int b() { return d[p++] & 0xFF; }
        int varint() { int r = 0, s = 0; while (true) { int x = b(); r |= (x & 0x7F) << s; if ((x & 0x80) == 0) return r; s += 7; } }
        String str() { int n = varint(); String s = new String(d, p, n, StandardCharsets.UTF_8); p += n; return s; }
        byte[] bytes() { int n = varint(); byte[] r = Arrays.copyOfRange(d, p, p + n); p += n; return r; }
    }

    public static byte[] zstd(byte[] raw) { return Zstd.compress(raw, ZSTD_LEVEL); }
    public static byte[] unzstd(byte[] c) { return Zstd.decompress(c, (int) Zstd.decompressedSize(c)); }

    static int ceilLog2(int n) { return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1); }

    /** 從 MC 的 long[] 打包（每個 long 不跨 entry）解出索引。 */
    static int[] unpackMc(long[] data, int bits, int count) {
        int[] r = new int[count];
        if (data == null || bits == 0) return r;
        int epl = 64 / bits; long mask = (1L << bits) - 1;
        for (int i = 0; i < count; i++) r[i] = (int) ((data[i / epl] >>> ((i % epl) * bits)) & mask);
        return r;
    }

    static long[] packMc(int[] idx, int bits) {
        int epl = 64 / bits;
        long[] r = new long[(idx.length + epl - 1) / epl];
        for (int i = 0; i < idx.length; i++) r[i / epl] |= ((long) idx[i]) << ((i % epl) * bits);
        return r;
    }

    /** 緊密位元流（LSB first），供自訂格式使用。 */
    static byte[] packBits(int[] idx, int bits) {
        if (bits == 0) return new byte[0];
        byte[] r = new byte[(idx.length * bits + 7) / 8];
        int bitPos = 0;
        for (int v : idx) {
            for (int k = 0; k < bits; k++, bitPos++) if (((v >> k) & 1) != 0) r[bitPos >> 3] |= (byte) (1 << (bitPos & 7));
        }
        return r;
    }

    static int[] unpackBits(byte[] d, int off, int bits, int count) {
        int[] r = new int[count];
        if (bits == 0) return r;
        int bitPos = off * 8;
        for (int i = 0; i < count; i++) {
            int v = 0;
            for (int k = 0; k < bits; k++, bitPos++) if ((d[bitPos >> 3] >> (bitPos & 7) & 1) != 0) v |= 1 << k;
            r[i] = v;
        }
        return r;
    }

    // ================= Section =================
    public record State(String name, TreeMap<String, String> props) {
        public String canon() {
            if (props.isEmpty()) return name;
            StringBuilder sb = new StringBuilder(name).append('[');
            boolean f = true;
            for (var e : props.entrySet()) { if (!f) sb.append(','); f = false; sb.append(e.getKey()).append('=').append(e.getValue()); }
            return sb.append(']').toString();
        }
        public boolean isAir() { return props.isEmpty() && name.equals("minecraft:air"); }
    }

    public static final class Section {
        public List<State> palette = new ArrayList<>();
        public int[] idx = new int[4096];
        /** 每筆：packed 位置 (x | z<<4 | y<<8) 與去掉 x/y/z 的 compound。 */
        public List<Map.Entry<Integer, NCompound>> blockEntities = new ArrayList<>();
        public String stateAt(int i) { return palette.get(idx[i]).canon(); }
    }

    static State stateOf(NCompound p) {
        TreeMap<String, String> props = new TreeMap<>();
        NCompound pr = p.comp("Properties");
        if (pr != null) for (var e : pr.entrySet()) props.put(e.getKey(), String.valueOf(e.getValue()));
        return new State(p.str("Name"), props);
    }

    static final Set<String> BE_TRANSIENT_FURNACE = Set.of("lit_time_remaining", "cooking_time_spent");
    /** block entity 暫態欄位（依 id）。 */
    static final Map<String, Set<String>> BE_IGNORE = Map.of(
            "minecraft:furnace", BE_TRANSIENT_FURNACE,
            "minecraft:blast_furnace", BE_TRANSIENT_FURNACE,
            "minecraft:smoker", BE_TRANSIENT_FURNACE,
            "minecraft:mob_spawner", Set.of("Delay"),
            "minecraft:jukebox", Set.of("ticks_since_song_started"),
            "minecraft:command_block", Set.of("LastExecution", "SuccessCount"),
            "minecraft:brewing_stand", Set.of("BrewTime"),
            "minecraft:campfire", Set.of("CookingTimes"),
            "minecraft:hopper", Set.of("TransferCooldown"));

    /** 回傳 null 表示這個 section 不存（全空氣且無 block entity，或沒有 block_states）。 */
    public static byte[] encodeSection(NCompound section, List<NCompound> bes, int sectionY) {
        NCompound bs = section.comp("block_states");
        if (bs == null) return null;
        NList pal = bs.list("palette");
        if (pal == null || pal.size() == 0) return null;
        int n = pal.size();
        int[] raw = (n == 1) ? new int[4096]
                : unpackMc(bs.get("data") instanceof long[] l ? l : null, Math.max(4, ceilLog2(n)), 4096);
        // 依「第一次出現」（YZX 走訪 = 線性索引順序）重編調色盤，state 依規範字串去重
        List<State> np = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        int[] remap = new int[n]; Arrays.fill(remap, -1);
        State[] states = new State[n];
        int[] idx = new int[4096];
        for (int i = 0; i < 4096; i++) {
            int o = raw[i];
            if (o >= n) o = 0;
            if (remap[o] < 0) {
                if (states[o] == null) states[o] = stateOf((NCompound) pal.items.get(o));
                String c = states[o].canon();
                Integer id = seen.get(c);
                if (id == null) { id = np.size(); np.add(states[o]); seen.put(c, id); }
                remap[o] = id;
            }
            idx[i] = remap[o];
        }
        if (np.size() == 1 && np.get(0).isAir() && bes.isEmpty()) return null;

        Out out = new Out();
        out.b(1); // format version
        out.varint(np.size());
        for (State s : np) {
            out.str(s.name());
            out.varint(s.props().size());
            for (var e : s.props().entrySet()) { out.str(e.getKey()); out.str(e.getValue()); }
        }
        int bits = ceilLog2(np.size());
        out.b(bits);
        if (bits > 0) out.bo.writeBytes(packBits(idx, bits));
        // block entities（依座標 y,z,x 排序）
        List<Map.Entry<Integer, byte[]>> list = new ArrayList<>();
        for (NCompound be : bes) {
            int x = be.intv("x", 0), y = be.intv("y", 0), z = be.intv("z", 0);
            int packed = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
            NCompound c = new NCompound();
            Set<String> ign = BE_IGNORE.getOrDefault(be.str("id"), Set.of());
            for (var e : be.entrySet()) {
                String k = e.getKey();
                if (k.equals("x") || k.equals("y") || k.equals("z") || ign.contains(k) || k.startsWith("Paper.") || k.startsWith("Bukkit.") || k.startsWith("Spigot.")) continue;
                c.put(k, e.getValue());
            }
            list.add(Map.entry(packed, Nbt.toBytes(c, true)));
        }
        list.sort(Comparator.<Map.Entry<Integer, byte[]>>comparingInt(e -> ((e.getKey() >> 8) << 8) | ((e.getKey() >> 4 & 15) << 4) | (e.getKey() & 15))
                .thenComparing(e -> Arrays.hashCode(e.getValue())));
        out.varint(list.size());
        for (var e : list) { out.varint(e.getKey()); out.bytes(e.getValue()); }
        return zstd(out.done());
    }

    public static Section decodeSection(byte[] blob) {
        In in = new In(unzstd(blob));
        if (in.b() != 1) throw new IllegalStateException("bad section version");
        Section s = new Section();
        int pn = in.varint();
        for (int i = 0; i < pn; i++) {
            String name = in.str(); int pc = in.varint();
            TreeMap<String, String> props = new TreeMap<>();
            for (int k = 0; k < pc; k++) { String pk = in.str(); props.put(pk, in.str()); }
            s.palette.add(new State(name, props));
        }
        int bits = in.b();
        s.idx = unpackBits(in.d, in.p, bits, 4096);
        in.p += (4096 * bits + 7) / 8;
        int bn = in.varint();
        for (int i = 0; i < bn; i++) {
            int packed = in.varint(); byte[] nb = in.bytes();
            try { s.blockEntities.add(Map.entry(packed, Nbt.readRoot(nb))); } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        return s;
    }

    /** 全空氣 section（缺檔語意）。 */
    public static Section airSection() {
        Section s = new Section();
        s.palette.add(new State("minecraft:air", new TreeMap<>()));
        return s;
    }

    /** 轉回 MC 的 block_states compound（無光照）。 */
    public static NCompound toMcBlockStates(Section s) {
        NList pal = new NList((byte) 10);
        for (State st : s.palette) {
            NCompound c = new NCompound().put2("Name", st.name());
            if (!st.props().isEmpty()) {
                NCompound pr = new NCompound();
                for (var e : st.props().entrySet()) pr.put(e.getKey(), e.getValue());
                c.put("Properties", pr);
            }
            pal.add(c);
        }
        NCompound bs = new NCompound().put2("palette", pal);
        if (s.palette.size() > 1) bs.put("data", packMc(s.idx, Math.max(4, ceilLog2(s.palette.size()))));
        return bs;
    }

    // ================= Biomes =================
    public static final class BiomeSection { public int y; public List<String> palette = new ArrayList<>(); public int[] idx = new int[64]; }

    public static byte[] encodeBiomes(NList sections) {
        List<NCompound> secs = new ArrayList<>();
        for (Object o : sections.items) if (o instanceof NCompound c && c.comp("biomes") != null) secs.add(c);
        if (secs.isEmpty()) return null;
        secs.sort(Comparator.comparingInt(c -> ((Number) c.get("Y")).intValue()));
        Out out = new Out();
        out.b(1);
        out.varint(secs.size());
        for (NCompound sec : secs) {
            int y = ((Number) sec.get("Y")).intValue();
            NCompound b = sec.comp("biomes");
            NList pal = b.list("palette");
            int n = pal.size();
            int[] raw = (n <= 1) ? new int[64] : unpackMc(b.get("data") instanceof long[] l ? l : null, ceilLog2(n), 64);
            List<String> np = new ArrayList<>(); Map<String, Integer> seen = new HashMap<>();
            int[] idx = new int[64];
            for (int i = 0; i < 64; i++) {
                String name = (String) pal.items.get(Math.min(raw[i], n - 1));
                Integer id = seen.get(name);
                if (id == null) { id = np.size(); np.add(name); seen.put(name, id); }
                idx[i] = id;
            }
            out.varint(y + 64);
            out.varint(np.size());
            for (String s : np) out.str(s);
            int bits = ceilLog2(np.size());
            out.b(bits);
            if (bits > 0) out.bo.writeBytes(packBits(idx, bits));
        }
        return zstd(out.done());
    }

    public static List<BiomeSection> decodeBiomes(byte[] blob) {
        In in = new In(unzstd(blob));
        in.b();
        int n = in.varint();
        List<BiomeSection> r = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BiomeSection bs = new BiomeSection();
            bs.y = in.varint() - 64;
            int pn = in.varint();
            for (int k = 0; k < pn; k++) bs.palette.add(in.str());
            int bits = in.b();
            bs.idx = unpackBits(in.d, in.p, bits, 64);
            in.p += (64 * bits + 7) / 8;
            r.add(bs);
        }
        return r;
    }

    public static NCompound toMcBiomes(BiomeSection b) {
        NList pal = new NList((byte) 8);
        for (String s : b.palette) pal.add(s);
        NCompound c = new NCompound().put2("palette", pal);
        if (b.palette.size() > 1) c.put("data", packMc(b.idx, ceilLog2(b.palette.size())));
        return c;
    }

    // ================= Ticks / structures =================
    public static byte[] encodeTicks(NCompound chunk) {
        NCompound out = new NCompound();
        boolean any = false;
        for (String key : new String[]{"block_ticks", "fluid_ticks"}) {
            NList l = chunk.list(key);
            if (l == null || l.size() == 0) continue;
            any = true;
            List<NCompound> items = new ArrayList<>();
            for (Object o : l.items) items.add((NCompound) o);
            items.sort(Comparator.comparing((NCompound c) -> String.format("%09d,%09d,%09d,%s,%d,%d", c.intv("y", 0), c.intv("z", 0), c.intv("x", 0), c.str("i"), c.intv("p", 0), c.intv("t", 0))));
            NList nl = new NList((byte) 10);
            for (NCompound c : items) nl.add(c);
            out.put(key, nl);
        }
        return any ? zstd(Nbt.toBytes(out, true)) : null;
    }

    public static byte[] encodeStructures(NCompound chunk) {
        NCompound st = chunk.comp("structures");
        if (st == null) return null;
        NCompound refs = st.comp("References"), starts = st.comp("starts");
        if ((refs == null || refs.isEmpty()) && (starts == null || starts.isEmpty())) return null;
        return zstd(Nbt.toBytes(st, true));
    }

    public static NCompound decodeNbtBlob(byte[] blob) {
        try { return Nbt.readRoot(unzstd(blob)); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // ================= Entities =================
    static final Set<String> ENT_STRIP = Set.of(
            "Motion", "FallDistance", "fall_distance", "OnGround", "Air", "Fire", "PortalCooldown", "HasTicked", "TicksFrozen",
            "HurtTime", "HurtByTimestamp", "DeathTime", "FallFlying", "current_impulse_context_reset_grace_time", "Brain",
            "Age", "InLove", "LoveCause", "InWaterTime", "DrownedConversionTime", "PickupDelay", "Time",
            "LastRestock", "RestocksToday", "LastGossipDecay", "Gossips", "FoodLevel", "sleeping_pos", "life", "inGround", "shake", "Fuse",
            "start_interpolation", "AngryAt", "AngerTime", "LeashDelay", "SleepingX", "SleepingY", "SleepingZ", "WasOnFire");
    static final Set<String> ENT_STATIC_TYPES = Set.of(
            "minecraft:armor_stand", "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting", "minecraft:block_display",
            "minecraft:item_display", "minecraft:text_display", "minecraft:interaction", "minecraft:leash_knot", "minecraft:marker");

    static boolean isExact(NCompound e) {
        Object noai = e.get("NoAI");
        if (noai instanceof Number n && n.intValue() != 0) return true;
        return ENT_STATIC_TYPES.contains(e.str("id"));
    }

    /** 實體正規化：剝除 Paper 欄位與暫態欄位、attributes 依 id 排序。回傳新的 compound（不改動輸入）。 */
    public static NCompound normEntity(NCompound e, boolean top) {
        boolean exact = isExact(e);
        NCompound r = new NCompound();
        for (var en : e.entrySet()) {
            String k = en.getKey();
            if (k.startsWith("Paper.") || k.startsWith("Bukkit.") || k.startsWith("Spigot.") || k.startsWith("WorldUUID")) continue;
            if (ENT_STRIP.contains(k)) continue;
            if (k.equals("Rotation") && !exact) continue; // 靜態實體（盔甲座、展示框、NoAI）保留朝向
            if (k.equals("Pos") && !top) continue;
            Object v = en.getValue();
            if (k.equals("attributes") && v instanceof NList l) {
                List<Object> sorted = new ArrayList<>(l.items);
                sorted.sort(Comparator.comparing(o -> ((NCompound) o).str("id")));
                NList nl = new NList(l.type); nl.items.addAll(sorted); v = nl;
            } else if (k.equals("Passengers") && v instanceof NList l) {
                NList nl = new NList((byte) 10);
                for (Object o : l.items) nl.add(normEntity((NCompound) o, false));
                v = nl;
            } else v = Nbt.copyVal(v);
            r.put(k, v);
        }
        IgnoreRules.extraNormalize(r);
        return r;
    }

    public static String uuidKey(NCompound e) {
        if (e.get("UUID") instanceof int[] u && u.length == 4) return String.format("%08x%08x%08x%08x", u[0], u[1], u[2], u[3]);
        return "nouuid-" + Arrays.hashCode(Nbt.toBytes(e, true));
    }

    public static double[] pos(NCompound e) {
        NList p = e.list("Pos");
        if (p == null || p.size() != 3) return new double[]{0, 0, 0};
        return new double[]{((Number) p.items.get(0)).doubleValue(), ((Number) p.items.get(1)).doubleValue(), ((Number) p.items.get(2)).doubleValue()};
    }

    public static int[] chunkOf(NCompound e) {
        double[] p = pos(e);
        return new int[]{(int) Math.floor(p[0] / 16), (int) Math.floor(p[2] / 16)};
    }

    /** 黏性比較：其餘欄位完全相同，且（exact 實體：Pos 也相同；其他：位置差 <= tol）。 */
    public static boolean stickyEqual(NCompound a, NCompound b, double tol) {
        if (isExact(a) || isExact(b) || tol <= 0) return Arrays.equals(Nbt.toBytes(a, true), Nbt.toBytes(b, true));
        double[] pa = pos(a), pb = pos(b);
        double d = Math.sqrt(sq(pa[0] - pb[0]) + sq(pa[1] - pb[1]) + sq(pa[2] - pb[2]));
        if (d > tol) return false;
        NCompound ca = new NCompound(), cb = new NCompound();
        ca.putAll(a); cb.putAll(b); ca.remove("Pos"); cb.remove("Pos");
        return Arrays.equals(Nbt.toBytes(ca, true), Nbt.toBytes(cb, true));
    }

    static double sq(double x) { return x * x; }

    /** records 需已依 uuidKey 排序。空列表回傳 null（不存）。 */
    public static byte[] encodeEntities(List<NCompound> records) {
        if (records.isEmpty()) return null;
        List<NCompound> s = new ArrayList<>(records);
        s.sort(Comparator.comparing(Codec::uuidKey));
        Out out = new Out();
        out.b(1);
        out.varint(s.size());
        for (NCompound e : s) out.bytes(Nbt.toBytes(e, true));
        return zstd(out.done());
    }

    public static List<NCompound> decodeEntities(byte[] blob) {
        In in = new In(unzstd(blob));
        in.b();
        int n = in.varint();
        List<NCompound> r = new ArrayList<>();
        try { for (int i = 0; i < n; i++) r.add(Nbt.readRoot(in.bytes())); } catch (IOException e) { throw new UncheckedIOException(e); }
        return r;
    }
}
