package org.worldgit.paper;

import java.io.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.worldgit.core.version.*;
import org.worldgit.platform.VersionedSnapshots;

/** Preview on the repo worker; owner renders the complete list. A replay revalidates every binding. */
final class VersionConfirmations implements VersionedSnapshots {
  record Approval(UUID operation,String fingerprint,String actor,long expires) {
    boolean permits(String snapshot,String requester,long now) {
      return expires>now && actor.equals(requester) && fingerprint.equals(snapshot);
    }
  }
  record Pending(Approval approval,OperationUi.Action action) {}
  static final class Required extends IOException {
    Required(String message){super(message);}
  }
  private final WorldGitPlugin plugin;
  private final Map<String,Pending> pending=new ConcurrentHashMap<>();
  VersionConfirmations(WorldGitPlugin plugin){this.plugin=plugin;}
  public MinecraftVersion serverVersion() throws IOException { return MinecraftVersion.byDataVersion(plugin.bridge().dataVersion()); }
  public NbtUpgrader dataFixer(){return plugin.bridge().dataFixer();}
  public CompletionStage<Boolean> confirmLoss(String operationId,String snapshotId,ConversionReport report) {
    var action=OperationUi.current();
    return CompletableFuture.completedFuture(action!=null && action.conversionApproval!=null && action.conversionApproval.operation().toString().equals(operationId)
        && action.conversionApproval.fingerprint().equals(snapshotId));
  }
  private static String actor(CommandSender sender){return sender instanceof Player player?player.getUniqueId().toString():sender.getName();}
  static String fingerprint(Map<String,ConversionReport> reports,String world) throws IOException {
    try {
      var digest=MessageDigest.getInstance("SHA-256");digest.update(world.getBytes(StandardCharsets.UTF_8));
      for(var entry:new TreeMap<>(reports).entrySet()) {digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));digest.update(entry.getValue().bytes());}
      return HexFormat.of().formatHex(digest.digest());
    }catch(NoSuchAlgorithmException error){throw new IOException(error);}
  }
  void preview(Map<String,ConversionReport> reports) {
    if(reports.values().stream().noneMatch(ConversionReport::lossy))return;
    var action=OperationUi.current();if(action==null)return;
    Runnable show=()->Messages.inLocale(action.sender,()->{
      action.sender.sendMessage(Messages.line("paper.version.preview","operation",action.id));
      for(var entry:reports.entrySet()) {
        var report=entry.getValue();action.sender.sendMessage(Messages.line("paper.version.source","snapshot",entry.getKey(),"from",MinecraftVersion.name(report.from()),"to",MinecraftVersion.name(report.to())));
        for(var loss:report.losses())action.sender.sendMessage(Messages.line("paper.version.loss","kind",loss.kind(),"source",loss.source(),"replacement",loss.replacement(),"count",loss.count(),"locations",String.join(", ",loss.locations())));
      }
    });
    if(action.sender instanceof Player player)plugin.platform().entity(player,show,()->{});else plugin.platform().global(show);
  }
  void require(Map<String,ConversionReport> reports,String world) throws IOException {
    if(reports.values().stream().noneMatch(ConversionReport::lossy))return;
    var action=OperationUi.current();
    if(action==null || action.retry==null)throw new IOException("Lossy online conversion requires an interactive command");
    String fingerprint=fingerprint(reports,world);var approved=action.conversionApproval;
    if(approved!=null && approved.permits(fingerprint,actor(action.sender),System.currentTimeMillis()) && confirmLoss(approved.operation().toString(),fingerprint,reports.values().iterator().next()).toCompletableFuture().join())return;
    pending.entrySet().removeIf(e->e.getValue().approval().expires()<System.currentTimeMillis());
    if(pending.size()>=256)pending.clear();
    String token=UUID.randomUUID().toString().replace("-","");
    pending.put(token,new Pending(new Approval(action.id,fingerprint,actor(action.sender),System.currentTimeMillis()+300_000),action));
    Runnable show=()->Messages.inLocale(action.sender,()->{
      action.sender.sendMessage(Messages.line("paper.version.preview","operation",action.id));
      for(var entry:reports.entrySet()) {
        var report=entry.getValue();action.sender.sendMessage(Messages.line("paper.version.source","snapshot",entry.getKey(),"from",MinecraftVersion.name(report.from()),"to",MinecraftVersion.name(report.to())));
        for(var loss:report.losses())action.sender.sendMessage(Messages.line("paper.version.loss","kind",loss.kind(),"source",loss.source(),"replacement",loss.replacement(),"count",loss.count(),"locations",String.join(", ",loss.locations())));
      }
      action.sender.sendMessage(Messages.line("paper.version.confirm","token",token).clickEvent(ClickEvent.runCommand("/wg confirm "+token)));
    });
    if(action.sender instanceof Player player)plugin.platform().entity(player,show,()->{});else plugin.platform().global(show);
    throw new Required(Messages.inLocale(action.sender,()->Messages.plain(Messages.text("paper.version.confirm-required"))));
  }
  void confirm(CommandSource source,String token) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    var value=pending.get(token);
    if(value==null || value.approval().expires()<System.currentTimeMillis() || !value.approval().actor().equals(actor(source.getSender()))) {
      source.getSender().sendMessage(Messages.line("paper.version.invalid-token"));return;
    }
    String operation=value.action().operation.split("\\.")[0];
    if(!CommandTree.allowed(source,operation)){source.getSender().sendMessage(Messages.line("common.no-permission"));return;}
    if(!pending.remove(token,value))return;
    value.action().retry.accept(value.approval());
  }
  void clear(){pending.clear();}
}
