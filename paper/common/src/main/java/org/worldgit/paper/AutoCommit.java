package org.worldgit.paper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;

/**
 * 自動 commit（docs/04 §5）。觸發：定時、玩家登出、伺服器關閉前（由 plugin 處理）。
 *
 * <p>沒有變動不產生 commit（core 保證）。定時觸發還有兩道門檻，來自 Phase 0 的生存實測：
 * 站著不動也會有隨機刻底噪，所以只有「方塊 section 變動數 ≥ min-changed-sections」才寫；
 * 較小的變動合併到 max-wait 再寫；只有實體變動不觸發（除非 entity-only-triggers）。
 */
final class AutoCommit {
  private final WorldGitPlugin plugin;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean quitPending = new AtomicBoolean();
  private volatile long lastAttempt = System.currentTimeMillis();
  private volatile boolean stopped;

  AutoCommit(WorldGitPlugin plugin) {
    this.plugin = plugin;
  }

  void start() {
    if (!plugin.settings().autoEnabled()) return;
    // 每 30 秒檢查一次是否到期；實際頻率由 interval-minutes 決定。
    plugin.platform().asyncRepeating(30, 30_000, this::tick);
  }

  void shutdown() {
    stopped = true;
  }

  private void tick() {
    var s = plugin.settings();
    long now = System.currentTimeMillis();
    if (stopped || now - lastAttempt < s.autoIntervalMinutes() * 60_000L) return;
    if (plugin.repo() == null) return;
    long sinceCommit = now - plugin.repo().lastCommitMillis();
    boolean mustCommit = sinceCommit >= s.autoMaxWaitMinutes() * 60_000L;
    // 沒有任何 dirty 候選也沒有作者事件 → 不必擷取。
    if (!hasCandidates()) {
      lastAttempt = now;
      return;
    }
    run("定時自動存檔點", status -> qualifies(status.diff(), s, mustCommit), true);
  }

  static boolean qualifies(org.worldgit.core.diff.WorldDiff diff, PluginSettings s, boolean overdue) {
    int sections = diff.sections().size();
    if (sections >= s.autoMinChangedSections()) return true;
    if (overdue && (sections > 0 || !diff.biomes().isEmpty() || !diff.metadata().isEmpty())) return true;
    return s.autoEntityOnlyTriggers() && !diff.entities().isEmpty();
  }

  private boolean hasCandidates() {
    return plugin.hasAttribution() || plugin.hasDirty();
  }

  void onQuit(String player) {
    var s = plugin.settings();
    if (stopped || !s.autoEnabled() || !s.autoOnQuit()) return;
    if (!quitPending.compareAndSet(false, true)) return; // 同時多人登出只做一次
    plugin.platform().asyncDelayed(Math.max(1, s.autoQuitDelaySeconds()), () -> {
      quitPending.set(false);
      if (!stopped) run("玩家登出自動存檔點（" + player + "）", status -> true, false);
    });
  }

  private void run(String message, java.util.function.Predicate<DimensionRepository.Status> gate, boolean timer) {

    if (!running.compareAndSet(false, true)) return;
    if (timer) lastAttempt = System.currentTimeMillis();
    var jobs=new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
    for(var mapping:plugin.mappings()) {
    var action=plugin.operations().begin(plugin.getServer().getConsoleSender(),timer?"auto.commit":"logout.commit",mapping,null);
    OperationUi.within(action,()->{jobs.add(plugin
        .repo()
        .commit(new RepoService.CommitRequest(message, plugin.serverIdentity(), true, gate))
        .whenComplete(
            (batch, error) -> {
              if (error != null) {
                // 尚未 init 是正常情況，不當成錯誤噴 log
                if (!String.valueOf(error.getMessage()).contains("尚未 init"))
                  plugin.getLogger().log(Level.WARNING, "自動 commit 失敗", error);
                return;
              }
              batch.dimensions().forEach((id, o) -> {
                if (!o.success()) plugin.getLogger().warning("自動 commit " + id + " 失敗：" + o.error());
                else if (o.value().changed())
                  plugin.getLogger().info("自動 commit " + id + " → " + o.value().commit().substring(0, 8) + "（" + message + "）");
              });
              plugin.notifyCommit(batch, true);
            }));action.release();return null;});
    }
    java.util.concurrent.CompletableFuture.allOf(jobs.toArray(java.util.concurrent.CompletableFuture[]::new)).whenComplete((result,error)->running.set(false));
  }
}
