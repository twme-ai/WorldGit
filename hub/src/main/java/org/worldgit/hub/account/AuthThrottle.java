package org.worldgit.hub.account;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.worldgit.hub.config.HubProperties;

/** 認證前先保留嘗試次數；IP、帳號及其組合皆有限流，狀態有容量上限且不驅逐有效鎖定。 */
@Component
public class AuthThrottle {
  public static final class Limited extends RuntimeException {
    private final long retryAfter;
    public Limited(long seconds) { super("認證嘗試過多，請稍後重試"); retryAfter = Math.max(1, seconds); }
    public long retryAfter() { return retryAfter; }
  }
  private static final class State {
    long start, lockedUntil;
    int attempts, failures;
    State(long now) { start = now; }
  }
  private final HubProperties.Auth settings;
  private final Set<String> proxies;
  private final Clock clock;
  private final Map<String, State> states = new HashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  public AuthThrottle(HubProperties props) { this(props, Clock.systemUTC()); }
  public AuthThrottle(HubProperties props, Clock clock) {
    this.settings = props.auth();
    this.clock = clock;
    var p = new HashSet<String>();
    for (String ip : props.security().trustedProxies()) {
      String normalized = literal(ip);
      if (normalized == null) throw new IllegalArgumentException("可信代理必須為 IP 位址");
      p.add(normalized);
    }
    proxies = Set.copyOf(p);
  }

  private static String literal(String ip) {
    if (ip == null || !ip.matches("[0-9a-fA-F:.]+")) return null;
    if (!ip.contains(":")) {
      if (!ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return null;
      for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255) return null;
    }
    try { return InetAddress.getByName(ip).getHostAddress(); }
    catch (Exception e) { return null; }
  }

  public String sourceIp(HttpServletRequest request) {
    String remote = literal(request.getRemoteAddr());
    if (remote == null) remote = "unknown";
    if (!proxies.contains(remote)) return remote;
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null || forwarded.length() > 4096) return remote;
    String[] hops = forwarded.split(",", -1);
    String candidate = remote;
    for (int i = hops.length - 1; i >= 0 && proxies.contains(candidate); i--) {
      String next = literal(hops[i].trim());
      if (next == null) return remote;
      candidate = next;
    }
    return candidate;
  }

  private List<String> keys(String account, HttpServletRequest request) {
    String name = account == null ? "" : account.toLowerCase(Locale.ROOT);
    if (name.length() > 128) name = name.substring(0, 128);
    String ip = sourceIp(request);
    // Bearer token 沒有可宣告的帳號；避免一個固定鍵鎖住全站 token 使用者。
    if (name.equals("bearer")) return List.of("ip:" + ip);
    return List.of("ip:" + ip, "account:" + name, "pair:" + name + ":" + ip);
  }

  private synchronized void reserve(List<String> keys, boolean count) {
    long now = clock.millis(), window = settings.windowSeconds() * 1000;
    states.entrySet().removeIf(e -> now >= e.getValue().lockedUntil && now - e.getValue().start >= window);
    long missing = keys.stream().filter(k -> !states.containsKey(k)).count();
    if (states.size() + missing > settings.maxKeys()) throw new Limited(settings.windowSeconds());
    for (String k : keys) {
      State s = states.computeIfAbsent(k, ignored -> new State(now));
      if (now < s.lockedUntil) throw new Limited((s.lockedUntil - now + 999) / 1000);
      if (s.attempts >= settings.attempts()) throw new Limited((s.start + window - now + 999) / 1000);
    }
    if (count) for (String k : keys) states.get(k).attempts++;
  }

  private synchronized void finish(List<String> keys, boolean success, boolean counted) {
    long now = clock.millis();
    for (String k : keys) {
      State s = states.get(k);
      if (s == null) continue;
      if (success) { s.failures = 0; continue; }
      if (!counted) s.attempts++;
      if (++s.failures >= settings.failures()) s.lockedUntil = now + settings.lockSeconds() * 1000;
    }
  }

  public <T> Optional<T> authenticate(String account, HttpServletRequest request, boolean cheapToken,
      Supplier<Optional<T>> check) {
    var keys = keys(account, request);
    reserve(keys, !cheapToken);
    var result = check.get();
    finish(keys, result.isPresent(), !cheapToken);
    return result;
  }
}
