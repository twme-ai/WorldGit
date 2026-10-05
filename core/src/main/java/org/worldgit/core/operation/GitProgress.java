package org.worldgit.core.operation;

import org.eclipse.jgit.lib.ProgressMonitor;

/** JGit 工作量為 objects；傳輸 bytes 由傳輸層另行量測，不冒充 byte 數。 */
public final class GitProgress implements ProgressMonitor {
  private final OperationProgress context = OperationProgress.current();
  private String phase = "pack";
  private long completed;
  private Long total;
  public void start(int tasks) {}
  public void beginTask(String title, int totalWork) {
    phase = title; completed = 0; total = totalWork == UNKNOWN ? null : (long)totalWork; emit();
  }
  public void update(int done) { completed += done; emit(); }
  public void endTask() { if (total != null) completed = total; emit(); }
  private void emit() {
    try { if (context != null) context.publish(null, phase, completed, total, OperationProgress.Unit.OBJECT); else OperationProgress.report(null, phase, completed, total, OperationProgress.Unit.OBJECT); }
    catch (java.io.InterruptedIOException ignored) {}
  }
  public boolean isCancelled() { return context != null && context.isCancelled() || OperationProgress.cancelled(); }
  public void showDuration(boolean enabled) {}
}
