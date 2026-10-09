package org.worldgit.paper;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static org.worldgit.paper.CommandArguments.TokenKind.*;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/** 唯一的 Paper 語法定義。所有旗標以 literal 分支展開，有限排列不建立 redirect 循環。 */
final class CommandTree {
  interface Actions { void run(CommandSourceStack source, CommandRequest request) throws CommandSyntaxException; }
  interface NativeTypes { ArgumentType<?> type(String name); }
  static final NativeTypes PAPER_TYPES = name -> switch (name) {
    case "position" -> ArgumentTypes.blockPosition();
    case "dimension" -> ArgumentTypes.key();
    case "player" -> ArgumentTypes.player();
    default -> throw new IllegalArgumentException(name);
  };
  static final List<String> SUBS = List.of("init", "status", "commit", "log", "diff", "clear", "reload", "restore",
      "switch", "branch", "stash", "reset", "cancel", "merge", "resolve", "conflict-select", "conflicts",
      "conflict-preview", "tool", "revert", "cherry-pick", "remote", "fetch", "push", "pull", "pr", "comments", "comment", "ignore", "tag", "verify", "help", "debug");

  private final Actions actions;
  private final CommandSuggestions suggestions;
  private final NativeTypes types;
  private String building;

  CommandTree(Actions actions, CommandSuggestions suggestions) { this(actions, suggestions, PAPER_TYPES); }
  CommandTree(Actions actions, CommandSuggestions suggestions, NativeTypes types) {
    this.actions = actions; this.suggestions = suggestions; this.types = types;
  }

  static String permission(String sub) {
    return sub.equals("conflict-select") ? "resolve" : sub.equals("comments") ? "comment" : sub;
  }
  static boolean allowed(CommandSourceStack source, String sub) {
    var sender = source.getSender();
    if (sub.equals("help")) return true;
    if (sub.equals("debug")) return sender instanceof ConsoleCommandSender || sender.hasPermission("worldgit.debug");
    boolean permission = sender.hasPermission("worldgit.command." + permission(sub)) || sender.hasPermission("worldgit.admin");
    return permission && (!Set.of("tool", "diff", "clear", "conflict-preview").contains(sub) || sender instanceof Player);
  }
  private static boolean player(CommandSourceStack source) { return source.getSender() instanceof Player; }
  private <B extends ArgumentBuilder<CommandSourceStack, B>> B restricted(B node) {
    String sub = building;
    return node.requires(s -> allowed(s, sub));
  }
  private LiteralArgumentBuilder<CommandSourceStack> lit(String name) { return restricted(literal(name)); }
  private <T> RequiredArgumentBuilder<CommandSourceStack, T> arg(String name, ArgumentType<T> type) { return restricted(argument(name, type)); }
  private RequiredArgumentBuilder<CommandSourceStack, String> token(String name, CommandArguments.TokenKind kind, CommandSuggestions.Kind completion) {
    var node = arg(name, new CommandArguments.Token(kind));
    if (completion != null) node.suggests(suggestions.provider(completion));
    return node;
  }
  private RequiredArgumentBuilder<CommandSourceStack, ?> nativeArg(String name, String kind) { return arg(name, types.type(kind)); }
  private <B extends ArgumentBuilder<CommandSourceStack, B>> B players(B node) { return node.requires(node.getRequirement().and(CommandTree::player)); }
  private <B extends ArgumentBuilder<CommandSourceStack, ?>> B exec(B node, String command) {
    node.executes(ctx -> { actions.run(ctx.getSource(), request(ctx, command)); return 1; });
    return node;
  }

  static CommandRequest request(CommandContext<CommandSourceStack> context, String command) {
    var values = new LinkedHashMap<String, Object>();
    var flags = new HashSet<String>();
    for (var parsed : context.getNodes()) {
      var node = parsed.getNode();
      if (node instanceof com.mojang.brigadier.tree.ArgumentCommandNode<?, ?>) {
        Object value = context.getArgument(node.getName(), Object.class);
        values.put(node.getName().equals("legacy_id") ? "id" : node.getName(), value);
      } else if (node.getName().startsWith("--")) flags.add(node.getName());
    }
    if (values.remove("tail") instanceof CommandArguments.TextOptions tail) {
      values.put("text", tail.text());
      tail.options().forEach((key, value) -> { flags.add(key); if (!key.equals("--here")) values.put(key.substring(2), value); });
    }
    return new CommandRequest(command, values, flags);
  }

  LiteralArgumentBuilder<CommandSourceStack> build() { return build("wg"); }
  LiteralArgumentBuilder<CommandSourceStack> build(String label) {
    building = "help";
    var root = exec(lit(label), "help");
    for (String sub : SUBS) {
      building = sub;
      var node = lit(sub);
      switch (sub) {
        case "init" -> {
          exec(node, sub);
          var template = lit("--template");
          for (String value : List.of("creative", "survival")) {
            template.then(exec(lit(value), "init." + value));
            node.then(exec(lit("--template=" + value), "init." + value));
          }
          node.then(template);
        }
        case "status" -> flags(node, sub, List.of("--full", "--show"), Set.of());
        case "commit" -> node.then(lit("-m").then(exec(arg("text", greedyString()), sub)));
        case "log" -> {
          flags(node, sub, List.of("--graph", "--all"), Set.of());
          var limit = arg("limit", integer(1, 100)); flags(limit, sub, List.of("--graph", "--all"), Set.of()); node.then(limit);
          var graph = lit("--graph"); flags(graph, sub, List.of("--all"), Set.of());
          var page = lit("--page").then(exec(arg("page", integer(1, 1000)), sub)); graph.then(page); node.then(graph);
        }
        case "verify" -> { exec(node, sub); node.then(exec(token("revision", REVISION, CommandSuggestions.Kind.REVISIONS), sub)); }
        case "tag" -> {
          exec(node, "tag.list"); node.then(exec(token("tag", BRANCH, null), "tag.create"));
          node.then(lit("-d").then(exec(token("tag", BRANCH, CommandSuggestions.Kind.REVISIONS), "tag.delete")));
        }
        case "ignore" -> {
          exec(node, "ignore.gui");
          for (String mode : List.of("list", "check", "preview", "gui")) node.then(exec(lit(mode), "ignore." + mode));
          node.then(lit("add").then(exec(arg("text", greedyString()).suggests(suggestions.ignoreRules()), "ignore.add")));
          for (String mode : List.of("remove", "move", "enable", "disable")) {
            var id = arg("line", integer(1, 4096)).suggests(suggestions.ignoreLines());
            if (mode.equals("move")) id.then(exec(arg("destination", integer(1, 4096)), "ignore.move")); else exec(id, "ignore." + mode);
            node.then(lit(mode).then(id));
          }
          var test = exec(players(lit("test")), "ignore.test");
          for (String mode : List.of("target", "hand")) test.then(exec(players(lit(mode)), "ignore.test." + mode));
          test.then(exec(arg("text", greedyString()), "ignore.test")); node.then(test);
          node.then(lit("confirm").then(exec(arg("code", com.mojang.brigadier.arguments.StringArgumentType.word()), "ignore.confirm")));
        }
        case "diff" -> {
          flags(node, sub, List.of("--show", "--radius"), Set.of());
          var revision=token("revision",REVISION,CommandSuggestions.Kind.REVISIONS);flags(revision,sub,List.of("--show","--radius"),Set.of());node.then(revision);
        }
        case "clear", "reload", "cancel", "tool" -> exec(node, sub);
        case "restore", "switch" -> {
          var revision = token("revision", REVISION, CommandSuggestions.Kind.REVISIONS);
          flags(revision, sub, sub.equals("restore") ? List.of("--dry-run", "--selection", "--chunks", "--box")
              : List.of("--dry-run", "--stash", "--force"), Set.of());
          node.then(revision);
        }
        case "branch" -> {
          exec(node, "branch.list");
          node.then(exec(token("branch", BRANCH, null), "branch.create"));
          node.then(lit("-d").then(exec(token("branch", BRANCH, CommandSuggestions.Kind.BRANCHES), "branch.delete")));
        }
        case "stash" -> {
          node.then(exec(lit("list"), "stash.list"));
          var push = exec(lit("push"), "stash.push");
          push.then(exec(arg("text", greedyString()), "stash.push")); node.then(push);
          for (String mode : List.of("pop", "drop")) {
            var entry = exec(lit(mode), "stash." + mode);
            entry.then(exec(arg("index", integer(0)).suggests(suggestions.provider(CommandSuggestions.Kind.STASHES)), "stash." + mode));
            node.then(entry);
          }
        }
        case "reset" -> node.then(exec(lit("--hard"), sub));
        case "merge", "revert", "cherry-pick" -> {
          node.then(exec(token("revision", REVISION, CommandSuggestions.Kind.REVISIONS), sub));
          if (sub.equals("merge")) for (String mode : List.of("--continue", "--abort")) node.then(exec(lit(mode), "merge." + mode.substring(2)));
        }
        case "resolve", "conflict-select" -> {
          choices(lit("all"), sub + ".all", true, node);
          regionIds(node, id -> choices(id, sub, true, null));
        }
        case "conflicts", "conflict-preview" -> {
          if (sub.equals("conflicts")) {
            exec(node, "conflicts"); node.then(exec(arg("page", integer(1)), "conflicts"));
            var preview = players(lit("preview"));
            regionIds(preview, id -> choices(id, "conflict-preview", false, null)); node.then(preview);
          } else regionIds(node, id -> choices(id, sub, false, null));
        }
        case "remote" -> {
          node.then(exec(lit("list"), "remote.list"));
          for (String mode : List.of("add", "remove", "set-url")) {
            var name = token("remote", REMOTE, mode.equals("add") ? null : CommandSuggestions.Kind.REMOTES);
            if (mode.equals("remove")) exec(name, "remote." + mode);
            else name.then(exec(token("url", URL, null), "remote." + mode));
            node.then(lit(mode).then(name));
          }
        }
        case "fetch" -> { exec(node, sub); node.then(exec(token("remote", REMOTE, CommandSuggestions.Kind.REMOTES), sub)); }
        case "push" -> push(node, 0, false);
        case "pull" -> {
          exec(node, sub);
          var remote = exec(token("remote", REMOTE, CommandSuggestions.Kind.REMOTES), sub);
          remote.then(exec(token("branch", BRANCH, CommandSuggestions.Kind.BRANCHES), sub)); node.then(remote);
          node.then(lit("confirm").then(exec(arg("code", com.mojang.brigadier.arguments.StringArgumentType.word()), "pull.confirm")));
        }
        case "pr" -> {
          node.then(exec(lit("list"), "pr.list"));
          var view = lit("view"); ids(view, CommandSuggestions.Kind.PRS, id -> exec(id, "pr.view")); node.then(view);
          var create = lit("create"); textFlags(create, true, Set.of()); node.then(create);
        }
        case "comment" -> ids(node, CommandSuggestions.Kind.PRS, id -> textFlags(id, false, Set.of()));
        case "comments" -> {
          commentFilters(node, "comments", Set.of());
          var show = players(lit("show")); commentFilters(show, "comments.show", Set.of()); node.then(show);
          var hide = exec(players(lit("hide")), "comments.hide");
          ids(hide, CommandSuggestions.Kind.PRS, id -> exec(id, "comments.hide"));
          var pr = lit("pr"); ids(pr, CommandSuggestions.Kind.PRS, id -> exec(id, "comments.hide")); hide.then(pr); node.then(hide);
        }
        case "help" -> {
          exec(node, "help");
          for (String topic : SUBS) node.then(literal(topic).requires(s -> allowed(s, topic)).executes(ctx -> {
            actions.run(ctx.getSource(), new CommandRequest("help", Map.of("topic", topic), Set.of())); return 1;
          }));
        }
        case "debug" -> debug(node);
        default -> throw new IllegalStateException(sub);
      }
      if (!Set.of("help", "debug", "clear", "reload", "cancel").contains(sub)) node=targets(node);
      root.then(node);
    }
    return root;
  }


  /** 目標旗標可放參數前或非 greedy 參數後；文字尾端保持原有純文字語意。 */
  private LiteralArgumentBuilder<CommandSourceStack> targets(LiteralArgumentBuilder<CommandSourceStack> builder) {
    var pure=builder.build();
    return (LiteralArgumentBuilder<CommandSourceStack>) decorateTargets(pure);
  }

  private ArgumentBuilder<CommandSourceStack,?> decorateTargets(com.mojang.brigadier.tree.CommandNode<CommandSourceStack> pure) {
    var builder=pure.createBuilder();
    for(var child:pure.getChildren()) {
      boolean greedy=child instanceof com.mojang.brigadier.tree.ArgumentCommandNode<?,?> argument &&
          (argument.getType() instanceof com.mojang.brigadier.arguments.StringArgumentType text && text.getType()==com.mojang.brigadier.arguments.StringArgumentType.StringType.GREEDY_PHRASE
          || argument.getType() instanceof CommandArguments.TextTail);
      builder.then(greedy?child:decorateTargets(child).build());
    }
    if(pure.getCommand()==null && !pure.getName().equals(building))return builder;
    // 後續參數只共用未裝飾原樹，不把目標旗標再次遞迴展開。
    var dimension=nativeArg("dimension","dimension").suggests(suggestions.provider(CommandSuggestions.Kind.DIMENSIONS));
    if(pure.getCommand()!=null)dimension.executes(pure.getCommand());
    for(var child:pure.getChildren())if(!Set.of("--dimension","--all").contains(child.getName()))dimension.then(child);
    if(pure.getChild("--dimension")==null)builder.then(lit("--dimension").then(dimension));
    var all=lit("--all");if(pure.getCommand()!=null)all.executes(pure.getCommand());
    for(var child:pure.getChildren())if(!Set.of("--dimension","--all").contains(child.getName()))all.then(child);
    if(pure.getChild("--all")==null)builder.then(all);
    var world=arg("world",com.mojang.brigadier.arguments.StringArgumentType.string()).suggests(suggestions.worlds());
    if(pure.getCommand()!=null)world.executes(pure.getCommand());
    for(var child:pure.getChildren())world.then(child);
    world.then(lit("--dimension").then(dimension));world.then(all);builder.then(lit("--world").then(world));
    return builder;
  }

  private void flags(ArgumentBuilder<CommandSourceStack, ?> node, String command, List<String> available, Set<String> used) {
    exec(node, command);
    boolean range = used.stream().anyMatch(Set.of("--selection", "--chunks", "--box")::contains);
    for (String flag : available) {
      if (used.contains(flag) || range && Set.of("--selection", "--chunks", "--box").contains(flag)) continue;
      var next = new HashSet<>(used); next.add(flag);
      var option = lit(flag);
      if (Set.of("--show", "--selection").contains(flag)) players(option);
      switch (flag) {
        case "--radius", "--chunks" -> {
          var value = arg(flag.equals("--radius") ? "radius" : "chunks", flag.equals("--radius") ? integer(1, 32) : integer(0, 256));
          flags(value, command, available, next); option.then(value);
        }
        case "--box" -> {
          var from = nativeArg("from", "position"); var to = nativeArg("to", "position");
          flags(to, command, available, next); from.then(to); option.then(from);
        }
        default -> flags(option, command, available, next);
      }
      node.then(option);
    }
  }
  private void push(ArgumentBuilder<CommandSourceStack, ?> node, int position, boolean tags) {
    exec(node, "push");
    if (!tags) { var option = lit("--tags"); push(option, position, true); node.then(option); }
    if (position < 2) {
      var value = token(position == 0 ? "remote" : "branch", position == 0 ? REMOTE : BRANCH,
          position == 0 ? CommandSuggestions.Kind.REMOTES : CommandSuggestions.Kind.BRANCHES);
      push(value, position + 1, tags); node.then(value);
    }
  }
  private void textFlags(ArgumentBuilder<CommandSourceStack, ?> node, boolean pr, Set<String> used) {
    var tail = arg("tail", new CommandArguments.TextTail(pr, used)).suggests(suggestions.textProvider(pr));
    exec(tail, pr ? "pr.create" : "comment"); node.then(tail);
    for (String flag : pr ? List.of("--source", "--target") : List.of("--here")) {
      if (used.contains(flag)) continue;
      var next = new HashSet<>(used); next.add(flag); var option = lit(flag);
      if (pr) {
        var branch = token(flag.substring(2), BRANCH, CommandSuggestions.Kind.BRANCHES);
        textFlags(branch, true, next); option.then(branch);
      } else { players(option); textFlags(option, false, next); }
      node.then(option);
    }
  }
  private void commentFilters(ArgumentBuilder<CommandSourceStack, ?> node, String command, Set<String> used) {
    exec(node, command);
    if (!used.contains("id")) {
      var next = new HashSet<>(used); next.add("id");
      ids(node, CommandSuggestions.Kind.PRS, id -> commentFilters(id, command, next));
      var pr = lit("pr"); ids(pr, CommandSuggestions.Kind.PRS, id -> commentFilters(id, command, next)); node.then(pr);
    }
    if (!used.contains("scope")) {
      var next = new HashSet<>(used); next.add("scope");
      var here = players(lit("--here")); commentFilters(here, command, next); node.then(here);
      var dimension = nativeArg("dimension", "dimension").suggests(suggestions.provider(CommandSuggestions.Kind.DIMENSIONS));
      commentFilters(dimension, command, next); node.then(lit("--dimension").then(dimension));
    }
  }
  private void regionIds(ArgumentBuilder<CommandSourceStack, ?> node, java.util.function.Consumer<ArgumentBuilder<CommandSourceStack, ?>> end) {
    ids(node, CommandSuggestions.Kind.REGIONS, end);
  }
  private void ids(ArgumentBuilder<CommandSourceStack, ?> node, CommandSuggestions.Kind kind, java.util.function.Consumer<ArgumentBuilder<CommandSourceStack, ?>> end) {
    var numeric = arg("id", integer(1)).suggests(suggestions.provider(kind)); end.accept(numeric); node.then(numeric);
    // Native client 同時遇到兩個 ask_server 會取消前一個 future，使整組補全永不完成。
    // #id 僅保留伺服器解析相容性；原生數字分支是唯一的動態補全提供者。
    var legacy = arg("legacy_id", new CommandArguments.PrefixedId()); end.accept(legacy); node.then(legacy);
  }
  private void choices(ArgumentBuilder<CommandSourceStack, ?> id, String command, boolean manual, ArgumentBuilder<CommandSourceStack, ?> parent) {
    for (String choice : manual ? List.of("ours", "theirs", "base", "manual") : List.of("ours", "theirs", "base")) id.then(exec(lit(choice), command + "." + choice));
    if (parent != null) parent.then(id);
  }

  private void debug(LiteralArgumentBuilder<CommandSourceStack> node) {
    for (String mode : List.of("apply", "release", "measurements", "fixture-clean-items")) node.then(exec(lit(mode), "debug." + mode));
    for (String mode : List.of("probe", "guard")) {
      var option = lit(mode);
      for (String value : mode.equals("probe") ? List.of("start", "stop") : List.of("on", "off")) option.then(exec(lit(value), "debug." + mode + "." + value));
      node.then(option);
    }
    for (String mode : List.of("comment-camera", "comment-teleport", "protection", "merge-gui", "merge-tool")) {
      var player = nativeArg("player", "player");
      switch (mode) {
        case "comment-teleport" -> player.then(exec(nativeArg("dimension", "dimension"), "debug." + mode));
        case "merge-gui" -> player.then(exec(arg("slot", integer(0, 53)), "debug." + mode));
        case "merge-tool" -> { for (String action : List.of("cycle", "resolve")) player.then(exec(lit(action), "debug." + mode + "." + action)); }
        default -> exec(player, "debug." + mode);
      }
      node.then(lit(mode).then(player));
    }
    var displays = arg("cx", integer()).then(exec(arg("cz", integer()), "debug.comment-displays")); node.then(lit("comment-displays").then(displays));
    node.then(lit("sample").then(arg("cx", integer()).then(arg("cz", integer()).then(exec(arg("sy", integer()), "debug.sample")))));
    var entities = lit("entities");
    for (String dimension : List.of("overworld", "nether")) {
      var cz = arg("cz", integer());
      for (String mode : List.of("seed", "solo", "clear", "inspect")) cz.then(exec(lit(mode), "debug.entities." + dimension + "." + mode));
      entities.then(lit(dimension).then(arg("cx", integer()).then(cz)));
    }
    node.then(entities);
    node.then(lit("fixture-container").then(nativeArg("position", "position").then(exec(token("material", BRANCH, null), "debug.fixture-container"))));
    var side = exec(arg("side", integer(1, 128)), "debug.fill");
    var material = exec(token("material", BRANCH, null), "debug.fill");
    var total = exec(arg("total", integer(1, 16384)), "debug.fill");
    total.then(exec(arg("offset", integer(0, 15)), "debug.fill")); material.then(total); side.then(material); node.then(lit("fill").then(side));
  }
}
