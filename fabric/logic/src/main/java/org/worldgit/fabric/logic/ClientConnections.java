package org.worldgit.fabric.logic;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** JOIN／tick 由客戶端執行緒呼叫；DISCONNECT 可能由 Netty 執行緒送達。 */
public final class ClientConnections<T> {
  private final AtomicReference<T> disconnected = new AtomicReference<>();
  private T current;

  public void join(T handler) {
    current = Objects.requireNonNull(handler);
    disconnected.set(null);
  }

  public void disconnected(T handler) {
    disconnected.set(handler);
  }

  /** 在客戶端 tick 消費斷線事件，並偵測畫面退出；舊 handler 不影響新連線。 */
  public boolean shouldReset(T actual) {
    var event = disconnected.getAndSet(null);
    if (current != null && (event == current || actual != current)) {
      current = null;
      return true;
    }
    return false;
  }
}
