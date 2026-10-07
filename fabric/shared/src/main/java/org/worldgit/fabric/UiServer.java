package org.worldgit.fabric;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.worldgit.core.graph.CommitGraph;
import org.worldgit.core.model.DimensionId;
import org.worldgit.fabric.logic.MessageKeys;
import org.worldgit.fabric.logic.Msg;
import org.worldgit.fabric.logic.UiProtocol;

/**
 * 伺服器端的 UI 封包處理（分支圖畫面、.wgignore 編輯畫面）。客戶端送來的一切都重新驗證：握手與能力、讀取／ignore 權限、
 * 維度、規則 token 與 HEAD；寫入仍走 IgnoreService 的 preview → 確認流程。
 */
final class UiServer {
  private final ServerRuntime rt;
  private final IgnoreService ignore;
  private final java.util.concurrent.atomic.AtomicLong graphIds = new java.util.concurrent.atomic.AtomicLong();

  /** 命令與 UI refresh 共用伺服器 generation；不比較兩臺機器的 nanoTime。 */
  long nextGraphId() { return graphIds.incrementAndGet(); }

  UiServer(ServerRuntime rt, IgnoreService ignore) {
    this.rt = rt;
    this.ignore = ignore;
  }

  IgnoreService ignore() { return ignore; }

  boolean capable(ServerPlayer player) {
    return rt.handshake().ready(player.getUUID()) && rt.handshake().supports(player.getUUID(), UiProtocol.CAPABILITY)
        && ServerPlayNetworking.canSend(player, Net.UI.type());
  }

  void send(ServerPlayer player, UiProtocol.Message message) {
    try {
      ServerPlayNetworking.send(player, Net.UI.of(UiProtocol.encode(message)));
    } catch (IOException e) {
      ServerRuntime.LOG.warn("WORLDGIT UI 封包編碼失敗：{}", e.toString());
    }
  }

  private String plain(ServerPlayer player, Msg msg) {
    return Texts.component(rt, Texts.locale(rt, player), msg).getString();
  }

  /** 伺服器執行緒（網路 receiver）。 */
  void onPacket(ServerPlayer player, byte[] bytes) {
    if (!capable(player)) return;
    try {
      switch (UiProtocol.decode(bytes)) {
        case UiProtocol.GraphRequest request -> graph(player, request);
        case UiProtocol.IgnoreEdit edit -> ignore(player, edit);
        default -> ServerRuntime.LOG.warn("WORLDGIT UI 封包方向錯誤：{}", player.nameAndId().name());
      }
    } catch (IOException | RuntimeException e) {
      ServerRuntime.LOG.warn("WORLDGIT UI 封包被拒絕 player={} error={}", player.nameAndId().name(), e.toString());
    }
  }

  private DimensionId dimension(ServerPlayer player, String requested) {
    if (requested == null || requested.isBlank()) return ServerRuntime.dimensionId((ServerLevel) player.level());
    return new DimensionId(requested);
  }

  // ---------------------------------------------------------------- 分支圖

  void graph(ServerPlayer player, UiProtocol.GraphRequest request) {
    DimensionId dimension;
    try {
      dimension = dimension(player, request.dimension());
    } catch (IllegalArgumentException e) {
      return;
    }
    var action = rt.ui().begin(player.createCommandSourceStack(), "graph", dimension);
    if (!WgCommands.allowed(rt.config().readPermissionLevel()).test(player.createCommandSourceStack())) {
      OperationUi.within(action, () -> {
        Texts.failure(player.createCommandSourceStack(), rt, Msg.prefixed(MessageKeys.ERROR_PERMISSION));
        action.release();
        return null;
      });
      return;
    }
    long generation = nextGraphId();
    OperationUi.within(action, () -> {
      var future = rt.graph(dimension, request.limit(), request.all());
      action.retain();
      future.whenComplete((graph, error) -> rt.postToServer(() -> OperationUi.within(action, () -> {
        try {
          if (error != null) {
            action.failed(error);
            Texts.failure(player.createCommandSourceStack(), rt, Msg.of(MessageKeys.COMMON_ERROR, "message", rt.mask(String.valueOf(unwrap(error).getMessage()))));
          } else if (rt.server().getPlayerList().getPlayer(player.getUUID()) == player) {
            action.note(summary -> summary.count(graph.nodes().size()));
            for (byte[] part : UiProtocol.encodeGraph(generation, dimension.value(), graph, request.open()))
              ServerPlayNetworking.send(player, Net.UI.of(part));
            if (request.open())
              Texts.send(player.createCommandSourceStack(), rt, List.of(Msg.prefixed(MessageKeys.GRAPH_SCREEN, "dimension", dimension.value())));
          }
        } catch (IOException | RuntimeException e) {
          action.failed(e);
        } finally {
          action.release();
        }
        return null;
      })));
      return null;
    });
    action.release();
  }

  private static Throwable unwrap(Throwable error) {
    Throwable root = error;
    while (root instanceof CompletionException && root.getCause() != null) root = root.getCause();
    return root;
  }

  // ---------------------------------------------------------------- .wgignore

  private boolean allowed(ServerPlayer player) {
    return WgCommands.allowed(rt.config().ignorePermissionLevel()).test(player.createCommandSourceStack());
  }

  /** 送出規則清單（從 offset 起，能放進單一 payload 的行數）。 */
  void sendView(ServerPlayer player, long request, DimensionId dimension, IgnoreService.View view, int offset, boolean open) throws IOException {
    var lines = new ArrayList<UiProtocol.Line>();
    var entries = view.document().entries();
    for (var entry : entries.subList(Math.min(offset, entries.size()), entries.size())) {
      String text = entry.text();
      if (entry.rule() && !entry.enabled()) text = text.replaceFirst("^\\s*# worldgit-disabled: ", "");
      lines.add(new UiProtocol.Line(text, entry.rule(), entry.enabled(), text.codePointCount(0, text.length()) > UiProtocol.MAX_LINE_CHARS));
    }
    send(player, UiProtocol.fitView(request, dimension.value(), view.token(), allowed(player), view.merging(), entries.size(), offset, open, lines));
  }

  void ignore(ServerPlayer player, UiProtocol.IgnoreEdit edit) {
    DimensionId dimension;
    try {
      dimension = dimension(player, edit.dimension());
    } catch (IllegalArgumentException e) {
      return;
    }
    var source = player.createCommandSourceStack();
    var action = rt.ui().begin(source, "ignore." + edit.op(), dimension);
    if (!allowed(player)) {
      OperationUi.within(action, () -> {
        send(player, new UiProtocol.IgnoreResult(edit.request(), dimension.value(), false, "error", plain(player, Msg.of(MessageKeys.IGNORE_PERMISSION))));
        Texts.failure(source, rt, Msg.prefixed(MessageKeys.IGNORE_PERMISSION));
        action.release();
        return null;
      });
      return;
    }
    String owner = IgnoreService.owner(player);
    OperationUi.within(action, () -> {
      boolean aimed = edit.op().equals("test") && edit.text().isBlank();
      IgnoreService.Aim aim;
      try {
        aim = aimed ? IgnoreService.aim(player) : null;
      } catch (IOException e) {
        action.failed(e);
        send(player, new UiProtocol.IgnoreResult(edit.request(), dimension.value(), false, "error", rt.mask(String.valueOf(e.getMessage()))));
        action.release();
        return null;
      }
      if (aimed && aim == null) {
        send(player, new UiProtocol.IgnoreResult(edit.request(), dimension.value(), false, "error", plain(player, Msg.of(MessageKeys.IGNORE_NO_TARGET))));
        Texts.failure(source, rt, Msg.prefixed(MessageKeys.IGNORE_NO_TARGET));
        action.release();
        return null;
      }
      action.retain();
      rt.runRepo(() -> run(player, owner, dimension, edit, aim)).whenComplete((result, error) -> rt.postToServer(() -> OperationUi.within(action, () -> {
        try {
          if (rt.server().getPlayerList().getPlayer(player.getUUID()) != player) return null;
          if (error != null) {
            Throwable cause = unwrap(error);
            Msg msg = cause instanceof IgnoreService.Refused refused ? refused.msg
                : Msg.prefixed(MessageKeys.IGNORE_INVALID, "message", rt.mask(String.valueOf(cause.getMessage())));
            action.failed(cause);
            send(player, new UiProtocol.IgnoreResult(edit.request(), dimension.value(), false, "error", plain(player, msg)));
            Texts.failure(source, rt, msg);
            return null;
          }
          for (var message : result.messages()) send(player, message);
          if (result.view() != null) sendView(player, edit.request(), dimension, result.view(), result.offset(), false);
          if (result.chat() != null) Texts.send(source, rt, result.chat());
        } catch (IOException | RuntimeException e) {
          action.failed(e);
        } finally {
          action.release();
        }
        return null;
      })));
      action.release();
      return null;
    });
  }

  private record Outcome(List<UiProtocol.Message> messages, IgnoreService.View view, int offset, List<Msg> chat) {}

  /** repo 執行緒：套用單一編輯請求。 */
  private Outcome run(ServerPlayer player, String owner, DimensionId dimension, UiProtocol.IgnoreEdit edit, IgnoreService.Aim aim) throws IOException {
    String locale = rt.onServer(() -> {
      if (rt.server().getPlayerList().getPlayer(player.getUUID()) != player || !allowed(player)) return null;
      return Texts.locale(rt, player);
    });
    if (locale == null) throw new IgnoreService.Refused(Msg.prefixed(MessageKeys.IGNORE_PERMISSION));
    String name = dimension.value();
    switch (edit.op()) {
      case "view": {
        return new Outcome(List.of(), ignore.read(dimension), edit.offset(), null);
      }
      case "test": {
        var result = ignore.test(dimension, aim == null ? edit.text() : aim.selector(), aim == null ? null : aim.entity());
        var line = Msg.of(MessageKeys.IGNORE_TEST, "excluded", result.result().excluded(), "line", result.result().line() == null ? "-" : result.result().line(),
            "rule", result.result().rule() == null ? "-" : result.result().rule());
        return new Outcome(List.of(new UiProtocol.IgnoreResult(edit.request(), name, true, "test", Mini.plain(rt.catalog(), locale, line))), null, 0, List.of(line));
      }
      case "cancel": {
        boolean had = ignore.cancel(owner);
        return new Outcome(List.of(new UiProtocol.IgnoreResult(edit.request(), name, true, "cancelled", Mini.plain(rt.catalog(), locale, Msg.of(MessageKeys.IGNORE_CANCELLED)))), null, 0,
            had ? List.of(Msg.prefixed(MessageKeys.IGNORE_CANCELLED)) : List.of());
      }
      case "confirm": {
        ignore.confirm(owner, dimension, edit.code());
        var written = Msg.prefixed(MessageKeys.IGNORE_WRITTEN);
        return new Outcome(List.of(new UiProtocol.IgnoreResult(edit.request(), name, true, "written", Mini.plain(rt.catalog(), locale, written))), ignore.read(dimension), 0, List.of(written));
      }
      default: {
        var proposal = ignore.propose(owner, dimension, edit.op(), edit.line(), edit.destination(), edit.text(), edit.token());
        var p = proposal.preview();
        var samples = p.examples().isEmpty() ? "-" : String.join("; ", p.examples());
        var chat = List.of(Msg.prefixed(MessageKeys.IGNORE_PREVIEW, "blocks", ResultSummaryNumbers.n(p.blocks()), "bes", ResultSummaryNumbers.n(p.blockEntities()),
            "entities", ResultSummaryNumbers.n(p.entities()), "biomes", ResultSummaryNumbers.n(p.biomeSamples()), "fields", ResultSummaryNumbers.n(p.metadataFields()), "samples", samples));
        var message = new UiProtocol.IgnorePreview(edit.request(), name, edit.op().equals("preview") ? "" : proposal.code(), proposal.change(), p.blocks(),
            p.blockEntities(), p.entities(), p.biomeSamples(), p.metadataFields(), p.examples(), proposal.expiresSeconds());
        return new Outcome(List.of(message), null, 0, chat);
      }
    }
  }

  /** 千分位數字。 */
  static final class ResultSummaryNumbers {
    private ResultSummaryNumbers() {}
    static String n(long value) { return org.worldgit.platform.ResultSummary.number(value); }
  }
}
