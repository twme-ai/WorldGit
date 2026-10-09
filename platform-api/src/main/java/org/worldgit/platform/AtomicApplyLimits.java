package org.worldgit.platform;
import org.worldgit.core.apply.ApplyPlan;
import org.worldgit.core.apply.LiveApplyVerification;
/** Hard size limits; the platform also requires loaded chunks and a single owner. */
public record AtomicApplyLimits(int chunks,int sections,int entities) {
  public static final AtomicApplyLimits DEFAULT=new AtomicApplyLimits(1,1,8);
  public AtomicApplyLimits {
    if(chunks<0 || chunks>8 || sections<0 || sections>8 || entities<0 || entities>32) throw new IllegalArgumentException("atomic apply limits out of range");
  }
  public boolean eligible(ApplyPlan plan) {
    return !plan.empty() && plan.worldMeta().isEmpty() && !plan.chunks().values().stream().anyMatch(ApplyPlan.ChunkOp::delete)
      && LiveApplyVerification.affected(plan).size()<=chunks && plan.stats().sections()<=sections && plan.entities().size()<=entities;
  }
}
