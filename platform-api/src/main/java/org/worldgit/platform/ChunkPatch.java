package org.worldgit.platform;

import java.util.*;
import org.worldgit.core.model.*;

/** Phase 2 的套用載體；expectedChunkTree 支援樂觀一致性檢查，null 表示無先決條件。 */
public record ChunkPatch(
    ChunkPos chunk, String expectedChunkTree, ChunkSnapshot replacement, boolean delete) {
  public ChunkPatch {
    Objects.requireNonNull(chunk);
    if (delete == (replacement != null))
      throw new IllegalArgumentException("delete 與 replacement 必須擇一");
  }
}
