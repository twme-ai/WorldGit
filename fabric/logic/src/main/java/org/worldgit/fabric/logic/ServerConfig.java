package org.worldgit.fabric.logic;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import org.worldgit.core.anvil.RegionFile;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.protocol.Protocol;

/**
 * Fabric 伺服端（專用伺服器與單人世界的整合伺服器）設定，YAML。
 *
 * <p>與 CLI 共用的世界本機設定（調色盤、實體容許距離）仍在 {@code .worldgit/<world>/worldgit.yml}，
 * 這裡只放模組自己的行為。
 */
public record ServerConfig(
    String locale,
    String defaultTemplate,
    WorldGitConfig.Track track,
    int writePermissionLevel,
    int readPermissionLevel,
    AutoCommit autoCommit,
    Identity identity,
    Preview preview, org.worldgit.platform.remote.RemoteSettings remote,
    Aliases aliases, Feedback feedback, int ignorePermissionLevel, org.worldgit.platform.AtomicApplyLimits atomicApply) {
  public ServerConfig(String locale, String template, WorldGitConfig.Track track, int write, int read,
      AutoCommit auto, Identity identity, Preview preview) {
    this(locale,template,track,write,read,auto,identity,preview,org.worldgit.platform.remote.RemoteSettings.defaults(),
        new Aliases(true,true),Feedback.defaults(),2,org.worldgit.platform.AtomicApplyLimits.DEFAULT);
  }

  /** 指令別名：wg 與 worldgit 永遠存在；wgit／git 可停用。已有其他模組的 /git 時一律不覆蓋。 */
  public record Aliases(boolean wgit, boolean git) {}

  /** 長操作的進度與終態呈現。terminalSeconds 是完成後在 BossBar／HUD 保留終態的秒數。 */
  public record Feedback(boolean bossbar, boolean hud, int terminalSeconds, int consoleIntervalSeconds, boolean autoNotify) {
    public static Feedback defaults() { return new Feedback(true, true, 3, 1, true); }
  }
  public record AutoCommit(
      boolean onLogout, boolean onStop, int intervalMinutes, int minChangedChunks) {}

  public record Identity(String serverName, String serverEmail, String playerEmailDomain) {}

  public record Preview(int maxGhostCells, int packetsPerTick) {}

  public static final String FILE_NAME = "worldgit-server.yml";

  public static ServerConfig defaults() {
    try {
      return parse("", "<defaults>");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static ServerConfig parse(String text, String source) throws IOException {
    var root = YamlFile.parse(text, source);
    String locale = org.worldgit.i18n.MessageCatalog.normalizeLocale(root.string("locale", "en_us", null));
    String template = root.string("default-template", "creative", Set.of("creative", "survival"));
    String trackText = root.string("track", "all", Set.of("all", "modified-only"));
    int write = root.integer("permission-level", 2, 0, 4);
    int read = root.integer("read-permission-level", 0, 0, 4);
    var auto = root.section("auto-commit");
    var autoCommit =
        new AutoCommit(
            auto.bool("on-logout", true),
            auto.bool("on-stop", true),
            auto.integer("interval-minutes", 10, 0, 24 * 60),
            auto.integer("min-changed-chunks", 1, 1, 1_000_000));
    auto.rejectUnknown();
    var id = root.section("identity");
    var identity =
        new Identity(
            identityText(id.string("server-name", "WorldGit Server", null), source),
            identityText(id.string("server-email", "worldgit@localhost", null), source),
            identityText(id.string("player-email-domain", "players.worldgit.local", null), source));
    id.rejectUnknown();
    var pv = root.section("preview");
    var preview =
        new Preview(
            pv.integer("max-ghost-cells", Protocol.MAX_ENTRIES, 1, Protocol.MAX_ENTRIES),
            pv.integer("packets-per-tick", 4, 1, 64));
    pv.rejectUnknown();
    var remote = RemoteConfig.parse(root.section("remote"));
    var al = root.section("aliases");
    var aliases = new Aliases(al.bool("wgit", true), al.bool("git", true));
    al.rejectUnknown();
    var fb = root.section("feedback");
    var feedback = new Feedback(fb.bool("bossbar", true), fb.bool("hud", true), fb.integer("terminal-seconds", 3, 1, 60),
        fb.integer("console-interval-seconds", 1, 1, 60), fb.bool("auto-notify", true));
    fb.rejectUnknown();
    int ignoreLevel = root.integer("ignore-permission-level", 2, 0, 4);
    var apply=root.section("apply");
    var atomic=new org.worldgit.platform.AtomicApplyLimits(apply.integer("atomic-max-chunks",1,0,8),apply.integer("atomic-max-sections",1,0,8),apply.integer("atomic-max-entities",8,0,32));
    apply.rejectUnknown();
    root.rejectUnknown();
    return new ServerConfig(
        locale,
        template,
        trackText.equals("all") ? WorldGitConfig.Track.ALL : WorldGitConfig.Track.MODIFIED_ONLY,
        write,
        read,
        autoCommit,
        identity,
        preview, remote, aliases, feedback, ignoreLevel, atomic);
  }

  private static String identityText(String s, String source) throws IOException {
    if (s.isBlank() || s.matches("(?s).*[\\r\\n<>].*"))
      throw new IOException(source + "：identity 欄位不可空白或含 < > 換行");
    return s;
  }

  /** 讀取設定；檔案不存在時寫入附說明的預設檔，讓管理員知道有哪些選項。 */
  public static ServerConfig load(Path dir) throws IOException {
    Path file = dir.resolve(FILE_NAME);
    if (!Files.exists(file)) {
      Files.createDirectories(dir);
      RegionFile.atomicWrite(file, DEFAULT_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return parse(Files.readString(file), file.toString());
  }

  public static final String DEFAULT_TEXT =
      """
      # WorldGit Fabric 伺服端設定（單人世界與專用伺服器共用）
      # 調色盤與實體容許距離在世界的 .worldgit/<world>/worldgit.yml（與 wgit CLI 共用）。

      # 伺服器主控台訊息與寫進 git 歷史的自動 commit 訊息所用的語言（玩家看到的訊息依各自的客戶端語言）。
      # 內建 en_us、zh_tw；可在 config/worldgit/lang/<locale>.yml 覆寫部分訊息。
      locale: en_us

      # Loaded chunks on one owner: atomic write and verification copy in one tick.
      apply:
        atomic-max-chunks: 1
        atomic-max-sections: 1
        atomic-max-entities: 8

      # /wg init 沒指定 --template 時使用的 .wgignore 範本：creative | survival
      default-template: creative
      # 新 repo 的追蹤範圍：all | modified-only（目前 modified-only 只記錄設定）
      track: all

      # 需要的 op 等級（0-4）。單人世界的擁有者一律允許。
      permission-level: 2        # init / commit / restore / switch / stash / reset / merge / resolve / cancel
      read-permission-level: 0   # status / log / diff
      ignore-permission-level: 2 # /wg ignore 與 .wgignore 編輯畫面（等同 worldgit.command.ignore）；單人世界擁有者一律允許

      # 指令別名：wg、worldgit 永遠可用；wgit、git 可關閉。若已有其他模組註冊 /git，WorldGit 會跳過 /git 並在日誌提示一次。
      aliases:
        wgit: true
        git: true

      # 長操作進度與終態（BossBar 給執行者與有權限的觀察者，HUD 給安裝模組的客戶端，console 節流文字行）
      feedback:
        bossbar: true
        hud: true
        terminal-seconds: 3          # 完成後在 BossBar／HUD 顯示終態的秒數
        console-interval-seconds: 1  # console 進度行最短間隔
        auto-notify: true            # 自動 commit（定時／登出／停服）完成通知給有權限的玩家

      auto-commit:
        on-logout: true          # 專用伺服器：玩家登出時，若他本次改過 chunk
        on-stop: true            # 伺服器關閉、離開單人世界時
        interval-minutes: 10     # 定時；0 = 關閉
        min-changed-chunks: 1    # 定時 commit 至少要有幾個候選 chunk

      identity:
        server-name: WorldGit Server
        server-email: worldgit@localhost
        player-email-domain: players.worldgit.local   # 玩家 email = <uuid>@此網域

      preview:
        max-ghost-cells: 100000  # 超過時改送區域包圍盒（上限 100000）
        packets-per-tick: 4      # 每個 tick 對單一玩家最多送幾個預覽封包
      remote:
        hub-url: ""
        default-name: origin
        token-environment: WGIT_TOKEN
        credentials-file: credentials.yml  # config 直接子檔案，普通檔且 chmod 600；不放世界
        timeout-seconds: 30
        fetch-interval-seconds: 0          # 0 關閉；啟用至少 60 秒，永不自動套用
        webhook:                           # 單人世界一律不開 port
          enabled: false
          bind: 127.0.0.1
          port: 25761
          path: /worldgit/webhook
          secret-environment: WGIT_WEBHOOK_SECRET
          secret-file: webhook.secret
          max-body-bytes: 65536
          requests-per-minute: 60
      """;
}
