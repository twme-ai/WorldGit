package org.worldgit.platform.remote;

import java.nio.file.*;
import java.time.Duration;
import org.worldgit.core.remote.RemoteSpec;

/** 平台中立設定；YAML adapter 只提供非秘密欄位。 */
public record RemoteSettings(String hubUrl, String defaultRemote, String tokenEnvironment,
    String credentialsFile, int timeoutSeconds, int fetchIntervalSeconds, Webhook webhook) {
  public record Webhook(boolean enabled, String bind, int port, String path, String secretEnvironment,
      String secretFile, int maxBodyBytes, int requestsPerMinute) {
    public Webhook {
      if (bind == null || bind.isBlank() || port < 1 || port > 65535 || path == null
          || !path.matches("/[A-Za-z0-9/_-]{1,120}") || maxBodyBytes < 1024 || maxBodyBytes > 262144
          || requestsPerMinute < 1 || requestsPerMinute > 6000)
        throw new IllegalArgumentException("remote.webhook 設定無效");
      env(secretEnvironment); file(secretFile);
    }
  }
  public RemoteSettings {
    try { RemoteSpec.validateName(defaultRemote); if (!hubUrl.isEmpty()) HubClient.endpoint(hubUrl); }
    catch (Exception e) { throw new IllegalArgumentException("remote.hub-url/default-name 無效（內容已遮罩）"); }
    env(tokenEnvironment); file(credentialsFile);
    if (timeoutSeconds < 1 || timeoutSeconds > 120 || fetchIntervalSeconds != 0 && (fetchIntervalSeconds < 60 || fetchIntervalSeconds > 604800))
      throw new IllegalArgumentException("remote timeout 必須 1–120 秒；fetch-interval-seconds 為 0 或 60–604800");
    java.util.Objects.requireNonNull(webhook);
  }
  private static void env(String name) {
    if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException("remote 環境變數名稱無效");
  }
  private static void file(String name) {
    if (name == null || !name.matches("[A-Za-z0-9_-]+\\.(yml|secret)")) throw new IllegalArgumentException("remote secret 檔名無效（限插件資料夾直接子檔案）");
  }
  public Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }
  public static RemoteSettings defaults() {
    return new RemoteSettings("", "origin", "WGIT_TOKEN", "credentials.yml", 30, 0,
        new Webhook(false,"127.0.0.1",25731,"/worldgit/webhook","WGIT_WEBHOOK_SECRET","webhook.secret",65536,60));
  }
}
