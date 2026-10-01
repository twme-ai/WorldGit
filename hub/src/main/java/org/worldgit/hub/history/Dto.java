package org.worldgit.hub.history;

import java.util.List;
import java.util.Map;

/** REST API 的資料形狀（欄位名稱即 JSON 名稱；前端 hub/web/src/api.ts 有對應型別）。 */
public final class Dto {
  private Dto() {}

  public record Person(String name, String email) {}

  public record CommitInfo(
      String id,
      List<String> parents,
      String tree,
      long time,
      Person author,
      Person committer,
      String message,
      boolean auto,
      String source,
      int dataVersion,
      String dimension,
      String snapshot,
      List<String> coAuthors) {}

  public record DimensionState(String id, String repo, String head, Long headTime, int commits) {}

  public record SnapshotRow(
      String snapshot,
      long time,
      String message,
      Person author,
      boolean auto,
      String source,
      Map<String, CommitInfo> commits,
      boolean partial,
      List<String> missingDimensions,
      String partialReason) {}

  public record SnapshotPage(List<SnapshotRow> snapshots, Long nextBefore, List<String> declaredDimensions) {}

  public record EntityChangeInfo(String uuid, String kind, String type, double[] before, double[] after) {}

  /** changedChunks 每項：[chunkX, chunkZ, 新增, 移除, 修改, flags]，flags bit0=實體 bit1=biome。 */
  public record CommitDetail(
      CommitInfo commit,
      String parent,
      boolean initial,
      long added,
      long removed,
      long modified,
      int chunkCount,
      int sectionCount,
      int entitiesAdded,
      int entitiesRemoved,
      int entitiesModified,
      List<EntityChangeInfo> entityChanges,
      List<int[]> changedChunks,
      int[] bounds,
      List<String> metadataChanges,
      String mcVersion) {}

  // ---- 分支（Phase 2 Hub：決定 #H1）----

  /** 某維度 repo 上該分支的 head。 */
  public record BranchHead(String id, long time, String snapshot, boolean auto, String message, Person author) {}

  /**
   * 世界層級的一個分支：依名稱跨維度合併。consistent＝每個宣告／實際存在的維度 repo 都有這個分支；
   * aligned＝各維度 head 屬於同一次存檔（沒有變動的維度 head 會停在較舊的存檔，所以不一致不代表有問題）。
   * ahead／behind 以存檔（WorldGit-Snapshot）為單位，相對 comparedTo；比較基準本身為 null。
   */
  public record BranchRow(
      String name,
      boolean isDefault,
      Map<String, BranchHead> heads,
      boolean consistent,
      boolean aligned,
      List<String> missingDimensions,
      long time,
      String message,
      Person author,
      String snapshot,
      Integer ahead,
      Integer behind,
      boolean countsTruncated) {}

  public record BranchPage(String defaultBranch, String comparedTo, List<BranchRow> branches, List<String> declaredDimensions) {}

  // ---- 比較 ----

  /** 比較的一端：分支（各維度 head）或某次存檔的 commit（同 snapshot 的各維度 commit；缺少端點不推測）。 */
  public record RevInfo(
      String spec, String kind, String snapshot, long time, String message, Person author, Map<String, String> commits) {}

  /** 單一維度的 a→b 差異；changedChunks 整個回應最多 2000 項（座標排序），完整數量見 chunkCount。 */
  public record CompareDimension(
      String dimension,
      String repo,
      String status,
      CommitInfo a,
      CommitInfo b,
      long added,
      long removed,
      long modified,
      int chunkCount,
      int sectionCount,
      int entitiesAdded,
      int entitiesRemoved,
      int entitiesModified,
      List<EntityChangeInfo> entityChanges,
      List<int[]> changedChunks,
      boolean chunksTruncated,
      List<int[]> changedSections,
      boolean sectionsTruncated,
      int[] bounds,
      List<String> metadataChanges,
      String mcVersion,
      String beforeMcVersion) {}

  public record CompareResult(
      RevInfo a, RevInfo b, List<CompareDimension> dimensions, long added, long removed, long modified, int chunkCount, boolean identical) {}
}
