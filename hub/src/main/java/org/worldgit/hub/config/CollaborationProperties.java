package org.worldgit.hub.config;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Phase 4 的部署政策；第三方登入、自助註冊預設全部關閉。 */
@ConfigurationProperties("worldgit.hub.collaboration")
public record CollaborationProperties(Registration registration, Map<String, OAuth> oauth, Downloads downloads, Webhooks webhooks, Duration mergeLockTimeout) {
  public CollaborationProperties {
    if (registration == null) registration = new Registration(false, "http://localhost:8080", "worldgit@localhost");
    if (oauth == null) oauth = Map.of(); else oauth = Map.copyOf(oauth);
    if (downloads == null) downloads = new Downloads(536870912L, 300L, 2);
    if (webhooks == null) webhooks = new Webhooks(List.of(), 5, 30L);
    if (mergeLockTimeout == null) mergeLockTimeout = Duration.ofSeconds(10);
    if (mergeLockTimeout.compareTo(Duration.ofMillis(1)) < 0 || mergeLockTimeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("合併鎖等待時間需為 1ms–30s");
  }
  public record Registration(boolean enabled, String publicUrl, String from) {}
  /** 可覆寫端點供本機 mock；正常部署使用下面三種 provider 的預設端點。 */
  public record OAuth(boolean enabled, String clientId, String clientSecret, String authorizationUri,
      String tokenUri, String userInfoUri, String userNameAttribute, List<String> scopes) {}
  public record Downloads(long maxBytes, long maxSeconds, int concurrency, HubProperties.Limits limits) {
    public Downloads(long maxBytes,long maxSeconds,int concurrency){this(maxBytes,maxSeconds,concurrency,null);}
    public Downloads { if(limits==null)limits=new HubProperties.Limits(512L<<20,512L<<20,20_000_000L,1_000_000L,200_000_000L); if (maxBytes < 1 || maxBytes > (2L<<30) || maxSeconds < 1 || maxSeconds > 900 || concurrency < 1 || concurrency > 16) throw new IllegalArgumentException("下載預算無效"); }
  }
  public record Webhooks(List<String> allowedHosts, int attempts, long retrySeconds) {
    public Webhooks {
      if (allowedHosts == null) allowedHosts = List.of(); else allowedHosts = List.copyOf(allowedHosts);
      if (attempts < 1 || attempts > 10 || retrySeconds < 1 || retrySeconds > 3600) throw new IllegalArgumentException("webhook 重試設定無效");
    }
  }
}
