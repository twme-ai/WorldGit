package org.worldgit.platform;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.worldgit.core.capture.*;
import org.worldgit.core.model.*;

/** 實作端負責 Paper/Folia region 執行緒、Fabric server 執行緒與 chunk IO 競爭處理。 */
public interface LiveWorld extends SnapshotSource {
  Set<ChunkPos> knownChunks() throws IOException;

  Set<ChunkPos> dirtyChunks();

  /** 實體沒有 unsaved 旗標；來源應回傳含實體的 chunk，每次 capture 重新比對。 */
  Set<ChunkPos> entityChunks();

  CompletionStage<Void> apply(ChunkPatch patch);

  AutoCloseable lockEdits(Collection<ChunkPos> chunks, String reason) throws IOException;

  CompletionStage<Void> flush();

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
