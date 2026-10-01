package org.worldgit.hub.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Hub 設定（application.yml 的 worldgit.hub.*，也可用環境變數 WORLDGIT_HUB_* 覆寫）。 */
@ConfigurationProperties("worldgit.hub")
public record HubProperties(
    Path dataDir,
    Boolean autoCreateWorlds,
    Bootstrap bootstrap,
    Git git,
    Assets assets,
    Security security,
    Limits limits,
    Auth auth,
    Tokens tokens,
    List<McVersion> minecraftVersions) {

  public HubProperties {
    if (dataDir == null) dataDir = Path.of("data");
    if (autoCreateWorlds == null) autoCreateWorlds = true;
    if (bootstrap == null) bootstrap = new Bootstrap(null, null, null);
    if (git == null) git = new Git(null, null, null);
    if (security == null) security = new Security(null, null);
    if (limits == null) limits = new Limits(null, null, null, null, null);
    if (auth == null) auth = new Auth(null, null, null, null, null);
    if (tokens == null) tokens = new Tokens(null);
    if (assets == null) assets = new Assets(null, null);
    if (minecraftVersions == null) minecraftVersions = List.of();
  }

  /** 首次啟動建立的本機管理員；adminToken 非空時固定該 token（CI／冒煙測試用）。 */
  public record Bootstrap(String adminUser, String adminPassword, String adminToken) {}

  public record Git(Long maxPackBytes, Long packLimitBytes, Long ownerQuotaBytes) {
    public Git {
      if (maxPackBytes == null) maxPackBytes = 95_000_000L;
      if (packLimitBytes == null) packLimitBytes = 95_000_000L;
      if (ownerQuotaBytes == null) ownerQuotaBytes = 10L << 30;
      if (maxPackBytes <= 0 || packLimitBytes <= 0 || ownerQuotaBytes <= 0)
        throw new IllegalArgumentException("Git 限制必須大於零");
    }
  }

  public record Security(Boolean hsts, List<String> trustedProxies) {
    public Security {
      if (hsts == null) hsts = false;
      if (trustedProxies == null) trustedProxies = List.of();
      trustedProxies = List.copyOf(trustedProxies);
    }
  }

  public record Limits(Long decodedBytes, Long readBytes, Long nodes, Long objects, Long work) {
    public Limits {
      if (decodedBytes == null) decodedBytes = 256L << 20;
      if (readBytes == null) readBytes = 128L << 20;
      if (nodes == null) nodes = 1_000_000L;
      if (objects == null) objects = 100_000L;
      if (work == null) work = 50_000_000L;
      if (decodedBytes <= 0 || readBytes <= 0 || nodes <= 0 || objects <= 0 || work <= 0)
        throw new IllegalArgumentException("解析預算必須大於零");
    }
  }

  public record Auth(Integer attempts, Integer failures, Long windowSeconds, Long lockSeconds, Integer maxKeys) {
    public Auth {
      if (attempts == null) attempts = 30;
      if (failures == null) failures = 5;
      if (windowSeconds == null) windowSeconds = 60L;
      if (lockSeconds == null) lockSeconds = 300L;
      if (maxKeys == null) maxKeys = 10_000;
      if (attempts <= 0 || failures <= 0 || windowSeconds <= 0 || lockSeconds <= 0 || maxKeys <= 0)
        throw new IllegalArgumentException("認證限流設定必須大於零");
    }
  }

  public record Tokens(Integer patDays) {
    public Tokens {
      if (patDays == null) patDays = 90;
      if (patDays <= 0 || patDays > 3650) throw new IllegalArgumentException("PAT 預設期限必須為 1–3650 天");
    }
  }

  /** 資源管線：sourceDir 指向已解開的 client jar 目錄（assets/ data/）；空白則自 Mojang 下載。 */
  public record Assets(Path sourceDir, String manifestUrl) {
    public Assets {
      if (manifestUrl == null || manifestUrl.isBlank())
        manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    }
  }

  /** DataVersion → Minecraft 版本；commit 的 DataVersion 選擇小於等於它的最大一項。 */
  public record McVersion(String id, int dataVersion) {}
}
