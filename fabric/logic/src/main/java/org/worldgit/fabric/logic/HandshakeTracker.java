package org.worldgit.fabric.logic;

import java.util.*;
import org.worldgit.protocol.Protocol;

/**
 * 伺服端握手狀態機（以 tick 計時，沒有執行緒或 Minecraft 相依）。重送 hello 的排程與逾時是 05 實驗
 * 的結論：玩家剛加入時 channel 可能尚未註冊，所以不能只送一次；沒回覆就判定沒有模組，不送預覽。
 */
public final class HandshakeTracker {
  public enum State {
    PENDING,
    READY,
    NO_MOD,
    REJECTED
  }

  public static final int[] RETRY_TICKS = {0, 20, 60, 100};
  public static final int DEADLINE_TICKS = 120;
  public static final Set<String> REQUIRED = Set.of("outline");

  /** 要求呼叫端嘗試送出 hello（呼叫端可先檢查對方是否註冊了 channel）。 */
  public record Send(UUID player, long nonce, int attempt) {}

  private static final class Session {
    long nonce;
    long startTick;
    int attempts;
    State state = State.PENDING;
    Set<String> capabilities = Set.of();
    String reason = "";
  }

  private final Map<UUID, Session> sessions = new HashMap<>();
  private final java.util.function.LongSupplier nonces;

  public HandshakeTracker(java.util.function.LongSupplier nonces) {
    this.nonces = nonces;
  }

  public HandshakeTracker() {
    this(new java.security.SecureRandom()::nextLong);
  }

  public synchronized void join(UUID player, long tick) {
    var s = new Session();
    s.nonce = nonces.getAsLong();
    s.startTick = tick;
    sessions.put(player, s);
  }

  public synchronized void leave(UUID player) {
    sessions.remove(player);
  }

  /** 每個 tick 呼叫一次；回傳這個 tick 該送的 hello。逾時者轉成 NO_MOD（之後仍可被遲來的回覆救回）。 */
  public synchronized List<Send> tick(long tick) {
    var sends = new ArrayList<Send>();
    for (var e : sessions.entrySet()) {
      var s = e.getValue();
      if (s.state != State.PENDING) continue;
      long elapsed = tick - s.startTick;
      if (s.attempts < RETRY_TICKS.length && elapsed >= RETRY_TICKS[s.attempts]) {
        sends.add(new Send(e.getKey(), s.nonce, s.attempts));
        s.attempts++;
      }
      if (elapsed >= DEADLINE_TICKS) {
        s.state = State.NO_MOD;
        s.reason = "沒有在 " + DEADLINE_TICKS + " ticks 內回覆 hello";
      }
    }
    return sends;
  }

  /** 驗證客戶端回覆：版本、nonce、必要能力。成功回傳 true。 */
  public synchronized boolean reply(UUID player, Protocol.Hello hello) {
    var s = sessions.get(player);
    if (s == null) return false;
    if (hello.version() != Protocol.VERSION) {
      s.state = State.REJECTED;
      s.reason = "協定版本 " + hello.version() + " 不相容（需要 " + Protocol.VERSION + "）";
      return false;
    }
    if (hello.nonce() != s.nonce) {
      s.reason = "nonce 不符";
      return false;
    }
    if (!hello.capabilities().containsAll(REQUIRED)) {
      s.state = State.REJECTED;
      s.reason = "缺少必要能力：" + REQUIRED;
      return false;
    }
    s.state = State.READY;
    s.capabilities = Set.copyOf(hello.capabilities());
    return true;
  }

  public synchronized State state(UUID player) {
    var s = sessions.get(player);
    return s == null ? State.NO_MOD : s.state;
  }

  public synchronized String reason(UUID player) {
    var s = sessions.get(player);
    return s == null ? "" : s.reason;
  }

  public synchronized boolean ready(UUID player) {
    return state(player) == State.READY;
  }

  public synchronized boolean supports(UUID player, String capability) {
    var s = sessions.get(player);
    return s != null && s.state == State.READY && s.capabilities.contains(capability);
  }
}
