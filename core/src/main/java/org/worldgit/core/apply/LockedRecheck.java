package org.worldgit.core.apply;

import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.*;
import org.worldgit.core.store.ObjectStore;

/**
 * 上鎖後的重新核對（決策 #163）。預檢在世界照常 tick 時擷取；鎖住受影響 chunk 後的擷取才是權威。
 *
 * <p>與預檢基底不同時不再整個中止，而是以上鎖後的狀態重算計畫；唯一的例外是 switch 這類「不得覆蓋未提交變動」的操作：
 * 上鎖後的範圍若與 HEAD 不同（預檢時是乾淨的），就是窗口內新出現的未提交變動，回報 {@code dirt}。
 */
public final class LockedRecheck {
  private LockedRecheck() {}

  /**
   * 測試鉤子：{@code -Dworldgit.test.preflight-delay-ms=N} 在預檢與上鎖之間暫停 N 毫秒，用來在真伺服器上穩定製造競態窗口
   * （例如在窗口內由玩家放置方塊）。未設定時不做任何事。
   */
  public static void testWindow() {
    String value = System.getProperty("worldgit.test.preflight-delay-ms");
    if (value == null || value.isBlank()) return;
    try {
      Thread.sleep(Long.parseLong(value.trim()));
    } catch (NumberFormatException ignored) {
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @FunctionalInterface
  public interface Replan {
    ApplyPlan apply(String lockedTree) throws IOException;
  }

  /**
   * @param plan 之後要套用的計畫（未變動時即原計畫）
   * @param replanned 是否以上鎖後的狀態重算過
   * @param needLock 重算後的足跡超出已鎖範圍的 chunk；非空時須擴大鎖後重試
   * @param entitiesGrew 重算後多出原本未鎖定追蹤的實體 UUID，須重新計算鎖範圍後重試
   * @param dirt 保護未提交變動時，上鎖後範圍與 HEAD 的差異；非 null 表示不可覆蓋
   */
  public record Result(
      ApplyPlan plan,
      boolean replanned,
      Set<ChunkPos> needLock,
      boolean entitiesGrew,
      WorldDiff dirt) {
    public boolean stable() {
      return needLock.isEmpty() && !entitiesGrew && dirt == null;
    }
  }

  public static Result check(
      ObjectStore store,
      DimensionId dimension,
      ApplyPlan plan,
      String locked,
      double tolerance,
      Set<ChunkPos> lockedChunks,
      Set<UUID> lockedEntities,
      String protectHead,
      UnaryOperator<String> tracked,
      Replan replan)
      throws IOException {
    var engine = new DiffEngine(store);
    var affected = LiveApplyVerification.affected(plan);
    if (engine.compare(dimension, plan.baseTree(), locked, tolerance, DiffEngine.Detail.SUMMARY, affected)
        .empty()) return new Result(plan, false, Set.of(), false, null);
    if (replan == null) {
      var window = engine.compare(dimension, plan.baseTree(), locked, tolerance, DiffEngine.Detail.SUMMARY, affected);
      return new Result(plan, true, Set.of(), false, window);
    }
    var next = replan.apply(locked);
    var need = new TreeSet<>(LiveApplyVerification.affected(next));
    need.removeAll(lockedChunks);
    boolean grew = false;
    for (var op : next.entities()) if (!lockedEntities.contains(op.uuid())) grew = true;
    if (!need.isEmpty() || grew) return new Result(next, true, need, grew, null);
    if (protectHead != null) {
      var scope = new TreeSet<>(affected);
      scope.addAll(LiveApplyVerification.affected(next));
      var dirt =
          engine.compare(
              dimension, protectHead, tracked.apply(locked), tolerance, DiffEngine.Detail.SUMMARY, scope);
      if (!dirt.empty()) return new Result(next, true, Set.of(), false, dirt);
    }
    return new Result(next, true, Set.of(), false, null);
  }

  /** 預檢（未上鎖）階段對 HEAD 的整體 dirty 判定；有差異時回報可列出的內容。 */
  public static WorldDiff dirt(
      ObjectStore store, DimensionId dimension, String head, String tracked, double tolerance)
      throws IOException {
    return new DiffEngine(store)
        .compare(dimension, head, tracked, tolerance, DiffEngine.Detail.SUMMARY);
  }
}
