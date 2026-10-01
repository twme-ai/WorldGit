package org.worldgit.hub.data;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.worldgit.core.store.ObjectStore;

/**
 * 伺服器端預先計算的「俯視 tile」：一個 region（32×32 chunk = 512×512 格）一張 PNG 加上 4×4 解析度的高度圖
 * （doc 10 §5.1：不預先產生近景網格，只算高度圖與平均色）。內容定址：快取鍵 = region tree id + 資源版本，
 * 沒變動的 region 在不同 commit 之間共用同一份。
 *
 * <p>BlueMap core 的嵌入是 Phase 1 的選配項，這裡是「伺服器高度圖簡化版」（見 docs/11）。
 */
final class TileRenderer {
  static final int SIZE = 512;
  static final int CELL = 4;
  /** 高度圖單位偏移，0 保留給「沒有資料」。 */
  static final int HEIGHT_BIAS = 2049;
  private static final Set<String> GRASS = Set.of("grass_block", "short_grass", "tall_grass", "grass", "fern", "large_fern", "sugar_cane");
  private static final Set<String> AIRLIKE = Set.of("air", "cave_air", "void_air", "light", "structure_void", "barrier");

  record Result(byte[] png, byte[] heights, int chunks) {}

  private final ObjectStore store;
  private final Map<String, Integer> mapColors;
  private final Map<String, int[]> biomeColors;

  TileRenderer(ObjectStore store, Map<String, Integer> mapColors, Map<String, int[]> biomeColors) {
    this.store = store;
    this.mapColors = mapColors;
    this.biomeColors = biomeColors;
  }

  Result render(String regionTree, int rx, int rz) throws IOException {
    int[] colors = new int[SIZE * SIZE];
    int[] heights = new int[SIZE * SIZE];
    Arrays.fill(heights, Integer.MIN_VALUE);
    int chunks = 0;
    for (var ce : store.readTree(regionTree).values()) {
      if (ce.kind() != ObjectStore.Kind.TREE || !ce.name().startsWith("c.")) continue;
      int[] p = TreeNav.coords(ce.name(), "c");
      long lx = (long) p[0] - (long) rx * 32, lz = (long) p[1] - (long) rz * 32;
      if (lx < 0 || lz < 0 || lx >= 32 || lz >= 32) continue;
      chunk(store.readTree(ce.id()), (int) lx * 16, (int) lz * 16, colors, heights);
      chunks++;
    }
    var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
    for (int z = 0; z < SIZE; z++) {
      for (int x = 0; x < SIZE; x++) {
        int i = z * SIZE + x;
        if (heights[i] == Integer.MIN_VALUE) continue;
        int north = z > 0 && heights[i - SIZE] != Integer.MIN_VALUE ? heights[i - SIZE] : heights[i];
        double shade = Math.max(0.72, Math.min(1.28, 1.0 + (heights[i] - north) * 0.07));
        int c = colors[i];
        int r = Math.min(255, (int) (((c >> 16) & 255) * shade)), g = Math.min(255, (int) (((c >> 8) & 255) * shade)), b = Math.min(255, (int) ((c & 255) * shade));
        image.setRGB(x, z, 0xFF000000 | r << 16 | g << 8 | b);
      }
    }
    var png = new ByteArrayOutputStream();
    ImageIO.write(image, "png", png);
    var hb = new ByteArrayOutputStream();
    var out = new DataOutputStream(hb);
    int cells = SIZE / CELL;
    for (int cz = 0; cz < cells; cz++) {
      for (int cx = 0; cx < cells; cx++) {
        int max = Integer.MIN_VALUE;
        for (int dz = 0; dz < CELL; dz++) for (int dx = 0; dx < CELL; dx++) max = Math.max(max, heights[(cz * CELL + dz) * SIZE + cx * CELL + dx]);
        out.writeShort(max == Integer.MIN_VALUE ? 0 : Math.max(1, max + HEIGHT_BIAS));
      }
    }
    return new Result(png.toByteArray(), hb.toByteArray(), chunks);
  }

  private void chunk(SortedMap<String, ObjectStore.Entry> files, int ox, int oz, int[] colors, int[] heights) throws IOException {
    var sections = new TreeMap<Integer, ObjectStore.Entry>(Comparator.reverseOrder());
    for (var e : files.entrySet()) {
      String n = e.getKey();
      if (n.startsWith("s.") && n.endsWith(".bin")) sections.put(Integer.parseInt(n.substring(2, n.length() - 4)), e.getValue());
    }
    if (sections.isEmpty()) return;
    boolean[] done = new boolean[256], water = new boolean[256];
    int[] waterDepth = new int[256], waterTop = new int[256];
    int unresolved = 256;
    for (var se : sections.entrySet()) {
      if (unresolved == 0) break;
      int sy = se.getKey();
      SectionBlob blob = SectionBlob.parse(store.readBlob(se.getValue().id()));
      int n = blob.palette().length;
      int[] kind = new int[n], pcolor = new int[n]; // kind: 0 空氣 1 實心 2 水
      String[] names = new String[n];
      for (int i = 0; i < n; i++) {
        String name = SectionBlob.blockName(blob.palette()[i]);
        name = name.startsWith("minecraft:") ? name.substring(10) : name;
        names[i] = name;
        if (AIRLIKE.contains(name)) kind[i] = 0;
        else if (name.equals("water")) kind[i] = 2;
        else {
          kind[i] = 1;
          pcolor[i] = mapColors.getOrDefault(name, 0x7F7F7F);
        }
      }
      int[] idx = new int[4096];
      if (blob.bits() > 0) for (int i = 0; i < 4096; i++) idx[i] = blob.index(i);
      for (int y = 15; y >= 0 && unresolved > 0; y--) {
        for (int z = 0; z < 16; z++) {
          for (int x = 0; x < 16; x++) {
            int col = z * 16 + x;
            if (done[col]) continue;
            int pi = idx[x | z << 4 | y << 8];
            int k = kind[pi];
            if (k == 0) continue;
            int wy = sy * 16 + y;
            if (k == 2) {
              if (!water[col]) {
                water[col] = true;
                waterTop[col] = wy;
              }
              waterDepth[col]++;
              continue;
            }
            int c = pcolor[pi];
            c = tint(names[pi], c, biomeColorsAt(files, sy, x, y, z));
            int o = (oz + z) * SIZE + ox + x;
            if (water[col]) {
              int[] wc = biomeColorsAt(files, sy, x, y, z);
              int wcol = wc == null ? 0x3F76E4 : wc[2];
              double a = Math.min(0.92, 0.5 + 0.06 * waterDepth[col]);
              c = mix(c, wcol, a);
              heights[o] = waterTop[col];
            } else heights[o] = wy;
            colors[o] = c;
            done[col] = true;
            unresolved--;
          }
        }
      }
    }
    // 全是水（沒有實心底）的 column：用水色
    for (int col = 0; col < 256; col++) {
      if (done[col] || !water[col]) continue;
      int o = (oz + col / 16) * SIZE + ox + col % 16;
      colors[o] = 0x3F76E4;
      heights[o] = waterTop[col];
    }
  }

  // biomes.bin 的快取（每個 chunk 只讀一次）
  private SortedMap<String, ObjectStore.Entry> lastFiles;
  private Map<Integer, List<String>> lastBiomes;

  private Map<Integer, List<String>> biomesOf(SortedMap<String, ObjectStore.Entry> files) throws IOException {
    if (lastFiles != files) {
      lastFiles = files;
      var e = files.get("biomes.bin");
      lastBiomes = e == null ? Map.of() : org.worldgit.core.normalize.SnapshotCodec.biomes(store.readBlob(e.id()));
    }
    return lastBiomes;
  }

  private int[] biomeColorsAt(SortedMap<String, ObjectStore.Entry> files, int sy, int x, int y, int z) throws IOException {
    var list = biomesOf(files).get(sy);
    String name = list == null ? "minecraft:plains" : list.get((y >> 2) << 4 | (z >> 2) << 2 | (x >> 2));
    if (name.isEmpty()) name = "minecraft:plains";
    return biomeColors.getOrDefault(name, biomeColors.get("minecraft:plains"));
  }

  private static int tint(String name, int base, int[] biome) {
    if (biome == null) return base;
    if (GRASS.contains(name)) return multiply(base, biome[0]);
    if (name.endsWith("_leaves") && !name.equals("cherry_leaves") && !name.equals("azalea_leaves") && !name.equals("flowering_azalea_leaves") && !name.equals("pale_oak_leaves"))
      return multiply(base, biome[1]);
    return base;
  }

  private static int multiply(int a, int b) {
    int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255, g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255, bl = (a & 255) * (b & 255) / 255;
    return r << 16 | g << 8 | bl;
  }

  private static int mix(int a, int b, double t) {
    int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t),
        bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
    return r << 16 | g << 8 | bl;
  }
}
