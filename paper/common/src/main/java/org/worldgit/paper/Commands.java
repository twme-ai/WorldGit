package org.worldgit.paper;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.*;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.protocol.DiffPalette;
import org.worldgit.protocol.Protocol;

/**
 * /wg init | status | commit | log | diff | clear。所有重工作都在背景 repo 執行緒，指令本身立刻返回；
 * 結果以聊天訊息送回（Player 走自己的 entity scheduler，Folia 安全）。權限節點見 plugin.yml。
 */
final class Commands implements CommandExecutor, TabCompleter {
  private static final List<String> SUBS = List.of("init", "status", "commit", "log", "diff", "clear", "reload", "help");
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
        case "init" -> init(sender, rest);
        case "status" -> status(sender, rest);
        case "commit" -> commit(sender, rest);
        case "log" -> log(sender, rest);
        case "diff" -> diff(sender, rest);
        case "clear" -> clear(sender);
        case "reload" -> reload(sender);
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
        Messages.line("paper.help.clear"), Messages.line("paper.help.reload"));
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
          default -> List.of();
        };
    return options.stream().filter(o -> o.startsWith(last)).toList();
  }
}
