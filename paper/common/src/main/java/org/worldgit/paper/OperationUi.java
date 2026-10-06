package org.worldgit.paper;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.operation.*;
import org.worldgit.core.service.*;

/** repo queue 的動作邊界；progress callback 只排 owner UI，從不等待 owner 或讀檔。 */
final class OperationUi implements AutoCloseable {
  private static final ThreadLocal<Action> CURRENT = new ThreadLocal<>();
  static Action current() { return CURRENT.get(); }
  static <T> T within(Action action, Supplier<T> task) {
    var previous = CURRENT.get(); CURRENT.set(action);
    try { return task.get(); } finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
  }
  final class Action {
    final UUID id = UUID.randomUUID();
    final String operation;
    final CommandSender sender;
    final WorldMapper.Mapping mapping;
    final DimensionId dimension;
    final Action parent;
    final AtomicInteger successfulChildren=new AtomicInteger(),failedChildren=new AtomicInteger();
    final long started = System.nanoTime();
    final AtomicInteger pending = new AtomicInteger(1);
    final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    final Map<String,Object> summary = new ConcurrentHashMap<>();
    volatile OperationResult.Status status = OperationResult.Status.SUCCESS;
    volatile OperationResult.ErrorReport error;
    volatile OperationProgress progress;
    volatile boolean finished;
    volatile long lastConsole;
    Action(CommandSender sender, String operation, WorldMapper.Mapping mapping, DimensionId dimension) {
      this.sender=sender;this.operation=operation;this.mapping=mapping;this.dimension=dimension;this.parent=current();if(parent!=null)parent.retain();
    }
    void retain() { pending.incrementAndGet(); }
    void release() { if (pending.decrementAndGet()==0) finish(); }
    void failed(Throwable failure) {
      Throwable root=failure; while(root instanceof CompletionException && root.getCause()!=null)root=root.getCause();
      if(status!=OperationResult.Status.PARTIAL)status=root instanceof InterruptedIOException ? OperationResult.Status.CANCELLED : OperationResult.Status.FAILED;
      error=report(this, String.valueOf(root.getMessage()));
    }
    void observe(Object value) {
      if (value instanceof WorldRepositories.Batch<?> batch) {
        int failures=0; boolean changed=false;
        for(var row:batch.dimensions().entrySet()) {
          var outcome=row.getValue();
          if(!outcome.success()) {failures++;error=report(this,outcome.error());}
          else if(outcome.value() instanceof DimensionRepository.CommitResult commit) {
            changed|=commit.changed(); summary.put(row.getKey().value(),Messages.shortId(commit.commit())+" sections="+commit.status().diff().sections().size()+" entities="+commit.status().diff().entities().size()+" blocks="+commit.status().diff().counts());
          } else if(outcome.value() instanceof DimensionRepository.Status state) summary.put(row.getKey().value(),"sections="+state.diff().sections().size()+" entities="+state.diff().entities().size());
        }
        status=failures==0 ? operation.endsWith("commit") && !changed ? OperationResult.Status.NO_OP : OperationResult.Status.SUCCESS
            : failures==batch.dimensions().size() ? OperationResult.Status.FAILED : OperationResult.Status.PARTIAL;
      } else if(value instanceof PaperOperations.Result applied) {
        summary.put("changes",applied.dimensions().toString());
        if(!applied.success()) {status=applied.error()!=null && applied.error().contains("取消")?OperationResult.Status.CANCELLED:OperationResult.Status.PARTIAL;error=report(this,applied.error());}
      } else if(value instanceof WorldOperations.MergeResult merge) {
        summary.put("commits",merge.commits().toString());summary.put("remaining",merge.merging()==null?0:merge.merging().remaining());
        if(!merge.success()) {status=OperationResult.Status.PARTIAL;error=report(this,merge.error());}
        else if(merge.state().toString().equals("NO_OP"))status=OperationResult.Status.NO_OP;
      } else if(value instanceof org.worldgit.core.remote.WorldRemotes.TransferResult transfer) {
        summary.put("commits",transfer.commits().toString());summary.put("bytes",transfer.packs().values().stream().flatMap(Collection::stream).mapToLong(pack->pack.wireBytes()==null?pack.preparedBytes():pack.wireBytes()).sum());
        if(!transfer.success()) {status=transfer.commits().isEmpty()?OperationResult.Status.FAILED:OperationResult.Status.PARTIAL;error=report(this,transfer.error());}
      } else if(value instanceof Collection<?> rows) summary.put("count",rows.size());
      else if(value instanceof OperationResult child) {
        summary.put(child.dimension()==null?child.operation():child.dimension().value(),child.status()+" "+child.summary());
        if(child.status()==OperationResult.Status.SUCCESS || child.status()==OperationResult.Status.NO_OP)successfulChildren.incrementAndGet();
        else {failedChildren.incrementAndGet();error=child.error();}
        status=failedChildren.get()==0?OperationResult.Status.SUCCESS:successfulChildren.get()==0?OperationResult.Status.FAILED:OperationResult.Status.PARTIAL;
      }
    }
    void event(OperationProgress.Event event) {
      if(finished || closed)return;
      long now=System.currentTimeMillis();
      if(!(sender instanceof Player) && now-lastConsole>=1000) {
        lastConsole=now;send(sender,()->progressText(event));
      }
      plugin.platform().global(()->{
        if(finished || closed)return;
        for(var player:plugin.getServer().getOnlinePlayers()) plugin.platform().entity(player,()->{
          if(finished || closed || !(player.equals(sender) || player.hasPermission("worldgit.notify")))return;
          var bar=bars.computeIfAbsent(player.getUniqueId(),k->BossBar.bossBar(Component.empty(),0,BossBar.Color.BLUE,BossBar.Overlay.PROGRESS));
          bar.name(Messages.inLocale(player,()->progressText(event)));
          bar.progress(event.total()==null || event.total()==0?0:Math.min(1f,(float)event.completed()/event.total()));
          player.showBossBar(bar);
        },()->bars.remove(player.getUniqueId()));
      });
    }
    void finish() {
      finished=true; actions.remove(id);
      var result=new OperationResult(id,operation,status,dimension,new TreeMap<>(summary),(System.nanoTime()-started)/1_000_000,List.of(),error);
      if(parent!=null) {parent.observe(result);parent.release();}
      completion.complete(result);
      if(closed) {
        // scheduler 已關閉時仍記錄被中斷動作的終態；Paper shutdown.commit 由 disable 邊界直接輸出。
        if(!operation.equals("shutdown.commit")) {
          plugin.getLogger().info(PlainTextComponentSerializer.plainText().serialize(completion(result)));
          if(result.error()!=null)plugin.getLogger().info(result.error().text().replace("\n"," | "));
        }
        return;
      }
      send(sender,()->completion(result));
      if(operation.equals("auto.commit") || operation.equals("logout.commit"))plugin.platform().global(()->{
        if(!plugin.getConfig().getBoolean("feedback.auto-notify",true))return;
        for(var p:plugin.getServer().getOnlinePlayers())plugin.platform().entity(p,()->{if(p.hasPermission("worldgit.notify"))p.sendMessage(Messages.inLocale(p,()->completion(result)));},()->{});
      });
      if(sender instanceof Player player && !closed)plugin.platform().entity(player,()->{
        if(plugin.getConfig().getBoolean("feedback.title",false))player.showTitle(net.kyori.adventure.title.Title.title(Messages.text("paper.phase5.result-title","status",status),Component.text(operation)));
        if(plugin.getConfig().getBoolean("feedback.sound",false))player.playSound(player.getLocation(),status==OperationResult.Status.SUCCESS || status==OperationResult.Status.NO_OP?org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP:org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS,1f,1f);
      },()->{});
      plugin.platform().global(()->{
        if(closed)return;
        for(var p:plugin.getServer().getOnlinePlayers()) plugin.platform().entity(p,()->{
          var bar=bars.get(p.getUniqueId());if(bar==null)return;
          bar.color(status==OperationResult.Status.SUCCESS || status==OperationResult.Status.NO_OP?BossBar.Color.GREEN:BossBar.Color.RED);
          bar.progress(1);bar.name(Messages.inLocale(p,()->Messages.text("paper.phase5.terminal","operation",operation,"status",status,"dimension",dimension==null?"all":dimension,"millis",result.elapsedMillis())));
          plugin.platform().entityDelayed(p,plugin.getConfig().getInt("feedback.terminal-seconds",3)*20L,()->{p.hideBossBar(bar);bars.remove(p.getUniqueId(),bar);},()->bars.remove(p.getUniqueId(),bar));
        },()->bars.remove(p.getUniqueId()));
      });
    }
  }
  private final WorldGitPlugin plugin;
  private final Map<UUID,Action> actions=new ConcurrentHashMap<>();
  private volatile boolean closed;
  OperationUi(WorldGitPlugin plugin) {this.plugin=plugin;}
  Action begin(CommandSender sender,String operation,WorldMapper.Mapping mapping,DimensionId dimension) {
    var action=new Action(sender,operation,mapping,dimension);actions.put(action.id,action);return action;
  }
  static Component progressText(OperationProgress.Event e) {
    return Messages.line("paper.phase5.progress","operation",e.operation(),"dimension",e.dimension()==null?"—":e.dimension(),"phase",e.phase(),
        "percent",e.total()==null?"…":e.total()==0?"100%":(e.completed()*100/e.total())+"%","eta",e.remainingMillis()==null?"—":e.remainingMillis()/1000+"s");
  }
  Component completion(OperationResult result) {
    var line=Messages.line("paper.phase5.result","operation",result.operation(),"status",result.status(),"dimension",result.dimension()==null?"all":result.dimension(),
        "summary",result.summary(),"millis",result.elapsedMillis(),"id",result.operationId());
    if(result.status()==OperationResult.Status.PARTIAL || result.status()==OperationResult.Status.CANCELLED) {
      boolean applied=result.operation().matches("(?:restore|switch|reset|merge|resolve|conflict-select|revert|cherry-pick)(?:\\..*)?")
          || result.operation().startsWith("stash.push") || result.operation().startsWith("stash.pop") || result.operation().equals("pull.confirm") || result.operation().startsWith("tool.");
      line=line.append(Component.space()).append(Messages.text(applied?"paper.phase5.recovery":"paper.phase5.batch-recovery"));
    }
    return result.error()==null?line:copy(line,result.error());
  }
  OperationResult.ErrorReport report(Action action,String message) {
    String masked=plugin.remote()==null?OperationResult.redact(message):plugin.remote().mask(message);
    return OperationResult.ErrorReport.create("PAPER_OPERATION",action==null?UUID.randomUUID():action.id,action==null?"command":action.operation,
        action==null?null:action.dimension,plugin.getPluginMeta().getVersion(),plugin.bridge().minecraftVersion(),plugin.getServer().getVersion(),masked,List.of());
  }
  static Component copy(Component original,OperationResult.ErrorReport report) {
    return original.append(Component.space()).append(Messages.text("paper.phase5.copy")
        .clickEvent(ClickEvent.copyToClipboard(report.text())).hoverEvent(Messages.text("paper.phase5.copy-hover")));
  }
  Component error(Component original) {
    var action=current();var report=action!=null && action.error!=null?action.error:report(action,PlainTextComponentSerializer.plainText().serialize(original));
    if(action!=null) {action.error=report;if(action.status==OperationResult.Status.SUCCESS)action.status=OperationResult.Status.FAILED;}
    return copy(original,report);
  }
  void send(CommandSender sender,Supplier<Component> text) {
    if(closed || !plugin.isEnabled())return;
    var action=current();
    Runnable send=()->within(action,()->{Messages.inLocale(sender,()->{
      var message=text.get();
      if(!(sender instanceof Player)) {
        sender.sendMessage(message);
        if(action!=null && action.error!=null)sender.sendMessage(action.error.text().replace("\n"," | "));
      } else sender.sendMessage(message);
    });return null;});
    if(sender instanceof Player player)plugin.platform().entity(player,send,()->{});else plugin.platform().global(send);
  }
  boolean cancel(CommandSender sender) {
    boolean found=false;
    for(var action:actions.values())if(action.sender.equals(sender) && action.progress!=null) {action.progress.cancel();found=true;}
    return found;
  }
  public void close() {closed=true;actions.clear();}
}
