package org.worldgit.core.apply;

import java.io.IOException;

/** 單 tick 原子套用在 owner tick 內重新核對時發現 chunk 已變動；尚未寫入任何內容，呼叫端可改用上鎖路徑重試。 */
public final class AtomicChangedException extends IOException {
  public AtomicChangedException() {
    super("受影響 chunk 在預檢後已變動，尚未寫入；請重試");
  }

  public static boolean in(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) if (t instanceof AtomicChangedException) return true;
    return false;
  }
}
