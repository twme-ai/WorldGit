package org.worldgit.platform;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
import org.worldgit.core.model.ChunkSnapshot;
import org.worldgit.core.version.*;

/** 任務 2／3 的線上轉換契約。先在背景計算完整損失，再在遊戲 owner 顯示／確認，最後預檢套用。 */
public interface VersionedSnapshots {
  MinecraftVersion serverVersion() throws IOException;

  /** 呼叫伺服器既有 DFU；完成時的 NBT 必須依原版 schema 升級，不能只更改版本數字。 */
  NbtUpgrader dataFixer();

  /** Token 綁定來源 snapshot、目標版本、規則 fingerprint、損失清單及操作 id；世界變動後失效。 */
  CompletionStage<Boolean> confirmLoss(
      String operationId, String snapshotId, ConversionReport report);

  default ChunkSnapshot upgrade(ChunkSnapshot source) throws IOException {
    int target = serverVersion().dataVersion();
    if (source.dataVersion() == target) return source;
    if (source.dataVersion() > target) throw new IOException("線上降級須先預覽損失並取得 confirmation token");
    try (var converter = new SnapshotConverter(source.dataVersion(), target, null, dataFixer())) {
      return converter.convert(source);
    }
  }
}
