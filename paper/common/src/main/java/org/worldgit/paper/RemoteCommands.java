package org.worldgit.paper;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.worldgit.core.model.*;
import org.worldgit.core.remote.*;
import org.worldgit.core.service.*;
import org.worldgit.platform.remote.*;

/** 網路走 repo 背景 queue；所有 sender/玩家 API 回到 global/entity owner。 */
final class RemoteCommands implements AutoCloseable {
  private record Pending(String code,String remote,String branch,String url,long expires,WorldOperations.PullPreview preview) {}
  private record Target(String remote,String branch,String url,SortedMap<DimensionId,String> heads) {}
  private final WorldGitPlugin plugin;
  private final RemoteSettings settings;
  private final PlatformCredentials secrets;
  private final Map<String,Pending> pending=new ConcurrentHashMap<>();
  private final Set<String> busy=ConcurrentHashMap.newKeySet();
  private final AtomicBoolean fetching=new AtomicBoolean();
  private volatile boolean stopped;
  private volatile Map<DimensionId,String> announced=Map.of();
  private WebhookReceiver webhook;
  RemoteCommands(WorldGitPlugin plugin,RemoteSettings settings) {
    this.plugin=plugin;this.settings=settings;secrets=new PlatformCredentials(settings,System.getenv(),plugin.getDataFolder().toPath());
  }
  void start() throws IOException {
    if(settings.webhook().enabled()) {
      webhook=new WebhookReceiver(settings.webhook(),secrets.webhookSecret(),plugin.getDataFolder().toPath().resolve("webhook-replays.yml"),e->{
        if(stopped)return false;
        plugin.repo().submit(()->{
          try(var r=open()) {
            var spec=r.remotes().get(settings.defaultRemote()); if(spec==null)return null;
            var endpoint=HubClient.endpoint(spec.url());
            if(endpoint.owner().equals(e.owner()) && endpoint.world().equals(e.world()) && r.branch().equals(e.branch())) poll(0);
          }
          return null;
        }); return true;
      });
    }
    if(settings.fetchIntervalSeconds()>0) plugin.platform().asyncRepeating(settings.fetchIntervalSeconds(),settings.fetchIntervalSeconds()*1000L,()->poll(0));
  }
  private WorldRemotes open() throws IOException {
    var r=new WorldRemotes(WorldMapper.map().layout(),secrets.credentials(),settings.timeoutSeconds());
    try {
      if(!settings.hubUrl().isEmpty() && !r.remotes().containsKey(settings.defaultRemote()))r.configure("add",settings.defaultRemote(),settings.hubUrl(),false);
      return r;
    } catch(Throwable t) {r.close();throw t;}
  }
  private HubClient hub() throws IOException {
    try(var r=open()) {
      var spec=r.remotes().get(settings.defaultRemote());if(spec==null)throw new IOException("remote 不存在");
      return new HubClient(spec.url(),secrets.credentials().resolve(settings.defaultRemote(),spec.url()),settings.timeout());
    }
  }
  private static String key(CommandSender sender) {return sender instanceof Player p?p.getUniqueId().toString():"console";}
  private static CommitMetadata.Identity author(CommandSender sender,WorldGitPlugin plugin) {
    return sender instanceof Player p?new CommitMetadata.Identity(p.getName(),p.getUniqueId()+"@players.worldgit.invalid"):plugin.serverIdentity();
  }
  private void reply(CommandSender sender,String message,Object... args) {
    send(sender,()->Messages.line("paper.remote."+message,args));
  }
  private void send(CommandSender sender,java.util.function.Supplier<Component> message) {
    if(stopped)return;
    Runnable task=()->{if(!stopped) Messages.inLocale(sender,()->sender.sendMessage(message.get()));};
    if(sender instanceof Player p)plugin.platform().entity(p,task,()->{});else plugin.platform().global(task);
  }
  private void fail(CommandSender sender,Throwable error) {
    Throwable root=error;while(root instanceof CompletionException && root.getCause()!=null)root=root.getCause();
    if(root instanceof HubClient.Error e) {reply(sender,"error."+e.failure().name().toLowerCase(Locale.ROOT));return;}
    String text=PlatformCredentials.redact(root);
    String reason=text.replaceAll("(?i)https?://\\S+","[URL]");
    // core 會遮罩 PAT；不把異常 cause/body/stacktrace 送到 logger。
    if(reason.contains("pull preview")) {reply(sender,"preview-changed");return;}
    if(reason.contains("fast-forward")) {reply(sender,"non-ff");return;}
    if(authenticationRequired(reason)) {reply(sender,"error.unauthorized");return;}
    if(hasStatus(reason,403) || reason.toLowerCase(Locale.ROOT).contains("forbidden")) {reply(sender,"error.forbidden");return;}
    if(hasStatus(reason,404) || reason.toLowerCase(Locale.ROOT).contains("not found")) {reply(sender,"error.not_found");return;}
    if(hasStatus(reason,409)) {reply(sender,"error.conflict");return;}
    if(hasStatus(reason,429) || reason.toLowerCase(Locale.ROOT).contains("too many requests")) {reply(sender,"error.rate_limit");return;}
    if(reason.matches("(?is).*tim(e|ed)[ -]?out.*")) {reply(sender,"error.timeout");return;}
    if(reason.matches("(?is).*connect.*(refused|failed|exception).*|.*unknownhost.*|.*cannot open git.*")) {reply(sender,"error.unreachable");return;}
    send(sender,()->Messages.error(text));
  }
  static boolean authenticationRequired(String text) {
    String lower=text.replaceAll("(?i)https?://\\S+","[URL]").toLowerCase(Locale.ROOT);
    return hasStatus(lower,401) || lower.contains("not authorized") || lower.contains("authentication is required");
  }
  static boolean hasStatus(String text,int code) {
    return java.util.regex.Pattern.compile("(?i)(?:\\bHTTP(?:/[0-9.]+)?\\s+|\\bstatus(?:\\s+code)?\\s*[:=]?\\s*|:\\s+)"+code+"\\b").matcher(text).find();
  }
  static long affectedChunks(Map<DimensionId,org.worldgit.core.apply.ApplyPlan> plans,Map<DimensionId,org.worldgit.core.merge.MergeReport> reports) {
    var chunks=new HashMap<DimensionId,Set<ChunkPos>>();
    plans.forEach((dimension,plan)->{
      var set=chunks.computeIfAbsent(dimension,d->new HashSet<>());set.addAll(plan.chunks().keySet());
      for(var entity:plan.entities()) {if(entity.hint()!=null)set.add(entity.hint());if(entity.target()!=null)set.add(entity.target().chunk());}
    });
    reports.forEach((dimension,report)->{
      var set=chunks.computeIfAbsent(dimension,d->new HashSet<>());
      for(var region:report.regions())for(var atom:region.atoms())if(atom.position()!=null && atom.kind()!=org.worldgit.core.merge.MergeReport.Kind.METADATA && atom.kind()!=org.worldgit.core.merge.MergeReport.Kind.FILE)set.add(atom.position().chunk());
    });
    return chunks.values().stream().mapToLong(Set::size).sum();
  }
  private <T> void async(CommandSender sender,Callable<T> work,java.util.function.Consumer<T> done) {
    String id=key(sender);if(!busy.add(id)) {reply(sender,"busy");return;}
    reply(sender,"start");plugin.repo().submit(work).whenComplete((v,e)->{
      busy.remove(id);if(stopped)return;if(e!=null)fail(sender,e);else done.accept(v);
    });
  }
  void run(CommandSender sender, CommandRequest request) {
    String command = request.command();
    try {
      if (command.startsWith("remote.")) remote(sender, command.substring(7), request.text("remote"), request.text("url"));
      else if (command.equals("fetch") || command.equals("push")) transfer(sender, command,
          request.text("remote", settings.defaultRemote()), request.text("branch"), request.flag("--tags"));
      else if (command.startsWith("pull")) pull(sender, request);
      else if (command.startsWith("pr.")) pr(sender, request);
      else comments(sender, request);
    } catch (IllegalArgumentException e) { reply(sender, "usage"); }
  }
  private void remote(CommandSender sender, String action, String name, String url) {
    if (action.equals("list")) {
      async(sender, () -> { try (var r = open()) { return r.remotes(); } }, rows -> {
        if (rows.isEmpty()) reply(sender, "empty"); rows.forEach((key, spec) -> reply(sender, "remote-row", "name", key, "url", spec.url()));
      }); return;
    }
    async(sender,()->{try(var r=open()) {r.configure(action, name, url, false);return true;}},ignored->{pending.clear();announced=Map.of();plugin.suggestions().invalidateHub();plugin.suggestions().invalidateLocal();reply(sender,"configured");});
  }
  private void transfer(CommandSender sender, String sub, String remote, String b, boolean t) {
    var identity=author(sender,plugin);
    async(sender,()->{try(var r=open()) {
      var result=sub.equals("fetch")?r.fetch(remote,false):r.push(remote,b,t,false,false,identity);
      if(!result.success())throw new IOException(result.error());return result;
    }},result->reply(sender,"transferred","mode",sub,"dimensions",result.commits().size()));
  }
  private Target fetch(String remote,String b) throws IOException {
    try(var r=open()) {var result=r.fetch(remote,false);if(!result.success())throw new IOException(result.error());
      String target=b==null?r.branch():b;return new Target(remote,target,r.remotes().get(remote).url(),r.trackingHeads(remote,target));}
  }
  private void pull(CommandSender sender, CommandRequest request) {
    String id = key(sender); var identity = author(sender, plugin);
    if (request.command().equals("pull.confirm")) {
      Pending p=pending.get(id);
      if(p==null || !p.code().equals(request.text("code")) || System.currentTimeMillis()>p.expires()) {reply(sender,"expired");return;}
      if(!busy.add(id)) {reply(sender,"busy");return;}
      pending.remove(id,p);reply(sender,"start");
      // 先 fetch 網路，不持編輯鎖；再在 coordinator 同一 repo queue 內檢查固定 targets/HEAD。
      plugin.repo().submit(()->{
        var target=fetch(p.remote(),p.branch());
        if(!target.url().equals(p.url()) || !target.heads().equals(p.preview().targets()))throw new IOException("pull preview 已過期；遠端 tip 改變，請重新 /wg pull");return target;
      }).thenCompose(target->plugin.repo().mergeOperation("pull "+p.branch(),ops->{
        var spec=RemoteSpec.read(WorldMapper.map().layout().repositoryRoot()).get(p.remote());
        if(spec==null || !spec.url().equals(p.url()))throw new IOException("pull preview 已過期；remote 設定改變，請重新 /wg pull");
        var result=ops.core().pull(p.preview().targets(),p.preview().expectedHeads(),false,
            new WorldOperations.MergeOptions(true,null,1,false,identity,CommitMetadata.Source.PLUGIN)).result();
        if(result.success() && result.merging()!=null && result.merging().remaining()==0)result=ops.core().continueMerge(identity,CommitMetadata.Source.PLUGIN,false);
        return result;
      })).whenComplete((r,e)->{busy.remove(id);if(e!=null)fail(sender,e);else {send(sender,()->r.success()?Messages.line("paper.merge.result","state",r.state(),"count",r.merging()==null?0:r.merging().remaining(),"commits",r.commits()):Messages.line("paper.merge.partial","message",r.error()));if(r.success())broadcast("applied",p.branch());}});
      return;
    }
    String remote = request.text("remote", settings.defaultRemote()), b = request.text("branch");
    pending.remove(id);
    if(!busy.add(id)) {reply(sender,"busy");return;}
    reply(sender,"start");
    plugin.repo().submit(()->fetch(remote,b)).thenCompose(target->plugin.repo().mergeOperation("pull preview",ops->{
      var preview=ops.core().pull(target.heads(),null,false,new WorldOperations.MergeOptions(true,null,1,true,identity,CommitMetadata.Source.PLUGIN));
      return new Pending(UUID.randomUUID().toString().substring(0,8),remote,target.branch(),target.url(),System.currentTimeMillis()+120000,preview);
    })).whenComplete((p,e)->{
      busy.remove(id);if(e!=null) {fail(sender,e);return;}if(stopped)return;pending.put(id,p);
      var r=p.preview().result();long chunks=affectedChunks(r.plans(),r.reports());
      long sections=r.plans().values().stream().mapToLong(plan->plan.stats().sections()).sum();
      long regions=r.reports().values().stream().mapToLong(report->report.regions().size()).sum();
      reply(sender,"preview","mode",p.preview().fastForward()?"FF":"3-way","regions",regions,"chunks",chunks,"seconds",Math.max(1,(sections+79)/80));
      send(sender,()->Messages.line("paper.remote.confirm","code",p.code()).clickEvent(ClickEvent.suggestCommand("/wg pull confirm "+p.code())));
    });
  }
  private void pr(CommandSender sender, CommandRequest request) {
    String command = request.command();
    if (command.equals("pr.list")) {
      async(sender, () -> { try (var h = hub()) { return h.pulls(); } }, rows -> {
        if (rows.isEmpty()) reply(sender, "empty"); rows.stream().limit(20).forEach(p -> prLine(sender, p));
        if (rows.size() > 20) reply(sender, "pr-more", "count", rows.size() - 20);
      }); return;
    }
    if (command.equals("pr.view")) {
      int number = request.number("id", 0);
      async(sender, () -> { try (var h = hub()) { return h.view(h.find(number)); } }, d -> {
        prLine(sender, d.pr()); reply(sender, "pr-detail", "state", CommentText.plain(d.mergeability(), 32), "approvals", d.approvals(), "required", d.requiredReviews());
      }); return;
    }
    String s = request.text("source"), t = request.text("target", "main"), text = request.text("text");
    var identity = author(sender, plugin);
    async(sender,()->{
      String b;try(var r=open()) {b=s==null?r.branch():s;
        if(r.hasBranch(b)) {var push=r.push(settings.defaultRemote(),b,false,false,false,identity);if(!push.success())throw new IOException(push.error());}
        else {var fetched=r.fetch(settings.defaultRemote(),false);if(!fetched.success())throw new IOException(fetched.error());r.trackingHeads(settings.defaultRemote(),b);}}
      try(var h=hub()) {return h.create(text,b,t);}
    }, p -> { plugin.suggestions().invalidateHub(); prLine(sender, p); });
  }
  private void prLine(CommandSender sender,HubClient.Pull p) {
    // URI 由本機管理者 URL 與驗證過 UUID 組成，Hub 文字不解析。
    plugin.repo().submit(()->{try(var h=hub()){return h.link(p);}}).whenComplete((link,error)->{
      if(error!=null)fail(sender,error);else send(sender,()->Messages.line("paper.remote.pr-row","number",p.number(),"title",CommentText.plain(p.title(),200),"state",CommentText.plain(p.status(),32),"source",CommentText.plain(p.source(),128),"target",CommentText.plain(p.target(),128))
          .append(Component.text(" [Hub]").clickEvent(ClickEvent.openUrl(link))));
    });
  }
  private void comments(CommandSender sender, CommandRequest request) {
    String command = request.command();
    if (command.equals("comments.hide")) {
      plugin.comments().clear((Player) sender); reply(sender, "hidden"); return;
    }
    boolean show = command.equals("comments.show"), here = request.flag("--here");
    Integer pr = request.values().containsKey("id") ? request.number("id", 0) : null;
    var key = request.value("dimension", net.kyori.adventure.key.Key.class);
    String dimension = key == null ? null : key.asString();
    if (here && !(sender instanceof Player) || here && dimension != null) throw new IllegalArgumentException();
    Player player = sender instanceof Player p ? p : null; org.bukkit.World world = player == null ? null : player.getWorld();
    String current = world == null ? null : plugin.dimensionOf(world).orElseThrow().value();
    if (show || here) dimension = current;
    HubClient.Pin pin=null;
    if(here) {var loc=player.getLocation();pin=new HubClient.Pin(current,loc.getBlockX(),loc.getBlockY(),loc.getBlockZ(),null,null,null);}
    String d=dimension;Integer n=pr;HubClient.Pin at=pin;boolean local=here;
    if(show && busy.contains(key(sender))) {reply(sender,"busy");return;}
    long showRequest=show?plugin.comments().request(player):0;
    if (command.equals("comment")) {
      String body = request.text("text");
      async(sender,()->{try(var h=hub()) {h.comment(h.find(n),body,at);return true;}},ignored->reply(sender,"commented"));return;
    }
    async(sender,()->{try(var h=hub()) {var rows=h.comments(n==null?null:h.find(n),d);
      if(local) {int minX=(at.x()>>4)<<4,minZ=(at.z()>>4)<<4;return rows.stream().filter(c->c.pin().x()<=minX+15 && (c.pin().maxX()==null?c.pin().x():c.pin().maxX())>=minX && c.pin().z()<=minZ+15 && (c.pin().maxZ()==null?c.pin().z():c.pin().maxZ())>=minZ).toList();}
      return rows;}},rows->{
      if(show) plugin.platform().entity(player,()->{
        if(player.isOnline() && player.getWorld()==world && (player.hasPermission("worldgit.command.comment") || player.hasPermission("worldgit.admin"))) {
          if(plugin.comments().show(player,world,rows,showRequest))reply(sender,"shown","count",Math.min(64,rows.size()));
        }
      },()->{});
      else {if(rows.isEmpty())reply(sender,"empty");rows.stream().limit(20).forEach(c->reply(sender,"comment-row","text",CommentText.display(c),"dimension",c.pin().dimension(),"x",c.pin().x(),"y",c.pin().y(),"z",c.pin().z()));}
    });
  }
  Map<String, String> suggestionDefaults() {
    return settings.hubUrl().isEmpty() ? Map.of() : Map.of(settings.defaultRemote(), settings.hubUrl());
  }
  List<CommandSuggestions.Entry> suggestionPulls() throws IOException {
    if (stopped) return List.of();
    var urls = RepositorySuggestions.remoteUrls(WorldMapper.map().layout().repositoryRoot(), suggestionDefaults());
    String url = urls.get(settings.defaultRemote()); if (url == null) return List.of();
    try (var h = new HubClient(url, secrets.credentials().resolve(settings.defaultRemote(), url), java.time.Duration.ofMillis(1500))) {
      return h.pulls().stream().limit(256).map(pr -> CommandSuggestions.Entry.of(Integer.toString(pr.number()),
          "paper.command.tip.pr", "title", CommentText.plain(pr.title(), 160), "state", CommentText.plain(pr.status(), 32))).toList();
    }
  }
  private void broadcast(String key,String branch) {
    plugin.platform().global(()->{
      if(stopped)return;reply(plugin.getServer().getConsoleSender(),key,"branch",branch);
      for(var p:plugin.getServer().getOnlinePlayers())plugin.platform().entity(p,()->{
        if(p.hasPermission("worldgit.command.pull") || p.hasPermission("worldgit.admin"))reply(p,key,"branch",branch);
      },()->{});
    });
  }
  private void poll(int attempt) {
    if(stopped || !fetching.compareAndSet(false,true))return;
    plugin.repo().submit(()->{
      try(var r=open()) {
        if(!r.remotes().containsKey(settings.defaultRemote()))return null;
        String b=r.branch();var result=r.fetch(settings.defaultRemote(),false);if(!result.success())throw new IOException(result.error());
        var heads=r.trackingHeads(settings.defaultRemote(),b);
        var tracking=r.tracking().stream().filter(t->t.remote().equals(settings.defaultRemote()) && t.branch().equals(b)).findFirst().orElse(null);
        if(tracking!=null && tracking.behind()>0 && !announced.equals(heads)) {announced=Map.copyOf(heads);broadcast("new-version",b);}
        return null;
      }
    }).whenComplete((v,e)->{
      fetching.set(false);
      if(e!=null && !stopped) {
        if(attempt<5)plugin.platform().asyncDelayed(2L<<attempt,()->poll(attempt+1));
        else reply(plugin.getServer().getConsoleSender(),"notification-failed");
      }
    });
  }
  void quit(Player p) {pending.remove(key(p));}
  public void close() {stopped=true;pending.clear();if(webhook!=null)webhook.close();}
}
