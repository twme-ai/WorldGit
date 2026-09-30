package wg;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.core.map.BmMap;
import de.bluecolored.bluemap.core.resources.MinecraftVersion;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.storage.file.FileMapStorage;
import de.bluecolored.bluemap.core.util.Grid;
import wg.FakeWorld.FakeChunk;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

import static wg.FakeWorld.bs;

/**
 * 原型：BlueMap core 從「記憶體中的假 World」產生 hires/lowres tile，
 * 並示範以「涵蓋 chunk 的內容雜湊」為 key 的增量重繪。
 * 工作目錄 = .work/bluemap-proto（由 gradle run 設定）。
 */
public class Proto {

    public static void main(String[] args) throws Exception {
        String mc = args.length > 0 ? args[0] : "1.21.11";
        Path work = Path.of("").toAbsolutePath();
        long t0 = System.nanoTime();

        // 1. 資源：下載原版 client jar（快取在 data/），加上 BlueMap 附帶的 resourceExtensions.zip
        Path data = work.resolve("data");
        MinecraftVersion version = MinecraftVersion.load(mc, data, true);
        Path ext = data.resolve("resourceExtensions.zip");
        try (InputStream in = BmMap.class.getResourceAsStream("/de/bluecolored/bluemap/resourceExtensions.zip")) {
            Files.copy(in, ext, StandardCopyOption.REPLACE_EXISTING);
        }
        ResourcePack pack = new ResourcePack(version.getResourcePackVersion());
        pack.loadResources(new ArrayDeque<>(List.of(version.getResourcePack(), ext)));
        long t1 = System.nanoTime();
        System.out.printf("[resources] mc=%s loaded in %d ms%n", mc, (t1 - t0) / 1_000_000);

        // 2. 假世界：blocks 0..63 x 0..63（chunk 0..3），外圍一圈 chunk（-1..4）為鄰居
        FakeWorld world = new FakeWorld();
        for (int cx = -1; cx <= 4; cx++) for (int cz = -1; cz <= 4; cz++) world.put(cx, cz, buildChunk(cx, cz));

        // 3. BmMap（storage 用 BlueMap 內建的 FileMapStorage；Hub 可自行實作 MapStorage 介面）
        Path out = work.resolve("out-" + mc);
        FileMapStorage storage = new FileMapStorage(out.resolve("map"), Compression.GZIP, false);
        BmMap map = new BmMap("wg-demo", "WorldGit demo", world, storage, pack, new Settings());
        Grid tg = map.getHiresModelManager().getTileGrid();

        // 4. 依「涵蓋 chunk（含 1 格鄰居）的內容雜湊」決定要不要重繪 hires tile
        Map<Vector2i, String> keys = new HashMap<>();
        Set<Vector2i> tiles = new TreeSet<>(Comparator.comparingInt(Vector2i::getX).thenComparingInt(Vector2i::getY));
        for (int x = 0; x <= 63; x += 8) for (int z = 0; z <= 63; z += 8) tiles.add(new Vector2i(tg.getCellX(x), tg.getCellY(z)));
        System.out.println("[tiles] hires tiles covering blocks 0..63: " + tiles);

        renderPass("pass 1 (cold)", map, world, tg, tiles, keys);
        map.save();
        // 改一格方塊（chunk 1,1 放一個金塊），只有涵蓋（含鄰居 1 格）該 chunk 的 tile 應重繪
        FakeChunk c = buildChunk(1, 1); c.set(5, 64, 5, bs("minecraft:gold_block")); c.finish(); world.put(1, 1, c);
        renderPass("pass 2 (chunk 1,1 changed)", map, world, tg, tiles, keys);
        map.save();

        System.out.println("[output] " + out.resolve("map"));
        System.exit(0);
    }

    static void renderPass(String label, BmMap map, FakeWorld world, Grid tg, Set<Vector2i> tiles, Map<Vector2i, String> keys) {
        int rendered = 0, skipped = 0;
        long t = System.nanoTime();
        for (Vector2i tile : tiles) {
            // tile 範圍向外擴 1 格（面剔除/AO 會讀鄰居方塊），涵蓋的 chunk 內容雜湊 = 快取 key
            int minCx = (tg.getCellMinX(tile.getX()) - 1) >> 4, maxCx = (tg.getCellMaxX(tile.getX()) + 1) >> 4;
            int minCz = (tg.getCellMinY(tile.getY()) - 1) >> 4, maxCz = (tg.getCellMaxY(tile.getY()) + 1) >> 4;
            StringBuilder sb = new StringBuilder();
            for (int cx = minCx; cx <= maxCx; cx++) for (int cz = minCz; cz <= maxCz; cz++) {
                FakeChunk fc = world.raw(cx, cz);
                sb.append(fc == null ? "-" : fc.contentHash()).append(',');
            }
            String key = Integer.toHexString(sb.toString().hashCode());
            if (key.equals(keys.get(tile))) { skipped++; continue; }
            map.renderTile(tile);
            keys.put(tile, key);
            rendered++;
        }
        System.out.printf("[%s] rendered=%d skipped=%d in %d ms%n", label, rendered, skipped, (System.nanoTime() - t) / 1_000_000);
    }

    static FakeChunk buildChunk(int cx, int cz) {
        FakeChunk c = new FakeChunk();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            c.set(x, 0, z, bs("minecraft:bedrock"));
            for (int y = 1; y <= 58; y++) c.set(x, y, z, bs("minecraft:stone"));
            for (int y = 59; y <= 61; y++) c.set(x, y, z, bs("minecraft:dirt"));
            c.set(x, 62, z, bs("minecraft:grass_block", "snowy", "false"));
        }
        // 只在 chunk (1,1) 與 (2,1) 蓋東西（世界座標 16..47, 16..31）
        if (cz == 1 && (cx == 1 || cx == 2)) {
            int ox = 0;
            // 石磚平台
            for (int x = 2; x < 14; x++) for (int z = 2; z < 14; z++) c.set(x, 63, z, bs("minecraft:stone_bricks"));
            // 橡木樓梯（四個方向 + 上下）
            String[] facing = {"north", "east", "south", "west"};
            for (int i = 0; i < 4; i++) c.set(3 + i * 2, 64, 3, bs("minecraft:oak_stairs", "facing", facing[i], "half", "bottom", "shape", "straight", "waterlogged", "false"));
            c.set(3, 64, 5, bs("minecraft:oak_stairs", "facing", "east", "half", "top", "shape", "outer_right", "waterlogged", "false"));
            // 柵欄（一排，含相連狀態）
            for (int x = 3; x < 12; x++) c.set(x, 64, 8, bs("minecraft:oak_fence",
                    "east", x < 11 ? "true" : "false", "west", x > 3 ? "true" : "false", "north", "false", "south", "false", "waterlogged", "false"));
            // 原木 + 樹葉
            for (int y = 64; y < 68; y++) c.set(10, y, 4, bs("minecraft:oak_log", "axis", "y"));
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = 67; y <= 69; y++)
                if (Math.abs(dx) + Math.abs(dz) + (y - 67) < 4) c.set(10 + dx, y, 4 + dz, bs("minecraft:oak_leaves", "distance", "1", "persistent", "true", "waterlogged", "false"));
            // 玻璃、水池、羊毛
            c.set(4, 64, 11, bs("minecraft:glass")); c.set(5, 64, 11, bs("minecraft:red_stained_glass"));
            for (int x = 7; x < 10; x++) for (int z = 10; z < 13; z++) { c.set(x, 63, z, bs("minecraft:water", "level", "0")); }
            c.set(12, 64, 11, bs("minecraft:red_wool")); c.set(12, 64, 12, bs("minecraft:blue_wool"));
        }
        c.finish();
        return c;
    }
}
