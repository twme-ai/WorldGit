package org.worldgit.core.capture;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.*;

/** core 不依賴 platform-api。線上 LiveWorld 透過此來源送 dirty chunk，快照在來源擁有的執行緒複製。 */
public interface SnapshotSource extends AutoCloseable {
  record Scan(
      Set<ChunkPos> present,
      Set<ChunkPos> candidates,
      Map<ChunkPos, ScanIndex.Stamp> stamps,
      int payloadsRead,
      long scannedAt) {
    public Scan {
      present = Set.copyOf(present);
      candidates = Set.copyOf(candidates);
      stamps = Map.copyOf(stamps);
    }
  }

  DimensionId dimension();

  int dataVersion() throws IOException;

  Scan scan(ScanIndex previous, boolean full) throws IOException;

  /** 僅在完整快照與索引成功持久化後推進來源的 capture 游標；不清除待 commit 變動。 */
  default void indexSaved() {}

  /** 正規化版本、tag registry 等政策；改變時重建快照，不能沿用舊 index。 */
  default String normalizationFingerprint() throws IOException {
    return "normalize-v1:" + dataVersion();
  }

  default List<String> warnings() throws IOException {
    return List.of();
  }

  /** 完整、持久的「曾被編輯」集合；unknown 為 empty Optional，不可用當次 dirty 集合代替。 */
  default Optional<Set<ChunkPos>> modifiedChunks() throws IOException {
    return Optional.empty();
  }

  /** Optional.empty 表示不存在或尚未生成完成的 chunk。 */
  CompletionStage<Optional<ChunkSnapshot>> snapshot(ChunkPos pos, IgnoreRules rules);

  /** 來源自行限制 owner 複製預算；core 只保留此數量的在途結果，物件寫入仍單執行緒。 */
  default int snapshotWindow() { return 1; }

  /** 可在不正規化地形的情況下普查未擷取 chunk 的原始 UUID（包含 passengers／忽略實體）。 */
  default boolean entityCensusAvailable() { return false; }
  default Set<UUID> unchangedEntityIds(Set<ChunkPos> captured) throws IOException {
    throw new UnsupportedOperationException("來源沒有完整的 entity census");
  }

  default Map<String, byte[]> worldMetadata() throws IOException {
    return Map.of();
  }

  @Override
  default void close() throws IOException {}
}
