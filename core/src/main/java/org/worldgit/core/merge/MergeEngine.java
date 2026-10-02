package org.worldgit.core.merge;

import static org.worldgit.core.merge.MergeReport.*;

import java.io.*;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.BlockBox;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

/** 中性 tree 三方合併。缺少 tree／section 為空；NBT list／陣列、BE、tick／structure 原子。 */
public final class MergeEngine {
  public record Result(String tree, MergeReport report) {}

  private final ObjectStore store;
  private final DimensionId dimension;
  private final String base, ours, theirs;
  private final List<String> oursAuthors, theirsAuthors;
  private final List<Atom> conflicts = new ArrayList<>();
  private final Set<String> changedSections = new TreeSet<>();
  private final Map<String, Section> cache =
      new LinkedHashMap<>(32, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Section> e) {
          return size() > 192;
        }
      };
  private static final int[][] NEIGHBORS = {
    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
  };

  public MergeEngine(
      ObjectStore store,
      DimensionId dimension,
      String base,
      String ours,
      String theirs,
      List<String> oursAuthors,
      List<String> theirsAuthors) {
    this.store = store;
    this.dimension = dimension;
    this.base = base;
    this.ours = ours;
    this.theirs = theirs;
    this.oursAuthors = List.copyOf(oursAuthors);
    this.theirsAuthors = List.copyOf(theirsAuthors);
  }

  public Result merge(int distance) throws IOException {
    if (distance < 0 || distance > 16) throw new IllegalArgumentException("分群距離 k 必須為 0..16");
    String tree = mergeNode("", base, ours, theirs, ObjectStore.Kind.TREE);
    if (tree == null) tree = store.writeTree(List.of());
    tree = mergeEntities(tree);
    collectSections("", base, ours, theirs);
    var regions = group(distance);
    // 多格結構中只有一格衝突時，整組預設 ours，不能留下混合的門／床。
    for (var region : regions) tree = select(store, tree, ours, region);
    var shapes = shapes(tree);
    var conflictSections = new HashSet<String>();
    for (var r : regions)
      for (var a : r.atoms()) if (a.kind() == Kind.BLOCK) conflictSections.add(a.path());
    int automatic =
        (int) changedSections.stream().filter(p -> !conflictSections.contains(p)).count();
    var warnings = new ArrayList<String>();
    if (!shapes.isEmpty())
      warnings.add("離線保留方塊連接 state；" + shapes.size() + " 格需要線上 updateShape，伺服器載入不保證自行修正。");
    if (changedRedstone || regions.stream().anyMatch(Region::redstone)) warnings.add("含紅石元件，建議測試。");
    store.flush();
    return new Result(tree, new MergeReport(automatic, regions, List.of(), shapes, warnings));
  }

  private String treeForLookup;
  private boolean changedRedstone;

  private String mergeNode(String path, String b, String o, String t, ObjectStore.Kind kind)
      throws IOException {
    if (Objects.equals(o, t)) return o;
    if (Objects.equals(o, b)) return t;
    if (Objects.equals(t, b)) return o;
    if (kind == ObjectStore.Kind.TREE) {
      var bm = entries(b);
      var om = entries(o);
      var tm = entries(t);
      var names = new TreeSet<>(bm.keySet());
      names.addAll(om.keySet());
      names.addAll(tm.keySet());
      var out = new ArrayList<ObjectStore.Entry>();
      for (String name : names) {
        var be = bm.get(name);
        var oe = om.get(name);
        var te = tm.get(name);
        var sample = oe != null ? oe : te != null ? te : be;
        for (var e : Arrays.asList(be, oe, te))
          if (e != null && e.kind() != sample.kind())
            throw new IOException("tree/blob 型別衝突：" + path + "/" + name);
        String child =
            mergeNode(
                path.isEmpty() ? name : path + "/" + name, id(be), id(oe), id(te), sample.kind());
        if (child != null) out.add(new ObjectStore.Entry(name, sample.kind(), child));
      }
      return out.isEmpty() ? null : store.writeTree(out);
    }
    if (path.matches("r\\.-?\\d+\\.-?\\d+/c\\.-?\\d+\\.-?\\d+/s\\.-?\\d+\\.bin")) {
      var bs = decode(b);
      var os = decode(o);
      var ts = decode(t);
      var be = bs.blockEntities();
      var oe = os.blockEntities();
      var te = ts.blockEntities();
      var blocks = new ArrayList<BlockState>(4096);
      var bes = new TreeMap<Integer, byte[]>();
      for (int i = 0; i < 4096; i++) {
        int side =
            pick(
                cellEqual(os, oe, ts, te, i),
                cellEqual(os, oe, bs, be, i),
                cellEqual(ts, te, bs, be, i));
        if (side < 0) {
          conflicts.add(atom(Kind.BLOCK, position(path, i), path));
          side = 0;
        }
        Section s = side == 1 ? ts : os;
        Map<Integer, byte[]> e = side == 1 ? te : oe;
        blocks.add(s.block(i));
        if (e.containsKey(i)) bes.put(i, e.get(i));
      }
      var s = new Section(blocks, bes);
      return s.empty() ? null : store.writeBlob(SnapshotCodec.section(s));
    }
    if (path.endsWith("/biomes.bin")) return mergeBiomes(path, b, o, t);
    if (path.endsWith("/entities.bin")) return o; // 第二階段以全域 UUID 合併，跨 chunk 移動不能逐 blob 比較。
    if (path.startsWith("world-meta/") && MetadataNormalizer.type(path.substring(11)) != null) {
      Object merged = mergeNbt(path, List.of(), nbt(b), nbt(o), nbt(t));
      return merged == null ? null : store.writeBlob(Nbt.write((Nbt.Compound) merged));
    }
    Kind k =
        path.endsWith("/ticks.bin")
            ? Kind.TICKS
            : path.endsWith("/structures.bin") ? Kind.STRUCTURES : Kind.FILE;
    conflicts.add(atom(k, path.startsWith("r.") ? position(path, 0) : null, path));
    return o;
  }

  private Object mergeNbt(String path, List<String> key, Object b, Object o, Object t)
      throws IOException {
    if (equal(o, t) || equal(t, b)) return NbtCopy(o);
    if (equal(o, b)) return NbtCopy(t);
    if ((b == null || b instanceof Nbt.Compound)
        && (o == null || o instanceof Nbt.Compound)
        && (t == null || t instanceof Nbt.Compound)) {
      var bm = compound(b);
      var om = compound(o);
      var tm = compound(t);
      var names = new TreeSet<>(bm.keySet());
      names.addAll(om.keySet());
      names.addAll(tm.keySet());
      var out = new Nbt.Compound();
      for (String name : names) {
        var child = new ArrayList<>(key);
        child.add(name);
        Object value = mergeNbt(path, child, bm.get(name), om.get(name), tm.get(name));
        if (value != null) out.put(name, value);
      }
      return out.isEmpty() && o == null && t == null ? null : out;
    }
    conflicts.add(new Atom(Kind.METADATA, null, path, key, null));
    return NbtCopy(o);
  }

  private String mergeBiomes(String path, String b, String o, String t) throws IOException {
    var bm = biomes(b);
    var om = biomes(o);
    var tm = biomes(t);
    var ys = new TreeSet<>(bm.keySet());
    ys.addAll(om.keySet());
    ys.addAll(tm.keySet());
    var out = new TreeMap<Integer, List<String>>();
    for (int y : ys) {
      var values = new ArrayList<String>();
      for (int i = 0; i < 64; i++) {
        String bv = sample(bm, y, i), ov = sample(om, y, i), tv = sample(tm, y, i);
        int side = pick(ov.equals(tv), ov.equals(bv), tv.equals(bv));
        if (side < 0) {
          var cp = chunk(path);
          var pos =
              new Cell(
                  cp.x() * 16 + (i & 3) * 4,
                  y * 16 + (i >> 4) * 4,
                  cp.z() * 16 + ((i >> 2) & 3) * 4);
          conflicts.add(new Atom(Kind.BIOME, pos, path, List.of("" + y, "" + i), null));
          side = 0;
        }
        values.add(side == 1 ? tv : ov);
      }
      if (values.stream().anyMatch(v -> !v.isEmpty())) out.put(y, values);
    }
    return out.isEmpty() ? null : store.writeBlob(SnapshotCodec.biomes(out));
  }

  private String mergeEntities(String tree) throws IOException {
    var engine = new DiffEngine(store);
    var bm = engine.entities(base);
    var om = engine.entities(ours);
    var tm = engine.entities(theirs);
    var ids = new TreeSet<>(bm.keySet());
    ids.addAll(om.keySet());
    ids.addAll(tm.keySet());
    var selected = new HashMap<UUID, DiffEngine.Placed>();
    for (UUID id : ids) {
      var b = bm.get(id);
      var o = om.get(id);
      var t = tm.get(id);
      boolean ob = entityEqual(o, b), tb = entityEqual(t, b);
      var chosen = o;
      if (ob) chosen = t;
      else if (!tb) {
        var positions = new TreeSet<Cell>();
        for (var e : Arrays.asList(b, o, t))
          if (e != null) {
            double[] p = e.entity().position();
            positions.add(
                new Cell((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])));
          }
        for (var p : positions) conflicts.add(new Atom(Kind.ENTITY, p, "", List.of(), id));
      }
      if (chosen != null) selected.put(id, chosen);
    }
    return writeEntities(store, tree, selected);
  }

  static String writeEntities(ObjectStore store, String tree, Map<UUID, DiffEngine.Placed> entities)
      throws IOException {
    var old = new DiffEngine(store).entities(tree);
    var grouped = new TreeMap<ChunkPos, List<EntitySnapshot>>();
    var chunks = new TreeSet<ChunkPos>();
    old.values().forEach(e -> chunks.add(e.chunk()));
    for (var e : entities.values()) {
      chunks.add(e.chunk());
      grouped.computeIfAbsent(e.chunk(), p -> new ArrayList<>()).add(e.entity());
    }
    var editor = new TreeEditor(store, tree);
    for (var p : chunks) {
      var list = grouped.getOrDefault(p, List.of());
      String path = p.treePath() + "/entities.bin";
      if (list.isEmpty()) editor.remove(path);
      else editor.putBlob(path, SnapshotCodec.entities(list));
    }
    return editor.write();
  }

  private void collectSections(String path, String b, String o, String t) throws IOException {
    if (Objects.equals(b, o) && Objects.equals(b, t)) return;
    var bm = entries(b);
    var om = entries(o);
    var tm = entries(t);
    var names = new TreeSet<>(bm.keySet());
    names.addAll(om.keySet());
    names.addAll(tm.keySet());
    for (String name : names) {
      var be = bm.get(name);
      var oe = om.get(name);
      var te = tm.get(name);
      var s = oe != null ? oe : te != null ? te : be;
      String child = path.isEmpty() ? name : path + "/" + name;
      if (s.kind() == ObjectStore.Kind.TREE && (child.startsWith("r.")))
        collectSections(child, id(be), id(oe), id(te));
      else if (name.matches("s\\.-?\\d+\\.bin")
          && (!Objects.equals(id(be), id(oe)) || !Objects.equals(id(be), id(te))))
        changedSections.add(child);
    }
  }

  private List<Region> group(int k) throws IOException {
    // 門／大型植物上下半、床頭尾、活塞與活塞頭的精確 partner 一併作為可切換格。
    var points = new TreeMap<Cell, List<Atom>>();
    var global = new ArrayList<Atom>();
    for (var a : conflicts)
      if (a.position() == null) global.add(a);
      else points.computeIfAbsent(a.position(), p -> new ArrayList<>()).add(a);
    var queue = new ArrayDeque<Cell>();
    points.forEach(
        (p, atoms) -> {
          if (atoms.stream().anyMatch(a -> a.kind() == Kind.BLOCK)) queue.add(p);
        });
    var visited = new HashSet<Cell>();
    var forced = new ArrayList<List<Cell>>();
    while (!queue.isEmpty()) {
      var p = queue.remove();
      if (!visited.add(p)) continue;
      for (String tree : Arrays.asList(base, ours, theirs))
        for (Cell other : partners(tree, p)) {
          forced.add(List.of(p, other));
          var atoms = points.computeIfAbsent(other, c -> new ArrayList<>());
          if (atoms.stream().noneMatch(a -> a.kind() == Kind.BLOCK)) {
            atoms.add(atom(Kind.BLOCK, other, sectionPath(other)));
            queue.add(other);
          }
        }
    }
    var cells = new ArrayList<>(points.keySet());
    var indices = new HashMap<Cell, Integer>();
    for (int i = 0; i < cells.size(); i++) indices.put(cells.get(i), i);
    var union = new Union(cells.size());
    int size = Math.max(1, k);
    var buckets = new HashMap<Cell, List<Integer>>();
    for (int i = 0; i < cells.size(); i++) {
      var p = cells.get(i);
      var bucket =
          new Cell(
              Math.floorDiv(p.x(), size), Math.floorDiv(p.y(), size), Math.floorDiv(p.z(), size));
      for (int dx = -1; dx <= 1; dx++)
        for (int dy = -1; dy <= 1; dy++)
          for (int dz = -1; dz <= 1; dz++)
            for (int j : buckets.getOrDefault(bucket.offset(dx, dy, dz), List.of())) {
              var q = cells.get(j);
              if (Math.abs((long) p.x() - q.x())
                      + Math.abs((long) p.y() - q.y())
                      + Math.abs((long) p.z() - q.z())
                  <= k) union.join(i, j);
            }
      buckets.computeIfAbsent(bucket, p2 -> new ArrayList<>()).add(i);
    }
    for (var pair : forced) union.join(indices.get(pair.get(0)), indices.get(pair.get(1)));
    var entityRoots = new HashMap<UUID, Integer>();
    for (int i = 0; i < cells.size(); i++)
      for (var a : points.get(cells.get(i)))
        if (a.kind() == Kind.ENTITY) {
          Integer prior = entityRoots.putIfAbsent(a.uuid(), i);
          if (prior != null) union.join(prior, i);
        }
    // 落在既有區域包圍盒內的實體歸入該區，距離圖仍處理包圍盒外的鄰近實體。
    var bounds = new HashMap<Integer, int[]>();
    for (int i = 0; i < cells.size(); i++)
      if (points.get(cells.get(i)).stream().anyMatch(a -> a.kind() == Kind.BLOCK)) {
        var p = cells.get(i);
        var v =
            bounds.computeIfAbsent(
                union.root(i), root -> new int[] {p.x(), p.y(), p.z(), p.x(), p.y(), p.z()});
        v[0] = Math.min(v[0], p.x());
        v[1] = Math.min(v[1], p.y());
        v[2] = Math.min(v[2], p.z());
        v[3] = Math.max(v[3], p.x());
        v[4] = Math.max(v[4], p.y());
        v[5] = Math.max(v[5], p.z());
      }
    for (int i = 0; i < cells.size(); i++) {
      if (points.get(cells.get(i)).stream().noneMatch(a -> a.kind() == Kind.ENTITY)) continue;
      var p = cells.get(i);
      for (var e : bounds.entrySet()) {
        var v = e.getValue();
        if (p.x() >= v[0]
            && p.y() >= v[1]
            && p.z() >= v[2]
            && p.x() <= v[3]
            && p.y() <= v[4]
            && p.z() <= v[5]) union.join(i, e.getKey());
      }
    }

    var groups = new LinkedHashMap<Integer, List<Atom>>();
    for (int i = 0; i < cells.size(); i++)
      groups
          .computeIfAbsent(union.root(i), p -> new ArrayList<>())
          .addAll(points.get(cells.get(i)));
    // world-meta 沒有空間座標；同一 blob 的 key 衝突合為獨立區域。
    var meta = new TreeMap<String, List<Atom>>();
    for (var a : global) meta.computeIfAbsent(a.path(), p -> new ArrayList<>()).add(a);
    for (var list : meta.values()) groups.put(-1 - groups.size(), list);
    var result = new ArrayList<Region>();
    for (var atoms : groups.values()) {
      var ps = atoms.stream().map(Atom::position).filter(Objects::nonNull).toList();
      BlockBox box =
          ps.isEmpty()
              ? null
              : new BlockBox(
                  ps.stream().mapToInt(Cell::x).min().orElseThrow(),
                  ps.stream().mapToInt(Cell::y).min().orElseThrow(),
                  ps.stream().mapToInt(Cell::z).min().orElseThrow(),
                  ps.stream().mapToInt(Cell::x).max().orElseThrow(),
                  ps.stream().mapToInt(Cell::y).max().orElseThrow(),
                  ps.stream().mapToInt(Cell::z).max().orElseThrow());
      var blockCells = new TreeSet<Cell>();
      boolean red = false;
      for (var a : atoms)
        if (a.kind() == Kind.BLOCK) {
          blockCells.add(a.position());
          for (String tree : Arrays.asList(base, ours, theirs))
            red |= redstone(section(tree, a.position()).block(a.position().index()));
        }
      if (!red && box != null)
        for (String tree : Arrays.asList(base, ours, theirs))
          if (redstoneInBounds(tree, box)) {
            red = true;
            break;
          }
      result.add(
          new Region(
              result.size() + 1,
              dimension,
              box,
              blockCells.size(),
              oursAuthors,
              theirsAuthors,
              red,
              Choice.OURS,
              false,
              atoms));
    }
    return result;
  }

  private boolean redstoneInBounds(String tree, BlockBox box) throws IOException {
    for (var region : entries(tree).values())
      if (region.kind() == ObjectStore.Kind.TREE && region.name().matches("r\\.-?\\d+\\.-?\\d+")) {
        String[] r = region.name().split("\\.");
        long rx = Long.parseLong(r[1]) * 512, rz = Long.parseLong(r[2]) * 512;
        if (rx > box.maxX() || rx + 511 < box.minX() || rz > box.maxZ() || rz + 511 < box.minZ())
          continue;
        for (var chunk : store.readTree(region.id()).values()) {
          var cp = chunk(region.name() + "/" + chunk.name());
          if (!box.intersectsChunk(cp.x(), cp.z())) continue;
          for (var e : store.readTree(chunk.id()).values())
            if (e.name().matches("s\\.-?\\d+\\.bin")) {
              int sy = Integer.parseInt(e.name().substring(2, e.name().length() - 4));
              if ((long) sy * 16 > box.maxY() || (long) sy * 16 + 15 < box.minY()) continue;
              var s = decode(e.id());
              for (int y = Math.max(0, box.minY() - sy * 16);
                  y <= Math.min(15, box.maxY() - sy * 16);
                  y++)
                for (int z = Math.max(0, box.minZ() - cp.z() * 16);
                    z <= Math.min(15, box.maxZ() - cp.z() * 16);
                    z++)
                  for (int x = Math.max(0, box.minX() - cp.x() * 16);
                      x <= Math.min(15, box.maxX() - cp.x() * 16);
                      x++) if (redstone(s.block(x | (z << 4) | (y << 8)))) return true;
            }
        }
      }
    return false;
  }

  private List<Cell> partners(String tree, Cell p) throws IOException {
    var s = section(tree, p).block(p.index());
    var props = s.properties();
    var result = new ArrayList<Cell>();
    String name = s.name();
    if (props.containsKey("half")
        && !name.endsWith("_trapdoor")
        && Set.of("upper", "lower").contains(props.get("half")))
      result.add(p.offset(0, props.get("half").equals("upper") ? -1 : 1, 0));
    if (name.endsWith("_bed") && props.containsKey("part")) {
      int[] d = direction(props.getOrDefault("facing", "north"));
      int sign = props.get("part").equals("head") ? -1 : 1;
      result.add(p.offset(d[0] * sign, 0, d[2] * sign));
    }
    if (name.equals("minecraft:piston_head")
        || ((name.equals("minecraft:piston") || name.equals("minecraft:sticky_piston"))
            && props.getOrDefault("extended", "false").equals("true"))) {
      int[] d = direction(props.getOrDefault("facing", "north"));
      int sign = name.endsWith("piston_head") ? -1 : 1;
      result.add(p.offset(d[0] * sign, d[1] * sign, d[2] * sign));
    }
    return result;
  }

  private static int[] direction(String facing) {
    return switch (facing) {
      case "east" -> new int[] {1, 0, 0};
      case "west" -> new int[] {-1, 0, 0};
      case "south" -> new int[] {0, 0, 1};
      case "up" -> new int[] {0, 1, 0};
      case "down" -> new int[] {0, -1, 0};
      default -> new int[] {0, 0, -1};
    };
  }

  private List<Cell> shapes(String merged) throws IOException {
    treeForLookup = merged;
    // 對每個來自 theirs 的變動格檢查六鄰居；包括跨 section／chunk、未改的 ours 柵欄。
    var result = new TreeSet<Cell>();
    for (String path : changedSections) {
      var b = decode(blob(base, path));
      var o = decode(blob(ours, path));
      var t = decode(blob(theirs, path));
      var m = decode(blob(merged, path));
      for (int i = 0; i < 4096; i++) {
        if ((!o.block(i).equals(b.block(i)) || !t.block(i).equals(b.block(i)))
            && (redstone(o.block(i)) || redstone(t.block(i)) || redstone(b.block(i))))
          changedRedstone = true;
        if (t.block(i).equals(b.block(i))
            || !m.block(i).equals(t.block(i))
            || t.block(i).equals(o.block(i))) continue;
        var p = position(path, i);
        for (int[] d : NEIGHBORS) {
          var q = p.offset(d[0], d[1], d[2]);
          var qb = section(base, q).block(q.index());
          var qo = section(ours, q).block(q.index());
          var qt = section(theirs, q).block(q.index());
          var qm = section(merged, q).block(q.index());
          boolean other = qm.equals(qo) && (!qm.equals(qt) || qt.equals(qb));
          if (other) {
            if (shape(m.block(i))) result.add(p);
            if (shape(qm)) result.add(q);
          }
        }
      }
    }
    return List.copyOf(result);
  }

  public static boolean shape(BlockState s) {
    String n = s.name();
    return n.endsWith("_fence")
        || n.endsWith("_fence_gate")
        || n.endsWith("_wall")
        || n.endsWith("_pane")
        || n.endsWith(":iron_bars")
        || n.endsWith(":redstone_wire")
        || n.endsWith("_stairs")
        || n.endsWith("_rail")
        || n.endsWith("_door")
        || n.contains("vine");
  }

  public static boolean redstone(BlockState s) {
    String n = s.name();
    return n.contains("redstone")
        || n.contains("piston")
        || n.contains("repeater")
        || n.contains("comparator")
        || n.contains("observer")
        || n.contains("lever")
        || n.endsWith("_button")
        || n.contains("pressure_plate")
        || n.contains("tripwire")
        || n.contains("sculk_sensor")
        || n.contains("daylight_detector")
        || n.contains("target");
  }

  private Section section(String tree, Cell p) throws IOException {
    return decode(blob(tree, sectionPath(p)));
  }

  private Section decode(String id) throws IOException {
    if (id == null) return Section.air();
    var s = cache.get(id);
    if (s == null) {
      s = SnapshotCodec.section(store.readBlob(id));
      cache.put(id, s);
    }
    return s;
  }

  private Map<String, ObjectStore.Entry> entries(String tree) throws IOException {
    return tree == null ? Map.of() : store.readTree(tree);
  }

  private String blob(String tree, String path) throws IOException {
    return id(TreeEditor.find(store, tree, path));
  }

  private Nbt.Compound nbt(String id) throws IOException {
    return id == null ? null : Nbt.read(store.readBlob(id));
  }

  private SortedMap<Integer, List<String>> biomes(String id) throws IOException {
    return id == null ? new TreeMap<>() : SnapshotCodec.biomes(store.readBlob(id));
  }

  private static String sample(Map<Integer, List<String>> b, int y, int i) {
    return b.containsKey(y) ? b.get(y).get(i) : "";
  }

  private static boolean entityEqual(DiffEngine.Placed a, DiffEngine.Placed b) {
    return a == null
        ? b == null
        : b != null && Arrays.equals(a.entity().bytes(), b.entity().bytes());
  }

  private static boolean cellEqual(
      Section a, Map<Integer, byte[]> ae, Section b, Map<Integer, byte[]> be, int i) {
    return a.block(i).equals(b.block(i)) && Arrays.equals(ae.get(i), be.get(i));
  }

  private static int pick(boolean ot, boolean ob, boolean tb) {
    return ot ? 0 : ob ? 1 : tb ? 0 : -1;
  }

  private static String id(ObjectStore.Entry e) {
    return e == null ? null : e.id();
  }

  private static Atom atom(Kind k, Cell p, String path) {
    return new Atom(k, p, path, List.of(), null);
  }

  private static ChunkPos chunk(String path) {
    String[] p = path.split("/")[1].split("\\.");
    return new ChunkPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
  }

  private static Cell position(String path, int i) {
    var p = chunk(path);
    String[] parts = path.split("/");
    int y =
        parts.length > 2 && parts[2].startsWith("s.")
            ? Integer.parseInt(parts[2].substring(2, parts[2].length() - 4))
            : 0;
    return new Cell(p.x() * 16 + (i & 15), y * 16 + (i >> 8), p.z() * 16 + ((i >> 4) & 15));
  }

  public static String sectionPath(Cell p) {
    return p.chunk().treePath() + "/s." + p.sectionY() + ".bin";
  }

  private static Object NbtCopy(Object v) {
    return v == null ? null : Nbt.copy(v);
  }

  private static Nbt.Compound compound(Object v) {
    return v instanceof Nbt.Compound c ? c : new Nbt.Compound();
  }

  private static boolean equal(Object a, Object b) {
    return a == null
        ? b == null
        : b != null
            && Arrays.equals(
                Nbt.write(new Nbt.Compound().with("v", a)),
                Nbt.write(new Nbt.Compound().with("v", b)));
  }

  private static final class Union {
    private final int[] p;

    Union(int n) {
      p = new int[n];
      for (int i = 0; i < n; i++) p[i] = i;
    }

    int root(int i) {
      while (i != p[i]) {
        p[i] = p[p[i]];
        i = p[i];
      }
      return i;
    }

    void join(int a, int b) {
      p[root(a)] = root(b);
    }
  }

  public static List<PreviewBlock> preview(ObjectStore store, String source, Region region)
      throws IOException {
    var result = new ArrayList<PreviewBlock>();
    var sections = new HashMap<String, Section>();
    for (var a : region.atoms())
      if (a.kind() == Kind.BLOCK) {
        var s = sections.get(a.path());
        if (s == null) {
          var e = TreeEditor.find(store, source, a.path());
          s = e == null ? Section.air() : SnapshotCodec.section(store.readBlob(e.id()));
          sections.put(a.path(), s);
        }
        int i = a.position().index();
        result.add(new PreviewBlock(a.position(), s.block(i), s.blockEntities().get(i)));
      }
    return List.copyOf(result);
  }

  public static List<Cell> updateShapes(
      ObjectStore store,
      DimensionId dimension,
      String base,
      String ours,
      String theirs,
      String result)
      throws IOException {
    var engine = new MergeEngine(store, dimension, base, ours, theirs, List.of(), List.of());
    engine.collectSections("", base, ours, theirs);
    return engine.shapes(result);
  }

  /** 從目前世界樹替換區域精確 atoms；包圍盒內的其他方塊及玩家手動編輯保持。 */
  public static String select(ObjectStore store, String current, String source, Region region)
      throws IOException {
    var editor = new TreeEditor(store, current);
    var blocks = new TreeMap<String, List<Atom>>();
    var biomes = new TreeMap<String, List<Atom>>();
    var metadata = new TreeMap<String, List<Atom>>();
    var uuids = new HashSet<UUID>();
    for (var a : region.atoms())
      switch (a.kind()) {
        case BLOCK -> blocks.computeIfAbsent(a.path(), p -> new ArrayList<>()).add(a);
        case BIOME -> biomes.computeIfAbsent(a.path(), p -> new ArrayList<>()).add(a);
        case METADATA -> metadata.computeIfAbsent(a.path(), p -> new ArrayList<>()).add(a);
        case ENTITY -> uuids.add(a.uuid());
        default -> {
          var e = TreeEditor.find(store, source, a.path());
          if (e == null) editor.remove(a.path());
          else editor.putBlob(a.path(), store.readBlob(e.id()));
        }
      }
    for (var e : blocks.entrySet()) {
      var ce = TreeEditor.find(store, current, e.getKey());
      var se = TreeEditor.find(store, source, e.getKey());
      var c = ce == null ? Section.air() : SnapshotCodec.section(store.readBlob(ce.id()));
      var s = se == null ? Section.air() : SnapshotCodec.section(store.readBlob(se.id()));
      var out = new ArrayList<>(c.blocks());
      var bes = c.blockEntities();
      var sb = s.blockEntities();
      for (var a : e.getValue()) {
        int i = a.position().index();
        out.set(i, s.block(i));
        if (sb.containsKey(i)) bes.put(i, sb.get(i));
        else bes.remove(i);
      }
      var result = new Section(out, bes);
      if (result.empty()) editor.remove(e.getKey());
      else editor.putBlob(e.getKey(), SnapshotCodec.section(result));
    }
    for (var e : biomes.entrySet()) {
      var ce = TreeEditor.find(store, current, e.getKey());
      var se = TreeEditor.find(store, source, e.getKey());
      var c =
          ce == null
              ? new TreeMap<Integer, List<String>>()
              : SnapshotCodec.biomes(store.readBlob(ce.id()));
      var s =
          se == null
              ? new TreeMap<Integer, List<String>>()
              : SnapshotCodec.biomes(store.readBlob(se.id()));
      for (var a : e.getValue()) {
        int y = Integer.parseInt(a.key().get(0)), i = Integer.parseInt(a.key().get(1));
        var values = new ArrayList<>(c.getOrDefault(y, Collections.nCopies(64, "")));
        values.set(i, sample(s, y, i));
        c.put(y, values);
      }
      editor.putBlob(e.getKey(), SnapshotCodec.biomes(c));
    }
    for (var e : metadata.entrySet()) {
      var ce = TreeEditor.find(store, current, e.getKey());
      var se = TreeEditor.find(store, source, e.getKey());
      var c = ce == null ? new Nbt.Compound() : Nbt.read(store.readBlob(ce.id()));
      var s = se == null ? new Nbt.Compound() : Nbt.read(store.readBlob(se.id()));
      for (var a : e.getValue()) {
        if (a.key().isEmpty()) {
          c = Nbt.copy(s);
          continue;
        }
        var parent = c;
        var src = s;
        for (int i = 0; i < a.key().size() - 1; i++) {
          String k = a.key().get(i);
          if (!(parent.get(k) instanceof Nbt.Compound)) parent.put(k, new Nbt.Compound());
          parent = parent.compound(k);
          src = src.compound(k);
        }
        String k = a.key().getLast();
        if (src.containsKey(k)) parent.put(k, Nbt.copy(src.get(k)));
        else parent.remove(k);
      }
      if (c.isEmpty() && se == null) editor.remove(e.getKey());
      else editor.putBlob(e.getKey(), Nbt.write(c));
    }
    String target = editor.write();
    if (!uuids.isEmpty()) {
      var engine = new DiffEngine(store);
      var c = engine.entities(target);
      var s = engine.entities(source);
      for (UUID uuid : uuids) {
        if (s.containsKey(uuid)) c.put(uuid, s.get(uuid));
        else c.remove(uuid);
      }
      target = writeEntities(store, target, c);
    }
    return target;
  }
}
