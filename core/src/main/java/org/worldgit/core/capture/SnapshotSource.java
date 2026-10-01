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

  /** 正規化版本、tag registry 等政策；改變時重建快照，不能沿用舊 index。 */
  default String normalizationFingerprint() throws IOException {
    return "normalize-v1:" + dataVersion();
  }

  default List<String> warnings() throws IOException {
    return List.of();
  }

  /** Optional.empty 表示不存在或尚未生成完成的 chunk。 */
  CompletionStage<Optional<ChunkSnapshot>> snapshot(ChunkPos pos, IgnoreRules rules);

  default Map<String, byte[]> worldMetadata() throws IOException {
    return Map.of();
  }

  @Override
  default void close() throws IOException {}
}
