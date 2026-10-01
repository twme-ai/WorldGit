package org.worldgit.platform;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.worldgit.core.capture.*;
import org.worldgit.core.model.*;
import org.worldgit.core.apply.ApplyPlan;

/** 實作端負責 Paper/Folia region 執行緒、Fabric server 執行緒與 chunk IO 競爭處理。 */
public interface LiveWorld extends SnapshotSource {
  Set<ChunkPos> knownChunks() throws IOException;

  Set<ChunkPos> dirtyChunks();

  /** 實體沒有 unsaved 旗標；來源應回傳含實體的 chunk，每次 capture 重新比對。 */
  Set<ChunkPos> entityChunks();

  CompletionStage<Void> apply(ChunkPatch patch);

  /** 在 owner 執行緒逐 section 套用；實體 remove 必須掃描全維度 UUID（含 passengers）。
   * 不得把線上未載入 chunk 直接寫入 .mca；用 ticket 載入並在 future 完成／失敗前釋放。
   * 跨 region 移除與生成的 barrier 由 ApplyScheduler 提供。
   */
  default CompletionStage<Void> apply(ApplyPlan batch, ApplyBudget budget) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("平台尚未實作 Phase 2 apply"));
  }

  /** 平台在真正的 chunk owner／region scheduler 延到下一 tick；不以 32×32 格網當 Folia region。 */
  default CompletionStage<Void> nextApplyTick(ApplyPlan batch) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("平台尚未實作 apply tick 排程"));
  }

  /** completion 必須表示 heightmap、光照 queue、POI、dirty 與玩家 chunk 封包均已完成。 */
  default CompletionStage<Void> finishApply(Collection<ChunkPos> chunks) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("平台尚未實作 apply 完成 barrier"));
  }

  /** 平台用 EntityScheduler／玩家執行緒取消指定傷害；不得移動玩家或套 Resistance。
   * active=true 持續到同 operation 的 active=false，再延續 duration（預設 10 秒）；
   * 也必須涵蓋操作期間加入範圍的玩家。completion 表示 owner 上已安裝／更新事件政策。 */
  default CompletionStage<Void> protectPlayers(PlayerProtection protection) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("平台尚未實作玩家保護"));
  }

  /** 不含方塊資料的結構化事件，可交給 bossbar／MiniMessage；通知要排到玩家 owner 執行緒。 */
  default void applyProgress(ApplyProgress progress) {}


  /** future 完成前涵蓋玩家方塊／容器、生物、活塞、流體、紅石與插件寫入協調；
   * 平台可實作 tick freeze。close 必須恢復原本狀態。不可在 region thread 阻塞等待別的 owner。 */
  AutoCloseable lockEdits(Collection<ChunkPos> chunks, String reason) throws IOException;

  /** 所有 owner 已提交存檔且 chunk/entity/POI IO 已持久化；不得只表示排入 save queue。 */
  CompletionStage<Void> flush();

  /** 文字通知也須排到玩家 owner；結構化 Phase 2 通知優先使用 applyProgress。 */
  void notifyPlayers(String message);

  @Override
  default Scan scan(ScanIndex previous, boolean full) throws IOException {
    Set<ChunkPos> present = knownChunks();
    var candidates = new HashSet<ChunkPos>();
    if (full) candidates.addAll(present);
    else {
      candidates.addAll(dirtyChunks());
      candidates.addAll(entityChunks());
    }
    return new Scan(present, candidates, Map.of(), 0, System.currentTimeMillis() / 1000);
  }
}
