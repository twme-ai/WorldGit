package org.worldgit.core.operation;

import java.time.Instant;
import java.util.*;
import org.worldgit.core.model.DimensionId;

/** 四端共用的終止結果；summary 必須只包含可公開的結果資料。 */
public record OperationResult(UUID operationId, String operation, Status status,
    DimensionId dimension, Map<String, Object> summary, long elapsedMillis,
    List<String> nextSteps, ErrorReport error) {
  public enum Status { SUCCESS, NO_OP, PARTIAL, FAILED, CANCELLED }
  public OperationResult {
    summary = Collections.unmodifiableMap(new LinkedHashMap<>(summary));
    nextSteps = List.copyOf(nextSteps);
    if (elapsedMillis < 0) throw new IllegalArgumentException("elapsedMillis");
  }
  public int exitCode() {
    return switch (status) { case SUCCESS, NO_OP -> 0; case PARTIAL -> 2;
      case FAILED -> 1; case CANCELLED -> 130; };
  }
  public record ErrorReport(String code, UUID operationId, String operation,
      String dimension, String worldGitVersion, String minecraftVersion, String platformVersion, String utc,
      String message, String text) {
    public static ErrorReport create(String code, UUID id, String operation, DimensionId dimension,
        String version, String platform, String message) {
      return create(code, id, operation, dimension, version, platform, message, List.of());
    }
    public static ErrorReport create(String code, UUID id, String operation, DimensionId dimension,
        String version, String platform, String message, Collection<String> secrets) {
      return create(code,id,operation,dimension,version,"unknown",platform,message,secrets);
    }
    public static ErrorReport create(String code, UUID id, String operation, DimensionId dimension,
        String version, String minecraft, String platform, String message, Collection<String> secrets) {
      for (String secret : secrets) if (secret != null && !secret.isEmpty()) {
        if (message != null) message = message.replace(secret, "[REDACTED]");
        if (code != null) code = code.replace(secret, "[REDACTED]");
        if (operation != null) operation = operation.replace(secret, "[REDACTED]");
        if (version != null) version = version.replace(secret, "[REDACTED]");
        if (minecraft != null) minecraft = minecraft.replace(secret, "[REDACTED]");
        if (platform != null) platform = platform.replace(secret, "[REDACTED]");
      }
      String safe = redact(message);
      String timestamp = Instant.now().toString();
      String text = "code=" + redact(code) + " operation=" + redact(operation) + " id=" + id
          + " dimension=" + (dimension == null ? "all" : dimension.value())
          + " WorldGit=" + redact(version) + " Minecraft=" + redact(minecraft) + " platform=" + redact(platform)
          + " UTC=" + timestamp + "\n" + safe;
      return new ErrorReport(redact(code), id, redact(operation),
          dimension == null ? "all" : dimension.value(), redact(version), redact(minecraft), redact(platform),
          timestamp, safe, bounded(text));
    }
  }
  public static String redact(String value) {
    if (value == null) return "";
    String safe = value.replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+(?::[^\\s/@]*)?@", "$1[REDACTED]@")
        .replaceAll("(?i)\\b(?:github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9_]+)", "[REDACTED]")
        .replaceAll("(?i)(authorization\\s*[:=]\\s*)(?:bearer|basic)?\\s*[^\\r\\n,;]+", "$1[REDACTED]")
        .replaceAll("(?i)((?:[\\w.-]*(?:token|secret|password|pat)|WGIT_TOKEN)\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]");
    return bounded(safe);
  }
  private static String bounded(String value) {
    return value.length() <= 8192 ? value : value.substring(0, 8170) + "…[truncated]";
  }
}
