package wgproto;

import org.eclipse.jgit.lib.*;
import wgproto.Nbt.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** init / commit：世界 → git tree。 */
public final class Snapshot {
    public static IgnoreRules rules;
    public static boolean streamInit;
    static boolean forceScan;
    /** index：每個 chunk 上次看到的時間戳。key = dim + "/" + cx + "," + cz → {ts, ets, full} */
    public static final class Index {
        public String commit = "";
        public final Map<String, Map<Long, int[]>> dims = new TreeMap<>();

        static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
        static int kx(long k) { return (int) (k >> 32); }
        static int kz(long k) { return (int) k; }

        public static Index load(Path p) throws IOException {
            Index ix = new Index();
            if (!Files.exists(p)) return ix;
            List<String> lines = Files.readAllLines(p);
            ix.commit = lines.get(0);
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split("\t");
                ix.dims.computeIfAbsent(f[0], k -> new HashMap<>()).put(key(Integer.parseInt(f[1]), Integer.parseInt(f[2])),
                        new int[]{Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5])});
            }
            return ix;
        }

        public void save(Path p) throws IOException {
            StringBuilder sb = new StringBuilder(commit).append('\n');
            for (var d : dims.entrySet())
                for (var e : d.getValue().entrySet())
                    sb.append(d.getKey()).append('\t').append(kx(e.getKey())).append('\t').append(kz(e.getKey())).append('\t')
                            .append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\t').append(e.getValue()[2]).append('\n');
            Files.writeString(p, sb.toString());
        }
    }

    public static final class Result {
        public int chunksScanned, chunksRecomputed, chunksTracked, chunksUntracked;
        public int sectionsChanged, biomesChanged, ticksChanged, structuresChanged, entityChunksChanged, chunksRemoved;
        public int entityChunkFilesRead, entitiesSeen, entitiesStickyKept;
        public long millis;
        public ObjectId commit; public boolean committed;
        @Override public String toString() {
            return String.format("scanned=%d recomputed=%d tracked=%d untracked(non-full)=%d removed=%d | blobs changed: sections=%d biomes=%d ticks=%d structures=%d entityChunks=%d | entities seen=%d stickyKept=%d | %d ms | commit=%s",
                    chunksScanned, chunksRecomputed, chunksTracked, chunksUntracked, chunksRemoved, sectionsChanged, biomesChanged, ticksChanged, structuresChanged,
                    entityChunksChanged, entitiesSeen, entitiesStickyKept, millis, committed ? commit.abbreviate(10).name() : "(none: no changes)");
        }
    }

    public static Path indexPath(Path repoDir) { return repoDir.resolveSibling(repoDir.getFileName() + ".index"); }

    static boolean isFull(NCompound chunk) {
        String st = chunk.str("Status");
        return st != null && (st.equals("minecraft:full") || st.equals("full"));
    }

    public static Result snapshot(Path serverRoot, Path repoDir, String message, double tol, boolean forceFull, boolean allowEmpty) throws IOException {
        long t0 = System.nanoTime();
        Result res = new Result();
        Layout layout = new Layout(serverRoot);
        try (Repo repo = new Repo(repoDir, true)) {
            ObjectId head = repo.head();
            Index oldIdx = Index.load(indexPath(repoDir));
            boolean validIndex = head != null && oldIdx.commit.equals(head.name());
            boolean useIdx = !forceFull && validIndex;
            if (rules == null) rules = new IgnoreRules(null);
            String signature = rules.source + "\ntol=" + tol + " extra=" + IgnoreRules.extraNoise;
            Path configPath = repoDir.resolveSibling(repoDir.getFileName() + ".config-cache");
            useIdx &= Files.exists(configPath) && Files.readString(configPath).equals(signature);
            forceScan = !useIdx;
            Repo.Node root = repo.rootOf(head);
            Index newIdx = new Index();
            for (Layout.Dim d : layout.dims) {
                Map<Long, int[]> old = validIndex ? oldIdx.dims.getOrDefault(d.id(), Map.of()) : Map.of();
                Map<Long, int[]> nw = new HashMap<>();
                newIdx.dims.put(d.id(), nw);
                snapshotDim(repo, root, d, old, nw, tol, res, head != null);
            }
            if (!rules.source.isEmpty()) root.putBlob(".wgignore", repo.ins.insert(Constants.OBJ_BLOB, rules.source.getBytes(StandardCharsets.UTF_8)));
            else root.remove(".wgignore");
            // world-meta 占位
            String meta = "worldgit-proto-meta 1\ndataVersion=" + layout.dataVersion() + "\nlayout=" + layout.kind + "\n";
            root.putBlob("world-meta", repo.ins.insert(Constants.OBJ_BLOB, meta.getBytes(StandardCharsets.UTF_8)));
            ObjectId tree = root.write();
            ObjectId headTree = head == null ? null : repo.treeOf(head);
            if (tree == null) { tree = repo.ins.insert(new TreeFormatter()); }
            repo.ins.flush();
            if (headTree == null || !tree.equals(headTree) || allowEmpty) {
                res.commit = repo.commit(tree, head, message, System.currentTimeMillis() / 1000);
                res.committed = true;
                newIdx.commit = res.commit.name();
            } else {
                newIdx.commit = head.name();
            }
            newIdx.save(indexPath(repoDir));
            Files.writeString(configPath, signature);
        }
        res.millis = (System.nanoTime() - t0) / 1_000_000;
        return res;
    }

    static Repo.Node dimNode(Repo.Node root, String dimId) {
        Repo.Node n = root;
        for (String seg : dimId.split("/")) n = n.tree(seg);
        return n;
    }

    static Repo.Node findChunk(Repo.Node root, String dimId, int cx, int cz, boolean create) {
        Repo.Node n = root;
        for (String seg : dimId.split("/")) { n = create ? n.tree(seg) : n.get(seg); if (n == null) return null; }
        String r = "r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32);
        n = create ? n.tree(r) : n.get(r);
        if (n == null) return null;
        String c = "c." + cx + "." + cz;
        return create ? n.tree(c) : n.get(c);
    }

    static void snapshotDim(Repo repo, Repo.Node root, Layout.Dim d, Map<Long, int[]> old, Map<Long, int[]> nw, double tol, Result res, boolean hasHead) throws IOException {
        // ---- A. 方塊 ----
        for (Path rp : Region.list(d.region())) {
            Region r = new Region(rp);
            for (int i = 0; i < 1024; i++) {
                if (!r.has(i)) continue;
                int cx = r.rx * 32 + (i & 31), cz = r.rz * 32 + (i >> 5);
                long key = Index.key(cx, cz);
                int ts = r.timestamp(i);
                res.chunksScanned++;
                int[] o = old.get(key);
                if (!forceScan && o != null && o[0] == ts) { nw.put(key, new int[]{ts, o[1], o[2]}); if (o[2] == 1) res.chunksTracked++; else res.chunksUntracked++; continue; }
                res.chunksRecomputed++;
                NCompound nbt = r.read(i);
                if (!isFull(nbt)) {
                    nw.put(key, new int[]{ts, 0, 0});
                    res.chunksUntracked++;
                    Repo.Node cn = findChunk(root, d.id(), cx, cz, false);
                    if (cn != null) { removeChunk(root, d.id(), cx, cz); res.chunksRemoved++; }
                    continue;
                }
                nw.put(key, new int[]{ts, o == null ? 0 : o[1], 1});
                res.chunksTracked++;
                Repo.Node cn = findChunk(root, d.id(), cx, cz, true);
                applyChunk(repo, cn, nbt, res);
                if (streamInit && !hasHead) { cn.id = cn.write(); cn.kids = null; }
            }
        }
        // 消失的 chunk
        for (long key : old.keySet()) {
            if (!nw.containsKey(key)) {
                if (old.get(key)[2] == 1 && findChunk(root, d.id(), Index.kx(key), Index.kz(key), false) != null) {
                    removeChunk(root, d.id(), Index.kx(key), Index.kz(key)); res.chunksRemoved++;
                }
            }
        }
        // ---- B. 實體 ----
        Map<Long, List<NCompound>> newRecs = new HashMap<>();
        Set<Long> changedE = new HashSet<>();
        Set<Long> present = new HashSet<>();
        for (Path rp : Region.list(d.entities())) {
            Region r = new Region(rp);
            for (int i = 0; i < 1024; i++) {
                if (!r.has(i)) continue;
                int cx = r.rx * 32 + (i & 31), cz = r.rz * 32 + (i >> 5);
                long key = Index.key(cx, cz);
                int[] cur = nw.get(key);
                if (cur == null || cur[2] != 1) continue; // 只追蹤 full chunk
                present.add(key);
                int ets = r.timestamp(i);
                if (!forceScan && old.containsKey(key) && old.get(key)[1] == ets && old.get(key)[2] == 1) { cur[1] = ets; continue; }
                cur[1] = ets;
                changedE.add(key);
                res.entityChunkFilesRead++;
                NCompound en = r.read(i);
                NList l = en.list("Entities");
                List<NCompound> recs = new ArrayList<>();
                if (l != null) for (Object o : l.items) if (!rules.ignored((NCompound)o)) recs.add(Codec.normEntity((NCompound) o, true));
                newRecs.put(key, recs);
            }
        }
        // 上次有實體紀錄、這次 entities 檔案已無該 chunk
        for (var e : old.entrySet()) {
            long key = e.getKey();
            if (e.getValue()[1] != 0 && !present.contains(key) && nw.containsKey(key) && nw.get(key)[2] == 1) {
                changedE.add(key); newRecs.putIfAbsent(key, new ArrayList<>());
                nw.get(key)[1] = 0;
            }
        }
        // 新追蹤且沒有 entities 紀錄的 chunk：什麼都不用做
        if (changedE.isEmpty()) return;

        // HEAD 候選：changedE 及其 8 鄰居
        Set<Long> around = new HashSet<>();
        for (long k : changedE) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) around.add(Index.key(Index.kx(k) + dx, Index.kz(k) + dz));
        Map<Long, List<NCompound>> headLists = new HashMap<>();
        Map<String, Object[]> cand = new HashMap<>(); // uuid → {rec, chunkKey}
        if (hasHead) {
            for (long k : around) {
                Repo.Node cn = findChunk(root, d.id(), Index.kx(k), Index.kz(k), false);
                if (cn == null) continue;
                Repo.Node en = cn.get("entities.bin");
                if (en == null) continue;
                List<NCompound> hl = Codec.decodeEntities(repo.blob(en.id));
                headLists.put(k, hl);
                for (NCompound rec : hl) cand.putIfAbsent(Codec.uuidKey(rec), new Object[]{rec, k});
            }
        }
        Set<String> newUuids = new HashSet<>();
        for (var e : newRecs.entrySet()) for (NCompound rec : e.getValue()) newUuids.add(Codec.uuidKey(rec));

        Map<Long, List<NCompound>> out = new HashMap<>();
        for (long k : changedE) out.put(k, new ArrayList<>());
        // 非 changedE 的鄰居：以 HEAD 為底，移除會被重新處理的 UUID
        for (var e : headLists.entrySet()) {
            if (changedE.contains(e.getKey())) continue;
            List<NCompound> keep = new ArrayList<>();
            for (NCompound rec : e.getValue()) if (!newUuids.contains(Codec.uuidKey(rec))) keep.add(rec);
            out.put(e.getKey(), keep);
        }
        for (var e : newRecs.entrySet()) {
            for (NCompound rec : e.getValue()) {
                res.entitiesSeen++;
                Object[] c = cand.get(Codec.uuidKey(rec));
                NCompound chosen = rec; long place = e.getKey();
                if (c != null && Codec.stickyEqual(rec, (NCompound) c[0], tol)) { chosen = (NCompound) c[0]; place = (Long) c[1]; res.entitiesStickyKept++; }
                out.computeIfAbsent(place, kk -> new ArrayList<>()).add(chosen);
            }
        }
        for (var e : out.entrySet()) {
            long k = e.getKey();
            Repo.Node cn = findChunk(root, d.id(), Index.kx(k), Index.kz(k), false);
            if (cn == null) continue; // 未追蹤的 chunk 不存實體
            byte[] blob = Codec.encodeEntities(e.getValue());
            Repo.Node existing = cn.get("entities.bin");
            if (blob == null) { if (existing != null) { cn.remove("entities.bin"); res.entityChunksChanged++; } }
            else {
                ObjectId id = repo.ins.insert(Constants.OBJ_BLOB, blob);
                if (existing == null || !existing.id.equals(id)) res.entityChunksChanged++;
                cn.putBlob("entities.bin", id);
            }
        }
    }

    static void removeChunk(Repo.Node root, String dimId, int cx, int cz) {
        Repo.Node n = root;
        for (String seg : dimId.split("/")) n = n.get(seg);
        Repo.Node r = n.get("r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32));
        if (r != null) r.remove("c." + cx + "." + cz);
    }

    /** 由 chunk NBT 產生各 blob 並更新 chunk 節點（保留 entities.bin）。 */
    static void applyChunk(Repo repo, Repo.Node cn, NCompound nbt, Result res) throws IOException {
        Map<String, byte[]> files = chunkFiles(nbt);
        // 移除不再存在的檔案（保留 entities.bin）
        for (String name : new ArrayList<>(cn.kids().keySet())) {
            if (name.equals("entities.bin")) continue;
            if (!files.containsKey(name)) { cn.remove(name); if (name.startsWith("s.")) res.sectionsChanged++; }
        }
        for (var f : files.entrySet()) {
            ObjectId id = repo.ins.insert(Constants.OBJ_BLOB, f.getValue());
            Repo.Node ex = cn.get(f.getKey());
            if (ex == null || !ex.id.equals(id)) {
                String n = f.getKey();
                if (n.startsWith("s.")) res.sectionsChanged++;
                else if (n.equals("biomes.bin")) res.biomesChanged++;
                else if (n.equals("ticks.bin")) res.ticksChanged++;
                else if (n.equals("structures.bin")) res.structuresChanged++;
            }
            cn.putBlob(f.getKey(), id);
        }
    }

    /** chunk NBT → { "s.Y.bin", "biomes.bin", "ticks.bin", "structures.bin" } */
    public static Map<String, byte[]> chunkFiles(NCompound chunk) {
        Map<String, byte[]> files = new TreeMap<>();
        NList secs = chunk.list("sections");
        Map<Integer, List<NCompound>> besBySec = new HashMap<>();
        NList bel = chunk.list("block_entities");
        if (bel != null) for (Object o : bel.items) {
            NCompound be = (NCompound) o;
            besBySec.computeIfAbsent(Math.floorDiv(be.intv("y", 0), 16), k -> new ArrayList<>()).add(be);
        }
        if (secs != null) {
            for (Object o : secs.items) {
                NCompound s = (NCompound) o;
                int y = ((Number) s.get("Y")).intValue();
                byte[] b = Codec.encodeSection(s, besBySec.getOrDefault(y, List.of()), y);
                if (b != null) files.put("s." + y + ".bin", b);
            }
            byte[] bio = Codec.encodeBiomes(secs);
            if (bio != null) files.put("biomes.bin", bio);
        }
        byte[] t = Codec.encodeTicks(chunk);
        if (t != null) files.put("ticks.bin", t);
        byte[] st = Codec.encodeStructures(chunk);
        if (st != null) files.put("structures.bin", st);
        return files;
    }
}
