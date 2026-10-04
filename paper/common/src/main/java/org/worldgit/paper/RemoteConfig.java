package org.worldgit.paper;

import org.bukkit.configuration.ConfigurationSection;
import org.worldgit.platform.remote.RemoteSettings;

/** Bukkit YAML → 平台中立設定，不接受 config.yml 內 PAT/secret。 */
final class RemoteConfig {
  static RemoteSettings from(ConfigurationSection c) {
    if(c.contains("remote") && !c.isConfigurationSection("remote"))throw new IllegalArgumentException("remote 必須是 YAML 區段");
    if(c.contains("remote.webhook") && !c.isConfigurationSection("remote.webhook"))throw new IllegalArgumentException("remote.webhook 必須是 YAML 區段");
    var section=c.getConfigurationSection("remote");
    if(section!=null) {
      var allowed=java.util.Set.of("hub-url","default-name","token-environment","credentials-file","timeout-seconds","fetch-interval-seconds","webhook");
      if(!allowed.containsAll(section.getKeys(false))) throw new IllegalArgumentException("remote 包含未知設定；PAT/secret 必須使用環境或 600 檔案");
      var w=section.getConfigurationSection("webhook");
      if(w!=null && !java.util.Set.of("enabled","bind","port","path","secret-environment","secret-file","max-body-bytes","requests-per-minute").containsAll(w.getKeys(false)))
        throw new IllegalArgumentException("remote.webhook 包含未知設定；secret 不可寫在 config.yml");
    }
    for(String key:java.util.List.of("hub-url","default-name","token-environment","credentials-file","webhook.bind","webhook.path","webhook.secret-environment","webhook.secret-file")) type(c,key,String.class);
    for(String key:java.util.List.of("timeout-seconds","fetch-interval-seconds","webhook.port","webhook.max-body-bytes","webhook.requests-per-minute")) type(c,key,Integer.class);
    type(c,"webhook.enabled",Boolean.class);
    var d=RemoteSettings.defaults();var w=d.webhook();
    return new RemoteSettings(c.getString("remote.hub-url",d.hubUrl()),c.getString("remote.default-name",d.defaultRemote()),
        c.getString("remote.token-environment",d.tokenEnvironment()),c.getString("remote.credentials-file",d.credentialsFile()),
        c.getInt("remote.timeout-seconds",d.timeoutSeconds()),c.getInt("remote.fetch-interval-seconds",d.fetchIntervalSeconds()),
        new RemoteSettings.Webhook(c.getBoolean("remote.webhook.enabled",w.enabled()),c.getString("remote.webhook.bind",w.bind()),
            c.getInt("remote.webhook.port",w.port()),c.getString("remote.webhook.path",w.path()),c.getString("remote.webhook.secret-environment",w.secretEnvironment()),
            c.getString("remote.webhook.secret-file",w.secretFile()),c.getInt("remote.webhook.max-body-bytes",w.maxBodyBytes()),c.getInt("remote.webhook.requests-per-minute",w.requestsPerMinute())));
  }
  private static void type(ConfigurationSection c,String key,Class<?> type) {
    if(c.contains("remote."+key) && !type.isInstance(c.get("remote."+key))) throw new IllegalArgumentException("remote."+key+" 型別無效");
  }
}
