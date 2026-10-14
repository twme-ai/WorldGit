package org.worldgit.fabric.logic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.worldgit.core.version.ConversionReport;

/** 每個 server runtime 獨立、五分鐘、單次授權；內容 fingerprint 在重試及鎖後重新計算。 */
public final class VersionConfirmationTokens<T> {
  public record Approval(UUID operation, String fingerprint, String actor, long expires) {
    public boolean permits(String content, String requester, long now) {
      return expires > now && actor.equals(requester) && fingerprint.equals(content);
    }
  }
  public record Pending<T>(Approval approval, T retry) {}
  private final Map<String, Pending<T>> pending = new ConcurrentHashMap<>();
  public String issue(UUID operation, String fingerprint, String actor, long now, T retry) {
    pending.entrySet().removeIf(e -> e.getValue().approval().expires() <= now);
    if (pending.size() >= 256) pending.clear();
    String code = UUID.randomUUID().toString().replace("-", "");
    pending.put(code, new Pending<>(new Approval(operation, fingerprint, actor, now + 300_000), retry));
    return code;
  }
  public Pending<T> consume(String code, String actor, long now) {
    var value = pending.get(code);
    if (value == null || !value.approval().actor().equals(actor)) return null;
    if (!pending.remove(code, value) || value.approval().expires() <= now) return null;
    return value;
  }
  public void invalidate(java.util.function.Predicate<Pending<T>> denied) { pending.entrySet().removeIf(e -> denied.test(e.getValue())); }
  public void clear() { pending.clear(); }
  public static String fingerprint(Map<String, ConversionReport> reports, String state) throws IOException {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      hash.update(state.getBytes(StandardCharsets.UTF_8));
      for (var entry : new TreeMap<>(reports).entrySet()) {
        hash.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
        hash.update((byte) 0); hash.update(entry.getValue().bytes());
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException error) { throw new IOException(error); }
  }
}
