package org.worldgit.paper;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.apply.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.*;
import org.worldgit.core.merge.*;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.protocol.DiffPalette;
import org.worldgit.protocol.Protocol;

/**
 * /wg init | status | commit | log | diff | clear。所有重工作都在背景 repo 執行緒，指令本身立刻返回；
 * 結果以聊天訊息送回（Player 走自己的 entity scheduler，Folia 安全）。權限節點見 plugin.yml。
 */
final class Commands implements CommandExecutor, TabCompleter {
  private static final List<String> SUBS = List.of("init", "status", "commit", "log", "diff", "clear", "reload", "restore", "switch", "branch", "stash", "reset", "cancel", "merge", "resolve", "tool", "conflicts", "conflict-preview", "revert", "cherry-pick", "help");
  private final WorldGitPlugin plugin;
  private final Debug debug;

  private static final class UserError extends IllegalArgumentException {
    final String key;
    final Object[] args;
    UserError(String key, Object... args) { super(key); this.key = key; this.args = args; }
  }
  private static UserError bad(String key, Object... args) { return new UserError(key, args); }

  Commands(WorldGitPlugin plugin) {
    this.plugin = plugin;
    this.debug = new Debug(plugin);
  }

  private void reply(CommandSender sender, Component... lines) {
    reply(sender, List.of(lines));
  }

  private void reply(CommandSender sender, List<Component> lines) {
    Runnable send = () -> lines.forEach(sender::sendMessage);
    if (sender instanceof Player p) plugin.platform().entity(p, send, () -> {});
    else send.run();
  }

  private boolean allowed(CommandSender sender, String sub) {
    if (sender.hasPermission("worldgit.command." + sub) || sender.hasPermission("worldgit.admin")) return true;
    reply(sender, Messages.permission(sub));
    return false;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    return Messages.inLocale(sender, () -> execute(sender, args));
  }

  private boolean execute(CommandSender sender, String[] args) {
    if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
      help(sender);
      return true;
    }
    String sub = args[0].toLowerCase(Locale.ROOT);
    if (sub.equals("debug")) {
      if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("worldgit.debug")) {
        reply(sender, Messages.debugPermission());
        return true;
      }
      try {
        debug.run(sender, Arrays.copyOfRange(args, 1, args.length));
      } catch (RuntimeException e) {
        reply(sender, Messages.error(String.valueOf(e.getMessage())));
      }
      return true;
    }
    if (!SUBS.contains(sub)) {
      reply(sender, Messages.line("paper.error.unknown-command", "sub", sub), Messages.line("paper.error.help-command"));
      return true;
    }
    if (!allowed(sender, sub)) return true;
    var rest = Arrays.copyOfRange(args, 1, args.length);
    try {
      switch (sub) {
        case "merge", "resolve", "revert", "cherry-pick" -> merge(sender,sub,rest);
        case "tool" -> { if(rest.length!=0) throw bad("paper.merge.usage"); if(!(sender instanceof Player p)) throw bad("paper.error.player-only"); plugin.merges().tool(p); }
        case "conflicts", "conflict-preview" -> conflicts(sender,sub,rest);
        case "init" -> init(sender, rest);
        case "status" -> status(sender, rest);
        case "commit" -> commit(sender, rest);
        case "log" -> log(sender, rest);
        case "diff" -> diff(sender, rest);
        case "clear" -> clear(sender);
        case "reload" -> reload(sender);
        case "restore", "switch", "branch", "stash", "reset" -> operation(sender,sub,rest);
        case "cancel" -> { if(rest.length!=0) throw bad("paper.apply.usage"); reply(sender,Messages.line(plugin.repo().cancel() ? "paper.apply.cancel-requested" : "paper.apply.no-operation")); }
        default -> help(sender);
      }
    } catch (UserError e) {
      reply(sender, Messages.line(e.key, e.args));
    } catch (IllegalArgumentException e) {
      reply(sender, Messages.line("common.error", "message", e.getMessage()));
    } catch (RuntimeException e) {
      plugin.getLogger().log(Level.WARNING, "指令 /wg " + sub + " 失敗", e);
      reply(sender, Messages.line("common.error", "message", e.toString()));
    }
    return true;
  }

  private void help(CommandSender sender) {
    reply(
        sender,
        Messages.line("paper.help.title"), Messages.line("paper.help.init"), Messages.line("paper.help.status"),
        Messages.line("paper.help.commit"), Messages.line("paper.help.log"), Messages.line("paper.help.diff"),
        Messages.line("paper.help.clear"), Messages.line("paper.help.reload"), Messages.line("paper.apply.help"),Messages.line("paper.merge.usage"));
  }

  private CommitMetadata.Identity identity(CommandSender sender) {
    if (sender instanceof Player p)
      return new CommitMetadata.Identity(p.getName(), p.getUniqueId() + "@players.worldgit.invalid");
    return plugin.serverIdentity();
  }

  private DiffPalette palette() {
    try {
      return Messages.palette(plugin.repo().readLocal().palette());
    } catch (IOException | RuntimeException e) {
      return DiffPalette.DEFAULT;
    }
  }

  private <T> void fail(CommandSender sender, Throwable error) {
    Throwable root = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
    reply(sender, Messages.error(root.getMessage() == null ? root.toString() : root.getMessage()));
    if (!(root instanceof IOException)) plugin.getLogger().log(Level.WARNING, "WorldGit 指令失敗", root);
  }

  // ---------------------------------------------------------------- init

  private void init(CommandSender sender, String[] args) {
    String template = "creative";
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--template") && i + 1 < args.length) template = args[++i];
      else if (args[i].startsWith("--template=")) template = args[i].substring("--template=".length());
      else throw bad("paper.error.unknown-option", "option", args[i]);
    }
    if (!Set.of("creative", "survival").contains(template))
      throw bad("paper.error.init-template");
    final String selectedTemplate = template;
    Messages.inLocale(sender, () -> reply(sender, Messages.line("paper.init.start", "template", selectedTemplate)));
    plugin.repo().init(template, WorldGitConfig.Track.ALL, identity(sender)).whenComplete((batch, error) -> Messages.inLocale(sender, () -> {
      if (error != null) { fail(sender, error); return; }
      var lines = new ArrayList<Component>();
      lines.add(Messages.line("paper.init.done"));
      lines.addAll(Messages.commit(batch, palette()));
      reply(sender, lines);
    }));
  }

  // ---------------------------------------------------------------- status

  private void status(CommandSender sender, String[] args) {
    boolean show = false, full = false;
    for (String a : args) {
      switch (a) {
        case "--show" -> show = true;
        case "--full" -> full = true;
        default -> throw bad("paper.error.unknown-option", "option", a);
      }
    }
    final boolean fShow = show, fFull = full;
    Player player = sender instanceof Player p ? p : null;
    if (show && player == null) throw bad("paper.error.player-only");
    plugin.repo().status(fFull).whenComplete((batch, error) -> Messages.inLocale(sender, () -> {
      if (error != null) {
        fail(sender, error);
        return;
      }
      var palette = palette();
      var lines = new ArrayList<Component>();
      var merging=plugin.merges().state();
      if(merging!=null) lines.add(Messages.line("paper.merge.status","count",merging.remaining()));
      lines.add(Messages.line("paper.status.title", "suffix", fFull ? Messages.fullSuffix() : ""));
      batch.dimensions().forEach((id, o) -> {
        if (!o.success()) lines.add(Messages.line("paper.commit.dimension-failed", "dimension", id.value(), "message", o.error()));
        else lines.addAll(Messages.status(id, o.value(), palette, 8));
      });
      var attribution = plugin.attribution().peek();
      if (!attribution.empty()) {
        var names = new TreeSet<String>();
        attribution.byDimension().values().forEach(l -> l.forEach(c -> names.add(c.name() + "(" + c.chunks().size() + ")")));
        lines.add(Messages.line("paper.status.authors", "names", String.join("、", names)));
      }
      if (fShow && player != null) lines.addAll(showStatus(player, batch));
      reply(sender, lines);
    }));
  }

  private List<Component> showStatus(Player player, WorldRepositories.Batch<DimensionRepository.Status> batch) {
    var lines = new ArrayList<Component>();
    boolean mod = plugin.fabric().ready(player);
    if (!mod) lines.add(Messages.line("paper.status.show-no-mod"));
    var id = plugin.dimensionOf(player.getWorld());
    if (id.isEmpty() || !batch.dimensions().containsKey(id.get()) || !batch.dimensions().get(id.get()).success()) {
      lines.add(Messages.line("paper.status.show-no-data"));
      return lines;
    }
    if (!mod) {
      var diff = batch.dimensions().get(id.get()).value().diff();
      if (diff.empty()) {
        plugin.displays().clear(player);
        lines.add(Messages.line("paper.status.show-empty"));
      } else lines.add(displayLine(plugin.displays().showBoxes(player, diff, palette())));
      return lines;
    }
    try {
      var diff = batch.dimensions().get(id.get()).value().diff();
      if (diff.empty()) {
        plugin.fabric().clear(player);
        lines.add(Messages.line("paper.status.show-empty"));
      } else {
        int packets = plugin.fabric().sendStatus(player, id.get(), diff);
        lines.add(Messages.line("paper.status.show-sent", "count", diff.sections().size(), "packets", packets));
      }
    } catch (IOException | RuntimeException e) {
      lines.add(Messages.line("paper.diff.send-failed", "message", e.getMessage()));
    }
    return lines;
  }

  // ---------------------------------------------------------------- commit

  private void commit(CommandSender sender, String[] args) {
    int m = Arrays.asList(args).indexOf("-m");
    if (m < 0 || m + 1 >= args.length) throw bad("paper.error.commit-usage");
    String message = String.join(" ", Arrays.copyOfRange(args, m + 1, args.length)).trim();
    if (message.isBlank()) throw bad("paper.error.commit-empty");
    if(plugin.merges().state()!=null) {
      plugin.repo().mergeOperation("merge commit",ops->ops.core().commitMerge(identity(sender),CommitMetadata.Source.PLUGIN,message,false))
          .whenComplete((result,error)->plugin.merges().feedback(sender,result,error)); return;
    }
    Messages.inLocale(sender, () -> reply(sender, Messages.line("paper.commit.start")));
    plugin
        .repo()
        .commit(new RepoService.CommitRequest(message, identity(sender), false, status -> true))
        .whenComplete((batch, error) -> Messages.inLocale(sender, () -> {
          if (error != null) {
            fail(sender, error);
            return;
          }
          reply(sender, Messages.commit(batch, palette()));
          plugin.notifyCommit(batch, false);
        }));
  }

  // ---------------------------------------------------------------- log

  private void log(CommandSender sender, String[] args) {
    int limit = 10;
    if (args.length > 0)
      try {
        limit = Integer.parseInt(args[0]);
        if (limit < 1 || limit > 100) throw new NumberFormatException();
      } catch (NumberFormatException e) {
        throw bad("paper.error.log-limit");
      }
    final int n = limit;
    plugin.repo().log(n).whenComplete((logs, error) -> Messages.inLocale(sender, () -> {
      if (error != null) {
        fail(sender, error);
        return;
      }
      var lines = new ArrayList<Component>();
      lines.add(Messages.line("paper.log.title", "count", n));
      lines.addAll(Messages.log(logs, n));
      reply(sender, lines);
    }));
  }

  // ---------------------------------------------------------------- diff

  private void diff(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) throw bad("paper.error.diff-player");
    boolean show = false;
    int radius = plugin.settings().showRadiusChunks();
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--show")) show = true;
      else if (args[i].equals("--radius") && i + 1 < args.length)
        try {
          radius = Integer.parseInt(args[++i]);
          if (radius < 1 || radius > 32) throw new NumberFormatException();
        } catch (NumberFormatException e) {
          throw bad("paper.error.diff-radius");
        }
      else throw bad("paper.error.unknown-option", "option", args[i]);
    }
    final boolean fShow = show;
    var dimension = plugin.dimensionOf(player.getWorld()).orElseThrow(() -> bad("paper.error.diff-not-tracked"));
    Set<ChunkPos> window = WorldGitPlugin.window(player, radius);
    final int r = radius;
    plugin.repo().diff(Map.of(dimension, window), false).whenComplete((batch, error) -> Messages.inLocale(sender, () -> {
      if (error != null) {
        fail(sender, error);
        return;
      }
      var outcome = batch.dimensions().get(dimension);
      if (outcome == null || !outcome.success()) {
        reply(sender, Messages.line("paper.error.not-initialized"));
        return;
      }
      var palette = palette();
      WorldDiff diff = outcome.value().diff();
      var lines = new ArrayList<Component>();
      lines.add(Messages.line("paper.diff.title", "dimension", dimension.value(), "radius", r));
      var counts = diff.counts();
      lines.add(Component.text("  ", NamedTextColor.GRAY).append(Messages.counts(counts, palette)).append(Messages.line("paper.status.section-count", "count", diff.sections().size())).append(Messages.line("paper.status.entity-count", "count", diff.entities().size())));
      var samples = diff.sections().stream().flatMap(s -> s.blocks().stream()).limit(10).toList();
      for (var b : samples) lines.add(Component.text("  ").append(Messages.block(dimension, b, palette)));
      long total = counts.added() + counts.removed() + counts.modified() + counts.conflict();
      if (total > samples.size()) lines.add(Messages.line("paper.diff.more", "count", total - samples.size()));
      if (fShow) lines.addAll(showDiff(player, dimension, diff, total));
      reply(sender, lines);
    }));
  }

  private List<Component> showDiff(Player player, DimensionId dimension, WorldDiff diff, long total) {
    var lines = new ArrayList<Component>();
    if (!plugin.fabric().ready(player)) {
      lines.add(Messages.line("paper.diff.no-mod"));
      if (total == 0) {
        plugin.displays().clear(player);
        lines.add(Messages.line("paper.diff.empty"));
      } else if (total <= plugin.settings().displayMaxEntities()) lines.add(displayLine(plugin.displays().showCells(player, diff, palette())));
      else lines.add(displayLine(plugin.displays().showBoxes(player, diff, palette())));
      return lines;
    }
    try {
      if (total == 0) {
        plugin.fabric().clear(player);
        lines.add(Messages.line("paper.diff.empty"));
      } else if (total > plugin.settings().showMaxCells()) {
        int packets = plugin.fabric().sendStatus(player, dimension, diff);
        lines.add(Messages.line("paper.diff.too-large", "count", plugin.settings().showMaxCells(), "packets", packets));
      } else {
        int packets = plugin.fabric().sendDiff(player, diff);
        lines.add(Messages.line("paper.diff.sent", "count", total, "packets", packets, "version", Protocol.VERSION));
      }
    } catch (IOException | RuntimeException e) {
      lines.add(Messages.line("paper.diff.send-failed", "message", e.getMessage()));
    }
    return lines;
  }

  private Component displayLine(DisplayFallback.Shown shown) {
    return Messages.line(shown.boxes() ? "paper.display.boxes" : "paper.display.cells", "count", shown.entities(), "omitted", shown.omitted(), "seconds", plugin.settings().displaySeconds());
  }

  private void clear(CommandSender sender) {
    if (!(sender instanceof Player player)) throw bad("paper.error.player-only");
    plugin.fabric().clear(player);
    plugin.displays().clear(player);
    reply(sender, Messages.line("paper.clear.done"));
  }

  private void reload(CommandSender sender) {
    Messages.reload();
    reply(sender, Messages.line("paper.reload.done"));
  }

  private record Applied(PaperOperations.Result result,String head) {}
  private void operation(CommandSender sender,String sub,String[] args) {
    if(sub.equals("branch")) {
      if(args.length>2 || (args.length==2 && !args[0].equals("-d"))) throw bad("paper.apply.usage");
      plugin.repo().operation("branch",ops->{
        if(args.length==0) return ops.branches().stream().map(b->(b.current()?"* ":"  ")+b.name()+" "+b.commits()).toList();
        if(args[0].equals("-d")) { if(args.length!=2) throw new IOException("/wg branch -d <name>"); ops.deleteBranch(args[1]); }
        else ops.createBranch(args[0],null);
        return List.of(args[args.length-1]);
      }).whenComplete((rows,error)->Messages.inLocale(sender,()->{ if(error!=null) fail(sender,error); else { reply(sender,Messages.line("paper.apply.branch")); rows.forEach(r->reply(sender,Component.text(r))); } }));
      return;
    }
    if(sub.equals("stash") && args.length>=1 && Set.of("list","drop").contains(args[0])) {
      if(args.length>2 || (args[0].equals("list") && args.length!=1)) throw bad("paper.apply.usage");
      int index=args.length==2 ? Integer.parseInt(args[1]) : 0;
      plugin.repo().operation("stash",ops->{
        if(args[0].equals("drop")) { ops.stashDrop(index); return List.of("stash@{"+index+"}"); }
        var list=ops.stashes(); var rows=new ArrayList<String>();
        for(int i=0;i<list.size();i++) rows.add("stash@{"+i+"} "+list.get(i).time()+" "+list.get(i).message());
        return rows;
      }).whenComplete((rows,error)->Messages.inLocale(sender,()->{ if(error!=null) fail(sender,error); else { reply(sender,Messages.line("paper.apply.stash")); rows.forEach(r->reply(sender,Component.text(r))); } }));
      return;
    }
    String revision=null; boolean dry=false,force=false,stash=false; Scope scope=Scope.all(); DimensionId dimension=null;
    if(sub.equals("restore")||sub.equals("switch")) {
      if(args.length<1 || args[0].startsWith("--")) throw bad("paper.apply.usage");
      revision=args[0];
      boolean range=false;
      for(int i=1;i<args.length;i++) switch(args[i]) {
        case "--dry-run" -> dry=true;
        case "--force" -> { if(!sub.equals("switch")) throw bad("paper.apply.usage"); force=true; }
        case "--stash" -> { if(!sub.equals("switch")) throw bad("paper.apply.usage"); stash=true; }
        case "--selection" -> {
          if(!sub.equals("restore") || range || !(sender instanceof Player player)) throw bad("paper.apply.usage");
          range=true; scope=WorldEditHook.selection(player); dimension=plugin.dimensionOf(player.getWorld()).orElseThrow(()->bad("paper.error.diff-not-tracked"));
        }
        case "--chunks" -> {
          if(!sub.equals("restore")||range||i+1>=args.length) throw bad("paper.apply.usage");
          int radius=Integer.parseInt(args[++i]); if(radius<0||radius>256) throw bad("paper.apply.usage");
          var world=sender instanceof Player p ? p.getWorld() : plugin.getServer().getWorlds().getFirst();
          var loc=sender instanceof Player p ? p.getLocation() : world.getSpawnLocation();
          scope=Scope.chunkRadius(loc.getBlockX()>>4,loc.getBlockZ()>>4,radius); range=true;
          dimension=plugin.dimensionOf(world).orElseThrow(()->bad("paper.error.diff-not-tracked"));
        }
        case "--box" -> {
          if(!sub.equals("restore")||range||i+6>=args.length) throw bad("paper.apply.usage");
          int x1=Integer.parseInt(args[++i]),y1=Integer.parseInt(args[++i]),z1=Integer.parseInt(args[++i]);
          int x2=Integer.parseInt(args[++i]),y2=Integer.parseInt(args[++i]),z2=Integer.parseInt(args[++i]);
          scope=Scope.box(x1,y1,z1,x2,y2,z2); range=true;
          var world=sender instanceof Player p ? p.getWorld() : plugin.getServer().getWorlds().getFirst();
          dimension=plugin.dimensionOf(world).orElseThrow(()->bad("paper.error.diff-not-tracked"));
        }
        default -> throw bad("paper.error.unknown-option","option",args[i]);
      }
    } else if(sub.equals("reset")) { if(args.length!=1||!args[0].equals("--hard")) throw bad("paper.apply.usage"); }
    else if(sub.equals("stash")) { if(args.length<1||args.length>2||!Set.of("push","pop").contains(args[0])) throw bad("paper.apply.usage"); }
    else throw bad("paper.apply.usage");
    final String rev=revision; final boolean d=dry,f=force,st=stash; final Scope selected=scope; final DimensionId dim=dimension;
    reply(sender,Messages.line("paper.apply.start","target",rev==null ? sub : rev));
    plugin.repo().operation(rev==null ? sub : rev,ops->{
      var result=switch(sub) {
        case "restore"->ops.restore(rev,dim,selected,d,false);
        case "switch"->ops.switchTo(rev,st,f,d,false);
        case "reset"->ops.resetHard(null,false,false);
        case "stash"->args[0].equals("push") ? ops.stashPush(args.length==2 ? args[1] : null,false) : ops.stashPop(args.length==2 ? Integer.parseInt(args[1]) : 0,false);
        default->throw new IOException("unknown operation");
      };
      return new Applied(result,ops.head());
    }).whenComplete((applied,error)->Messages.inLocale(sender,()->{
      if(plugin.repo().stopping()) return;
      if(error!=null) { fail(sender,error); return; }
      var r=applied.result();
      if(r.state()==PaperOperations.State.PARTIAL) reply(sender,Messages.line("paper.apply.partial","message",r.error()));
      else if(r.state()==PaperOperations.State.DRY_RUN) reply(sender,Messages.line("paper.apply.dry-run","stats",r.dimensions()));
      else if(sub.equals("switch")) {
        plugin.getServer().getConsoleSender().sendMessage(Messages.line("paper.apply.switched","target",rev,"commit",Messages.shortId(applied.head())));
        for(var p:plugin.getServer().getOnlinePlayers()) plugin.platform().entity(p,()->Messages.inLocale(p,()->p.sendMessage(Messages.line("paper.apply.switched","target",rev,"commit",Messages.shortId(applied.head())))),()->{});
      } else reply(sender,Messages.line("paper.apply.complete","mode",sub));
    }));
  }

  private void merge(CommandSender sender,String sub,String[] args) {
    if(sub.equals("resolve")) {
      if(args.length!=2) throw bad("paper.merge.usage");
      int id=args[0].equals("all") ? 0 : Integer.parseInt(args[0].replaceFirst("^#",""));
      if(id<0 || (id==0 && !args[0].equals("all"))) throw bad("paper.merge.usage");
      var choice=MergeReport.Choice.valueOf(args[1].toUpperCase(Locale.ROOT));
      plugin.repo().mergeOperation("resolve",ops->choice==MergeReport.Choice.MANUAL ? ops.core().markResolved(id,true,false) : ops.core().selectRegion(id,choice,true,false))
          .whenComplete((result,error)->plugin.merges().feedback(sender,result,error)); return;
    }
    if(args.length!=1) throw bad("paper.merge.usage");
    String revision=args[0]; if(revision.startsWith("--") && (!sub.equals("merge") || !Set.of("--abort","--continue").contains(revision))) throw bad("paper.merge.usage");
    var author=identity(sender);
    reply(sender,Messages.line("paper.merge.start","mode",sub,"target",revision));
    plugin.repo().mergeOperation(sub,ops->{
      var core=ops.core();
      if(revision.equals("--abort")) return core.abortMerge(false);
      if(revision.equals("--continue")) return core.continueMerge(author,CommitMetadata.Source.PLUGIN,false);
      var options=new WorldOperations.MergeOptions(true,null,1,false,author,CommitMetadata.Source.PLUGIN);
      var result=switch(sub) { case "revert"->core.revert(revision,options); case "cherry-pick"->core.cherryPick(revision,options); default->core.merge(revision,options); };
      // 線上先 noCommit 套用／驗證；乾淨合併在同一編輯鎖內 capture 成 merge commit。
      if(result.success() && result.merging()!=null && result.merging().remaining()==0) return core.continueMerge(author,CommitMetadata.Source.PLUGIN,false);
      return result;
    }).whenComplete((result,error)->plugin.merges().feedback(sender,result,error));
  }

  private void conflicts(CommandSender sender,String sub,String[] args) {
    if(sub.equals("conflict-preview") || (args.length>0 && args[0].equals("preview"))) {
      if(!(sender instanceof Player p)) throw bad("paper.error.player-only");
      int offset=sub.equals("conflict-preview") ? 0 : 1;
      if(args.length!=offset+2) throw bad("paper.merge.usage");
      int id=Integer.parseInt(args[offset].replaceFirst("^#",""));
      var choice=MergeReport.Choice.valueOf(args[offset+1].toUpperCase(Locale.ROOT));
      if(id<1 || choice==MergeReport.Choice.MANUAL) throw bad("paper.merge.usage");
      if(!plugin.fabric().supports(p,org.worldgit.protocol.MergeProtocol.CAPABILITY)) throw bad("paper.diff.no-mod");
      // 查詢不需要 freeze；仍以 repo executor 序列化並拿 core 的持久化候選。
      plugin.repo().submit(()->{
        var mapping=WorldMapper.map();
        try(var ops=new PaperOperations(plugin,mapping,new ApplyQueue(plugin),true)) {
          var region=ops.core().merging().regions().stream().filter(r->r.id()==id).findFirst().orElseThrow(()->new IOException("找不到衝突區域 #"+id));
          var cells=ops.core().regionPreview(id,choice);
          plugin.fabric().sendConflictPreview(p,region.dimension(),id,choice,cells); return null;
        }
      }).whenComplete((ignored,error)->{ if(error!=null) fail(sender,error); }); return;
    }
    if(args.length>1) throw bad("paper.merge.usage");
    if(sender instanceof Player p) { plugin.merges().open(p,args.length==1 ? Integer.parseInt(args[0])-1 : 0); return; }
    var state=plugin.merges().state(); if(state==null) { reply(sender,Messages.line("paper.merge.none")); return; }
    reply(sender,Messages.line("paper.merge.status","count",state.remaining()));
    for(var r:state.regions()) reply(sender,Messages.line("paper.merge.region","id",r.id(),"count",r.blockCount()),Messages.line("paper.merge.coords","dimension",r.dimension(),"bounds",r.bounds()),Messages.line("paper.merge.region-status","choice",r.choice(),"status",r.resolved()));
  }

  // ---------------------------------------------------------------- tab

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)) && sender.hasPermission("worldgit.command." + s)).toList();
    String sub = args[0].toLowerCase(Locale.ROOT);
    String last = args[args.length - 1];
    List<String> options =
        switch (sub) {
          case "init" -> args.length == 3 && args[1].equals("--template") ? List.of("creative", "survival") : List.of("--template");
          case "status" -> List.of("--show", "--full");
          case "diff" -> List.of("--show", "--radius");
          case "commit" -> List.of("-m");
          case "restore" -> List.of("HEAD","--selection","--chunks","--box","--dry-run");
          case "switch" -> List.of("main","HEAD","--stash","--force");
          case "branch" -> List.of("-d");
          case "stash" -> List.of("push","pop","list","drop");
          case "reset" -> List.of("--hard");
          case "merge" -> List.of("main","HEAD","--abort","--continue");
          case "resolve", "conflict-preview" -> List.of("all","ours","theirs","base","manual");
          case "conflicts" -> List.of("preview");
          case "revert", "cherry-pick" -> List.of("HEAD");
          default -> List.of();
        };
    return options.stream().filter(o -> o.startsWith(last)).toList();
  }
}
