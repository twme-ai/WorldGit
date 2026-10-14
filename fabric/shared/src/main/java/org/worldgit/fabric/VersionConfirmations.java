package org.worldgit.fabric;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.commands.CommandSourceStack;
import net.kyori.adventure.text.event.ClickEvent;
import org.worldgit.core.version.*;
import org.worldgit.fabric.logic.Msg;
import org.worldgit.fabric.logic.VersionConfirmationTokens;
import org.worldgit.platform.VersionedSnapshots;

/** Repo 上算指紋，owner 上顯示完整損失；所有重試及鎖後套用重新核對授權。 */
final class VersionConfirmations implements VersionedSnapshots {
  static final class Required extends IOException { Required(String message) { super(message); } }
  private final ServerRuntime rt;
  private final VersionConfirmationTokens<OperationUi.Action> tokens = new VersionConfirmationTokens<>();
  VersionConfirmations(ServerRuntime rt) { this.rt = rt; }
  public MinecraftVersion serverVersion() throws IOException { return MinecraftVersion.byDataVersion(ChunkCapture.currentDataVersion()); }
  public NbtUpgrader dataFixer() { return rt.dataFixer(); }
  public CompletionStage<Boolean> confirmLoss(String operation, String fingerprint, ConversionReport report) {
    var action = OperationUi.current(); var approval = action == null ? null : action.conversionApproval;
    return CompletableFuture.completedFuture(approval != null && approval.operation().toString().equals(operation)
        && approval.permits(fingerprint, actor(action.source), System.currentTimeMillis()));
  }
  static String actor(CommandSourceStack source) {
    return source.getPlayer() == null ? "console:" + source.getTextName() : source.getPlayer().getUUID().toString();
  }
  private boolean permitted(OperationUi.Action action) {
    return rt.onServer(() -> {
      var player = action.source.getPlayer();
      return (player == null || rt.server().getPlayerList().getPlayer(player.getUUID()) == player)
          && WgCommands.allowed(rt.config().writePermissionLevel()).test(player == null ? action.source : player.createCommandSourceStack());
    });
  }
  private void show(OperationUi.Action action, Map<String, ConversionReport> reports, String token) {
    var copy = new TreeMap<>(reports);
    rt.postToServer(() -> {
      String locale = Texts.locale(rt, action.source.getPlayer());
      var lines = new ArrayList<net.kyori.adventure.text.Component>();
      lines.add(Texts.adventure(rt, locale, Msg.prefixed("fabric.version.preview", "operation", action.id)));
      for (var entry : copy.entrySet()) {
        var report = entry.getValue();
        lines.add(Texts.adventure(rt, locale, Msg.of("fabric.version.source", "snapshot", entry.getKey(),
            "from", MinecraftVersion.name(report.from()), "to", MinecraftVersion.name(report.to()))));
        for (var loss : report.losses()) lines.add(Texts.adventure(rt, locale, Msg.of("fabric.version.loss", "kind", loss.kind(),
            "source", loss.source(), "replacement", loss.replacement(), "count", loss.count(), "locations", String.join(", ", loss.locations()))));
      }
      if (token != null) lines.add(Texts.adventure(rt, locale, Msg.of("fabric.version.confirm", "token", token))
          .clickEvent(ClickEvent.runCommand("/wg confirm " + token)));
      Texts.sendComponents(action.source, rt, lines);
    });
  }
  void preview(Map<String, ConversionReport> reports) {
    var action = OperationUi.current();
    if (action != null && reports.values().stream().anyMatch(ConversionReport::lossy)) show(action, reports, null);
  }
  void require(Map<String, ConversionReport> reports, String state) throws IOException {
    if (reports.values().stream().noneMatch(ConversionReport::lossy)) return;
    var action = OperationUi.current();
    if (action == null || action.retry == null) throw new IOException("Lossy online conversion requires an interactive command");
    if (!permitted(action)) throw new Required(Mini.renderedPlain(rt.catalog(),rt.config().locale(),Msg.of("common.no-permission")));
    String fingerprint = VersionConfirmationTokens.fingerprint(reports, state);
    var approval = action.conversionApproval;
    if (approval != null && confirmLoss(approval.operation().toString(), fingerprint, reports.values().iterator().next()).toCompletableFuture().join()) return;
    String code = tokens.issue(action.id, fingerprint, actor(action.source), System.currentTimeMillis(), action);
    show(action, reports, code);
    throw new Required(rt.commitText(Msg.of("fabric.version.confirm-required")));
  }
  void confirm(CommandSourceStack source, String code) {
    var value = tokens.consume(code, actor(source), System.currentTimeMillis());
    if (value == null) { Texts.failure(source, rt, Msg.of("fabric.version.invalid-token")); return; }
    if (!WgCommands.allowed(rt.config().writePermissionLevel()).test(source)) {
      Texts.failure(source, rt, Msg.of("common.no-permission")); return;
    }
    value.retry().retry.accept(value.approval());
  }
  void tick() {
    tokens.invalidate(value -> {
      var player=value.retry().source.getPlayer();
      return value.approval().expires() <= System.currentTimeMillis()
          || player != null && (rt.server().getPlayerList().getPlayer(player.getUUID()) != player
              || !WgCommands.allowed(rt.config().writePermissionLevel()).test(player.createCommandSourceStack()));
    });
  }
  boolean interceptDenied(net.minecraft.server.level.ServerPlayer player, String command) {
    String[] parts=command.split("\\s+",3);
    if(parts.length!=3 || !Set.of("wg","worldgit","wgit","git").contains(parts[0]) || !parts[1].equals("confirm"))return false;
    if(WgCommands.allowed(rt.config().writePermissionLevel()).test(player.createCommandSourceStack()))return false;
    tokens.consume(parts[2],player.getUUID().toString(),System.currentTimeMillis());
    Texts.failure(player.createCommandSourceStack(),rt,Msg.of("common.no-permission"));return true;
  }
  void clear() { tokens.clear(); }
}
