package org.worldgit.core.service;

import java.util.*;

/** opt-in 單次 repo 作業的分段計時；巢狀階段為 inclusive，非累加總和。 */
public final class OperationTimings implements AutoCloseable {
  private static final ThreadLocal<OperationTimings> CURRENT = new ThreadLocal<>();
  private final String operation;
  private final long started = System.nanoTime();
  private final Map<String, Long> stages = new LinkedHashMap<>();
  private final OperationTimings previous;

  private OperationTimings(String operation) {
    this.operation = operation;
    previous = CURRENT.get();
    CURRENT.set(this);
  }

  public static OperationTimings start(String operation) {
    return new OperationTimings(operation);
  }

  public static final class Stage implements AutoCloseable {
    private final OperationTimings owner = CURRENT.get();
    private final String name;
    private final long started = System.nanoTime();

    private Stage(String name) {
      this.name = name;
    }

    @Override
    public void close() {
      if (owner != null) owner.record(name, System.nanoTime() - started);
    }
  }

  public static OperationTimings current() {
    return CURRENT.get();
  }

  public synchronized void record(String name, long nanos) {
    stages.merge(name, nanos, Long::sum);
  }

  public static Stage stage(String name) {
    return new Stage(name);
  }

  @Override
  public void close() {
    CURRENT.set(previous);
    if (Boolean.getBoolean("worldgit.profile")) {
      var out =
          new StringJoiner(
              ",",
              "WGPROFILE {\"operation\":\""
                  + operation.replace("\"", "'")
                  + "\",\"total_ms\":"
                  + (System.nanoTime() - started) / 1_000_000.0
                  + ",\"stages_ms\":{",
              "}}");
      stages.forEach((name, ns) -> out.add("\"" + name + "\":" + ns / 1_000_000.0));
      System.getLogger(OperationTimings.class.getName())
          .log(System.Logger.Level.INFO, out.toString());
    }
  }
}
