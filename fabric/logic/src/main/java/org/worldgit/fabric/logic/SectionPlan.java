package org.worldgit.fabric.logic;

import java.util.*;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.protocol.Protocol;

/**
 * 客戶端分塊：把逐格 cell 依 16×16×16 section 分組，每組有自己的包圍盒與主要類型，距離遠時只畫包圍盒
 * （05 實驗：10 萬格逐格畫太重）。status 的 outline 則依 8×8 chunk 的區域分組。
 */
public final class SectionPlan {
  public record Key(int x, int y, int z) {}

  private final Key key;
  private final List<Protocol.Cell> cells = new ArrayList<>();
  private final List<Protocol.Outline> outlines = new ArrayList<>();
  private final long[] counts = new long[4];
  private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
  private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

  private SectionPlan(Key key) {
    this.key = key;
  }

  public Key key() {
    return key;
  }

  public List<Protocol.Cell> cells() {
    return Collections.unmodifiableList(cells);
  }

  public List<Protocol.Outline> outlines() {
    return Collections.unmodifiableList(outlines);
  }

  public long count(ChangeKind kind) {
    return counts[kind.ordinal()];
  }

  public long cellCount() {
    return cells.size();
  }

  /** 衝突 > 修改 > 移除 > 新增（doc 06 §1.1）。 */
  public ChangeKind priority() {
    for (ChangeKind k : new ChangeKind[] {ChangeKind.CONFLICT, ChangeKind.MODIFIED, ChangeKind.REMOVED, ChangeKind.ADDED})
      if (counts[k.ordinal()] > 0) return k;
    return ChangeKind.ADDED;
  }

  /** 包圍盒，max 為排他上界（方塊的 +1）。 */
  public int[] bounds() {
    return new int[] {minX, minY, minZ, maxX, maxY, maxZ};
  }

  private void grow(int x1, int y1, int z1, int x2, int y2, int z2) {
    minX = Math.min(minX, x1);
    minY = Math.min(minY, y1);
    minZ = Math.min(minZ, z1);
    maxX = Math.max(maxX, x2);
    maxY = Math.max(maxY, y2);
    maxZ = Math.max(maxZ, z2);
  }

  public static List<SectionPlan> ofCells(Collection<Protocol.Cell> cells) {
    var map = new LinkedHashMap<Key, SectionPlan>();
    for (var c : cells) {
      var key = new Key(Math.floorDiv(c.x(), 16), Math.floorDiv(c.y(), 16), Math.floorDiv(c.z(), 16));
      var plan = map.computeIfAbsent(key, SectionPlan::new);
      plan.cells.add(c);
      plan.counts[c.kind().ordinal()]++;
      plan.grow(c.x(), c.y(), c.z(), c.x() + 1, c.y() + 1, c.z() + 1);
    }
    return List.copyOf(map.values());
  }

  /** status 的 outline：每 8×8 chunk 為一區，區域內各自畫外框。 */
  public static List<SectionPlan> ofOutlines(Collection<Protocol.Outline> outlines) {
    var map = new LinkedHashMap<Key, SectionPlan>();
    for (var o : outlines) {
      var key = new Key(Math.floorDiv(o.x1(), 128), 0, Math.floorDiv(o.z1(), 128));
      var plan = map.computeIfAbsent(key, SectionPlan::new);
      plan.outlines.add(o);
      plan.counts[o.kind().ordinal()]++;
      plan.grow(o.x1(), o.y1(), o.z1(), o.x2() + 1, o.y2() + 1, o.z2() + 1);
    }
    return List.copyOf(map.values());
  }

  /** 相機到包圍盒的最短距離（方塊）。 */
  public double distance(double cx, double cy, double cz) {
    double dx = Math.max(Math.max(minX - cx, 0), cx - maxX);
    double dy = Math.max(Math.max(minY - cy, 0), cy - maxY);
    double dz = Math.max(Math.max(minZ - cz, 0), cz - maxZ);
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }
}
