package org.worldgit.platform;

import java.util.UUID;

/** completedBatches 是已通過 adapter durability contract 的批次，不含在途工作。 */
public record ApplyProgress(UUID operation,Phase phase,int completedBatches,int totalBatches,
    int completedSections,int totalSections,boolean cancellationRequested) {
  public enum Phase { LOCKING, APPLYING, LIGHTING, SAVING, COMPLETE, CANCELLED, PARTIAL, FAILED }
}
