package org.worldgit.fabric.logic;

/**
 * 何時自動 commit：定時（有足夠候選才做，避免只為底噪建立 commit；doc 04 §5）、登出（專用伺服器，且
 * 該玩家本次改過 chunk）、關閉／離開世界。單人世界離開世界就是伺服器關閉，登出不重複觸發。
 */
public final class AutoCommitPolicy {
  private final ServerConfig.AutoCommit config;
  private long lastRunMillis;

  public AutoCommitPolicy(ServerConfig.AutoCommit config, long nowMillis) {
    this.config = config;
    this.lastRunMillis = nowMillis;
  }

  public boolean intervalDue(long nowMillis, int changedChunks) {
    return config.intervalMinutes() > 0
        && nowMillis - lastRunMillis >= config.intervalMinutes() * 60_000L
        && changedChunks >= config.minChangedChunks();
  }

  /** 到點但候選不足時也重置計時，避免每個 tick 都重新檢查。 */
  public boolean intervalElapsed(long nowMillis) {
    return config.intervalMinutes() > 0 && nowMillis - lastRunMillis >= config.intervalMinutes() * 60_000L;
  }

  public void ran(long nowMillis) {
    lastRunMillis = nowMillis;
  }

  public boolean onLogout(boolean singleplayer, boolean playerTouchedChunks) {
    return config.onLogout() && !singleplayer && playerTouchedChunks;
  }

  public boolean onStop() {
    return config.onStop();
  }
}
