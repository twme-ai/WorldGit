package org.worldgit.fabric.logic;

import java.util.*;

/**
 * 分塊 LOD：每個畫面依相機位置決定每個區塊畫「逐格明細」、「包圍盒」或不畫。
 *
 * <ul>
 *   <li>近於 detailDistance 且在視錐內 → 明細候選，最近的 maxDetailSections 個可保留在 GPU；
 *   <li>明細尚未建好的區塊先畫包圍盒，每個畫面最多建立 buildPerFrame 個（避免首幀停頓）；
 *   <li>離開 detailDistance×1.25（遲滯）或被擠出名額的明細會釋放；
 *   <li>超過 maxDistance 或不在視錐內的區塊不畫。
 * </ul>
 */
public final class LodSelector {
  public enum Level {
    HIDDEN,
    BBOX,
    DETAIL
  }

  public record Frame(Level[] levels, List<Integer> build, List<Integer> release) {}

  private final List<SectionPlan> plans;
  private final ClientConfig config;
  private final boolean supportsDetail;
  private final boolean[] resident;

  /** supportsDetail=false（只有 outline 的 status 預覽）時永遠畫 BBOX 以外不建明細。 */
  public LodSelector(List<SectionPlan> plans, ClientConfig config, boolean supportsDetail) {
    this.plans = plans;
    this.config = config;
    this.supportsDetail = supportsDetail;
    this.resident = new boolean[plans.size()];
  }

  public boolean isResident(int index) {
    return resident[index];
  }

  /** 呼叫端建不出某區塊的明細（例如模型錯誤）時，標記為未常駐，之後只畫包圍盒。 */
  public void failed(int index) {
    resident[index] = false;
  }

  public int residentCount() {
    int n = 0;
    for (boolean b : resident) if (b) n++;
    return n;
  }

  /** frustum 可為 null（不做視錐篩選）。 */
  public Frame select(double cx, double cy, double cz, Frustum frustum) {
    int n = plans.size();
    var levels = new Level[n];
    var release = new ArrayList<Integer>();
    var candidates = new ArrayList<int[]>();
    double[] dist = new double[n];
    for (int i = 0; i < n; i++) {
      var p = plans.get(i);
      double d = p.distance(cx, cy, cz);
      dist[i] = d;
      var b = p.bounds();
      boolean visible =
          d <= config.maxDistance()
              && (frustum == null
                  || frustum.intersects(b[0] - cx, b[1] - cy, b[2] - cz, b[3] - cx, b[4] - cy, b[5] - cz));
      levels[i] = visible ? Level.BBOX : Level.HIDDEN;
      boolean wantDetail = supportsDetail && visible && d <= config.detailDistance();
      boolean keepDetail = supportsDetail && resident[i] && d <= config.detailDistance() * 1.25 && d <= config.maxDistance();
      if (wantDetail || keepDetail) candidates.add(new int[] {i});
      else if (resident[i]) {
        resident[i] = false;
        release.add(i);
      }
    }
    candidates.sort(Comparator.comparingDouble(a -> dist[a[0]]));
    var build = new ArrayList<Integer>();
    for (int rank = 0; rank < candidates.size(); rank++) {
      int i = candidates.get(rank)[0];
      if (rank >= config.maxDetailSections()) {
        if (resident[i]) {
          resident[i] = false;
          release.add(i);
        }
        continue;
      }
      boolean visibleNow = levels[i] != Level.HIDDEN;
      if (resident[i]) levels[i] = visibleNow ? Level.DETAIL : Level.HIDDEN;
      else if (visibleNow && dist[i] <= config.detailDistance() && build.size() < config.ghostBuildPerFrame()) {
        build.add(i);
        resident[i] = true;
        levels[i] = Level.DETAIL;
      }
    }
    return new Frame(levels, build, release);
  }
}
