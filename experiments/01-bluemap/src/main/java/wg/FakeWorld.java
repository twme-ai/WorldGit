package wg;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.core.util.Grid;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.world.*;
import de.bluecolored.bluemap.core.world.biome.Biome;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 記憶體中的 World。真實版本會在 getChunk() 內從 JGit 讀 chunk tree -> section blob -> 解碼。
 * 只實作 BlueMap 渲染實際會呼叫的方法；光照、heightmap、inhabitedTime 都不提供（WorldGit 也不存這些）。
 */
public class FakeWorld implements World {

    private final Map<Long, FakeChunk> chunks = new ConcurrentHashMap<>();
    private final Grid chunkGrid = new Grid(16);
    private final Grid regionGrid = new Grid(512);

    public void put(int cx, int cz, FakeChunk chunk) { chunks.put(key(cx, cz), chunk); }
    public FakeChunk raw(int cx, int cz) { return chunks.get(key(cx, cz)); }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }

    @Override public String getId() { return "wg-fake#minecraft:overworld"; }
    @Override public DimensionType getDimensionType() { return DimensionType.OVERWORLD; }
    @Override public Grid getChunkGrid() { return chunkGrid; }
    @Override public Grid getRegionGrid() { return regionGrid; }

    @Override public Chunk getChunkAtBlock(int x, int z) { return getChunk(x >> 4, z >> 4); }
    @Override public Chunk getChunk(int x, int z) {
        FakeChunk c = chunks.get(key(x, z));
        return c != null ? c : Chunk.EMPTY_CHUNK;
    }
    @Override public Region<Chunk> getRegion(int x, int z) { throw new UnsupportedOperationException(); }
    @Override public Collection<Vector2i> listRegions() { return List.of(Vector2i.ZERO); }
    @Override public void preloadRegionChunks(int x, int z, Predicate<Vector2i> chunkFilter) {}
    @Override public void invalidateChunkCache() {}
    @Override public void invalidateChunkCache(int x, int z) {}
    @Override public void iterateEntities(int minX, int minZ, int maxX, int maxZ, Consumer<Entity> entityConsumer) {}

    /** 一個 chunk：y 範圍 [0, height)，只有方塊；天光用「柱頂以上=15」近似（WorldGit 丟棄光照，Hub 必須自己補）。 */
    public static class FakeChunk implements Chunk {
        public static final int HEIGHT = 96;
        private final BlockState[] blocks = new BlockState[16 * HEIGHT * 16];
        private final int[] top = new int[256]; // 每柱最高非空氣 y

        public void set(int x, int y, int z, BlockState bs) { blocks[idx(x, y, z)] = bs; }
        private static int idx(int x, int y, int z) { return (y * 16 + (z & 15)) * 16 + (x & 15); }

        public void finish() {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int t = -1;
                for (int y = HEIGHT - 1; y >= 0; y--) { BlockState b = blocks[idx(x, y, z)]; if (b != null && !b.isAir()) { t = y; break; } }
                top[z * 16 + x] = t;
            }
        }

        @Override public boolean isGenerated() { return true; }
        @Override public BlockState getBlockState(int x, int y, int z) {
            if (y < 0 || y >= HEIGHT) return BlockState.AIR;
            BlockState b = blocks[idx(x, y, z)];
            return b == null ? BlockState.AIR : b;
        }
        @Override public LightData getLightData(int x, int y, int z, LightData target) {
            return target.set(y > top[(z & 15) * 16 + (x & 15)] ? 15 : 0, 0);
        }
        @Override public Biome getBiome(int x, int y, int z) { return Biome.DEFAULT; }
        @Override public int getMinY(int x, int z) { return 0; }
        @Override public int getMaxY(int x, int z) { return HEIGHT - 1; }

        /** 「tree 雜湊」的替身：對內容做 SHA-1。真實系統直接用 git chunk tree 的 object id。 */
        public String contentHash() {
            try {
                var md = java.security.MessageDigest.getInstance("SHA-1");
                for (BlockState b : blocks) md.update((b == null ? "air" : b.getId().getFormatted() + b.getProperties()).getBytes());
                return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 12);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    public static BlockState bs(String id, String... kv) {
        java.util.Map<String, String> p = new java.util.TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return new BlockState(new Key(id), p);
    }
}
