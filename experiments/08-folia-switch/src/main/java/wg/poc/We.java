package wg.poc;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WorldEdit / FAWE 偵測：EditSessionEvent 包一層 Extent（逐格 setBlock 與 bulk 方法），
 * 另外在 FAWE 上加 IBatchProcessor（看 chunk 層級的 IChunkSet，繞過逐格 API 的路徑）。
 */
public final class We {
    public static final class Sess {
        public final int id; public final String actor, world, stage;
        public final AtomicInteger setBlock = new AtomicInteger(), tile = new AtomicInteger(), biome = new AtomicInteger();
        public final Map<String, AtomicInteger> bulk = new ConcurrentHashMap<>();
        public final Map<String, AtomicInteger> perChunkSetBlock = new ConcurrentHashMap<>();
        public final Map<String, Integer> bulkChunks = new ConcurrentHashMap<>(); // bulk 方法的 Region 所涵蓋的 chunk -> 次數
        public final Map<String, Integer> procChunks = new ConcurrentHashMap<>(); // "cx,cz" -> 非 0 的格數
        public volatile int commits;
        public volatile String threads = "";
        Sess(int id, String actor, String world, String stage) { this.id = id; this.actor = actor; this.world = world; this.stage = stage; }
        void bulk(String n) { bulk.computeIfAbsent(n, k -> new AtomicInteger()).incrementAndGet(); }
        void bulk(String n, Region r) {
            bulk(n);
            try {
                BlockVector3 a = r.getMinimumPoint(), b = r.getMaximumPoint();
                for (int cx = a.x() >> 4; cx <= b.x() >> 4; cx++) for (int cz = a.z() >> 4; cz <= b.z() >> 4; cz++) bulkChunks.merge(cx + "," + cz, 1, Integer::sum);
            } catch (Throwable t) { bulkChunks.merge("err:" + t.getClass().getSimpleName(), 1, Integer::sum); }
        }
        void chunk(int x, int z) { perChunkSetBlock.computeIfAbsent((x >> 4) + "," + (z >> 4), k -> new AtomicInteger()).incrementAndGet(); }
        void thread() { String n = Thread.currentThread().getName(); if (!threads.contains(n)) threads += (threads.isEmpty() ? "" : "|") + n; }
        public Map<String, Object> toMap() {
            Map<String, Object> pc = new TreeMap<>();
            perChunkSetBlock.forEach((k, v) -> pc.put(k, v.get()));
            Map<String, Object> b = new TreeMap<>();
            bulk.forEach((k, v) -> b.put(k, v.get()));
            return Out.m("id", id, "actor", actor, "world", world, "setBlockCalls", setBlock.get(), "tileCalls", tile.get(), "biomeCalls", biome.get(),
                    "bulkCalls", b, "bulkRegionChunks", new TreeMap<>(bulkChunks), "setBlockPerChunk", pc, "processorChunks", new TreeMap<>(procChunks), "commits", commits, "threads", threads);
        }
    }

    public static final List<Sess> sessions = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger ids = new AtomicInteger();
    public static volatile int eventsSeen;
    public static final Map<String, Integer> stageCounts = new ConcurrentHashMap<>();
    public static volatile boolean fawe;

    public static void install() {
        try { Class.forName("com.fastasyncworldedit.core.Fawe"); fawe = true; } catch (Throwable t) { fawe = false; }
        WorldEdit.getInstance().getEventBus().register(new We());
    }

    /** 取出並清空目前累積的 session 統計。 */
    public static List<Map<String, Object>> drain() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (sessions) { for (Sess s : sessions) out.add(s.toMap()); sessions.clear(); }
        return out;
    }

    @Subscribe
    public void onEdit(EditSessionEvent ev) {
        eventsSeen++;
        stageCounts.merge(String.valueOf(ev.getStage()), 1, Integer::sum);
        if (ev.getStage() != EditSession.Stage.BEFORE_CHANGE) return;
        String actor = ev.getActor() == null ? "<none>" : ev.getActor().getName();
        String world = ev.getWorld() == null ? "<none>" : ev.getWorld().getName();
        Sess s = new Sess(ids.incrementAndGet(), actor, world, String.valueOf(ev.getStage()));
        sessions.add(s);
        Extent ex = ev.getExtent();
        if (fawe) {
            try { ex = FaweHook.wrap(ex, s); } catch (Throwable t) { Out.res("we_error", "where", "fawe-proc", "err", t.toString()); }
            ev.setExtent(FaweHook.log(ex, s));
        } else ev.setExtent(new Log(ex, s));
    }

    public static class Log extends AbstractDelegateExtent {
        protected final Sess s;
        public Log(Extent e, Sess s) { super(e); this.s = s; }
        @Override public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 p, T b) throws WorldEditException {
            s.setBlock.incrementAndGet(); s.chunk(p.x(), p.z()); s.thread(); return super.setBlock(p, b);
        }
        @Override public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T b) throws WorldEditException {
            s.setBlock.incrementAndGet(); s.chunk(x, z); s.thread(); return super.setBlock(x, y, z, b);
        }
        @Override public <B extends BlockStateHolder<B>> int setBlocks(Region r, B b) throws MaxChangedBlocksException { s.bulk("setBlocks(Region,Block)", r); return super.setBlocks(r, b); }
        @Override public int setBlocks(Region r, Pattern p) throws MaxChangedBlocksException { s.bulk("setBlocks(Region,Pattern)", r); return super.setBlocks(r, p); }
        @Override public int setBlocks(Set<BlockVector3> set, Pattern p) { s.bulk("setBlocks(Set,Pattern)"); for (BlockVector3 v : set) s.chunk(v.x(), v.z()); return super.setBlocks(set, p); }
        @Override public int replaceBlocks(Region r, Set<BaseBlock> f, Pattern p) throws MaxChangedBlocksException { s.bulk("replaceBlocks(Region,Set,Pattern)", r); return super.replaceBlocks(r, f, p); }
        @Override public int replaceBlocks(Region r, Mask m, Pattern p) throws MaxChangedBlocksException { s.bulk("replaceBlocks(Region,Mask,Pattern)", r); return super.replaceBlocks(r, m, p); }
        @Override public boolean setBiome(int x, int y, int z, com.sk89q.worldedit.world.biome.BiomeType b) { s.biome.incrementAndGet(); return super.setBiome(x, y, z, b); }
    }
}
