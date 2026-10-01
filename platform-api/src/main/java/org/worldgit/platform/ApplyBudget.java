package org.worldgit.platform;

/** region 的 section/time 為共享軟上限；同時持 ticket 的 chunk 有全域上限。 */
public record ApplyBudget(int sectionsPerTick,long nanosPerTick,int maxLoadedChunks) {
  public static final ApplyBudget DEFAULT=new ApplyBudget(8,5_000_000,24);
  public static final ApplyBudget WITH_PLAYERS=new ApplyBudget(4,5_000_000,16);
  public ApplyBudget {
    if(sectionsPerTick<1 || nanosPerTick<1 || maxLoadedChunks<1) throw new IllegalArgumentException("套用預算必須大於零");
  }
}
