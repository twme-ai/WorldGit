package org.worldgit.core.apply;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.ObjectStore;

/**
 * 由「目前世界的 working tree」與「目標 tree」算出 {@link ApplyPlan}。tree 分層短路：相同 id 的 region／chunk 不展開，只解碼有差異的
 * blob；範圍外的 chunk 不解碼方塊（實體因為以 UUID 為全域鍵，仍會比對所有有差異的實體 blob）。
 */
public final class ApplyPlanner {
  private ApplyPlanner() {}

  /**
   * @param workingRules 目前世界使用的 .wgignore 規則
   * @param targetRules 目標 commit 的 .wgignore 規則；兩者任一排除的區域／實體保持活世界現況
   * @param deleteUntracked 目標沒有、世界卻有的 chunk：false（預設）保留並標 untracked；true 刪除（需整個 chunk 在範圍內）
   * @param worldMeta 是否一併還原世界級 metadata（只有主世界 repo 有）
   */
  public record Options(
      IgnoreRules workingRules,
      IgnoreRules targetRules,
      EntitySemantics semantics,
      double tolerance,
      boolean deleteUntracked,
      boolean worldMeta,
      int dataVersion) {
    public Options {
      workingRules = workingRules == null ? IgnoreRules.none() : workingRules;
      targetRules = targetRules == null ? IgnoreRules.none() : targetRules;
      semantics = semantics == null ? EntitySemantics.OFFLINE : semantics;
    }

    public static Options defaults() {
      return new Options(null, null, null, 2, false, false, 0);
    }

    boolean keepBlock(int x, int y, int z) {
      return workingRules.ignoredBlock(x, y, z) || targetRules.ignoredBlock(x, y, z);
    }

    boolean hasAreas() {
      return workingRules.hasAreas() || targetRules.hasAreas();
    }
  }

  private record ChunkDiff(ChunkPos pos, String working, String target) {}

  public static ApplyPlan plan(
      ObjectStore store,
      DimensionId dimension,
      String working,
      String target,
      Scope scope,
      Options options)
      throws IOException {
    var chunkDiffs = new ArrayList<ChunkDiff>();
    if (Objects.equals(working, target)) return new ApplyPlan(dimension, working, target,
        options.dataVersion, scope, List.of(), List.of(), Map.of(), List.of());
    var rootWorking = store.readTree(working);
    var rootTarget = store.readTree(target);
    var regionNames = new TreeSet<String>();
    rootWorking.keySet().stream().filter(n -> n.startsWith("r.")).forEach(regionNames::add);
    rootTarget.keySet().stream().filter(n -> n.startsWith("r.")).forEach(regionNames::add);
    for (String region : regionNames) {
      var w = rootWorking.get(region);
      var t = rootTarget.get(region);
      if (w != null && t != null && w.id().equals(t.id())) continue;
      var wc = w == null ? Map.<String, ObjectStore.Entry>of() : store.readTree(w.id());
      var tc = t == null ? Map.<String, ObjectStore.Entry>of() : store.readTree(t.id());
      var names = new TreeSet<String>(wc.keySet());
      names.addAll(tc.keySet());
      for (String name : names) {
        var we = wc.get(name);
        var te = tc.get(name);
        if (we != null && te != null && we.id().equals(te.id())) continue;
        chunkDiffs.add(
            new ChunkDiff(
                chunkPos(name), we == null ? null : we.id(), te == null ? null : te.id()));
      }
    }

    var ops = new ArrayList<ApplyPlan.ChunkOp>();
    var untracked = new TreeSet<ChunkPos>();
    var oldEntities = new HashMap<UUID, DiffPlaced>();
    var newEntities = new HashMap<UUID, DiffPlaced>();
    for (var diff : chunkDiffs) {
      var w = diff.working == null ? Map.<String, ObjectStore.Entry>of() : store.readTree(diff.working);
      var t = diff.target == null ? Map.<String, ObjectStore.Entry>of() : store.readTree(diff.target);
      boolean removedChunk = diff.target == null;
      boolean keptUntracked = removedChunk && !(options.deleteUntracked && scope.coversChunk(diff.pos));
      if (removedChunk && scope.touchesChunk(diff.pos)) {
        if (keptUntracked) untracked.add(diff.pos);
        else
          ops.add(
              new ApplyPlan.ChunkOp(diff.pos, true, new TreeMap<>(), new TreeMap<>(), false, null, false, null));
      }
      // 實體：全域 UUID 比對；untracked 保留的 chunk 其實體不動
      if (!keptUntracked) {
        collectEntities(store, oldEntities, diff.pos, w.get("entities.bin"), t.get("entities.bin"), true);
        collectEntities(store, newEntities, diff.pos, t.get("entities.bin"), w.get("entities.bin"), false);
      }
      if (removedChunk || !scope.touchesChunk(diff.pos)) continue;
      var sections = new TreeMap<Integer, ApplyPlan.SectionOp>();
      var names = new TreeSet<String>(w.keySet());
      names.addAll(t.keySet());
      for (String name : names) {
        if (!name.matches("s\\.-?\\d+\\.bin")) continue;
        int y = Integer.parseInt(name.substring(2, name.length() - 4));
        var we = w.get(name);
        var te = t.get(name);
        if (we != null && te != null && we.id().equals(te.id())) continue;
        if (!scope.touchesSection(diff.pos, y)) continue;
        long[] mask = mask(diff.pos, y, scope, options);
        var tb = te == null ? null : store.readBlob(te.id());
        if (mask != null || we == null || te == null) {
          Section a = we == null ? Section.air() : SnapshotCodec.section(store.readBlob(we.id()));
          Section b = tb == null ? Section.air() : SnapshotCodec.section(tb);
          if (!differs(a, b, mask)) continue;
        }
        sections.put(y, new ApplyPlan.SectionOp(y, tb, mask));
      }
      var biomes = new TreeMap<Integer, ApplyPlan.BiomeOp>();
      var wb = w.get("biomes.bin");
      var tbio = t.get("biomes.bin");
      if (tbio != null && (wb == null || !wb.id().equals(tbio.id()))) {
        Map<Integer, List<String>> a = wb == null ? Map.of() : SnapshotCodec.biomes(store.readBlob(wb.id()));
        Map<Integer, List<String>> b = SnapshotCodec.biomes(store.readBlob(tbio.id()));
        for (var e : b.entrySet()) {
          int y = e.getKey();
          if (!scope.touchesSection(diff.pos, y)) continue;
          var out = new ArrayList<String>(64);
          boolean any = false;
          for (int i = 0; i < 64; i++) {
            String name = e.getValue().get(i);
            int gx = diff.pos.x() * 16 + (i & 3) * 4,
                gz = diff.pos.z() * 16 + ((i >> 2) & 3) * 4,
                gy = y * 16 + (i >> 4) * 4;
            boolean include =
                !name.isEmpty() && scope.containsBlock(gx, gy, gz) && !options.keepBlock(gx, gy, gz);
            if (!include) {
              out.add("");
              continue;
            }
            out.add(name);
            String old = a.containsKey(y) ? a.get(y).get(i) : null;
            if (!name.equals(old)) any = true;
          }
          if (any) biomes.put(y, new ApplyPlan.BiomeOp(y, out));
        }
      }
      boolean covers = scope.coversChunk(diff.pos) && !options.hasAreas();
      boolean setTicks = false, setStructures = false;
      byte[] ticks = null, structures = null;
      if (covers) {
        var wt = w.get("ticks.bin");
        var tt = t.get("ticks.bin");
        if (!Objects.equals(wt == null ? null : wt.id(), tt == null ? null : tt.id())) {
          setTicks = true;
          ticks = tt == null ? null : SnapshotCodec.nbt(4, store.readBlob(tt.id()), true);
        }
        var ws = w.get("structures.bin");
        var ts = t.get("structures.bin");
        if (!Objects.equals(ws == null ? null : ws.id(), ts == null ? null : ts.id())) {
          setStructures = true;
          structures = ts == null ? null : SnapshotCodec.nbt(5, store.readBlob(ts.id()), true);
        }
      }
      var op = new ApplyPlan.ChunkOp(diff.pos, false, sections, biomes, setTicks, ticks, setStructures, structures);
      if (!op.empty()) ops.add(op);
    }

    var entityOps = new ArrayList<ApplyPlan.EntityOp>();
    var uuids = new TreeSet<UUID>(oldEntities.keySet());
    uuids.addAll(newEntities.keySet());
    for (UUID uuid : uuids) {
      var w = oldEntities.get(uuid);
      var t = newEntities.get(uuid);
      boolean inW = w != null && inScope(scope, w.entity);
      boolean inT = t != null && inScope(scope, t.entity);
      if (!inW && !inT) continue;
      if (w != null && t != null && EntityNormalizer.stickyEqual(w.entity, t.entity, options.tolerance)) continue;
      if (t == null || !inT) {
        if (options.targetRules.ignoredEntity(w.entity.data(), options.semantics)) continue;
        entityOps.add(new ApplyPlan.EntityOp(uuid, w.chunk, null,w.entity.position()));
      } else if (!options.workingRules.ignoredEntity(t.entity.data(), options.semantics)
          && (w == null || !options.targetRules.ignoredEntity(w.entity.data(), options.semantics)))
        entityOps.add(new ApplyPlan.EntityOp(uuid, w == null ? null : w.chunk, t.entity,w==null ? null : w.entity.position()));
    }

    var meta = new TreeMap<String, byte[]>();
    if (options.worldMeta) {
      var w = rootWorking.get("world-meta");
      var t = rootTarget.get("world-meta");
      if (t != null && (w == null || !w.id().equals(t.id()))) {
        var wf = w == null ? Map.<String, ObjectStore.Entry>of() : store.readTree(w.id());
        var tf = store.readTree(t.id());
        for (var e : tf.values()) {
          if (e.kind() != ObjectStore.Kind.BLOB || e.name().equals("worldgit.yml")) continue;
          var old = wf.get(e.name());
          if (old == null || !old.id().equals(e.id())) meta.put(e.name(), store.readBlob(e.id()));
        }
        for (var e : wf.values())
          if (!tf.containsKey(e.name()) && !e.name().equals("worldgit.yml")
              && !MetadataNormalizer.normalize(Map.of(e.name(), store.readBlob(e.id())), options.targetRules).isEmpty())
            meta.put(e.name(), null);
      }
    }
    return new ApplyPlan(
        dimension, working, target, options.dataVersion, scope, ops, entityOps, meta, untracked).withRules(options.workingRules.source());
  }

  private record DiffPlaced(ChunkPos chunk, EntitySnapshot entity) {}

  private static boolean inScope(Scope scope, EntitySnapshot e) {
    double[] p = e.position();
    return scope.containsPoint(p[0], p[1], p[2]);
  }

  /** side 的 blob 內所有實體入 map；兩側 id 相同的 blob 已被 tree 短路，不會出現。 */
  private static void collectEntities(
      ObjectStore store,
      Map<UUID, DiffPlaced> into,
      ChunkPos pos,
      ObjectStore.Entry side,
      ObjectStore.Entry other,
      boolean working)
      throws IOException {
    if (side == null || (other != null && other.id().equals(side.id()))) return;
    for (var e : SnapshotCodec.entities(store.readBlob(side.id())))
      if (into.put(e.uuid(), new DiffPlaced(pos, e)) != null)
        throw new IOException("快照包含重複 UUID：" + e.uuid());
  }

  private static long[] mask(ChunkPos pos, int y, Scope scope, Options options) {
    if (!scope.needsMask(pos, y) && !options.hasAreas()) return null;
    long[] mask = new long[64];
    for (int i = 0; i < 4096; i++) {
      int x = pos.x() * 16 + (i & 15), z = pos.z() * 16 + ((i >> 4) & 15), gy = y * 16 + (i >> 8);
      if (scope.containsBlock(x, gy, z) && !options.keepBlock(x, gy, z)) mask[i >>> 6] |= 1L << (i & 63);
    }
    return mask;
  }

  private static boolean differs(Section a, Section b, long[] mask) {
    var ae = a.blockEntities();
    var be = b.blockEntities();
    for (int i = 0; i < 4096; i++) {
      if (mask != null && (mask[i >>> 6] & (1L << (i & 63))) == 0) continue;
      if (!a.block(i).equals(b.block(i))) return true;
      if (!Arrays.equals(ae.get(i), be.get(i))) return true;
    }
    return false;
  }

  private static ChunkPos chunkPos(String name) throws IOException {
    String[] p = name.split("\\.");
    try {
      if (p.length != 3 || !p[0].equals("c")) throw new IllegalArgumentException();
      return new ChunkPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    } catch (IllegalArgumentException e) {
      throw new IOException("chunk tree 座標無效：" + name, e);
    }
  }
}
