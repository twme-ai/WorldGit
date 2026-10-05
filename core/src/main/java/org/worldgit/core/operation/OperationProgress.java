package org.worldgit.core.operation;

import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.worldgit.core.model.DimensionId;

/** 計算端只更新記憶體；listener 在獨立執行緒節流派送，不在世界／repo 鎖內做 IO。 */
public final class OperationProgress implements AutoCloseable {
  public enum Unit { CHUNK, SECTION, OBJECT, BYTES, COMMIT }
  public record Event(UUID operationId, String operation, DimensionId dimension, String phase,
      long completed, Long total, Unit unit, Long remainingMillis, boolean cancellable, Double ratePerSecond) {}
  private static final ThreadLocal<OperationProgress> CURRENT = new ThreadLocal<>();
  private final UUID id;
  private final String operation;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicReference<Event> pending = new AtomicReference<>();
  private final ScheduledExecutorService dispatcher;
  private final Consumer<Event> listener;
  private final OperationProgress previous;
  private volatile boolean closed;
  private final long started = System.nanoTime();
  private volatile String phaseKey;
  private volatile DimensionId currentDimension;
  private volatile long phaseStarted = started;
  public OperationProgress(String operation, Consumer<Event> listener) {this(UUID.randomUUID(),operation,listener);}
  /** 外部排程先配置 id，工作執行緒再開啟同一個 context。 */
  public OperationProgress(UUID id,String operation,Consumer<Event> listener) {
    this.id=Objects.requireNonNull(id);
    this.operation = operation;
    this.listener = Objects.requireNonNull(listener);
    previous = CURRENT.get(); CURRENT.set(this);
    dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
      var thread = new Thread(r, "worldgit-progress"); thread.setDaemon(true); return thread;
    });
    dispatcher.scheduleWithFixedDelay(this::dispatch, 100, 100, TimeUnit.MILLISECONDS);
  }
  public static OperationProgress current() { return CURRENT.get(); }
  public boolean isCancelled() { return cancelled.get(); }
  public static UUID operationId() { var context = CURRENT.get(); return context == null ? UUID.randomUUID() : context.id; }
  public static boolean cancelled() { var context = CURRENT.get(); return Thread.currentThread().isInterrupted() || context != null && context.cancelled.get(); }
  public UUID id() { return id; }
  public long elapsedMillis() { return (System.nanoTime() - started) / 1_000_000; }
  public OperationResult result(OperationResult.Status status, DimensionId dimension,
      Map<String,Object> summary, List<String> nextSteps, OperationResult.ErrorReport error) {
    return new OperationResult(id, operation, status, dimension, summary, elapsedMillis(), nextSteps, error);
  }
  public void cancel() { cancelled.set(true); }
  public static void check() throws InterruptedIOException {
    var context = CURRENT.get();
    if (Thread.currentThread().isInterrupted() || context != null && context.cancelled.get())
      throw new InterruptedIOException("操作已取消；已套用的內容請依 PARTIAL journal 恢復");
  }
  public static void report(DimensionId dimension, String phase, long completed, Long total, Unit unit)
      throws InterruptedIOException {
    check();
    if (completed < 0 || total != null && (total < 0 || completed > total))
      throw new IllegalArgumentException("progress count");
    var context = CURRENT.get();
    if (context == null) return;
    context.publish(dimension, phase, completed, total, unit);
  }
  public void publish(DimensionId dimension, String phase, long completed, Long total, Unit unit) {
    if (closed) return;
    if (completed < 0 || total != null && (total < 0 || completed > total)) throw new IllegalArgumentException("progress count");
    if (dimension != null) currentDimension = dimension;
    else dimension = currentDimension;
    String key = dimension + "/" + phase + "/" + unit;
    if (!key.equals(phaseKey)) { phaseKey = key; phaseStarted = System.nanoTime(); }
    double seconds = (System.nanoTime() - phaseStarted) / 1_000_000_000.0;
    Double rate = seconds <= 0 || completed == 0 ? null : completed / seconds;
    Long eta = total == null || rate == null ? null : (long)((total - completed) / rate * 1000);
    pending.set(new Event(id, operation, dimension, phase, completed, total, unit, eta, true, rate));
  }

  private void dispatch() {
    var event = pending.getAndSet(null);
    if (event != null) try { listener.accept(event); } catch (RuntimeException ignored) {}
  }
  @Override public void close() {
    if (closed) return; closed = true;
    CURRENT.set(previous);
    dispatcher.execute(this::dispatch);
    dispatcher.shutdown();
    try { if (!dispatcher.awaitTermination(1, TimeUnit.SECONDS)) { dispatcher.shutdownNow(); dispatcher.awaitTermination(1, TimeUnit.SECONDS); } }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
