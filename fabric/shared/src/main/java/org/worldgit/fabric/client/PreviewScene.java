package org.worldgit.fabric.client;

import java.util.*;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.fabric.logic.*;
import org.worldgit.protocol.DiffPalette;
import org.worldgit.protocol.Protocol;

/**
 * 一個維度目前發布的預覽：分塊（SectionPlan）、LOD 選擇、GPU 網格。
 *
 * <ul>
 *   <li>DETAIL 區塊：逐格外框（新增實線、修改虛線、衝突閃爍）＋鬼影（移除＝原版模型紅、修改＝舊模型淡黃），延遲建立；
 *   <li>BBOX 區塊：只畫包圍盒（diff）或各 outline 的外框（status），所有 BBOX 合併成一個 buffer，只在 LOD 變化時重建。
 * </ul>
 *
 * 所有方法都在 render thread 呼叫。
 */
final class PreviewScene implements AutoCloseable {
  static final Logger LOG = LoggerFactory.getLogger("WorldGit");
  static final int GHOST_REMOVED_ALPHA = 108, GHOST_MODIFIED_ALPHA = 58;

  /** 一個區塊的明細：鬼影、靜態外框、閃爍外框。 */
  private record Detail(GpuMesh ghost, GpuMesh lines, GpuMesh pulse) implements AutoCloseable {
    @Override
    public void close() {
      for (var m : new GpuMesh[] {ghost, lines, pulse}) if (m != null) m.close();
    }
  }

  private final ClientPreviews.Published published;
  private final List<SectionPlan> plans;
  private final boolean cells;
  private final Map<String, List<Geometry.Quad>> models = new HashMap<>();
  private final Set<String> badModels = new HashSet<>();
  private final Function<String, List<Geometry.Quad>> modelLoader;
  private Detail[] details;
  private LodSelector lod;
  private GpuMesh bboxStatic, bboxPulse;
  private LodSelector.Level[] lastLevels;
  private ClientConfig config;
  private DiffPalette palette;
  private int lastDetail;

  PreviewScene(ClientPreviews.Published published, ClientConfig config, DiffPalette palette, Function<String, List<Geometry.Quad>> modelLoader) {
    this.published = published;
    this.cells = published.ghost();
    this.plans = cells ? SectionPlan.ofCells(published.cells()) : SectionPlan.ofOutlines(published.outlines());
    this.modelLoader = modelLoader;
    configure(config, palette);
  }

  ClientPreviews.Published published() {
    return published;
  }

  int sectionCount() {
    return plans.size();
  }

  int cellCount() {
    return cells ? published.cells().size() : published.outlines().size();
  }

  int detailCount() {
    return lastDetail;
  }

  /** 設定或色票改變：丟掉全部 GPU 網格，下個畫面依新設定重建。 */
  void configure(ClientConfig config, DiffPalette palette) {
    releaseMeshes();
    this.config = config;
    this.palette = palette;
    this.lod = new LodSelector(plans, config, cells);
    this.details = new Detail[plans.size()];
  }

  private void releaseMeshes() {
    if (details != null) for (var d : details) if (d != null) d.close();
    if (bboxStatic != null) bboxStatic.close();
    if (bboxPulse != null) bboxPulse.close();
    bboxStatic = bboxPulse = null;
    lastLevels = null;
    details = null;
  }

  /** 逐幀：LOD 選擇、延遲建立／釋放明細，並回傳這一幀要畫的網格（鬼影在前、外框在後）。 */
  List<GpuMesh> frame(double cx, double cy, double cz, Frustum frustum) {
    var frame = lod.select(cx, cy, cz, frustum);
    for (int i : frame.release()) {
      if (details[i] != null) details[i].close();
      details[i] = null;
    }
    var levels = frame.levels();
    for (int i : frame.build()) {
      try {
        details[i] = buildDetail(plans.get(i));
      } catch (RuntimeException e) {
        LOG.warn("WorldGit 區塊 {} 明細建立失敗，改畫包圍盒：{}", plans.get(i).key(), e.toString());
        lod.failed(i);
        levels[i] = LodSelector.Level.BBOX;
      }
    }
    int detail = 0;
    for (int i = 0; i < levels.length; i++) if (levels[i] == LodSelector.Level.DETAIL) detail++;
    lastDetail = detail;
    if (!Arrays.equals(levels, lastLevels)) {
      rebuildBbox(levels);
      lastLevels = levels.clone();
    }
    var out = new ArrayList<GpuMesh>();
    for (int i = 0; i < levels.length; i++)
      if (levels[i] == LodSelector.Level.DETAIL && details[i] != null && details[i].ghost() != null) out.add(details[i].ghost());
    for (int i = 0; i < levels.length; i++)
      if (levels[i] == LodSelector.Level.DETAIL && details[i] != null) {
        if (details[i].lines() != null) out.add(details[i].lines());
        if (details[i].pulse() != null) out.add(details[i].pulse());
      }
    if (bboxStatic != null) out.add(bboxStatic);
    if (bboxPulse != null) out.add(bboxPulse);
    return out;
  }

  private List<Geometry.Quad> model(String state) {
    if (badModels.contains(state)) return List.of();
    var cached = models.get(state);
    if (cached != null) return cached;
    try {
      cached = modelLoader.apply(state);
    } catch (RuntimeException e) {
      LOG.warn("WorldGit 無法取得方塊 {} 的模型：{}", state, e.toString());
      badModels.add(state);
      return List.of();
    }
    models.put(state, cached);
    return cached;
  }

  private Detail buildDetail(SectionPlan plan) {
    int[] b = plan.bounds();
    int ox = b[0], oy = b[1], oz = b[2];
    try (var ghost = new VertexBuilder(4096);
        var lines = new VertexBuilder(4096);
        var pulse = new VertexBuilder(1024)) {
      for (var c : plan.cells()) {
        float x = c.x() - ox, y = c.y() - oy, z = c.z() - oz;
        int rgb = palette.rgb(c.kind());
        switch (c.kind()) {
          case ADDED -> {
            Geometry.blockOutline(lines, x, y, z, rgb, false);
            Geometry.ghost(ghost, model(c.after()), x, y, z, rgb, GHOST_REMOVED_ALPHA);
          }
          case MODIFIED -> {
            Geometry.blockOutline(lines, x, y, z, rgb, true);
            var quads = model(c.after());
            Geometry.ghost(ghost, quads, x, y, z, rgb, GHOST_MODIFIED_ALPHA);
          }
          case CONFLICT -> {
            Geometry.blockOutline(pulse, x, y, z, rgb, false);
            Geometry.ghost(ghost, model(c.after()), x, y, z, rgb, GHOST_REMOVED_ALPHA);
          }
          case REMOVED -> {
            var quads = model(c.before());
            if (quads.isEmpty()) Geometry.blockOutline(lines, x, y, z, rgb, false); // 沒有模型可畫時退回紅色外框
            else Geometry.ghost(ghost, quads, x, y, z, rgb, GHOST_REMOVED_ALPHA);
          }
        }
      }
      return new Detail(GpuMesh.upload(ghost, true, false, ox, oy, oz), GpuMesh.upload(lines, false, false, ox, oy, oz), GpuMesh.upload(pulse, false, true, ox, oy, oz));
    }
  }

  private void rebuildBbox(LodSelector.Level[] levels) {
    if (bboxStatic != null) bboxStatic.close();
    if (bboxPulse != null) bboxPulse.close();
    bboxStatic = bboxPulse = null;
    int ox = Integer.MAX_VALUE, oy = Integer.MAX_VALUE, oz = Integer.MAX_VALUE;
    for (int i = 0; i < levels.length; i++)
      if (levels[i] == LodSelector.Level.BBOX) {
        int[] b = plans.get(i).bounds();
        ox = Math.min(ox, b[0]);
        oy = Math.min(oy, b[1]);
        oz = Math.min(oz, b[2]);
      }
    if (ox == Integer.MAX_VALUE) return;
    try (var solid = new VertexBuilder(4096); var pulse = new VertexBuilder(1024)) {
      for (int i = 0; i < levels.length; i++) {
        if (levels[i] != LodSelector.Level.BBOX) continue;
        var plan = plans.get(i);
        if (!plan.outlines().isEmpty()) {
          for (var o : plan.outlines()) bbox(o.kind(), o.x1() - ox, o.y1() - oy, o.z1() - oz, o.x2() + 1 - ox, o.y2() + 1 - oy, o.z2() + 1 - oz, solid, pulse);
        } else {
          int[] b = plan.bounds();
          bbox(plan.priority(), b[0] - ox, b[1] - oy, b[2] - oz, b[3] - ox, b[4] - oy, b[5] - oz, solid, pulse);
        }
      }
      bboxStatic = GpuMesh.upload(solid, false, false, ox, oy, oz);
      bboxPulse = GpuMesh.upload(pulse, false, true, ox, oy, oz);
    }
  }

  private void bbox(ChangeKind kind, float x1, float y1, float z1, float x2, float y2, float z2, VertexBuilder solid, VertexBuilder pulse) {
    Geometry.box(kind == ChangeKind.CONFLICT ? pulse : solid, x1, y1, z1, x2, y2, z2, palette.rgb(kind), kind == ChangeKind.MODIFIED);
  }

  @Override
  public void close() {
    releaseMeshes();
    models.clear();
  }
}
