package org.worldgit.core.capture;

/** 除錯用完整擷取；只影響目前操作執行緒，不會改變其他維度／伺服器操作。 */
public final class CaptureOptions implements AutoCloseable {
  private static final ThreadLocal<Boolean> FULL = ThreadLocal.withInitial(() -> false);
  private final boolean previous;
  public CaptureOptions(boolean full) { previous=FULL.get(); FULL.set(full || previous); }
  public static boolean full() { return FULL.get(); }
  @Override public void close() { FULL.set(previous); }
}
