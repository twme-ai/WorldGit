package org.worldgit.hub.assets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 資源管線（doc 10 §3）：從 Mojang 客戶端資源產生瀏覽器用的預處理產物。
 * 產物：atlas.png / atlas.json（自行打包，保留 64×64 實體貼圖）、blockstates.json、models.json（含 parent 閉包）、
 * flags.json（opaque / semi_transparent / self_culling）、biomes.json（草／葉／水染色）、mapcolors.json（俯視地圖用）。
 * 模型展開留給瀏覽器端的 deepslate（不在這裡重寫 Minecraft 的模型語意）。
 */
final class AssetPipeline {
  private static final Logger log = LoggerFactory.getLogger(AssetPipeline.class);
  private static final String MC = "assets/minecraft/";
  private static final List<String> ENTITY_DIRS = List.of("chest", "bed", "banner", "signs", "shulker", "bell", "conduit",
      "decorated_pot", "skeleton", "zombie", "creeper", "player", "piglin", "enderdragon", "copper_golem");
  private static final Pattern SELF_CULLING = Pattern.compile("glass$|_glass$|^ice$|slime_block|honey_block|^frosted_ice$");

  private final ObjectMapper json = new ObjectMapper();
  private final ResourceSource src;

  AssetPipeline(ResourceSource src) {
    this.src = src;
  }

  private static String norm(String id) {
    return id.contains(":") ? id : "minecraft:" + id;
  }

  private static String strip(String id) {
    return id.startsWith("minecraft:") ? id.substring(10) : id;
  }

  private JsonNode readJson(String path) throws IOException {
    return json.readTree(src.read(path));
  }

  private static String spriteOf(JsonNode t) {
    if (t == null || t.isNull()) return null;
    if (t.isTextual()) return t.asText();
    return t.has("sprite") ? t.get("sprite").asText() : null;
  }

  private record Tex(String id, int w, int h, int[] argb, double translucent, double transparent) {}

  /** 產生全部產物到 out（呼叫端負責原子性）。 */
  Map<String, Object> run(Path out) throws IOException {
    long t0 = System.nanoTime();
    Files.createDirectories(out);
    // ---- blockstates ----
    var blockstates = new TreeMap<String, JsonNode>();
    for (String f : src.list(MC + "blockstates/")) {
      if (f.endsWith(".json")) blockstates.put(f.substring((MC + "blockstates/").length(), f.length() - 5), readJson(f));
    }
    var modelRefs = new TreeSet<String>();
    for (JsonNode bs : blockstates.values()) for (String m : refsOf(bs)) modelRefs.add(norm(m));
    // ---- models（parent 閉包）----
    var models = new TreeMap<String, JsonNode>();
    var queue = new ArrayDeque<>(modelRefs);
    while (!queue.isEmpty()) {
      String id = queue.pop();
      if (models.containsKey(id) || id.startsWith("minecraft:builtin/")) continue;
      String file = MC + "models/" + strip(id) + ".json";
      if (!src.exists(file)) {
        log.warn("缺少 model {}", id);
        continue;
      }
      JsonNode m = readJson(file);
      models.put(id, m);
      if (m.has("parent")) queue.push(norm(m.get("parent").asText()));
    }
    // ---- 貼圖 ----
    var texIds = new TreeSet<String>();
    for (JsonNode m : models.values()) {
      if (!m.has("textures")) continue;
      for (var it = m.get("textures").elements(); it.hasNext(); ) {
        String s = spriteOf(it.next());
        if (s != null && !s.startsWith("#")) texIds.add(norm(s));
      }
    }
    for (String t : List.of("water_still", "water_flow", "lava_still", "lava_flow")) texIds.add("minecraft:block/" + t);
    for (String d : ENTITY_DIRS)
      for (String p : src.list(MC + "textures/entity/" + d))
        if (p.endsWith(".png")) texIds.add("minecraft:entity/" + p.substring((MC + "textures/entity/").length(), p.length() - 4));
    var loaded = new ArrayList<Tex>();
    var missing = new ArrayList<String>();
    for (String id : texIds) {
      String file = MC + "textures/" + strip(id) + ".png";
      if (!src.exists(file)) {
        missing.add(id);
        continue;
      }
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(src.read(file)));
      if (img == null) {
        missing.add(id);
        continue;
      }
      int w = img.getWidth(), h = img.getHeight();
      if (src.exists(file + ".mcmeta")) {
        JsonNode meta = readJson(file + ".mcmeta");
        if (meta.has("animation")) {
          JsonNode a = meta.get("animation");
          h = a.has("height") ? a.get("height").asInt() : a.has("width") ? a.get("width").asInt() : w; // 只取第一格
        }
      }
      h = Math.min(h, img.getHeight());
      int[] px = img.getRGB(0, 0, w, h, null, 0, w);
      long tl = 0, tr = 0;
      for (int p : px) {
        int a = p >>> 24;
        if (a == 0) tr++;
        else if (a < 255) tl++;
      }
      loaded.add(new Tex(id, w, h, px, tl / (double) px.length, tr / (double) px.length));
    }
    loaded.sort(Comparator.comparingInt((Tex t) -> -t.h()).thenComparingInt(t -> -t.w()));
    int W = 1024;
    int[][] pos;
    int H;
    for (; ; W *= 2) {
      pos = new int[loaded.size()][];
      int x = 0, y = 0, rowH = 0;
      for (int i = 0; i < loaded.size(); i++) {
        Tex t = loaded.get(i);
        if (x + t.w() > W) {
          x = 0;
          y += rowH;
          rowH = 0;
        }
        pos[i] = new int[] {x, y};
        x += t.w();
        rowH = Math.max(rowH, t.h());
      }
      H = y + rowH;
      if (H <= W) break;
    }
    H = W; // deepslate 要求寬高皆為 2 的冪且為方形
    var atlas = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
    var uv = json.createObjectNode();
    var texInfo = new HashMap<String, Tex>();
    for (int i = 0; i < loaded.size(); i++) {
      Tex t = loaded.get(i);
      atlas.setRGB(pos[i][0], pos[i][1], t.w(), t.h(), t.argb(), 0, t.w());
      ArrayNode a = uv.putArray(t.id());
      a.add(pos[i][0] / (double) W).add(pos[i][1] / (double) H).add((pos[i][0] + t.w()) / (double) W).add((pos[i][1] + t.h()) / (double) H);
      texInfo.put(t.id(), t);
    }
    var png = new ByteArrayOutputStream();
    ImageIO.write(atlas, "png", png);
    Files.write(out.resolve("atlas.png"), png.toByteArray());
    var atlasJson = json.createObjectNode();
    atlasJson.putArray("size").add(W).add(H);
    atlasJson.set("uv", uv);
    json.writeValue(out.resolve("atlas.json").toFile(), atlasJson);

    // ---- flags ----
    var flags = json.createObjectNode();
    var mapColors = json.createObjectNode();
    for (var e : blockstates.entrySet()) {
      String name = e.getKey();
      var refs = new LinkedHashSet<String>();
      for (String m : refsOf(e.getValue())) refs.add(norm(m));
      boolean opaque = !refs.isEmpty(), semi = false;
      for (String ref : refs) {
        Resolved r = resolve(models, ref, 0);
        if (r.elements == null || r.elements.size() == 0) {
          opaque = false;
          continue;
        }
        JsonNode first = r.elements.get(0);
        JsonNode faces = first.path("faces");
        boolean allFaces = true;
        for (String f : List.of("up", "down", "north", "south", "east", "west")) allFaces &= faces.path(f).has("cullface");
        boolean inside = true;
        for (JsonNode el : r.elements) inside &= within(el.path("from"), 0) && within(el.path("to"), 16 /*<=*/);
        if (!isFullCube(first) || !allFaces || !inside) opaque = false;
        for (var fi = faces.elements(); fi.hasNext(); ) {
          JsonNode face = fi.next();
          String tex = texOf(r.textures, face.path("texture").asText(null));
          Tex info = tex == null ? null : texInfo.get(tex);
          if (info == null) continue;
          if (info.transparent() > 0 || info.translucent() > 0) opaque = false;
          if (info.translucent() > 0.3) semi = true;
        }
      }
      boolean self = SELF_CULLING.matcher(name).find();
      if (opaque || semi || self) {
        ObjectNode f = flags.putObject(name);
        if (opaque) f.put("opaque", true);
        if (semi) f.put("semi_transparent", true);
        if (self) f.put("self_culling", true);
      }
      // 俯視地圖顏色：優先取第一個模型的 up 面貼圖，其次 particle，再其次任何貼圖的平均色
      for (String ref : refs) {
        Resolved r = resolve(models, ref, 0);
        String tex = null;
        if (r.elements != null)
          for (JsonNode el : r.elements) {
            String t = texOf(r.textures, el.path("faces").path("up").path("texture").asText(null));
            if (t != null && texInfo.containsKey(t)) { tex = t; break; }
          }
        if (tex == null) {
          String t = texOf(r.textures, "#particle");
          if (t != null && texInfo.containsKey(t)) tex = t;
        }
        if (tex == null) {
          for (JsonNode el : r.elements == null ? List.<JsonNode>of() : r.elements)
            for (var fi = el.path("faces").elements(); fi.hasNext() && tex == null; ) {
              String t = texOf(r.textures, fi.next().path("texture").asText(null));
              if (t != null && texInfo.containsKey(t)) tex = t;
            }
        }
        if (tex != null) {
          mapColors.put(name, average(texInfo.get(tex)));
          break;
        }
      }
    }
    var water = flags.putObject("water");
    water.put("semi_transparent", true);
    water.put("self_culling", true);
    flags.putObject("lava").put("self_culling", true);
    if (texInfo.containsKey("minecraft:block/water_still")) mapColors.put("water", 0x3F76E4);
    if (texInfo.containsKey("minecraft:block/lava_still")) mapColors.put("lava", average(texInfo.get("minecraft:block/lava_still")));
    json.writeValue(out.resolve("flags.json").toFile(), flags);
    json.writeValue(out.resolve("mapcolors.json").toFile(), mapColors);

    // ---- blockstates / models ----
    json.writeValue(out.resolve("blockstates.json").toFile(), blockstates);
    json.writeValue(out.resolve("models.json").toFile(), models);

    // ---- 生物群系顏色 ----
    int[][] grassMap = colormap("grass"), foliageMap = colormap("foliage"), dryMap = colormap("dry_foliage");
    var biomes = json.createObjectNode();
    for (String f : src.list("data/minecraft/worldgen/biome/")) {
      if (!f.endsWith(".json")) continue;
      JsonNode b = readJson(f);
      JsonNode e = b.path("effects");
      double temp = b.path("temperature").asDouble(0.8), down = b.path("downfall").asDouble(0.4);
      int grass = e.has("grass_color") ? color(e.get("grass_color")) : sample(grassMap, temp, down);
      String mod = e.path("grass_color_modifier").asText("");
      if (mod.equals("dark_forest")) grass = ((grass & 0xFEFEFE) + 0x28340A) >> 1;
      if (mod.equals("swamp")) grass = 0x6A7039; // 原版依噪音在 0x4C763C / 0x6A7039 之間，取固定值
      int foliage = e.has("foliage_color") ? color(e.get("foliage_color")) : sample(foliageMap, temp, down);
      int dry = e.has("dry_foliage_color") ? color(e.get("dry_foliage_color")) : dryMap != null ? sample(dryMap, temp, down) : foliage;
      ObjectNode o = biomes.putObject("minecraft:" + f.substring("data/minecraft/worldgen/biome/".length(), f.length() - 5));
      o.put("grass", grass).put("foliage", foliage).put("dry", dry).put("water", e.has("water_color") ? color(e.get("water_color")) : 0x3F76E4);
    }
    json.writeValue(out.resolve("biomes.json").toFile(), biomes);
    var stats = new LinkedHashMap<String, Object>();
    stats.put("ms", (System.nanoTime() - t0) / 1_000_000);
    stats.put("textures", loaded.size());
    stats.put("missingTextures", missing);
    stats.put("atlas", W);
    stats.put("blockstates", blockstates.size());
    stats.put("models", models.size());
    stats.put("biomes", biomes.size());
    return stats;
  }

  private static boolean within(JsonNode arr, int limit) {
    if (!arr.isArray() || arr.size() != 3) return false;
    for (JsonNode v : arr) if (limit == 0 ? v.asDouble() < 0 : v.asDouble() > 16) return false;
    return true;
  }

  private static boolean isFullCube(JsonNode e) {
    if (e.has("rotation")) return false;
    for (JsonNode v : e.path("from")) if (v.asDouble() != 0) return false;
    for (JsonNode v : e.path("to")) if (v.asDouble() != 16) return false;
    return e.path("from").size() == 3 && e.path("to").size() == 3;
  }

  private static int average(Tex t) {
    long r = 0, g = 0, b = 0, n = 0;
    for (int p : t.argb()) {
      if ((p >>> 24) < 128) continue;
      r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
    }
    if (n == 0) return 0x808080;
    return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
  }

  private static int color(JsonNode n) {
    if (n.isNumber()) return n.asInt() & 0xFFFFFF;
    String s = n.asText().replace("#", "");
    return (int) Long.parseLong(s, 16) & 0xFFFFFF;
  }

  private int[][] colormap(String name) throws IOException {
    String f = MC + "textures/colormap/" + name + ".png";
    if (!src.exists(f)) return null;
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(src.read(f)));
    int[][] m = new int[img.getHeight()][img.getWidth()];
    for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) m[y][x] = img.getRGB(x, y) & 0xFFFFFF;
    return m;
  }

  private static int sample(int[][] map, double temp, double down) {
    if (map == null) return 0x7FB238;
    temp = Math.min(1, Math.max(0, temp));
    down = Math.min(1, Math.max(0, down)) * temp;
    int x = (int) Math.floor((1 - temp) * 255), y = (int) Math.floor((1 - down) * 255);
    return map[Math.min(y, map.length - 1)][Math.min(x, map[0].length - 1)];
  }

  private static List<String> refsOf(JsonNode bs) {
    var out = new ArrayList<String>();
    if (bs.has("variants"))
      for (JsonNode v : bs.get("variants")) addRef(v, out);
    if (bs.has("multipart"))
      for (JsonNode p : bs.get("multipart")) addRef(p.path("apply"), out);
    return out;
  }

  private static void addRef(JsonNode v, List<String> out) {
    if (v.isArray()) for (JsonNode x : v) addRef(x, out);
    else if (v.has("model")) out.add(v.get("model").asText());
  }

  private record Resolved(JsonNode elements, Map<String, JsonNode> textures) {}

  private Resolved resolve(Map<String, JsonNode> models, String id, int depth) {
    JsonNode m = models.get(id);
    if (m == null || depth > 16) return new Resolved(null, Map.of());
    Resolved parent = m.has("parent") ? resolve(models, norm(m.get("parent").asText()), depth + 1) : new Resolved(null, Map.of());
    var textures = new HashMap<>(parent.textures);
    if (m.has("textures")) m.get("textures").fields().forEachRemaining(en -> textures.put(en.getKey(), en.getValue()));
    return new Resolved(m.has("elements") ? m.get("elements") : parent.elements, textures);
  }

  private static String texOf(Map<String, JsonNode> textures, String ref) {
    String r = ref;
    for (int guard = 0; r != null && r.startsWith("#") && guard < 10; guard++) r = spriteOf(textures.get(r.substring(1)));
    return r == null || r.startsWith("#") ? null : norm(r);
  }
}
