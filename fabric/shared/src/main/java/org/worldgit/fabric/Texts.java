package org.worldgit.fabric;

import java.util.List;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.worldgit.core.operation.OperationResult;
import org.worldgit.fabric.logic.MessageKeys;
import org.worldgit.fabric.logic.Msg;

/** 把 Msg 依「接收者的客戶端語言」與世界的色票渲染成原版 Component 並送出；error／failure／partial 附獨立 [複製]。 */
public final class Texts {
  private Texts() {}

  /** 玩家的客戶端語言（單人世界就是自己目前的遊戲語言）；沒有玩家（主控台）用設定的伺服器語言。 */
  public static String locale(ServerRuntime rt, ServerPlayer player) {
    if (player == null) return rt.config().locale();
    String language = player.clientInformation().language();
    return language == null || language.isBlank() ? rt.config().locale() : language;
  }

  static net.kyori.adventure.text.Component adventure(ServerRuntime rt, String locale, Msg msg) {
    return Mini.render(rt.catalog(), locale, msg, rt.palette());
  }

  public static Component component(ServerRuntime rt, String locale, Msg msg) {
    return MinecraftServerAudiences.of(rt.server()).asNative(adventure(rt, locale, msg));
  }

  /** 錯誤報告：優先沿用目前動作已建立的報告（秘密已遮罩）；沒有動作時建立一次性報告。 */
  static OperationResult.ErrorReport report(ServerRuntime rt, String plain) {
    var action = OperationUi.current();
    if (action == null) return rt.ui().report(null, plain);
    if (action.error == null) action.error = rt.ui().report(action, plain);
    else if (!action.error.text().contains(rt.mask(plain)))
      action.error = rt.ui().report(action, action.error.message() + "\n" + plain);
    if (action.status == OperationResult.Status.SUCCESS || action.status == OperationResult.Status.NO_OP)
      action.status = OperationResult.Status.FAILED;
    return action.error;
  }

  /** 原文字之後加獨立的 [複製]：不改原文字顏色與既有 click／hover。 */
  static net.kyori.adventure.text.Component withCopy(ServerRuntime rt, String locale, net.kyori.adventure.text.Component original,
      OperationResult.ErrorReport report) {
    var button = adventure(rt, locale, Msg.of(MessageKeys.RESULT_COPY))
        .clickEvent(ClickEvent.copyToClipboard(report.text()))
        .hoverEvent(HoverEvent.showText(adventure(rt, locale, Msg.of(MessageKeys.RESULT_COPY_HOVER))));
    return original.append(net.kyori.adventure.text.Component.space()).append(button);
  }

  private static void deliver(CommandSourceStack source, ServerRuntime rt, Msg msg, OperationResult.ErrorReport known, boolean failure) {
    String locale = locale(rt, source.getPlayer());
    var base = adventure(rt, locale, msg);
    var server = MinecraftServerAudiences.of(rt.server());
    var report = known != null ? known : report(rt, server.asNative(base).getString());
    var shown = server.asNative(source.getPlayer() == null ? base : withCopy(rt, locale, base, report));
    if (failure) source.sendFailure(shown);
    else source.sendSuccess(() -> shown, false);
    // console／RCON 沒有可點選按鈕：同一份報告以單行輸出。
    if (source.getPlayer() == null) {
      var action = OperationUi.current();
      if (action == null || !action.reportPrinted || action.error != report) {
        var line = Component.literal(report.text().replace("\n", " | "));
        source.sendSuccess(() -> line, false);
        if (action != null) action.reportPrinted = true;
      }
    }
  }

  public static void send(CommandSourceStack source, ServerRuntime rt, List<Msg> lines) {
    String locale = locale(rt, source.getPlayer());
    for (var line : lines) {
      if (MessageKeys.isError(line.key())) {
        deliver(source, rt, line, null, false);
        continue;
      }
      Component component = component(rt, locale, line);
      source.sendSuccess(() -> component, false);
    }
  }

  /** 已組好的 Adventure 元件（可點選／hover）；console 只會看到純文字。 */
  public static void sendComponents(CommandSourceStack source, ServerRuntime rt, List<net.kyori.adventure.text.Component> lines) {
    var server = MinecraftServerAudiences.of(rt.server());
    for (var line : lines) {
      Component component = server.asNative(line);
      source.sendSuccess(() -> component, false);
    }
  }

  public static void failure(CommandSourceStack source, ServerRuntime rt, Msg line) {
    deliver(source, rt, line, null, true);
  }

  /** 終止結果行；失敗／部分／取消附 [複製]（報告來自該動作）。 */
  static void sendResult(CommandSourceStack source, ServerRuntime rt, Msg line, OperationResult.ErrorReport error) {
    if (error == null) {
      send(source, rt, List.of(line));
      return;
    }
    deliver(source, rt, line, error, false);
  }
}
