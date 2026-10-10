package org.worldgit.core.apply;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.ChunkPos;

/**
 * 上鎖後的權威擷取發現受影響範圍有「未提交的新變動」。尚未寫入任何內容。
 *
 * <p>帶結構化資料（chunk 座標與變動種類），讓 Paper／Fabric 以各自語系呈現；{@link #getMessage()} 是繁體中文預設。
 */
public final class PreflightChangedException extends IOException {
  public static final int LIMIT = 8;

  /** 一個 chunk 的變動摘要：方塊（含 block entity）數、biome sample 數、依實體類型統計。 */
  public record ChunkChange(
      ChunkPos chunk, long blocks, long biomes, SortedMap<String, Integer> entities) {
    public ChunkChange {
      entities = Collections.unmodifiableSortedMap(new TreeMap<>(entities));
    }

    public int entityCount() {
      return entities.values().stream().mapToInt(Integer::intValue).sum();
    }
  }

  private final List<ChunkChange> chunks;
  private final int total;
  private final int attempts;

  public PreflightChangedException(List<ChunkChange> chunks, int total, int attempts) {
    super(message(chunks, total, attempts));
    this.chunks = List.copyOf(chunks);
    this.total = total;
    this.attempts = attempts;
  }

  /** 顯示的 chunk 項目（最多 {@link #LIMIT}）。 */
  public List<ChunkChange> chunks() {
    return chunks;
  }

  /** 變動的 chunk 總數（可能多於列出的項目）。 */
  public int total() {
    return total;
  }

  public int attempts() {
    return attempts;
  }

  public static PreflightChangedException of(WorldDiff diff, int attempts) {
    var all = summarize(diff);
    return new PreflightChangedException(
        all.size() > LIMIT ? all.subList(0, LIMIT) : all, all.size(), attempts);
  }

  public static List<ChunkChange> summarize(WorldDiff diff) {
    var blocks = new TreeMap<ChunkPos, long[]>();
    var entities = new TreeMap<ChunkPos, SortedMap<String, Integer>>();
    for (var s : diff.sections()) {
      var c = s.counts();
      blocks.computeIfAbsent(s.chunk(), k -> new long[2])[0] +=
          c.added() + c.removed() + c.modified() + c.conflict();
    }
    for (var b : diff.biomes()) blocks.computeIfAbsent(b.chunk(), k -> new long[2])[1] += b.count();
    for (var e : diff.entities()) {
      var chunk = e.afterChunk() != null ? e.afterChunk() : e.beforeChunk();
      var snapshot = e.after() != null ? e.after() : e.before();
      String id = snapshot == null ? "?" : snapshot.data().string("id");
      if (id == null || id.isEmpty()) id = "?";
      entities.computeIfAbsent(chunk, k -> new TreeMap<>()).merge(id, 1, Integer::sum);
    }
    var positions = new TreeSet<ChunkPos>(blocks.keySet());
    positions.addAll(entities.keySet());
    var result = new ArrayList<ChunkChange>();
    for (var pos : positions) {
      long[] n = blocks.getOrDefault(pos, new long[2]);
      result.add(new ChunkChange(pos, n[0], n[1], entities.getOrDefault(pos, new TreeMap<>())));
    }
    return result;
  }

  public static String describe(ChunkChange change) {
    var parts = new ArrayList<String>();
    if (change.blocks() > 0) parts.add("方塊 " + change.blocks());
    if (change.biomes() > 0) parts.add("biome " + change.biomes());
    if (!change.entities().isEmpty()) {
      var types = new ArrayList<String>();
      change.entities().forEach((type, n) -> types.add(type + "×" + n));
      parts.add("實體 " + String.join("、", types));
    }
    if (parts.isEmpty()) parts.add("其他資料");
    return "[" + change.chunk().x() + "," + change.chunk().z() + "] " + String.join("，", parts);
  }

  private static String message(List<ChunkChange> chunks, int total, int attempts) {
    var text = new StringBuilder("預檢到上鎖之間，受影響範圍出現未提交的變動，為避免覆蓋已拒絕套用，尚未寫入任何內容");
    if (attempts > 1) text.append("（已自動重試 ").append(attempts - 1).append(" 次）");
    text.append("。變動共 ").append(total).append(" 個 chunk：");
    for (int i = 0; i < chunks.size(); i++)
      text.append(i == 0 ? "" : "；").append(describe(chunks.get(i)));
    if (total > chunks.size()) text.append("；另 ").append(total - chunks.size()).append(" 個 chunk 未列出");
    text.append("。下一步：/wg status 查看，確認後 commit、stash push、switch --stash 或加 --force 再試。");
    return text.toString();
  }
}
