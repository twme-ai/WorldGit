package org.worldgit.cli;

import java.lang.reflect.*;
import org.worldgit.core.operation.OperationProgress;

/** HotSpot 的可選 INT handler；沒有此 API 的 JVM 沿用原始訊號行為。 */
final class CancelSignal implements AutoCloseable {
  private final Object signal, previous;
  private final Method handle;
  private CancelSignal(Object signal, Object previous, Method handle) {
    this.signal = signal; this.previous = previous; this.handle = handle;
  }
  static CancelSignal install(OperationProgress progress) {
    try {
      Class<?> signalType = Class.forName("sun.misc.Signal"), handlerType = Class.forName("sun.misc.SignalHandler");
      Object signal = signalType.getConstructor(String.class).newInstance("INT");
      Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType},
          (proxy, method, args) -> { if (method.getName().equals("handle")) progress.cancel(); return null; });
      Method handle = signalType.getMethod("handle", signalType, handlerType);
      return new CancelSignal(signal, handle.invoke(null, signal, handler), handle);
    } catch (ReflectiveOperationException | IllegalArgumentException ex) { return new CancelSignal(null, null, null); }
  }
  @Override public void close() {
    if (handle != null) try { handle.invoke(null, signal, previous); } catch (ReflectiveOperationException ignored) {}
  }
}
