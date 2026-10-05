package org.worldgit.fabric;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.worldgit.fabric.logic.*;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.protocol.CommentsProtocol;

import org.worldgit.core.model.*;
import org.worldgit.core.remote.*;
import org.worldgit.core.service.*;
import org.worldgit.platform.remote.*;

/** 網路走 repo 背景 queue；所有 sender/玩家 API 回到 global/entity owner。 */
final class RemoteCommands implements AutoCloseable {
  private record Pending(String code,String remote,String branch,String localBranch,String url,long expires,WorldOperations.PullPreview preview) {}
  private record Target(String remote,String branch,String url,SortedMap<DimensionId,String> heads) {}
  private final ServerRuntime rt;
  private final RemoteSettings settings;
  private final PlatformCredentials secrets;
  private final Map<String,Pending> pending=new ConcurrentHashMap<>();
  private final Set<String> busy=ConcurrentHashMap.newKeySet();
  private final AtomicBoolean fetching=new AtomicBoolean();
  private volatile boolean stopped;
  private volatile Map<DimensionId,String> announced=Map.of();
  private WebhookReceiver webhook;
  RemoteCommands(ServerRuntime rt,RemoteSettings settings) {
    this.rt=rt;this.settings=settings;secrets=new PlatformCredentials(settings,System.getenv(),net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
  }
  private final ScheduledExecutorService notifications=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"WorldGit-notifications");t.setDaemon(true);return t;});
  void start() throws IOException {
    if(!rt.server().isSingleplayer() && settings.webhook().enabled()) {
      webhook=new WebhookReceiver(settings.webhook(),secrets.webhookSecret(),net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("worldgit-webhook-replays.yml"),e->{
        if(stopped)return false;
        rt.runRepo(()->{
          try(var r=open()) {
            var spec=r.remotes().get(settings.defaultRemote());if(spec==null)return null;
            var endpoint=HubClient.endpoint(spec.url());
            if(endpoint.owner().equals(e.owner()) && endpoint.world().equals(e.world()) && r.branch().equals(e.branch()))poll(0);
          }return null;
        });return true;
      });
    }
    if(settings.fetchIntervalSeconds()>0)notifications.scheduleWithFixedDelay(()->poll(0),settings.fetchIntervalSeconds(),settings.fetchIntervalSeconds(),TimeUnit.SECONDS);
  }
  private WorldRemotes open() throws IOException { return open(DimensionId.OVERWORLD); }
  private WorldRemotes open(DimensionId dimension) throws IOException {
    var layout=WorldLayout.discover(rt.worldRoot());
    var paths=new WorldRepositories(layout).tracked();
    var primary=paths.get(dimension);if(primary==null)throw new IOException("維度尚未 init："+dimension);
    var r=new WorldRemotes(layout.repositoryRoot(),Map.of(dimension,primary),secrets.credentials(),d->{},settings.timeoutSeconds());
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
  private DimensionId dimension(CommandSourceStack sender) { return ServerRuntime.dimensionId(sender.getLevel()); }
  private static String key(CommandSourceStack sender) {return sender.getPlayer()!=null?sender.getPlayer().getUUID().toString():"console";}
  private static CommitMetadata.Identity author(CommandSourceStack sender,ServerRuntime rt) {
    var p=sender.getPlayer();return p==null?Identities.server(rt.config()):Identities.player(p.nameAndId().name(),p.getUUID(),rt.config().identity().playerEmailDomain());
  }
  private void reply(CommandSourceStack sender,String message,Object... args) {
    send(sender,()->Mini.render(rt.catalog(),Texts.locale(rt,sender.getPlayer()),Msg.prefixed("fabric.remote."+message,args),rt.palette()));
  }
  private void send(CommandSourceStack sender,java.util.function.Supplier<Component> message) {
    rt.postToServer(()->{
      if(stopped || sender.getPlayer()!=null && rt.server().getPlayerList().getPlayer(sender.getPlayer().getUUID())!=sender.getPlayer())return;
      var nativeText=net.kyori.adventure.platform.modcommon.MinecraftServerAudiences.of(rt.server()).asNative(message.get());
      sender.sendSuccess(()->nativeText,false);
    });
  }
  private void fail(CommandSourceStack sender,Throwable error) {
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
    if(reason.contains("乾淨") || reason.contains("未提交"))reply(sender,"dirty");
    else if(reason.contains("MERGING"))reply(sender,"merging");
    else if(reason.contains("PARTIAL"))reply(sender,"partial");
    else reply(sender,"error.invalid");
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
  private <T> void async(CommandSourceStack sender,Callable<T> work,java.util.function.Consumer<T> done) {
    String id=key(sender);if(!busy.add(id)) {reply(sender,"busy");return;}
    reply(sender,"start");rt.runRepo(work).whenComplete((v,e)->{
      busy.remove(id);if(stopped)return;if(e!=null)fail(sender,e);else rt.postToServer(()->{if(!stopped && (sender.getPlayer()==null || rt.server().getPlayerList().getPlayer(sender.getPlayer().getUUID())==sender.getPlayer()))done.accept(v);});
    });
  }
  void run(CommandSourceStack sender,String sub,String[] args) {
    try {
      switch(sub) {
        case "remote"->remote(sender,args);
        case "fetch","push"->transfer(sender,sub,args);
        case "pull"->pull(sender,args);
        case "pr"->pr(sender,args);
        case "comments","comment"->comments(sender,sub,args);
        default->reply(sender,"usage");
      }
    } catch(IllegalArgumentException e) {reply(sender,"usage");}
  }
  private static String branch(String s) {
    if(s.isBlank() || s.startsWith("-") || !s.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,127}") || s.contains("..") || s.contains("//") || s.endsWith("/") || s.endsWith(".") || s.endsWith(".lock"))throw new IllegalArgumentException();return s;
  }
  private void remote(CommandSourceStack sender,String[] args) {
    var dimension=dimension(sender);
    if(args.length==0)throw new IllegalArgumentException();String action=args[0];
    if(action.equals("list") && args.length==1) {async(sender,()->{try(var r=open(dimension)) {return r.remotes();}},rows->{
      if(rows.isEmpty())reply(sender,"empty");rows.forEach((name,spec)->reply(sender,"remote-row","name",name,"url",spec.url()));
    });return;}
    if(!Set.of("add","remove","set-url").contains(action) || args.length!=(action.equals("remove")?2:3))throw new IllegalArgumentException();
    // core URL validation never includes userinfo/PAT in error.
    async(sender,()->{try(var r=open(dimension)) {r.configure(action,args[1],args.length==3?args[2]:null,false);return true;}},ignored->{pending.clear();announced=Map.of();reply(sender,"configured");});
  }
  private void transfer(CommandSourceStack sender,String sub,String[] args) {
    boolean tags=false;var words=new ArrayList<String>();
    for(String arg:args) {if(sub.equals("push") && arg.equals("--tags") && !tags)tags=true;else if(arg.startsWith("--"))throw new IllegalArgumentException();else words.add(arg);}
    if(words.size()>(sub.equals("push")?2:1))throw new IllegalArgumentException();
    String remote=words.isEmpty()?settings.defaultRemote():words.getFirst();String b=words.size()==2?branch(words.get(1)):null;boolean t=tags;
    var identity=author(sender,rt); var dimension=dimension(sender);
    async(sender,()->{try(var r=open(dimension)) {
      var result=sub.equals("fetch")?r.fetch(remote,false):r.push(remote,b,t,false,false,identity);
      if(!result.success())throw new IOException(result.error());return result;
    }},result->reply(sender,"transferred","mode",sub,"dimensions",result.commits().size()));
  }
  private Target fetch(String remote,String b,DimensionId dimension) throws IOException {
    try(var r=open(dimension)) {var result=r.fetch(remote,false);if(!result.success())throw new IOException(result.error());
      String target=b==null?r.branch():b;return new Target(remote,target,r.remotes().get(remote).url(),r.trackingHeads(remote,target));}
  }
  private void pull(CommandSourceStack sender,String[] args) {
    String id=key(sender);var identity=author(sender,rt); var dimension=dimension(sender);
    if(args.length==2 && args[0].equals("confirm")) {
      Pending p=pending.get(id);
      if(p==null || !p.code().equals(args[1]) || System.currentTimeMillis()>p.expires()) {reply(sender,"expired");return;}
      if(!busy.add(id)) {reply(sender,"busy");return;}
      pending.remove(id,p);reply(sender,"start");
      // 先 fetch 網路，不持編輯鎖；再在 coordinator 同一 repo queue 內檢查固定 targets/HEAD。
      rt.runRepo(()->{
        var target=fetch(p.remote(),p.branch(),p.preview().targets().firstKey());
        if(!target.url().equals(p.url()) || !target.heads().equals(p.preview().targets()))throw new IOException("pull preview 已過期；遠端 tip 改變，請重新 /wg pull");return target;
      }).thenCompose(target->rt.live(p.preview().targets().firstKey(),ops->{
        var spec=RemoteSpec.read(new WorldRepositories(WorldLayout.discover(rt.worldRoot())).tracked().get(p.preview().targets().firstKey())).get(p.remote());
        if(spec==null || !spec.url().equals(p.url()))throw new IOException("pull preview 已過期；remote 設定改變，請重新 /wg pull");
        if(!Objects.equals(p.localBranch(),currentBranch(ops)))throw new IOException("pull preview 已過期；本地分支改變，請重新 /wg pull");
        var result=ops.pull(p.preview().targets(),p.preview().expectedHeads(),false,
            new WorldOperations.MergeOptions(true,null,1,false,identity,CommitMetadata.Source.MOD)).result();
        if(result.success() && result.merging()!=null && result.merging().remaining()==0)result=ops.continueMerge(identity,CommitMetadata.Source.MOD,false);
        return result;
      })).whenComplete((r,e)->{busy.remove(id);if(e!=null)fail(sender,e);else {if(r.success())reply(sender,"result","state",r.state(),"count",r.merging()==null?0:r.merging().remaining());else fail(sender,new IOException(r.error()));if(r.success())broadcast("applied",p.branch());}});
      return;
    }
    if(args.length>2 || args.length>0 && args[0].startsWith("--"))throw new IllegalArgumentException();
    String remote=args.length>0?args[0]:settings.defaultRemote(),b=args.length>1?branch(args[1]):null;
    pending.remove(id);
    if(!busy.add(id)) {reply(sender,"busy");return;}
    reply(sender,"start");
    rt.runRepo(()->fetch(remote,b,dimension)).thenCompose(target->rt.live(dimension,ops->{
      var preview=ops.pull(target.heads(),null,false,new WorldOperations.MergeOptions(true,null,1,true,identity,CommitMetadata.Source.MOD));
      return new Pending(UUID.randomUUID().toString().substring(0,8),remote,target.branch(),currentBranch(ops),target.url(),System.currentTimeMillis()+120000,preview);
    })).whenComplete((p,e)->{
      busy.remove(id);if(e!=null) {fail(sender,e);return;}if(stopped)return;rt.postToServer(()->{if(!stopped && (sender.getPlayer()==null || rt.server().getPlayerList().getPlayer(sender.getPlayer().getUUID())==sender.getPlayer()))pending.put(id,p);});
      var r=p.preview().result();long chunks=affectedChunks(r.plans(),r.reports());
      long sections=r.plans().values().stream().mapToLong(plan->plan.stats().sections()).sum();
      long regions=r.reports().values().stream().mapToLong(report->report.regions().size()).sum();
      reply(sender,"preview","mode",p.preview().fastForward()?"FF":"3-way","regions",regions,"chunks",chunks,"seconds",Math.max(1,(sections+79)/80));
      send(sender,()->Mini.render(rt.catalog(),Texts.locale(rt,sender.getPlayer()),Msg.prefixed("fabric.remote.confirm","code",p.code()),rt.palette()).clickEvent(ClickEvent.suggestCommand("/wg pull confirm "+p.code())));
    });
  }
  private static String currentBranch(WorldOperations ops) throws IOException {
    return ops.branches().stream().filter(WorldOperations.Branch::current).map(WorldOperations.Branch::name).findFirst().orElse(null);
  }
  private void pr(CommandSourceStack sender,String[] args) {
    if(args.length==1 && args[0].equals("list")) {async(sender,()->{try(var h=hub()) {return h.pulls();}},rows->{if(rows.isEmpty())reply(sender,"empty");rows.stream().limit(20).forEach(p->prLine(sender,p));if(rows.size()>20)reply(sender,"pr-more","count",rows.size()-20);});return;}
    if(args.length==2 && args[0].equals("view")) {int number=number(args[1]);async(sender,()->{try(var h=hub()) {return h.view(h.find(number));}},d->{prLine(sender,d.pr());reply(sender,"pr-detail","state",CommentText.plain(d.mergeability(),32),"approvals",d.approvals(),"required",d.requiredReviews());});return;}
    if(args.length<2 || !args[0].equals("create"))throw new IllegalArgumentException();
    String source=null,target="main";var title=new ArrayList<String>();
    for(int i=1;i<args.length;i++) {
      if(args[i].equals("--source") && source==null && i+1<args.length)source=branch(args[++i]);
      else if(args[i].equals("--target") && i+1<args.length)target=branch(args[++i]);
      else if(args[i].startsWith("--"))throw new IllegalArgumentException();else title.add(args[i]);
    }
    if(title.isEmpty())throw new IllegalArgumentException();String s=source,t=target,text=String.join(" ",title);var identity=author(sender,rt);
    async(sender,()->{
      String b;try(var r=open()) {b=s==null?r.branch():s;
        if(r.hasBranch(b)) {var push=r.push(settings.defaultRemote(),b,false,false,false,identity);if(!push.success())throw new IOException(push.error());}
        else {var fetched=r.fetch(settings.defaultRemote(),false);if(!fetched.success())throw new IOException(fetched.error());r.trackingHeads(settings.defaultRemote(),b);}}
      try(var h=hub()) {return h.create(text,b,t);}
    },p->prLine(sender,p));
  }
  private void prLine(CommandSourceStack sender,HubClient.Pull p) {
    // URI 由本機管理者 URL 與驗證過 UUID 組成，Hub 文字不解析。
    rt.runRepo(()->{try(var h=hub()){return h.link(p);}}).whenComplete((link,error)->{
      if(error!=null)fail(sender,error);else send(sender,()->Mini.render(rt.catalog(),Texts.locale(rt,sender.getPlayer()),Msg.prefixed("fabric.remote.pr-row","number",p.number(),"title",CommentText.plain(p.title(),200),"state",CommentText.plain(p.status(),32),"source",CommentText.plain(p.source(),128),"target",CommentText.plain(p.target(),128)),rt.palette())
          .append(Component.text(" [Hub]").clickEvent(ClickEvent.openUrl(link))));
    });
  }
  private static int number(String s) {int n=Integer.parseInt(s.replaceFirst("^#",""));if(n<1)throw new IllegalArgumentException();return n;}
  private final Map<UUID,Long> commentRequests=new HashMap<>();
  private final Map<UUID,String> commentDimensions=new HashMap<>();
  private long requestId;
  private void clearComments(ServerPlayer p) {
    commentRequests.put(p.getUUID(),++requestId);commentDimensions.remove(p.getUUID());
    rt.sendComments(p,ServerRuntime.dimensionId((net.minecraft.server.level.ServerLevel)p.level()),List.of());
  }
  private void comments(CommandSourceStack sender,String sub,String[] args) {
    var player=sender.getPlayer();
    if(sub.equals("comments") && args.length>0 && args[0].equals("hide")) {
      if(player==null || args.length>3)throw new IllegalArgumentException();
      clearComments(player);reply(sender,"hidden");return;
    }
    boolean show=sub.equals("comments") && args.length>0 && args[0].equals("show"),here=false;
    int offset=show?1:0;Integer pr=null;String dimension=null;var text=new ArrayList<String>();
    if(sub.equals("comment")) {if(args.length<2)throw new IllegalArgumentException();pr=number(args[0]);offset=1;}
    for(int i=offset;i<args.length;i++) {
      if(args[i].equals("--here") && !here)here=true;
      else if(sub.equals("comments") && args[i].equals("--dimension") && dimension==null && i+1<args.length) {dimension=args[++i];new DimensionId(dimension);}
      else if(sub.equals("comments") && pr==null) {if(args[i].equals("pr") && i+1<args.length)pr=number(args[++i]);else pr=number(args[i]);}
      else if(sub.equals("comment") && !args[i].startsWith("--"))text.add(args[i]);else throw new IllegalArgumentException();
    }
    if((show || here) && player==null || here && dimension!=null)throw new IllegalArgumentException();
    if(show && !rt.handshake().supports(player.getUUID(),CommentsProtocol.CAPABILITY)) {reply(sender,"no-comments-mod");return;}
    String current=player==null?null:ServerRuntime.dimensionId((net.minecraft.server.level.ServerLevel)player.level()).value();
    if(show || here)dimension=current;
    var pos=player==null?null:player.blockPosition();
    HubClient.Pin pin=here?new HubClient.Pin(current,pos.getX(),pos.getY(),pos.getZ(),null,null,null):null;
    String d=dimension;Integer n=pr;HubClient.Pin at=pin;boolean local=here;
    if(show && busy.contains(key(sender))) {reply(sender,"busy");return;}
    if(show) {clearComments(player);commentDimensions.put(player.getUUID(),current);}
    long request=show?commentRequests.get(player.getUUID()):0;
    if(sub.equals("comment")) {
      if(text.isEmpty())throw new IllegalArgumentException();String body=String.join(" ",text);
      async(sender,()->{try(var h=hub()) {h.comment(h.find(n),body,at);return true;}},ignored->reply(sender,"commented"));return;
    }
    async(sender,()->{try(var h=hub()) {
      var rows=h.comments(n==null?null:h.find(n),d);
      if(local) {int minX=(at.x()>>4)<<4,minZ=(at.z()>>4)<<4;return rows.stream().filter(c->c.pin().x()<=minX+15 && (c.pin().maxX()==null?c.pin().x():c.pin().maxX())>=minX && c.pin().z()<=minZ+15 && (c.pin().maxZ()==null?c.pin().z():c.pin().maxZ())>=minZ).toList();}
      return rows;
    }},rows->{
      if(show) {
        if(Objects.equals(commentRequests.get(player.getUUID()),request) && current.equals(ServerRuntime.dimensionId((net.minecraft.server.level.ServerLevel)player.level()).value()) && WgCommands.allowed(rt.config().readPermissionLevel()).test(sender)) {
          rt.sendComments(player,new DimensionId(current),rows.stream().limit(64).toList());reply(sender,"shown","count",Math.min(64,rows.size()));
        }
      }else {if(rows.isEmpty())reply(sender,"empty");rows.stream().limit(20).forEach(c->reply(sender,"comment-row","text",CommentText.display(c),"dimension",c.pin().dimension(),"x",c.pin().x(),"y",c.pin().y(),"z",c.pin().z()));}
    });
  }
  void tick() {
    for(var p:rt.server().getPlayerList().getPlayers()) {
      String d=commentDimensions.get(p.getUUID());
      if(d!=null && (!d.equals(ServerRuntime.dimensionId((net.minecraft.server.level.ServerLevel)p.level()).value()) || !WgCommands.allowed(rt.config().readPermissionLevel()).test(p.createCommandSourceStack())))clearComments(p);
    }
  }
  private void broadcast(String key,String branch) {
    rt.postToServer(()->{
      if(stopped)return;reply(rt.server().createCommandSourceStack(),key,"branch",branch);
      for(var p:rt.server().getPlayerList().getPlayers())
        if(WgCommands.allowed(rt.config().writePermissionLevel()).test(p.createCommandSourceStack()))reply(p.createCommandSourceStack(),key,"branch",branch);
    });
  }
  private void poll(int attempt) {
    if(stopped || !fetching.compareAndSet(false,true))return;
    rt.runRepo(()->{
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
        if(attempt<5)notifications.schedule(()->poll(attempt+1),2L<<attempt,TimeUnit.SECONDS);
        else reply(rt.server().createCommandSourceStack(),"notification-failed");
      }
    });
  }
  void quit(ServerPlayer p) {pending.remove(p.getUUID().toString());commentRequests.remove(p.getUUID());commentDimensions.remove(p.getUUID());}
  public void close() {stopped=true;pending.clear();commentRequests.clear();commentDimensions.clear();notifications.shutdownNow();if(webhook!=null)webhook.close();}
}
