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
}
