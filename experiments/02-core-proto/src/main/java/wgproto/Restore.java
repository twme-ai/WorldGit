package wgproto;

import org.eclipse.jgit.lib.ObjectId;
import wgproto.Nbt.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 把某 commit 中指定 chunk 範圍寫回世界（伺服器需關閉）。 */
public final class Restore {
    public static void restore(Path serverRoot, Path repoDir, String rev, String dimId, int cx1, int cz1, int cx2, int cz2, String poiMode, PrintStream log) throws IOException {
        if (Files.exists(serverRoot.resolve("world/session.lock"))) {
            // 伺服器關閉後 session.lock 仍存在（只是被解鎖），這裡只警告
            log.println("note: session.lock exists; make sure the server is NOT running");
        }
        Layout layout = new Layout(serverRoot);
        Layout.Dim dim = layout.dim(dimId);
        if (dim == null) throw new IOException("unknown dimension " + dimId);
        int x1 = Math.min(cx1, cx2), x2 = Math.max(cx1, cx2), z1 = Math.min(cz1, cz2), z2 = Math.max(cz1, cz2);
        try (Repo repo = new Repo(repoDir, false)) {
            ObjectId commit = repo.resolve(rev);
            Repo.Node root = repo.rootOf(commit);
            int dataVersion = 0;
            Repo.Node meta = root.get("world-meta");
            if (meta != null) for (String l : new String(repo.blob(meta.id)).split("\n")) if (l.startsWith("dataVersion=")) dataVersion = Integer.parseInt(l.substring(12));
            int ts = (int) (System.currentTimeMillis() / 1000);

            Map<String, Region> blocks = new HashMap<>(), ents = new HashMap<>(), pois = new HashMap<>();
            Set<String> restoredUuids = new HashSet<>();
            Set<Long> inRange = new HashSet<>();
            int restored = 0, missing = 0, entCount = 0;
            for (int cx = x1; cx <= x2; cx++) for (int cz = z1; cz <= z2; cz++) {
                Repo.Node cn = Snapshot.findChunk(root, dimId, cx, cz, false);
                if (cn == null) { missing++; continue; }
                inRange.add(Snapshot.Index.key(cx, cz));
                int rx = Math.floorDiv(cx, 32), rz = Math.floorDiv(cz, 32);
                String rn = "r." + rx + "." + rz + ".mca";
                Region br = blocks.get(rn);
                if (br == null) { br = new Region(dim.region().resolve(rn)); blocks.put(rn, br); }
                int idx = Region.idx(cx, cz);
                NCompound live = br.read(idx);
                br.put(idx, buildChunk(repo, cn, live, cx, cz, dataVersion), ts);
                restored++;
                // 實體
                Region er = ents.get(rn);
                if (er == null) { er = new Region(dim.entities().resolve(rn)); ents.put(rn, er); }
                NList el = new NList((byte) 10);
                Repo.Node en = cn.get("entities.bin");
                if (en != null) for (NCompound rec : Codec.decodeEntities(repo.blob(en.id))) { el.add(rec); restoredUuids.add(Codec.uuidKey(rec)); entCount++; }
                NCompound eroot = new NCompound().put2("DataVersion", dataVersion != 0 ? dataVersion : (live != null ? live.intv("DataVersion", 0) : 0))
                        .put2("Position", new int[]{cx, cz}).put2("Entities", el);
                er.put(idx, eroot, ts);
                // POI
                if (poiMode.equals("delete")) {
                    Region pr = pois.get(rn);
                    if (pr == null) { pr = new Region(dim.poi().resolve(rn)); pois.put(rn, pr); }
                    pr.put(idx, null, 0);
                }
            }
            // 以 UUID 去重：整個維度其他 chunk 中相同 UUID 的舊實體移除
            int dedup = 0;
            if (!restoredUuids.isEmpty()) {
                for (Path p : Region.list(dim.entities())) {
                    String rn = p.getFileName().toString();
                    Region er = ents.get(rn);
                    if (er == null) { er = new Region(p); ents.put(rn, er); }
                    for (int i = 0; i < 1024; i++) {
                        if (!er.has(i)) continue;
                        int cx = er.rx * 32 + (i & 31), cz = er.rz * 32 + (i >> 5);
                        if (inRange.contains(Snapshot.Index.key(cx, cz))) continue;
                        NCompound c = er.read(i);
                        NList l = c.list("Entities");
                        if (l == null) continue;
                        NList nl = new NList((byte) 10);
                        for (Object o : l.items) if (!restoredUuids.contains(Codec.uuidKey((NCompound) o))) nl.add(o); else dedup++;
                        if (nl.size() != l.size()) { c.put("Entities", nl); er.put(i, c, ts); }
                    }
                }
            }
            for (Region r : blocks.values()) { Files.createDirectories(r.path.getParent()); r.save(); }
            for (Region r : ents.values()) { Files.createDirectories(r.path.getParent()); r.save(); }
            for (Region r : pois.values()) r.save();
            log.printf("restored chunks=%d (not in snapshot: %d), entities written=%d, dedup-removed elsewhere=%d, poi=%s%n", restored, missing, entCount, dedup, poiMode);
        }
    }

    static NCompound buildChunk(Repo repo, Repo.Node cn, NCompound live, int cx, int cz, int dataVersion) throws IOException {
        NCompound nc = live != null ? Nbt.deepCopy(live) : new NCompound();
        TreeMap<Integer, NCompound> secs = new TreeMap<>();
        NList bes = new NList((byte) 10);
        Repo.Node bio = cn.get("biomes.bin");
        if (bio != null) for (Codec.BiomeSection b : Codec.decodeBiomes(repo.blob(bio.id))) {
            secs.put(b.y, new NCompound().put2("Y", (byte) b.y).put2("block_states", Codec.toMcBlockStates(Codec.airSection())).put2("biomes", Codec.toMcBiomes(b)));
        }
        for (var e : cn.kids().entrySet()) {
            String n = e.getKey();
            if (!n.startsWith("s.") || !n.endsWith(".bin")) continue;
            int y = Integer.parseInt(n.substring(2, n.length() - 4));
            Codec.Section s = Codec.decodeSection(repo.blob(e.getValue().id));
            NCompound sec = secs.computeIfAbsent(y, k -> new NCompound().put2("Y", (byte) y)
                    .put2("biomes", new NCompound().put2("palette", new NList((byte) 8).add("minecraft:plains"))));
            sec.put("block_states", Codec.toMcBlockStates(s));
            for (var be : s.blockEntities) {
                NCompound c = Nbt.deepCopy(be.getValue());
                int p = be.getKey();
                c.put("x", cx * 16 + (p & 15)); c.put("z", cz * 16 + ((p >> 4) & 15)); c.put("y", y * 16 + ((p >> 8) & 15));
                bes.add(c);
            }
        }
        NList sl = new NList((byte) 10);
        for (NCompound s : secs.values()) sl.add(s); // 無 BlockLight/SkyLight/starlight.*
        nc.put("sections", sl);
        nc.put("block_entities", bes);
        NList bt = new NList((byte) 10), ft = new NList((byte) 10);
        Repo.Node tk = cn.get("ticks.bin");
        if (tk != null) {
            NCompound t = Codec.decodeNbtBlob(repo.blob(tk.id));
            if (t.list("block_ticks") != null) bt = t.list("block_ticks");
            if (t.list("fluid_ticks") != null) ft = t.list("fluid_ticks");
        }
        nc.put("block_ticks", bt); nc.put("fluid_ticks", ft);
        Repo.Node st = cn.get("structures.bin");
        nc.put("structures", st != null ? Codec.decodeNbtBlob(repo.blob(st.id))
                : new NCompound().put2("References", new NCompound()).put2("starts", new NCompound()));
        // 光照/衍生資料：移除，讓伺服器重算
        nc.remove("Heightmaps");
        nc.remove("starlight.light_version");
        nc.put("isLightOn", (byte) 0);
        nc.put("Status", "minecraft:full");
        nc.put("xPos", cx); nc.put("zPos", cz);
        nc.put("yPos", secs.isEmpty() ? 0 : secs.firstKey());
        if (!nc.containsKey("DataVersion")) nc.put("DataVersion", dataVersion);
        if (!nc.containsKey("LastUpdate")) nc.put("LastUpdate", 0L);
        if (!nc.containsKey("InhabitedTime")) nc.put("InhabitedTime", 0L);
        return nc;
    }
}
