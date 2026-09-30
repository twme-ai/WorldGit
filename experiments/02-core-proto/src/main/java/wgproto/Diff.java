package wgproto;

import org.eclipse.jgit.lib.ObjectId;
import wgproto.Nbt.*;

import java.io.*;
import java.util.*;

/** 兩個 commit 之間的差異統計（section / 方塊 / block entity / 實體）。 */
public final class Diff {
    public static final class Stats {
        public int chunksAdded, chunksRemoved;
        public int secAdded, secRemoved, secModified;
        public long blocksChanged; public int beChanged;
        public int biomesChanged, ticksChanged, structuresChanged;
        public int entChunksChanged, entAdded, entRemoved, entModified, entRelocated;
        public Map<String, Integer> entFieldDiffs = new TreeMap<>();
        public Map<String, Integer> entTypeModified = new TreeMap<>();
        public Map<String, Integer> blockTransitions = new HashMap<>();
        public int sectionsChanged() { return secAdded + secRemoved + secModified; }
        @Override public String toString() {
            return String.format("chunks +%d -%d | sections added=%d removed=%d modified=%d (total %d) | blocks changed=%d, blockEntities changed=%d | biomes=%d ticks=%d structures=%d | entity chunks changed=%d: added=%d removed=%d modified=%d relocated-only=%d",
                    chunksAdded, chunksRemoved, secAdded, secRemoved, secModified, sectionsChanged(), blocksChanged, beChanged, biomesChanged, ticksChanged, structuresChanged,
                    entChunksChanged, entAdded, entRemoved, entModified, entRelocated);
        }
    }

    record Ch(String path, ObjectId a, ObjectId b) {}

    static void walk(Repo.Node a, Repo.Node b, String path, List<Ch> out) {
        if (a != null && b != null && a.id != null && a.id.equals(b.id)) return;
        boolean at = a != null && a.isTree, bt = b != null && b.isTree;
        if ((a == null || at) && (b == null || bt)) {
            Set<String> names = new TreeSet<>();
            if (a != null) names.addAll(a.kids().keySet());
            if (b != null) names.addAll(b.kids().keySet());
            for (String n : names) walk(a == null ? null : a.get(n), b == null ? null : b.get(n), path.isEmpty() ? n : path + "/" + n, out);
        } else {
            out.add(new Ch(path, a == null ? null : a.id, b == null ? null : b.id));
        }
    }

    public static Stats diff(Repo repo, ObjectId ca, ObjectId cb, boolean verbose, PrintStream log) throws IOException {
        Repo.Node ra = repo.rootOf(ca), rb = repo.rootOf(cb);
        List<Ch> changes = new ArrayList<>();
        walk(ra, rb, "", changes);
        Stats st = new Stats();
        Map<String, Ch> ent = new TreeMap<>();
        Set<String> chunksA = new HashSet<>(), chunksB = new HashSet<>();
        for (Ch c : changes) {
            String p = c.path;
            String name = p.substring(p.lastIndexOf('/') + 1);
            if (name.startsWith("s.") && name.endsWith(".bin")) {
                if (c.a == null) st.secAdded++; else if (c.b == null) st.secRemoved++; else st.secModified++;
                Codec.Section sa = c.a == null ? Codec.airSection() : Codec.decodeSection(repo.blob(c.a));
                Codec.Section sb = c.b == null ? Codec.airSection() : Codec.decodeSection(repo.blob(c.b));
                int n = 0;
                for (int i = 0; i < 4096; i++) if (!sa.stateAt(i).equals(sb.stateAt(i))) {
                    n++;
                    st.blockTransitions.merge(sa.palette.get(sa.idx[i]).name() + " -> " + sb.palette.get(sb.idx[i]).name(), 1, Integer::sum);
                    if (verbose && n <= 3) log.println("   block " + p + " idx=" + i + " " + sa.stateAt(i) + " -> " + sb.stateAt(i));
                }
                st.blocksChanged += n;
                if (verbose) log.println("   section " + p + " blocks=" + n);
                Map<Integer, byte[]> ba = new HashMap<>(), bb = new HashMap<>();
                for (var e : sa.blockEntities) ba.put(e.getKey(), Nbt.toBytes(e.getValue(), true));
                for (var e : sb.blockEntities) bb.put(e.getKey(), Nbt.toBytes(e.getValue(), true));
                Set<Integer> keys = new TreeSet<>(ba.keySet()); keys.addAll(bb.keySet());
                for (int k : keys) if (!(ba.containsKey(k) && bb.containsKey(k) && Arrays.equals(ba.get(k), bb.get(k)))) {
                    st.beChanged++;
                    if (verbose) log.println("   blockEntity " + p + " pos=" + k + " a=" + (ba.containsKey(k) ? Nbt.str(sa.blockEntities.stream().filter(e -> e.getKey() == k).findFirst().get().getValue()) : "-")
                            + " b=" + (bb.containsKey(k) ? Nbt.str(sb.blockEntities.stream().filter(e -> e.getKey() == k).findFirst().get().getValue()) : "-"));
                }
                if (verbose && n == 0 && c.a != null && c.b != null) log.println("   (section blob differs but blocks identical) " + p);
            } else if (name.equals("biomes.bin")) st.biomesChanged++;
            else if (name.equals("ticks.bin")) {
                st.ticksChanged++;
                if (verbose) log.println("   ticks changed " + p + "\n     a=" + (c.a == null ? "-" : Nbt.str(Codec.decodeNbtBlob(repo.blob(c.a)))) + "\n     b=" + (c.b == null ? "-" : Nbt.str(Codec.decodeNbtBlob(repo.blob(c.b)))));
            }
            else if (name.equals("structures.bin")) st.structuresChanged++;
            else if (name.equals("entities.bin")) { st.entChunksChanged++; ent.put(p, c); }
        }
        // 實體：以 UUID 為全域鍵
        Map<String, NCompound> ea = new HashMap<>(), eb = new HashMap<>();
        Map<String, String> ca2 = new HashMap<>(), cb2 = new HashMap<>();
        for (var e : ent.entrySet()) {
            String chunk = e.getKey().substring(0, e.getKey().lastIndexOf('/'));
            if (e.getValue().a != null) for (NCompound r : Codec.decodeEntities(repo.blob(e.getValue().a))) { ea.put(Codec.uuidKey(r), r); ca2.put(Codec.uuidKey(r), chunk); }
            if (e.getValue().b != null) for (NCompound r : Codec.decodeEntities(repo.blob(e.getValue().b))) { eb.put(Codec.uuidKey(r), r); cb2.put(Codec.uuidKey(r), chunk); }
        }
        Set<String> all = new TreeSet<>(ea.keySet()); all.addAll(eb.keySet());
        for (String u : all) {
            NCompound a = ea.get(u), b = eb.get(u);
            if (a == null) { st.entAdded++; if (verbose) log.println("   entity + " + b.str("id") + " " + u + " @" + Nbt.str(b.get("Pos"))); }
            else if (b == null) { st.entRemoved++; if (verbose) log.println("   entity - " + a.str("id") + " " + u + " @" + Nbt.str(a.get("Pos"))); }
            else if (Arrays.equals(Nbt.toBytes(a, true), Nbt.toBytes(b, true))) { if (!ca2.get(u).equals(cb2.get(u))) st.entRelocated++; }
            else {
                st.entModified++;
                st.entTypeModified.merge(a.str("id"), 1, Integer::sum);
                Set<String> keys = new TreeSet<>(a.keySet()); keys.addAll(b.keySet());
                List<String> diffs = new ArrayList<>();
                for (String k : keys) {
                    Object x = a.get(k), y = b.get(k);
                    if (x == null || y == null || !Arrays.equals(Nbt.toBytes(new NCompound().put2("v", x), true), Nbt.toBytes(new NCompound().put2("v", y), true))) {
                        diffs.add(k); st.entFieldDiffs.merge(k, 1, Integer::sum);
                    }
                }
                if (verbose) {
                    double[] pa = Codec.pos(a), pb = Codec.pos(b);
                    double dd = Math.sqrt(Codec.sq(pa[0] - pb[0]) + Codec.sq(pa[1] - pb[1]) + Codec.sq(pa[2] - pb[2]));
                    log.printf("   entity ~ %s %s fields=%s dist=%.3f%n", a.str("id"), u, diffs, dd);
                }
            }
        }
        return st;
    }
}
