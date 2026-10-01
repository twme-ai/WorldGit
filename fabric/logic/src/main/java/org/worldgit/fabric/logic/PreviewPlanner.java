package org.worldgit.fabric.logic;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.DimensionId;
import org.worldgit.protocol.Protocol;

/**
 * 決定送給客戶端的是逐格鬼影（diff）還是區域包圍盒（status）：能力不足或格數超過上限就改送 section
 * 包圍盒（doc 06 §1.1：數萬格以上改畫區域包圍盒）。方塊只展開有變動的 chunk 視窗。
 */
public final class PreviewPlanner {
  public enum Mode {
    EMPTY,
    GHOST,
    OUTLINE
  }

  public record Plan(long previewId, Mode mode, long cells, int outlines, List<byte[]> packets) {
    public Plan {
      packets = List.copyOf(packets);
    }
  }

  private PreviewPlanner() {}

  public static Plan plan(
      long previewId,
      WorldOps ops,
      DimensionId dimension,
      List<String> revisions,
      boolean outlineOnly,
      boolean ghostCapable,
      ServerConfig.Preview limits,
      int minY,
      int maxY)
      throws IOException {
    WorldDiff summary = ops.diff(dimension, revisions, DiffEngine.Detail.SUMMARY, null);
    var counts = summary.counts();
    long total = counts.added() + counts.removed() + counts.modified() + counts.conflict();
    if (summary.sections().isEmpty() && summary.chunks().isEmpty())
      return new Plan(previewId, Mode.EMPTY, 0, 0, Protocol.status(previewId, summary, minY, maxY));
    if (!outlineOnly && ghostCapable && total > 0 && total <= limits.maxGhostCells()) {
      Set<ChunkPos> window = new TreeSet<>();
      summary.sections().forEach(s -> window.add(s.chunk()));
      WorldDiff blocks = ops.diff(dimension, revisions, DiffEngine.Detail.BLOCKS, window);
      return new Plan(previewId, Mode.GHOST, total, 0, Protocol.diff(previewId, blocks));
    }
    var packets = Protocol.status(previewId, summary, minY, maxY);
    return new Plan(previewId, Mode.OUTLINE, total, summary.sections().size() + (int) summary.chunks().stream().filter(c -> summary.sections().stream().noneMatch(s -> s.chunk().equals(c))).count(), packets);
  }
}
