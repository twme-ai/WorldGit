package org.worldgit.core.diff;

import java.io.*;
import java.util.*;
import org.worldgit.core.diff.WorldDiff.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

/** 相同 tree id 立即短路，僅解碼有變化的 leaf。實體在變動 leaf 聯集以 UUID 比對。 */
public final class DiffEngine {
  public enum Detail {
    SUMMARY,
    BLOCKS
  }

  private final ObjectStore store;

  public DiffEngine(ObjectStore store) {
    this.store = store;
  }

  private record Leaf(String path, String before, String after) {}

  public WorldDiff compare(DimensionId dimension, String before, String after, double tolerance)
      throws IOException {
    return compare(dimension, before, after, tolerance, Detail.BLOCKS);
  }

  public WorldDiff compare(
      DimensionId dimension, String before, String after, double tolerance, Detail detail)
      throws IOException {
    return compare(dimension, before, after, tolerance, detail, null);
  }

  /** 指定 chunk 視窗；方塊/biome 不解碼範圍外資料，實體仍全域比對後才裁切。null 為全世界。 */
  public WorldDiff compare(
      DimensionId dimension,
      String before,
      String after,
      double tolerance,
      Detail detail,
      Set<ChunkPos> chunks)
      throws IOException {
    Set<ChunkPos> scope = chunks == null ? null : Set.copyOf(chunks);
    var leaves = new ArrayList<Leaf>();
    walk(before, after, "", leaves);
    var sections = new ArrayList<SectionChange>();
    var biomes = new ArrayList<BiomeChange>();
    var metadata = new ArrayList<BlobChange>();
    var oldEntities = new TreeMap<UUID, Placed>();
    var newEntities = new TreeMap<UUID, Placed>();
    for (var leaf : leaves) {
      String[] path = leaf.path.split("/");
      String name = path[path.length - 1];
      ChunkPos chunk =
          path.length == 3 && path[0].startsWith("r.") && path[1].startsWith("c.")
              ? chunk(path[1])
              : null;
      if (scope != null && chunk != null && !scope.contains(chunk) && !name.equals("entities.bin"))
        continue;
      if (chunk != null && name.matches("s\\.-?\\d+\\.bin")) {
        int y = Integer.parseInt(name.split("\\.")[1]);
        Section
            a =
                leaf.before == null
                    ? Section.air()
                    : SnapshotCodec.section(store.readBlob(leaf.before)),
            b =
                leaf.after == null
                    ? Section.air()
                    : SnapshotCodec.section(store.readBlob(leaf.after));
        var delta = sectionDelta(chunk, y, a, b, detail);
        if (delta.counts.added()
                + delta.counts.removed()
                + delta.counts.modified()
                + delta.counts.conflict()
            > 0)
          sections.add(
              new SectionChange(
                  chunk, y, kind(leaf.before, leaf.after), delta.changes, delta.counts));
      } else if (chunk != null && name.equals("biomes.bin")) {
        Map<Integer, List<String>>
            a = leaf.before == null ? Map.of() : SnapshotCodec.biomes(store.readBlob(leaf.before)),
            b = leaf.after == null ? Map.of() : SnapshotCodec.biomes(store.readBlob(leaf.after));
        var keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (int y : keys) {
          int count = 0;
          ChangeKind summary = ChangeKind.ADDED;
          for (int i = 0; i < 64; i++) {
            String av = a.containsKey(y) ? a.get(y).get(i) : null,
                bv = b.containsKey(y) ? b.get(y).get(i) : null;
            // area 排除的空字串代表未追蹤，不應被當成一種 biome 的修改。
            if (av != null && av.isEmpty()) av = null;
            if (bv != null && bv.isEmpty()) bv = null;
            if (!Objects.equals(av, bv)) {
              count++;
              summary = kind(av, bv);
              if (detail == Detail.BLOCKS)
                biomes.add(new BiomeChange(chunk, y, i, av, bv, summary));
            }
          }
          if (detail == Detail.SUMMARY && count > 0)
            biomes.add(new BiomeChange(chunk, y, -1, null, null, summary, count));
        }
      } else if (chunk != null && name.equals("entities.bin")) {
        addEntities(oldEntities, chunk, leaf.before);
        addEntities(newEntities, chunk, leaf.after);
      } else
        metadata.add(
            new BlobChange(leaf.path, kind(leaf.before, leaf.after), leaf.before, leaf.after));
    }
    var entities = new ArrayList<EntityChange>();
    var uuids = new TreeSet<>(oldEntities.keySet());
    uuids.addAll(newEntities.keySet());
    for (UUID uuid : uuids) {
      Placed a = oldEntities.get(uuid), b = newEntities.get(uuid);
      if (scope != null
          && (a == null || !scope.contains(a.chunk))
          && (b == null || !scope.contains(b.chunk))) continue;
      if (a != null && b != null && EntityNormalizer.stickyEqual(a.entity, b.entity, tolerance))
        continue;
      entities.add(
          new EntityChange(
              uuid,
              kind(a, b),
              a == null ? null : a.chunk,
              b == null ? null : b.chunk,
              a == null ? null : a.entity,
              b == null ? null : b.entity));
    }
    return new WorldDiff(dimension, sections, entities, biomes, metadata);
  }

  private void walk(String a, String b, String path, List<Leaf> leaves) throws IOException {
    if (Objects.equals(a, b)) return;
    var at = store.readTree(a);
    var bt = store.readTree(b);
    var keys = new TreeSet<>(at.keySet());
    keys.addAll(bt.keySet());
    for (String name : keys) {
      var ae = at.get(name);
      var be = bt.get(name);
      String p = path.isEmpty() ? name : path + "/" + name;
      if (ae != null && be != null && ae.id().equals(be.id()) && ae.kind() == be.kind()) continue;
      if ((ae == null || ae.kind() == ObjectStore.Kind.TREE)
          && (be == null || be.kind() == ObjectStore.Kind.TREE))
        walk(ae == null ? null : ae.id(), be == null ? null : be.id(), p, leaves);
      else if (ae != null && be != null && ae.kind() != be.kind())
        throw new IOException("tree/blob 型別改變：" + p);
      else leaves.add(new Leaf(p, ae == null ? null : ae.id(), be == null ? null : be.id()));
    }
  }

  private static ChunkPos chunk(String name) {
    String[] p = name.split("\\.");
    return new ChunkPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
  }

  private static ChangeKind kind(Object a, Object b) {
    return a == null ? ChangeKind.ADDED : b == null ? ChangeKind.REMOVED : ChangeKind.MODIFIED;
  }

  public static List<BlockChange> blocks(ChunkPos chunk, int y, Section a, Section b) {
    return sectionDelta(chunk, y, a, b, Detail.BLOCKS).changes;
  }

  private record SectionDelta(List<BlockChange> changes, Counts counts) {}

  private static SectionDelta sectionDelta(
      ChunkPos chunk, int y, Section a, Section b, Detail detail) {
    long[] counts = new long[4];
    var changes = new ArrayList<BlockChange>();
    var ae = a.blockEntities();
    var be = b.blockEntities();
    for (int i = 0; i < 4096; i++) {
      BlockState before = a.block(i), after = b.block(i);
      byte[] ab = ae.get(i), bb = be.get(i);
      if (before.equals(after) && Arrays.equals(ab, bb)) continue;
      ChangeKind kind =
          before.equals(after)
              ? ChangeKind.MODIFIED
              : before.air() || before.fluid() && !after.air() && !after.fluid()
                  ? ChangeKind.ADDED
                  : after.air() ? ChangeKind.REMOVED : ChangeKind.MODIFIED;
      counts[kind.ordinal()]++;
      if (detail == Detail.SUMMARY) continue;
      var pos =
          new BlockPos(
              chunk.x() * 16 + (i & 15), y * 16 + (i >> 8), chunk.z() * 16 + ((i >> 4) & 15));
      changes.add(
          new BlockChange(
              pos,
              kind,
              before,
              after,
              ab == null ? null : Base64.getEncoder().encodeToString(ab),
              bb == null ? null : Base64.getEncoder().encodeToString(bb)));
    }
    return new SectionDelta(
        List.copyOf(changes), new Counts(counts[0], counts[1], counts[2], counts[3]));
  }

  public record Placed(ChunkPos chunk, EntitySnapshot entity) {}

  private void addEntities(Map<UUID, Placed> target, ChunkPos pos, String id) throws IOException {
    if (id != null)
      for (var e : SnapshotCodec.entities(store.readBlob(id)))
        if (target.put(e.uuid(), new Placed(pos, e)) != null)
          throw new IOException("快照包含重複 UUID：" + e.uuid());
  }

  /** 供黏性 capture 及 Hub 的 UUID 全域查詢；每個維度独立。 */
  public Map<UUID, Placed> entities(String tree) throws IOException {
    var result = new HashMap<UUID, Placed>();
    for (var region : store.readTree(tree).values())
      if (region.kind() == ObjectStore.Kind.TREE && region.name().startsWith("r."))
        for (var chunk : store.readTree(region.id()).values())
          if (chunk.kind() == ObjectStore.Kind.TREE) {
            var entity = store.readTree(chunk.id()).get("entities.bin");
            if (entity != null) addEntities(result, chunk(chunk.name()), entity.id());
          }
    return result;
  }
}
