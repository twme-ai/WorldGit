package org.worldgit.fabric;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.operation.OperationProgress;
import org.worldgit.core.operation.OperationResult;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.fabric.logic.MessageKeys;
import org.worldgit.fabric.logic.Msg;
import org.worldgit.fabric.logic.UiProtocol;
import org.worldgit.platform.ResultSummary;

/**
 * repo queue 的動作邊界。每個 /wg 指令、自動 commit、登出／停服 commit 都是一個 {@link Action}：
 * 進度（BossBar、HUD、console 節流行）給執行者與有權限的觀察者，結束時送出人類可讀的終止訊息，
 * 並讓 BossBar／HUD 顯示終態數秒後消失。所有 UI 都在 server 執行緒更新；repo 執行緒只更新記憶體。
 */
public final class OperationUi {
  private static final ThreadLocal<Action> CURRENT = new ThreadLocal<>();

  static Action current() {
    return CURRENT.get();
  }

  static <T> T within(Action action, Supplier<T> task) {
    var previous = CURRENT.get();
    CURRENT.set(action);
    try {
      return task.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  /** 回呼結果的包裝：只有 {@link #observed()} 的內容參與終態與摘要。 */
  public interface Observed {
    Object observed();
  }

  private final ServerRuntime rt;
  private final Map<UUID, Action> running = new ConcurrentHashMap<>();
  private final List<Action> terminal = new ArrayList<>();
  private volatile boolean closed;

  OperationUi(ServerRuntime rt) {
    this.rt = rt;
  }

  /** 一個使用者可見的動作。pending 計數歸零（所有非同步子工作都釋放）才算完成。 */
  public final class Action {
    final UUID id = UUID.randomUUID();
    final String operation;
    final CommandSourceStack source;
    final DimensionId dimension;
    final Action parent;
    final boolean automatic;
    final long started = System.nanoTime();
    final AtomicInteger pending = new AtomicInteger(1);
    final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    final List<Object> observed = new CopyOnWriteArrayList<>();
    final List<Function<ResultSummary, String>> notes = new CopyOnWriteArrayList<>();
    final Map<UUID, ServerBossEvent> bars = new HashMap<>();
    final AtomicInteger successful = new AtomicInteger(), unsuccessful = new AtomicInteger();
    volatile OperationResult.Status status = OperationResult.Status.SUCCESS;
    volatile OperationResult.ErrorReport error;
    volatile OperationProgress progress;
    volatile OperationProgress.Event latest;
    volatile boolean finished;
    volatile boolean cancelled;
    /** console 已輸出過同一份錯誤報告的單行形式。 */
    volatile boolean reportPrinted;
    long lastRender, lastHud, lastConsole;
    long removeAt;
    OperationResult result;
    String terminalText = "";

    Action(CommandSourceStack source, String operation, DimensionId dimension, boolean automatic) {
      this.source = source;
      this.operation = operation;
      this.dimension = dimension;
      this.automatic = automatic;
      this.parent = current();
      if (parent != null) parent.retain();
    }

    public UUID id() { return id; }
    public String operation() { return operation; }
    public DimensionId dimension() { return dimension; }
    public CompletableFuture<OperationResult> completion() { return completion; }
    ServerPlayer player() { return source == null ? null : source.getPlayer(); }
    public OperationResult.ErrorReport error() { return error; }
    public OperationResult.Status status() { return status; }

    public void retain() { pending.incrementAndGet(); }

    public void release() {
      if (pending.decrementAndGet() == 0) rt.postToServer(() -> within(this, () -> {finish();return null;}));
    }

    /** 在結果摘要加上一段說明（例如 graph 的 commit 數）。 */
    public void note(Function<ResultSummary, String> note) { notes.add(note); }

    public void failed(Throwable failure) {
      Throwable root = failure;
      while ((root instanceof CompletionException || root instanceof ExecutionException) && root.getCause() != null) root = root.getCause();
      if (status != OperationResult.Status.PARTIAL)
        status = root instanceof InterruptedIOException || String.valueOf(root.getMessage()).contains("已取消") ? OperationResult.Status.CANCELLED : OperationResult.Status.FAILED;
      error = report(this, String.valueOf(root.getMessage()));
    }

    public void partial(String message) {
      status = OperationResult.Status.PARTIAL;
      error = report(this, message);
    }

    void noOp() { if (status == OperationResult.Status.SUCCESS) status = OperationResult.Status.NO_OP; }

    /** 以 repo／核心結果決定終態；其他型別只留給摘要使用。 */
    public void observe(Object value) {
      if (value instanceof Observed wrapper) value = wrapper.observed();
      if (value == null) return;
      observed.add(value);
      if (value instanceof WorldRepositories.Batch<?> batch) {
        if (batch.dimensions().isEmpty()) { noOp(); return; }
        int failures = 0;
        boolean changed = false, commit = false, skipped = false;
        for (var row : batch.dimensions().values()) {
          if (!row.success()) { failures++; error = report(this, row.error()); }
          else if (row.value() instanceof DimensionRepository.CommitResult c) { commit = true; changed |= c.changed(); }
          else if (row.value() == null) skipped = true;
        }
        status = failures == 0 ? (commit && !changed || skipped && !commit && !changed) ? OperationResult.Status.NO_OP : OperationResult.Status.SUCCESS
            : failures == batch.dimensions().size() ? OperationResult.Status.FAILED : OperationResult.Status.PARTIAL;
        if (failures > 0 && commit && changed) status = OperationResult.Status.PARTIAL;
      } else if (value instanceof WorldOperations.Result applied) {
        if (!applied.success()) {
          status = applied.error() != null && applied.error().contains("取消") ? OperationResult.Status.CANCELLED : OperationResult.Status.PARTIAL;
          error = report(this, applied.error());
        }
      } else if (value instanceof WorldOperations.MergeResult merge) {
        if (!merge.success()) { status = OperationResult.Status.PARTIAL; error = report(this, merge.error()); }
        else if (merge.state().equals("NO_OP")) status = OperationResult.Status.NO_OP;
      } else if (value instanceof org.worldgit.core.remote.WorldRemotes.TransferResult transfer) {
        if (!transfer.success()) { status = transfer.commits().isEmpty() ? OperationResult.Status.FAILED : OperationResult.Status.PARTIAL; error = report(this, transfer.error()); }
      } else if (value instanceof OperationResult child) {
        if (child.status() == OperationResult.Status.SUCCESS || child.status() == OperationResult.Status.NO_OP) successful.incrementAndGet();
        else { unsuccessful.incrementAndGet(); error = child.error(); }
        status = unsuccessful.get() == 0 ? OperationResult.Status.SUCCESS : successful.get() == 0 ? OperationResult.Status.FAILED : OperationResult.Status.PARTIAL;
        if (child.status() == OperationResult.Status.CANCELLED) status = OperationResult.Status.CANCELLED;
      }
    }

    /** 進度 dispatcher 執行緒：只存最新事件，UI 在 server tick 更新。 */
    void event(OperationProgress.Event event) {
      if (!finished) latest = event;
    }

    private void finish() {
      if (finished) return;
      finished = true;
      running.remove(id);
      String locale = Texts.locale(rt, player());
      var summary = new ResultSummary(rt.catalog(), locale);
      var parts = new ArrayList<String>();
      for (var value : observed) {
        if (value instanceof OperationResult child) {
          parts.add((child.dimension() == null ? child.operation() : child.dimension().value()) + " " + child.status());
        } else {
          String text = summary.describe(value);
          if (text != null) parts.add(text);
        }
      }
      for (var note : notes) parts.add(note.apply(summary));
      result = new OperationResult(id, operation, status, dimension, Map.of("text", String.join("；", parts)),
          (System.nanoTime() - started) / 1_000_000, List.of(), error);
      terminalText = String.join("；", parts);
      if (parent != null) {
        parent.observe(result);
        parent.release();
      }
      if (!closed || automatic || source != null) announce(locale);
      showTerminal(result);
      completion.complete(result);
    }

    private void announce(String locale) {
      var message = Msg.of(MessageKeys.RESULT_LINE, "operation", operation, "status", rt.statusText(locale, result.status()),
          "dimension", " · " + (dimension == null ? "all" : dimension.value()),
          "summary", terminalText.isBlank() ? "" : " · " + terminalText, "millis", result.elapsedMillis());
      var recovery = result.status() == OperationResult.Status.PARTIAL || result.status() == OperationResult.Status.CANCELLED;
      var console = rt.server().createCommandSourceStack();
      if (automatic || source == null) {
        ServerRuntime.LOG.info("WORLDGIT RESULT {}", Mini.plain(rt.catalog(), locale, message));
        if (result.error() != null) ServerRuntime.LOG.info("WORLDGIT RESULT_ERROR {}", result.error().text().replace("\n", " | "));
        // 自動 commit（定時／登出）完成只通知有權限的玩家，無變動不打擾；其他自動動作只留日誌。
        boolean notify = (operation.equals("auto.commit") || operation.equals("logout.commit"))
            && rt.config().feedback().autoNotify() && result.status() != OperationResult.Status.NO_OP;
        if (notify)
          for (var player : audience(null)) {
            String playerLocale = Texts.locale(rt, player);
            var line = Msg.of(MessageKeys.RESULT_LINE, "operation", operation, "status", rt.statusText(playerLocale, result.status()),
                "dimension", " · " + (dimension == null ? "all" : dimension.value()),
                "summary", terminalText.isBlank() ? "" : " · " + terminalText, "millis", result.elapsedMillis());
            Texts.sendResult(player.createCommandSourceStack(), rt, line, result.error());
          }
        return;
      }
      Texts.sendResult(source, rt, message, result.error());
      if (recovery) Texts.send(source, rt, List.of(Msg.of(MessageKeys.RESULT_RECOVERY)));
    }

    // ---- UI（server 執行緒）---------------------------------------------------------

    void refresh(long now) {
      var event = latest;
      if (event == null || now - lastRender < 150) return;
      lastRender = now;
      var observers = audience(this);
      var feedback = rt.config().feedback();
      if (feedback.bossbar()) {
        for (var player : observers) {
          var bar = bars.computeIfAbsent(player.getUUID(), k -> {
            var created = Platform.bossbar();
            created.addPlayer(player);
            return created;
          });
          String locale = Texts.locale(rt, player);
          bar.setName(Texts.component(rt, locale, progressMessage(locale, event)));
          bar.setProgress(ratio(event));
        }
        for (var entry : new ArrayList<>(bars.entrySet())) {
          if (observers.stream().noneMatch(p -> p.getUUID().equals(entry.getKey()))) {
            entry.getValue().removeAllPlayers();
            bars.remove(entry.getKey());
          }
        }
      }
      if (feedback.hud() && now - lastHud >= 250) {
        lastHud = now;
        for (var player : observers) sendHud(player, hud(event, UiProtocol.Status.RUNNING, "", 0));
      }
      boolean console = source == null || source.getPlayer() == null;
      if (console && now - lastConsole >= feedback.consoleIntervalSeconds() * 1000L) {
        lastConsole = now;
        String text = progressMessageText(rt.config().locale(), event);
        if (source != null) source.sendSuccess(() -> Component.literal(text), false);
        else ServerRuntime.LOG.info("WORLDGIT PROGRESS {}", text);
      }
    }

    private Msg progressMessage(String locale, OperationProgress.Event e) {
      return Msg.of(MessageKeys.PROGRESS_LINE, "operation", e.operation() == null ? operation : e.operation(),
          "dimension", e.dimension() == null ? "—" : e.dimension().value(), "phase", rt.phaseText(locale, e.phase()),
          "percent", percent(e), "eta", e.remainingMillis() == null ? "—" : Math.max(0, e.remainingMillis() / 1000) + "s");
    }

    private String progressMessageText(String locale, OperationProgress.Event e) {
      return org.worldgit.fabric.Mini.plain(rt.catalog(), locale, progressMessage(locale, e));
    }

    private void showTerminal(OperationResult r) {
      removeAt = System.currentTimeMillis() + rt.config().feedback().terminalSeconds() * 1000L;
      var observers = audience(this);
      boolean good = r.status() == OperationResult.Status.SUCCESS || r.status() == OperationResult.Status.NO_OP;
      var color = good ? BossEvent.BossBarColor.GREEN : r.status() == OperationResult.Status.PARTIAL ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.RED;
      var feedback = rt.config().feedback();
      if (feedback.bossbar() && (latest != null || !bars.isEmpty())) {
        for (var player : observers) {
          var bar = bars.computeIfAbsent(player.getUUID(), k -> {
            var created = Platform.bossbar();
            created.addPlayer(player);
            return created;
          });
          String locale = Texts.locale(rt, player);
          bar.setColor(color);
          bar.setProgress(1f);
          bar.setName(Texts.component(rt, locale, Msg.of(MessageKeys.PROGRESS_TERMINAL, "operation", operation,
              "status", rt.statusText(locale, r.status()), "dimension", dimension == null ? "all" : dimension.value(), "millis", r.elapsedMillis())));
        }
      }
      if (feedback.hud() && latest != null) {
        var status = UiProtocol.Status.valueOf(r.status().name());
        for (var player : observers) sendHud(player, hud(latest, status, terminalText, feedback.terminalSeconds() * 1000L));
      }
      latest = null;
      if (!bars.isEmpty()) synchronized (terminal) { terminal.add(this); }
    }

    UiProtocol.Progress hud(OperationProgress.Event e, UiProtocol.Status status, String text, long ttl) {
      return new UiProtocol.Progress(id, operation, e.dimension() == null ? "" : e.dimension().value(), e.phase() == null ? "" : e.phase(),
          e.unit() == null ? 0 : e.unit().ordinal(), e.completed(), e.total() == null ? -1 : e.total(),
          status == UiProtocol.Status.RUNNING ? (e.remainingMillis() == null ? -1 : e.remainingMillis()) : ttl, status, text,
          (System.nanoTime() - started) / 1_000_000, e.cancellable());
    }

    void dispose() {
      for (var bar : bars.values()) bar.removeAllPlayers();
      bars.clear();
    }
  }

  OperationResult.ErrorReport report(Action action, String message) {
    String masked = rt.mask(message);
    return OperationResult.ErrorReport.create("FABRIC_OPERATION", action == null ? UUID.randomUUID() : action.id,
        action == null ? "command" : action.operation, action == null ? null : action.dimension, rt.modVersion(), Platform.MINECRAFT,
        rt.platformText(), masked, List.of());
  }

  private static float ratio(OperationProgress.Event e) {
    return e.total() == null || e.total() <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) e.completed() / e.total()));
  }

  private static String percent(OperationProgress.Event e) {
    return e.total() == null ? "…" : e.total() == 0 ? "100%" : Math.min(100, e.completed() * 100 / e.total()) + "%";
  }

  /** 執行者與有權限（寫入等級，單人 owner）的玩家。 */
  List<ServerPlayer> audience(Action action) {
    var result = new ArrayList<ServerPlayer>();
    var write = WgCommands.allowed(rt.config().writePermissionLevel());
    for (var player : rt.server().getPlayerList().getPlayers()) {
      boolean executor = action != null && action.player() != null && action.player().getUUID().equals(player.getUUID());
      if (executor || write.test(player.createCommandSourceStack())) result.add(player);
    }
    return result;
  }

  private void sendHud(ServerPlayer player, UiProtocol.Progress progress) {
    if (!rt.handshake().supports(player.getUUID(), UiProtocol.CAPABILITY) || !ServerPlayNetworking.canSend(player, Net.UI.type())) return;
    try {
      ServerPlayNetworking.send(player, Net.UI.of(UiProtocol.encode(progress)));
    } catch (IOException e) {
      ServerRuntime.LOG.warn("WORLDGIT HUD 進度封包編碼失敗：{}", e.toString());
    }
  }

  /** 伺服器執行緒，每個 tick。 */
  void tick() {
    long now = System.currentTimeMillis();
    for (var action : running.values()) action.refresh(now);
    synchronized (terminal) {
      terminal.removeIf(action -> {
        if (now < action.removeAt) return false;
        action.dispose();
        return true;
      });
    }
  }

  /** 仍有動作（含 --all 批次的子動作）在進行。 */
  public boolean busy() { return !running.isEmpty(); }

  Action begin(CommandSourceStack source, String operation, DimensionId dimension) {
    var action = new Action(source, operation, dimension, false);
    running.put(action.id, action);
    return action;
  }

  Action beginAutomatic(String operation, DimensionId dimension) {
    var action = new Action(null, operation, dimension, true);
    running.put(action.id, action);
    return action;
  }

  /** /wg cancel：對所有進行中的動作發出取消 token；回傳是否找到。 */
  boolean cancelAll() {
    boolean found = false;
    for (var action : running.values()) {
      if (action.operation.equals("cancel")) continue;
      action.cancelled = true;
      found = true;
      var progress = action.progress;
      if (progress != null) { progress.cancel(); found = true; }
    }
    return found;
  }

  void close() {
    closed = true;
    synchronized (terminal) {
      terminal.forEach(Action::dispose);
      terminal.clear();
    }
    running.values().forEach(Action::dispose);
    running.clear();
  }
}
