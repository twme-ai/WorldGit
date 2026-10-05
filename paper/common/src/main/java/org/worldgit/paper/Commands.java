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
final class Commands implements CommandTree.Actions {
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

  @Override
  public void run(io.papermc.paper.command.brigadier.CommandSourceStack source, CommandRequest request)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    CommandSender sender = source.getSender();
    // 原生 resolver 的型別錯誤交回 Brigadier；業務錯誤仍使用既有 i18n。
    Scope scope = Scope.all();
    if (request.value("from", io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver.class) != null) {
      var from = request.value("from", io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver.class).resolve(source);
      var to = request.value("to", io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver.class).resolve(source);
      scope = Scope.box(from.blockX(), from.blockY(), from.blockZ(), to.blockX(), to.blockY(), to.blockZ());
    }
    if (request.command().startsWith("debug.")) { debug.run(source, request); return; }
    Scope selected = scope;
    Messages.inLocale(sender, () -> {
      String command = request.command();
      String sub = command.contains(".") ? command.substring(0, command.indexOf('.')) : command;
      try {
        switch (sub) {
          case "remote", "fetch", "push", "pull", "pr", "comments", "comment" -> plugin.remote().run(sender, request);
          case "merge", "resolve", "conflict-select", "revert", "cherry-pick" -> merge(sender, request);
          case "tool" -> plugin.merges().tool((Player) sender);
          case "conflicts", "conflict-preview" -> conflicts(sender, request);
          case "init" -> init(sender, command.equals("init.survival") ? "survival" : "creative");
          case "status" -> status(sender, request.flag("--show"), request.flag("--full"));
          case "commit" -> commit(sender, request.text("text").trim());
          case "log" -> log(sender, request.number("limit", 10));
          case "diff" -> diff(sender, request.flag("--show"), request.number("radius", plugin.settings().showRadiusChunks()));
          case "clear" -> clear(sender);
          case "reload" -> reload(sender);
          case "restore", "switch", "branch", "stash", "reset" -> operation(sender, request, selected);
          case "cancel" -> reply(sender, Messages.line(plugin.repo().cancel() ? "paper.apply.cancel-requested" : "paper.apply.no-operation"));
          case "help" -> help(source, request.text("topic"));
          default -> throw new IllegalArgumentException(command);
        }
      } catch (UserError e) { reply(sender, Messages.line(e.key, e.args)); }
      catch (IllegalArgumentException e) { reply(sender, Messages.error(e.getMessage())); }
      catch (RuntimeException e) {
        plugin.getLogger().log(Level.WARNING, "指令 /wg " + sub + " 失敗", e);
        reply(sender, Messages.error(e.toString()));
      }
    });
  }

  private void help(io.papermc.paper.command.brigadier.CommandSourceStack source, String topic) {
    var lines = new ArrayList<Component>();
    lines.add(Messages.line("paper.help.title"));
    for (String sub : CommandTree.SUBS) if ((topic == null || topic.equals(sub)) && CommandTree.allowed(source, sub)) {
      lines.add(Messages.text("paper.command.help." + sub)
          .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/wg " + sub + " "))
          .hoverEvent(Messages.text("paper.command.help-click")));
    }
    reply(source.getSender(), lines);
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
    if(root instanceof UserError user) { reply(sender,Messages.line(user.key,user.args)); return; }
    reply(sender, Messages.error(root.getMessage() == null ? root.toString() : root.getMessage()));
    if (!(root instanceof IOException)) plugin.getLogger().log(Level.WARNING, "WorldGit 指令失敗", root);
  }

  // ---------------------------------------------------------------- init

  private void init(CommandSender sender, String template) {
    final String selectedTemplate = template;
    Messages.inLocale(sender, () -> reply(sender, Messages.line("paper.init.start", "template", selectedTemplate)));
    plugin.repo().init(template, WorldGitConfig.Track.ALL, identity(sender)).whenComplete((batch, error) -> Messages.inLocale(sender, () -> {
      if (error != null) { fail(sender, error); return; }
      var lines = new ArrayList<Component>();
      plugin.suggestions().invalidateLocal();
      lines.add(Messages.line("paper.init.done"));
      lines.addAll(Messages.commit(batch, palette()));
      reply(sender, lines);
    }));
  }

  // ---------------------------------------------------------------- status

  private void status(CommandSender sender, boolean show, boolean full) {
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

  private void commit(CommandSender sender, String message) {
    if (message.isBlank()) throw bad("paper.error.commit-empty");
    if(plugin.merges().state()!=null) {
      plugin.repo().mergeOperation(operationDimension(sender),"merge commit",ops->ops.core().commitMerge(identity(sender),CommitMetadata.Source.PLUGIN,message,false))
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
          plugin.suggestions().invalidateLocal();
          reply(sender, Messages.commit(batch, palette()));
          plugin.notifyCommit(batch, false);
        }));
  }

  // ---------------------------------------------------------------- log

  private void log(CommandSender sender, int limit) {
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

  private void diff(CommandSender sender, boolean show, int radius) {
    Player player = (Player) sender;
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

  private DimensionId operationDimension(CommandSender sender) {
    return sender instanceof Player player ? plugin.dimensionOf(player.getWorld()).orElseThrow(() -> bad("paper.error.diff-not-tracked")) : DimensionId.OVERWORLD;
  }
  private record Applied(PaperOperations.Result result,String head) {}
  private void operation(CommandSender sender, CommandRequest request, Scope scope) {
    String command = request.command();
    String sub = command.contains(".") ? command.substring(0, command.indexOf('.')) : command;
    if (sub.equals("branch")) {
      String name = request.text("branch");
      plugin.repo().operation(operationDimension(sender),"branch", ops -> {
        if (command.equals("branch.list")) return ops.branches().stream().map(b -> (b.current() ? "* " : "  ") + b.name() + " " + b.commits()).toList();
        if (command.equals("branch.delete")) ops.deleteBranch(name); else ops.createBranch(name, null);
        return List.of(name);
      }).whenComplete((rows, error) -> Messages.inLocale(sender, () -> {
        if (error != null) fail(sender, error);
        else { plugin.suggestions().invalidateLocal(); reply(sender, Messages.line("paper.apply.branch")); rows.forEach(r -> reply(sender, Component.text(r))); }
      }));
      return;
    }
    if (Set.of("stash.list", "stash.drop").contains(command)) {
      int index = request.number("index", 0);
      plugin.repo().operation(operationDimension(sender),"stash", ops -> {
        if (command.equals("stash.drop")) { ops.stashDrop(index); return List.of("stash@{" + index + "}"); }
        var list = ops.stashes(); var rows = new ArrayList<String>();
        for (int i = 0; i < list.size(); i++) rows.add("stash@{" + i + "} " + list.get(i).time() + " " + list.get(i).message());
        return rows;
      }).whenComplete((rows, error) -> Messages.inLocale(sender, () -> {
        if (error != null) fail(sender, error);
        else { plugin.suggestions().invalidateLocal(); reply(sender, Messages.line("paper.apply.stash")); rows.forEach(r -> reply(sender, Component.text(r))); }
      }));
      return;
    }
    String revision = request.text("revision");
    boolean dry = request.flag("--dry-run"), force = request.flag("--force"), stash = request.flag("--stash");
    DimensionId dimension = null;
    if (request.flag("--selection")) {
      Player player = (Player) sender; scope = WorldEditHook.selection(player);
      dimension = plugin.dimensionOf(player.getWorld()).orElseThrow(() -> bad("paper.error.diff-not-tracked"));
    } else if (request.values().containsKey("chunks") || request.values().containsKey("from")) {
      var world = sender instanceof Player p ? p.getWorld() : plugin.getServer().getWorlds().getFirst();
      if (request.values().containsKey("chunks")) {
        var loc = sender instanceof Player p ? p.getLocation() : world.getSpawnLocation();
        scope = Scope.chunkRadius(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, request.number("chunks", 0));
      }
      dimension = plugin.dimensionOf(world).orElseThrow(() -> bad("paper.error.diff-not-tracked"));
    }
    final String rev=revision; final boolean d=dry,f=force,st=stash; final Scope selected=scope; final DimensionId dim=dimension;
    reply(sender,Messages.line("paper.apply.start","target",rev==null ? sub : rev));
    plugin.repo().operation(operationDimension(sender),rev==null ? sub : rev,ops->{
      var result=switch(sub) {
        case "restore"->ops.restore(rev,dim,selected,d,false);
        case "switch"->ops.switchTo(rev,st,f,d,false);
        case "reset"->ops.resetHard(null,false,false);
        case "stash" -> command.equals("stash.push") ? ops.stashPush(request.text("text"), false) : ops.stashPop(request.number("index", 0), false);
        default->throw new IOException("unknown operation");
      };
      return new Applied(result,ops.head());
    }).whenComplete((applied,error)->Messages.inLocale(sender,()->{
      if(plugin.repo().stopping()) return;
      if(error!=null) { fail(sender,error); return; }
      plugin.suggestions().invalidateLocal();
      var r=applied.result();
      if(r.state()==PaperOperations.State.PARTIAL) reply(sender,Messages.line("paper.apply.partial","message",r.error()));
      else if(r.state()==PaperOperations.State.DRY_RUN) reply(sender,Messages.line("paper.apply.dry-run","stats",r.dimensions()));
      else if(sub.equals("switch")) {
        plugin.getServer().getConsoleSender().sendMessage(Messages.line("paper.apply.switched","target",rev,"commit",Messages.shortId(applied.head())));
        for(var p:plugin.getServer().getOnlinePlayers()) plugin.platform().entity(p,()->Messages.inLocale(p,()->p.sendMessage(Messages.line("paper.apply.switched","target",rev,"commit",Messages.shortId(applied.head())))),()->{});
      } else reply(sender,Messages.line("paper.apply.complete","mode",sub));
    }));
  }

  private void merge(CommandSender sender, CommandRequest request) {
    String command = request.command();
    String sub = command.contains(".") ? command.substring(0, command.indexOf('.')) : command;
    if (sub.equals("resolve") || sub.equals("conflict-select")) {
      int id = command.contains(".all.") ? 0 : request.number("id", 0);
      var choice = MergeReport.Choice.valueOf(command.substring(command.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT));
      var state = plugin.merges().state();
      if (state == null) throw bad("paper.merge.none");
      if (id != 0 && state.regions().stream().noneMatch(r -> r.id() == id)) throw bad("paper.merge.unknown-region", "id", id);
      plugin.repo().regionOperation(operationDimension(sender),sub, ops -> ops.core().selectRegion(id, choice, sub.equals("resolve"), false))
          .whenComplete((result, error) -> plugin.merges().feedback(sender, result, error));
      return;
    }
    String revision = command.equals("merge.abort") ? "--abort" : command.equals("merge.continue") ? "--continue" : request.text("revision");
    var author=identity(sender);
    reply(sender,Messages.line("paper.merge.start","mode",sub,"target",revision));
    plugin.repo().mergeOperation(operationDimension(sender),sub,ops->{
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

  private void conflicts(CommandSender sender, CommandRequest request) {
    String command = request.command();
    if (command.startsWith("conflict-preview.")) {
      Player p = (Player) sender;
      int id = request.number("id", 0);
      var choice = MergeReport.Choice.valueOf(command.substring(command.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT));
      if(!plugin.fabric().supports(p,org.worldgit.protocol.MergeProtocol.CAPABILITY)) throw bad("paper.diff.no-mod");
      // 查詢不需要 freeze；仍以 repo executor 序列化並拿 core 的持久化候選。
      plugin.repo().submit(()->{
        var mapping=WorldMapper.map();
        try(var ops=new PaperOperations(plugin,mapping,new ApplyQueue(plugin),true)) {
          var state=ops.core().merging();
          if(state==null) throw bad("paper.merge.none");
          var region=state.regions().stream().filter(r->r.id()==id).findFirst().orElseThrow(()->bad("paper.merge.unknown-region","id",id));
          var cells=ops.core().regionPreview(id,choice);
          plugin.fabric().sendConflictPreview(p,region.dimension(),id,choice,cells); return null;
        }
      }).whenComplete((ignored,error)->{ if(error!=null) fail(sender,error); }); return;
    }
    if (sender instanceof Player p) { plugin.merges().open(p, request.number("page", 1) - 1); return; }
    var state=plugin.merges().state(); if(state==null) { reply(sender,Messages.line("paper.merge.none")); return; }
    reply(sender,Messages.line("paper.merge.status","count",state.remaining()));
    for(var r:state.regions()) reply(sender,Messages.line("paper.merge.region","id",r.id(),"count",r.blockCount()),Messages.line("paper.merge.coords","dimension",r.dimension(),"bounds",r.bounds()),Messages.line("paper.merge.region-status","choice",r.choice(),"status",r.resolved()));
  }

}
