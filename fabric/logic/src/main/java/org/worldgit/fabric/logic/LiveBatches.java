package org.worldgit.fabric.logic;

import java.util.*;
import org.worldgit.core.apply.ApplyPlan;
import org.worldgit.core.model.ChunkPos;

/** core 的 entity 批次另按 ticket 上限裁切，保留原本 removal／spawn 順序。 */
public final class LiveBatches {
  private LiveBatches() {}

  public static List<ApplyPlan> limitTickets(ApplyPlan batch, int maxChunks) {
    if (maxChunks < 1) throw new IllegalArgumentException("ticket 上限必須大於零");
    if (batch.entities().isEmpty()) return List.of(batch);
    if (!batch.chunks().isEmpty() || !batch.worldMeta().isEmpty())
      throw new IllegalArgumentException("請先用 ApplyPlan.batches 分離實體階段");
    var result = new ArrayList<ApplyPlan>();
    var pending = new ArrayList<ApplyPlan.EntityOp>();
    var tickets = new HashSet<ChunkPos>();
    for (var entity : batch.entities()) {
      var next = new HashSet<>(tickets);
      if (entity.hint() != null) next.add(entity.hint());
      if (entity.targetChunk() != null) next.add(entity.targetChunk());
      if (next.size() > maxChunks) {
        if (pending.isEmpty()) throw new IllegalArgumentException("單一實體移動超過 ticket 上限");
        result.add(part(batch, pending));
        pending.clear(); tickets.clear();
      }
      pending.add(entity);
      if (entity.hint() != null) tickets.add(entity.hint());
      if (entity.targetChunk() != null) tickets.add(entity.targetChunk());
      if (tickets.size() > maxChunks) throw new IllegalArgumentException("單一實體移動超過 ticket 上限");
    }
    if (!pending.isEmpty()) result.add(part(batch, pending));
    return List.copyOf(result);
  }

  private static ApplyPlan part(ApplyPlan batch, List<ApplyPlan.EntityOp> entities) {
    return new ApplyPlan(batch.dimension(), batch.baseTree(), batch.targetTree(), batch.dataVersion(),
        batch.scope(), List.of(), entities, Map.of(), List.of()).withRules(batch.ignoreRules());
  }
}
