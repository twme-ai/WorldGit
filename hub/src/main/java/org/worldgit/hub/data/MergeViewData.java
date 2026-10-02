package org.worldgit.hub.data;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.merge.MergeReport.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.core.store.ObjectStore;
import org.worldgit.hub.history.HistoryService;

/** 候選 tree 沿用 WGCK／WGDF 與實體線框；完整候選（含 BE／biome／metadata）由 core select 建立。 */
public final class MergeViewData {
  private MergeViewData() {}
  public record Entity(String uuid, String id, double x, double y, double z, String kind) {}
  public record Summary(String tree, WorldDiff.Counts counts, int entities, int biomes,
      int metadata, List<Cell> updateShapes) {}
  public static Object build(ObjectStore s, DimensionId dim, String base, String tree, List<Region> regions,
      DataService.Window window, String kind, List<Cell> shapes) throws IOException {
    var chunks = window.chunks();
    if (kind.equals("chunks")) return ChunkWire.chunks(s, new TreeNav(s, tree), chunks);
    if (!Set.of("diff", "entities", "summary").contains(kind)) throw new IllegalArgumentException("預覽資料種類無效");
    var diff = new DiffEngine(s).compare(dim, base, tree, HistoryService.TOLERANCE,
        kind.equals("diff") ? DiffEngine.Detail.BLOCKS : DiffEngine.Detail.SUMMARY,
        kind.equals("summary") ? null : new HashSet<>(chunks));
    if (kind.equals("summary")) return new Summary(tree, diff.counts(), diff.entities().size(), diff.biomes().size(), diff.metadata().size(), shapes);
    if (kind.equals("diff")) {
      // purple 格包含 state 不變但 BE 衝突；只標精確 atoms，空氣仍可畫外框。
      var cells = new TreeMap<String, Map<Integer, WorldDiff.BlockChange>>();
      var coords = new TreeMap<String, int[]>();
      for (var sec : diff.sections()) {
        String key = sec.chunk().x()+","+sec.sectionY()+","+sec.chunk().z();
        coords.put(key, new int[]{sec.chunk().x(),sec.sectionY(),sec.chunk().z()});
        var map = cells.computeIfAbsent(key,k -> new TreeMap<>());
        for (var b : sec.blocks()) map.put((b.pos().x()&15)|((b.pos().z()&15)<<4)|((b.pos().y()&15)<<8), b);
      }
      var inWindow = new HashSet<>(chunks);
      for (var region : regions) for (var b : org.worldgit.core.merge.MergeEngine.preview(s, tree, region)) {
        var p=b.position(); if (!inWindow.contains(p.chunk())) continue;
        String key=p.chunk().x()+","+p.sectionY()+","+p.chunk().z();
        coords.put(key,new int[]{p.chunk().x(),p.sectionY(),p.chunk().z()});
        cells.computeIfAbsent(key,k -> new TreeMap<>()).put(p.index(), new WorldDiff.BlockChange(
            new WorldDiff.BlockPos(p.x(),p.y(),p.z()), ChangeKind.CONFLICT, b.state(), b.state(), null, null));
      }
      var sections = new ArrayList<WorldDiff.SectionChange>();
      for (var e : cells.entrySet()) {
        int[] p=coords.get(e.getKey());
        sections.add(new WorldDiff.SectionChange(new ChunkPos(p[0],p[2]),p[1],ChangeKind.MODIFIED,List.copyOf(e.getValue().values()),new WorldDiff.Counts(0,0,0,0)));
      }
      return ChunkWire.diff(new WorldDiff(dim,sections,List.of(),List.of(),List.of()));
    }
    var out = new ArrayList<Entity>(); var nav = new TreeNav(s,tree); var kinds=new HashMap<UUID,String>();
    diff.entities().forEach(e -> kinds.put(e.uuid(),e.kind().name().toLowerCase(Locale.ROOT)));
    for (var c : chunks) {
      var files=nav.chunk(c); var e=files==null?null:files.get("entities.bin");
      if (e != null) for (var entity : SnapshotCodec.entities(s.readBlob(e.id()))) add(out,entity,kinds.get(entity.uuid()));
    }
    for (var e : diff.entities()) if (e.after()==null) add(out,e.before(),"removed");
    return out;
  }
  private static void add(List<Entity> out,EntitySnapshot e,String kind) {
    double[] p=e.position(); out.add(new Entity(e.uuid().toString(),e.data().string("id"),p[0],p[1],p[2],kind));
  }
}
