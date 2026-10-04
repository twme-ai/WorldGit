package org.worldgit.fabric.logic;

import java.io.IOException;
import org.worldgit.platform.remote.RemoteSettings;

/** 僅非秘密欄位；所有 parser 例外遮罩輸入。 */
final class RemoteConfig {
  static RemoteSettings parse(YamlFile r) throws IOException {
    var d=RemoteSettings.defaults();var w=r.section("webhook");var v=d.webhook();
    try {
      var hook=new RemoteSettings.Webhook(w.bool("enabled",false),w.string("bind",v.bind(),null),
          w.integer("port",25761,1,65535),w.string("path",v.path(),null),
          w.string("secret-environment",v.secretEnvironment(),null),w.string("secret-file",v.secretFile(),null),
          w.integer("max-body-bytes",v.maxBodyBytes(),1024,262144),w.integer("requests-per-minute",v.requestsPerMinute(),1,6000));
      w.rejectUnknown();
      var settings=new RemoteSettings(r.string("hub-url","",null),r.string("default-name",d.defaultRemote(),null),
          r.string("token-environment",d.tokenEnvironment(),null),r.string("credentials-file",d.credentialsFile(),null),
          r.integer("timeout-seconds",30,1,120),r.integer("fetch-interval-seconds",0,0,604800),hook);
      r.rejectUnknown();return settings;
    } catch(IllegalArgumentException e) {throw new IOException("remote 設定無效（內容已遮罩）");}
  }
}
