package org.worldgit.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import org.worldgit.core.apply.Scope;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.merge.MergeReport;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.operation.OperationResult;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.core.store.RefStore;
import org.worldgit.fabric.logic.*;
import org.worldgit.platform.ResultSummary;
import org.worldgit.protocol.CommentsProtocol;

/**
 * /wg（別名 wgit、git、worldgit）。輸出是語言鍵＋參數，依玩家客戶端語言與色票渲染（MiniMessage）。
 * 指令解析在伺服器執行緒，實際工作交給 repo executor，完成後回到伺服器執行緒送訊息。
 *
 * <p>維度語意：預設作用在玩家目前所在維度（console 為主世界）；{@code --dimension <id>} 指定；{@code --all} 逐維度
 * 執行（非原子，每個維度各自回報）。每個維度是獨立 repo，沒有跨維度的共同 snapshot。
 */
public final class WgCommands {
    private WgCommands() {}

    private static final AtomicBoolean WARNED_GIT = new AtomicBoolean();

    private static PermissionCheck check(int level) {
        return switch (level) {
            case 0 -> Commands.LEVEL_ALL;
            case 1 -> Commands.LEVEL_MODERATORS;
            case 2 -> Commands.LEVEL_GAMEMASTERS;
            case 3 -> Commands.LEVEL_ADMINS;
            default -> Commands.LEVEL_OWNERS;
        };
    }

    /** 單人世界的擁有者即使沒開作弊也允許（整合伺服器的 op 等級是 0）。 */
    static Predicate<CommandSourceStack> allowed(int level) {
        var check = Commands.hasPermission(check(level));
        return source -> {
            if (check.test(source)) return true;
            var server = source.getServer();
            var player = source.getPlayer();
            return server != null && player != null && server.isSingleplayer() && server.isSingleplayerOwner(player.nameAndId());
        };
    }

    // ---- 補全 -------------------------------------------------------------------------

    private static final List<String> TARGET_FLAGS = List.of("--dimension", "--all");
    private static final Map<String, List<String>> FLAGS = new HashMap<>();
    private static final Map<String, List<String>> SUBS = new HashMap<>();
    static {
        FLAGS.put("init", List.of("--template", "--track", "--dimension", "--all"));
        FLAGS.put("status", List.of("--full", "--blocks", "--show", "--dimension", "--all"));
        FLAGS.put("diff", List.of("--blocks", "--show", "--dimension", "--all"));
        FLAGS.put("log", List.of("--graph", "--refs", "--page", "--dimension", "--all"));
        FLAGS.put("graph", List.of("--refs", "--dimension", "--all"));
        FLAGS.put("restore", List.of("--chunks", "--box", "--dry-run", "--dimension", "--all"));
        FLAGS.put("switch", List.of("--stash", "--force", "--dry-run", "--dimension", "--all"));
        FLAGS.put("reset", List.of("--hard", "--force", "--dry-run", "--dimension", "--all"));
        FLAGS.put("merge", List.of("--no-commit", "--abort", "--continue", "--dry-run", "--dimension", "--all"));
        FLAGS.put("resolve", List.of("--ours", "--theirs", "--base", "--manual", "--dimension", "--all"));
        FLAGS.put("conflicts", List.of("--show", "--teleport", "--dimension", "--all"));
        FLAGS.put("comments", List.of("--here", "--dimension", "--all"));
        FLAGS.put("push", List.of("--tags", "--dimension", "--all"));
        SUBS.put("ignore", List.of("list", "add", "remove", "move", "enable", "disable", "test", "check", "preview", "confirm", "cancel", "gui"));
        SUBS.put("tag", List.of("list", "create", "delete"));
        SUBS.put("branch", List.of("list", "create", "delete"));
        SUBS.put("stash", List.of("push", "pop", "list", "drop"));
        SUBS.put("remote", List.of("list", "add", "remove", "set-url"));
        SUBS.put("pr", List.of("create", "list", "view"));
        SUBS.put("comments", List.of("show", "hide"));
        SUBS.put("pull", List.of("confirm"));
        SUBS.put("help", List.of("init", "status", "commit", "log", "graph", "diff", "restore", "switch", "branch", "tag", "stash", "reset", "merge", "ignore", "remote", "pr"));
    }

    private static SuggestionProvider<CommandSourceStack> suggest(String command) {
        return (ctx, builder) -> {
            String remaining = builder.getRemaining();
            var tokens = remaining.isBlank() ? new String[0] : remaining.split("\\s+", -1);
            boolean typing = !remaining.isEmpty() && !Character.isWhitespace(remaining.charAt(remaining.length() - 1));
            String last = typing ? tokens[tokens.length - 1] : "";
            int count = typing ? tokens.length - 1 : tokens.length;
            String previous = count > 0 ? tokens[count - 1] : "";
            var offset = builder.createOffset(builder.getStart() + remaining.length() - last.length());
            var out = new TreeSet<String>();
            switch (previous) {
                case "--dimension" -> {
                    for (var key : ctx.getSource().getServer().levelKeys()) out.add(key.identifier().toString());
                }
                case "--template" -> out.addAll(List.of("creative", "survival"));
                case "--track" -> out.addAll(List.of("all", "modified-only"));
                default -> {
                    var subs = SUBS.get(command);
                    if (subs != null && count == 0) out.addAll(subs);
                    var flags = FLAGS.getOrDefault(command, TARGET_FLAGS);
                    if (last.startsWith("-") || subs == null || count > 0) out.addAll(flags);
                    if (command.equals("commit") && count == 0) out.add("-m");
                }
            }
            for (String s : out) if (s.startsWith(last)) offset.suggest(s);
            return offset.buildFuture();
        };
    }

    // ---- 註冊 -------------------------------------------------------------------------

    private interface Body {
        void run(Run run) throws Exception;
    }

    private record Spec(boolean leadingOnly, Body body) {}

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    static {
        SPECS.put("init", new Spec(false, WgCommands::init));
        SPECS.put("status", new Spec(false, WgCommands::status));
        SPECS.put("commit", new Spec(true, WgCommands::commit));
        SPECS.put("log", new Spec(false, WgCommands::log));
        SPECS.put("graph", new Spec(false, WgCommands::graph));
        SPECS.put("diff", new Spec(false, WgCommands::diff));
        SPECS.put("clear", new Spec(false, WgCommands::clear));
        SPECS.put("info", new Spec(false, WgCommands::info));
        SPECS.put("reload", new Spec(false, WgCommands::reload));
        SPECS.put("help", new Spec(false, WgCommands::help));
        SPECS.put("preview", new Spec(false, WgCommands::preview));
        for (String command : List.of("restore", "switch", "branch", "stash", "reset")) SPECS.put(command, new Spec(false, run -> operation(run, command)));
        SPECS.put("cancel", new Spec(false, WgCommands::cancel));
        for (String command : List.of("merge", "resolve", "revert", "cherry-pick", "conflict-select")) SPECS.put(command, new Spec(false, run -> merge(run, command)));
        for (String command : List.of("conflicts", "conflict-preview")) SPECS.put(command, new Spec(false, run -> conflicts(run, command)));
        for (String command : List.of("remote", "fetch", "push", "pull", "pr", "comments", "comment")) SPECS.put(command, new Spec(false, run -> remote(run, command)));
        SPECS.put("tag", new Spec(false, WgCommands::tag));
        SPECS.put("verify", new Spec(false, WgCommands::verify));
        SPECS.put("ignore", new Spec(true, WgCommands::ignore));
    }

    private static boolean writes(String command) {
        return Set.of("init", "commit", "restore", "switch", "branch", "stash", "reset", "cancel", "reload", "merge", "resolve", "revert", "cherry-pick", "conflict-select", "tag").contains(command);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name, Predicate<CommandSourceStack> requires, String command) {
        var node = Commands.literal(name).requires(requires).executes(ctx -> dispatch(ctx, command, ""));
        if (!Set.of("clear", "info").contains(command))
            node.then(Commands.argument("options", StringArgumentType.greedyString()).suggests(suggest(command))
                .executes(ctx -> dispatch(ctx, command, StringArgumentType.getString(ctx, "options"))));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, ServerConfig config) {
        var write = allowed(config.writePermissionLevel());
        var read = allowed(config.readPermissionLevel());
        var ignore = allowed(config.ignorePermissionLevel());
        var root = Commands.literal(name).requires(read).executes(ctx -> dispatch(ctx, "help", ""));
        for (String command : SPECS.keySet()) {
            Predicate<CommandSourceStack> requires = command.equals("ignore") ? ignore
                : Set.of("remote", "fetch", "push", "pull", "pr", "comments", "comment").contains(command) ? read : writes(command) ? write : read;
            if (command.equals("tag")) requires = read;
            root.then(literal(command, requires, command));
        }
        return root;
    }

    /** 註冊 wg、worldgit，以及（設定允許時）wgit。裸 /git 另在較晚的 phase 檢查衝突後註冊。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ServerConfig config) {
        var wg = dispatcher.register(root("wg", config));
        alias(dispatcher, wg, "worldgit", config);
        if (config.aliases().wgit()) alias(dispatcher, wg, "wgit", config);
    }

    /** 已存在 /git（其他模組）時跳過並只記錄一次，避免覆蓋或與對方的子指令合併。 */
    public static void registerGit(CommandDispatcher<CommandSourceStack> dispatcher, ServerConfig config) {
        if (!config.aliases().git()) return;
        if (dispatcher.getRoot().getChild("git") != null) {
            if (WARNED_GIT.compareAndSet(false, true))
                ServerRuntime.LOG.warn("WORLDGIT 已有其他模組註冊 /git，WorldGit 不覆蓋；請改用 /wg、/wgit 或 /worldgit（可在 worldgit-server.yml 的 aliases.git 停用此別名）。");
            return;
        }
        var wg = dispatcher.getRoot().getChild("wg");
        if (wg != null) alias(dispatcher, (LiteralCommandNode<CommandSourceStack>) wg, "git", config);
    }

    private static void alias(CommandDispatcher<CommandSourceStack> dispatcher, LiteralCommandNode<CommandSourceStack> target, String name, ServerConfig config) {
        dispatcher.register(Commands.literal(name).requires(allowed(config.readPermissionLevel())).executes(ctx -> dispatch(ctx, "help", "")).redirect(target));
    }

    // ---- 派送 -------------------------------------------------------------------------

    /** 一次動作在單一維度的執行脈絡。 */
    private record Run(CommandContext<CommandSourceStack> ctx, ServerRuntime rt, OperationUi.Action action, DimensionId dimension, Targets targets, String text, String command) {
        CommandSourceStack source() { return ctx.getSource(); }
        ServerPlayer player() { return ctx.getSource().getPlayer(); }
    }

    private static ServerRuntime runtime(CommandContext<CommandSourceStack> ctx) {
        var rt = WorldGitMod.runtime(ctx.getSource().getServer());
        if (rt == null) throw new IllegalStateException("WorldGit is not running");
        return rt;
    }

    private static DimensionId currentDimension(CommandSourceStack source) {
        return ServerRuntime.dimensionId(source.getLevel());
    }

    private static int dispatch(CommandContext<CommandSourceStack> ctx, String command, String raw) {
        var rt = runtime(ctx);
        var spec = SPECS.getOrDefault(command, SPECS.get("help"));
        Targets targets;
        try {
            targets = Targets.parseCommand(command, raw, spec.leadingOnly());
        } catch (CommandArgs.Invalid e) {
            earlyFailure(ctx, rt, command, e.msg());
            return 0;
        }
        if (targets.kind() == Targets.Kind.ALL) {
            batch(ctx, rt, command, targets, spec);
            return 1;
        }
        DimensionId dimension = targets.kind() == Targets.Kind.EXPLICIT ? targets.dimension() : currentDimension(ctx.getSource());
        runOne(ctx, rt, command, dimension, targets, spec);
        return 1;
    }

    private static OperationUi.Action earlyFailure(CommandContext<CommandSourceStack> ctx, ServerRuntime rt, String command, Msg msg) {
        var action = rt.ui().begin(ctx.getSource(), command, null);
        OperationUi.within(action, () -> {
            action.failed(new IOException(msg.key()));
            Texts.failure(ctx.getSource(), rt, msg);
            action.release();
            return null;
        });
        return action;
    }

    private static OperationUi.Action runOne(CommandContext<CommandSourceStack> ctx, ServerRuntime rt, String command, DimensionId dimension, Targets targets, Spec spec) {
        var action = rt.ui().begin(ctx.getSource(), command, dimension);
        OperationUi.within(action, () -> {
            var run = new Run(ctx, rt, action, dimension, targets, targets.rest(), command);
            try {
                spec.body().run(run);
            } catch (CommandArgs.Invalid e) {
                action.failed(e);
                Texts.failure(ctx.getSource(), rt, e.msg());
            } catch (IllegalArgumentException e) {
                action.failed(e);
                Texts.failure(ctx.getSource(), rt, Msg.of(MessageKeys.ERROR_PHASE2_ARGS, "usage", usage(command)));
            } catch (Exception e) {
                ServerRuntime.LOG.warn("WORLDGIT command {} failed: {}", command, rt.mask(e.toString()));
                action.failed(e);
                Texts.failure(ctx.getSource(), rt, failure(rt, e));
            } finally {
                action.release();
            }
            return null;
        });
        return action;
    }

    /** --all：先解析目標維度，再依序逐一執行；每個維度各自的動作與結果，取消後不繼續。 */
    private static void batch(CommandContext<CommandSourceStack> ctx, ServerRuntime rt, String command, Targets targets, Spec spec) {
        var parent = rt.ui().begin(ctx.getSource(), command, null);
        OperationUi.within(parent, () -> {
            parent.retain();
            rt.runRepo(() -> {
                var repositories = new WorldRepositories(org.worldgit.core.anvil.WorldLayout.discover(rt.worldRoot()));
                if (command.equals("init"))
                    return repositories.initializable().stream().filter(d -> !d.initialized()).map(WorldRepositories.Initializable::dimension).sorted().toList();
                return List.copyOf(repositories.tracked().keySet());
            }).whenComplete((dimensions, error) -> rt.postToServer(() -> OperationUi.within(parent, () -> {
                if (error != null) {
                    parent.failed(error);
                    Texts.failure(ctx.getSource(), rt, failure(rt, root(error)));
                    parent.release();
                } else if (dimensions.isEmpty() && !command.equals("init")) {
                    parent.failed(new WorldOps.NotInitializedException());
                    Texts.failure(ctx.getSource(), rt, Messages.error(MessageKeys.ERROR_NOT_INITIALIZED));
                    parent.release();
                } else next(ctx, rt, command, targets, spec, dimensions, 0, parent);
                return null;
            })));
            return null;
        });
        parent.release();
    }

    private static void next(CommandContext<CommandSourceStack> ctx, ServerRuntime rt, String command, Targets targets, Spec spec,
            List<DimensionId> dimensions, int index, OperationUi.Action parent) {
        if (index == dimensions.size()) {
            if (dimensions.isEmpty()) parent.noOp();
            parent.release();
            return;
        }
        if (parent.cancelled) {
            parent.failed(new java.io.InterruptedIOException("操作已取消"));
            parent.release();
            return;
        }
        var child = runOne(ctx, rt, command, dimensions.get(index), targets, spec);
        child.completion().whenComplete((result, error) -> rt.postToServer(() -> {
            if (result != null && result.status() == OperationResult.Status.CANCELLED) {
                parent.release();
                return;
            }
            OperationUi.within(parent, () -> {
                next(ctx, rt, command, targets, spec, dimensions, index + 1, parent);
                return null;
            });
        }));
    }

    private static String usage(String command) {
        return "/wg " + command + " ( /wg help " + command + " )";
    }

    // ---- 共用 -------------------------------------------------------------------------

    private static CommitMetadata.Identity identity(CommandSourceStack source, ServerRuntime rt) {
        var player = source.getPlayer();
        return player == null
                ? Identities.server(rt.config())
                : Identities.player(player.nameAndId().name(), player.getUUID(), rt.config().identity().playerEmailDomain());
    }

    private static Throwable root(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) t = t.getCause();
        return t;
    }

    private static void logFailure(ServerRuntime rt, Throwable error) {
        var cause = root(error);
        // 已處理的 IO／業務拒絕由 OperationResult 回報；保留原因，避免把它記成未處理的 exception。
        String detail = cause instanceof IOException && cause.getMessage() != null ? cause.getMessage() : cause.toString();
        ServerRuntime.LOG.warn("WORLDGIT command failed: {}", rt.mask(detail));
    }

    private static Msg failure(ServerRuntime rt, Throwable error) {
        Throwable t = root(error);
        if (t instanceof IgnoreService.Refused refused) return refused.msg;
        if (t instanceof DuplicateEntityException duplicate || t.getCause() instanceof DuplicateEntityException) {
            var d = t instanceof DuplicateEntityException x ? x : (DuplicateEntityException) t.getCause();
            return Msg.of(MessageKeys.TOUCH_DUPLICATE, "uuid", d.uuid(), "dimension", d.dimension());
        }
        if (t instanceof WorldOps.NotInitializedException) return Messages.error(MessageKeys.ERROR_NOT_INITIALIZED);
        if (t instanceof org.worldgit.core.apply.PreflightChangedException changed) {
            var list = new StringBuilder();
            for (var c : changed.chunks()) {
                if (list.length() > 0) list.append("; ");
                list.append('[').append(c.chunk().x()).append(',').append(c.chunk().z()).append("] blocks=").append(c.blocks());
                if (c.biomes() > 0) list.append(" biomes=").append(c.biomes());
                if (!c.entities().isEmpty()) list.append(" entities=").append(c.entities().entrySet().stream().map(e -> e.getKey() + "x" + e.getValue()).collect(java.util.stream.Collectors.joining(",")));
            }
            return Msg.of(MessageKeys.ERROR_WORLD_CHANGED, "retries", changed.attempts() - 1, "total", changed.total(), "list", list,
                "more", changed.total() > changed.chunks().size() ? "; +" + (changed.total() - changed.chunks().size()) + " more" : "");
        }
        String text = t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : rt.mask(t.getMessage());
        if (text.contains("工作區有未提交")) return Msg.of(MessageKeys.ERROR_DIRTY);
        if (text.contains("世界為 PARTIAL")) return Msg.of(MessageKeys.ERROR_PARTIAL);
        return Msg.of(MessageKeys.COMMON_ERROR, "message", text);
    }

    private static <T> void deliver(Run run, CompletableFuture<T> future, Function<T, List<Msg>> render) {
        deliverRaw(run, future, value -> Texts.send(run.source(), run.rt(), render.apply(value)));
    }

    /** repo 工作完成後回到伺服器執行緒：先把結果交給動作（終態與摘要），再送內容；動作等這一步結束才完成。 */
    private static <T> void deliverRaw(Run run, CompletableFuture<T> future, Consumer<T> send) {
        var action = run.action();
        var rt = run.rt();
        var source = run.source();
        action.retain();
        future.whenComplete((value, error) -> rt.postToServer(() -> OperationUi.within(action, () -> {
            try {
                if (error != null) {
                    logFailure(rt, error);
                    action.failed(error);
                    Texts.failure(source, rt, failure(rt, error));
                } else {
                    try {
                        action.observe(value);
                        send.accept(value);
                    } catch (RuntimeException e) {
                        action.failed(e);
                        Texts.failure(source, rt, failure(rt, e));
                    }
                }
            } finally {
                action.release();
            }
            return null;
        })));
    }

    private static CommandArgs parse(Run run, Set<String> flags, Set<String> values) {
        return CommandArgs.parse(run.text(), flags, values);
    }

    private static boolean permitted(Run run, int level) {
        if (allowed(level).test(run.source())) return true;
        Texts.failure(run.source(), run.rt(), Msg.prefixed(MessageKeys.ERROR_PERMISSION));
        return false;
    }

    // ---- 指令 -------------------------------------------------------------------------

    private static void help(Run run) {
        var rt = run.rt();
        String topic = run.text().isBlank() ? null : run.text().trim().split("\\s+")[0];
        var lines = new ArrayList<Msg>();
        lines.add(Msg.prefixed(MessageKeys.HELP_TITLE));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("init", MessageKeys.HELP_INIT);
        entries.put("status", MessageKeys.HELP_STATUS);
        entries.put("commit", MessageKeys.HELP_COMMIT);
        entries.put("log", MessageKeys.HELP_LOG);
        entries.put("diff", MessageKeys.HELP_DIFF);
        entries.put("clear", MessageKeys.HELP_CLEAR);
        entries.put("info", MessageKeys.HELP_INFO);
        entries.put("reload", MessageKeys.HELP_RELOAD);
        for (var entry : entries.entrySet()) if (topic == null || topic.equals(entry.getKey())) lines.add(Msg.of(entry.getValue()));
        if (topic == null || !entries.containsKey(topic)) {
            lines.add(Msg.of(MessageKeys.HELP_PHASE2));
            lines.add(Msg.of(MessageKeys.HELP_PHASE3));
            lines.add(Msg.of(MessageKeys.HELP_PHASE5));
            lines.add(Msg.of(MessageKeys.HELP_TARGET));
        }
        Texts.send(run.source(), rt, lines);
    }

    private record InitResult(WorldRepositories.Batch<DimensionRepository.CommitResult> batch, List<WorldRepositories.Initializable> initializable) implements OperationUi.Observed {
        @Override public Object observed() { return batch; }
    }

    private static void init(Run run) {
        var rt = run.rt();
        var args = parse(run, Set.of(), Set.of("--template", "--track"));
        String template = args.value("--template").orElse(rt.config().defaultTemplate());
        var track = args.value("--track").map(v -> v.equals("modified-only") ? WorldGitConfig.Track.MODIFIED_ONLY : WorldGitConfig.Track.ALL).orElse(rt.config().track());
        if (!Set.of("creative", "survival").contains(template) || args.value("--track").filter(v -> !Set.of("all", "modified-only").contains(v)).isPresent()) {
            Texts.failure(run.source(), rt, Messages.error(MessageKeys.ERROR_TEMPLATE));
            run.action().failed(new IOException("template"));
            return;
        }
        // 玩家只 init 自己所在的維度；console／RCON 沒有位置，必須明確指定維度。
        if (run.player() == null && run.targets().kind() == Targets.Kind.CURRENT) {
            run.action().failed(new IOException("console init target"));
            Texts.failure(run.source(), rt, Messages.error(MessageKeys.ERROR_CONSOLE_INIT));
            return;
        }
        Texts.send(run.source(), rt, List.of(Msg.prefixed(MessageKeys.INIT_PROGRESS)));
        var future = rt.init(List.of(run.dimension()), template, track, identity(run.source(), rt))
            .thenCompose(batch -> rt.initializable().thenApply(list -> new InitResult(batch, list)));
        String trackOption = args.value("--track").map(v -> " --track " + v).orElse("");
        deliverRaw(run, future, result -> {
            var lines = rt.messages().commit(result.batch(), true);
            Texts.send(run.source(), rt, lines);
            if (!run.dimension().equals(DimensionId.OVERWORLD)) return;
            // 主世界 init 後：地獄／終界存在但未 init 時提供可點選的追加按鈕（執行同一個 init，需同權限）。
            String locale = Texts.locale(rt, run.player());
            var buttons = new ArrayList<net.kyori.adventure.text.Component>();
            boolean all = false;
            for (var candidate : result.initializable().stream().sorted(Comparator.comparing((WorldRepositories.Initializable c) -> c.dimension().value()).reversed()).toList()) {
                if (candidate.initialized()) continue;
                String id = candidate.dimension().value();
                if (!id.equals("minecraft:the_nether") && !id.equals("minecraft:the_end")) continue;
                String command = "/wg init --dimension " + id + " --template " + template + trackOption;
                buttons.add(Texts.adventure(rt, locale, Msg.of(id.endsWith("nether") ? MessageKeys.INIT_EXTRA_NETHER : MessageKeys.INIT_EXTRA_END))
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(HoverEvent.showText(Texts.adventure(rt, locale, Msg.of(MessageKeys.INIT_EXTRA_HOVER, "command", command)))));
                all = true;
            }
            if (all && buttons.size() > 1) {
                String command = "/wg init --all --template " + template + trackOption;
                buttons.add(Texts.adventure(rt, locale, Msg.of(MessageKeys.INIT_EXTRA_ALL))
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(HoverEvent.showText(Texts.adventure(rt, locale, Msg.of(MessageKeys.INIT_EXTRA_HOVER, "command", command)))));
            }
            if (!buttons.isEmpty()) {
                var row = net.kyori.adventure.text.Component.empty();
                for (var button : buttons) row = row.append(button).append(net.kyori.adventure.text.Component.space());
                Texts.sendComponents(run.source(), rt, List.of(row));
            }
        });
    }

    private static void commit(Run run) {
        var rt = run.rt();
        String text = run.text().trim();
        if (!text.startsWith("-m") || text.length() > 2 && !Character.isWhitespace(text.charAt(2))) throw new IllegalArgumentException();
        String message = text.substring(2).trim();
        if (message.isBlank()) {
            run.action().failed(new IOException("empty message"));
            Texts.failure(run.source(), rt, Messages.error(MessageKeys.ERROR_EMPTY_MESSAGE));
            return;
        }
        var identity = identity(run.source(), rt);
        CompletableFuture<Object> future = rt.merging(run.dimension()).thenCompose(state -> state == null
            ? rt.commit(run.dimension(), message, identity, false, true).thenApply(batch -> (Object) batch)
            : rt.live(run.dimension(), ops -> ops.commitMerge(identity, CommitMetadata.Source.MOD, message, false)).thenApply(m -> (Object) m));
        deliver(run, future, value -> value instanceof WorldOperations.MergeResult merge ? mergeLines(merge)
            : rt.messages().commit((WorldRepositories.Batch<DimensionRepository.CommitResult>) value, false));
    }

    private static void log(Run run) {
        var rt = run.rt();
        var args = parse(run, Set.of("--graph", "--refs"), Set.of("--page"));
        int count = 10;
        if (args.positional().size() > 1) throw new IllegalArgumentException();
        if (!args.positional().isEmpty()) count = Integer.parseInt(args.positional().getFirst());
        if (count < 1 || count > 50) throw new IllegalArgumentException();
        final int n = count;
        if (args.flag("--graph")) {
            int page = args.value("--page").map(Integer::parseInt).orElse(1);
            if (page < 1 || page > 200) throw new IllegalArgumentException();
            boolean refs = args.flag("--refs") || run.targets().kind() == Targets.Kind.ALL;
            var dimension = run.dimension();
            deliverRaw(run, rt.graph(dimension, Math.min(10000, page * n + 1), refs), graph -> {
                run.action().note(summary -> summary.count(Math.min(graph.nodes().size(), n)));
                Texts.sendComponents(run.source(), rt, GraphChat.lines(rt, Texts.locale(rt, run.player()), dimension, graph, page, n));
            });
            return;
        }
        if (args.value("--page").isPresent()) throw new IllegalArgumentException();
        var dimension = run.dimension();
        deliverRaw(run, rt.log(dimension, n), commits -> {
            run.action().note(summary -> summary.count(commits.size()));
            String locale = Texts.locale(rt, run.player());
            var lines = new ArrayList<net.kyori.adventure.text.Component>();
            lines.add(Texts.adventure(rt, locale, Msg.prefixed(commits.isEmpty() ? MessageKeys.LOG_EMPTY : MessageKeys.LOG_HEADER)));
            if (!commits.isEmpty()) lines.add(Texts.adventure(rt, locale, Msg.of(MessageKeys.LOG_DIMENSIONS, "dimensions", dimension.value())));
            for (var commit : commits) {
                var m = commit.metadata();
                var row = Texts.adventure(rt, locale, Msg.of(m.auto() ? MessageKeys.LOG_ROW_AUTO : MessageKeys.LOG_ROW, "snapshot", ResultSummary.shortId(commit.id()),
                    "message", m.message(), "author", m.author().name(), "time", m.time().toString().replace('T', ' ').substring(0, 19) + "Z"));
                var hover = Texts.adventure(rt, locale, Msg.of(MessageKeys.GRAPH_HOVER_SUFFIX, "id", commit.id(), "author", m.author().git(), "time", m.time().toString(),
                    "message", m.message(), "parents", commit.parents().isEmpty() ? "-" : String.join(", ", commit.parents())));
                lines.add(row.hoverEvent(HoverEvent.showText(hover)).clickEvent(ClickEvent.suggestCommand("/wg diff " + commit.id() + " --dimension " + dimension.value())));
            }
            Texts.sendComponents(run.source(), rt, lines);
        });
    }

    private static void graph(Run run) {
        var rt = run.rt();
        var args = parse(run, Set.of("--refs"), Set.of());
        if (!args.positional().isEmpty()) throw new IllegalArgumentException();
        boolean refs = args.flag("--refs") || run.targets().kind() == Targets.Kind.ALL;
        var dimension = run.dimension();
        var player = run.player();
        boolean screen = player != null && rt.uiServer().capable(player);
        long generation = rt.uiServer().nextGraphId();
        deliverRaw(run, rt.graph(dimension, 200, refs), graph -> {
            run.action().note(summary -> summary.count(graph.nodes().size()));
            if (screen) {
                try {
                    for (byte[] part : UiProtocol.encodeGraph(generation, dimension.value(), graph, true))
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, Net.UI.of(part));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                Texts.send(run.source(), rt, List.of(Msg.prefixed(MessageKeys.GRAPH_SCREEN, "dimension", dimension.value())));
            } else {
                Texts.send(run.source(), rt, List.of(Msg.prefixed(MessageKeys.GRAPH_NO_SCREEN)));
                Texts.sendComponents(run.source(), rt, GraphChat.lines(rt, Texts.locale(rt, run.player()), dimension, graph, 1, 12));
            }
        });
    }

    private record Statused(PreviewPlanner.Plan plan, WorldRepositories.Batch<DimensionRepository.Status> batch, org.worldgit.core.merge.MergeState state) implements OperationUi.Observed {
        @Override public Object observed() { return batch; }
    }

    private static ServerLevel level(Run run) {
        var level = run.rt().server().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.Identifier.parse(run.dimension().value())));
        if (level == null) throw new IllegalArgumentException("dimension not loaded");
        return level;
    }

    private static void status(Run run) {
        var rt = run.rt();
        var args = parse(run, Set.of("--full", "--blocks", "--show"), Set.of());
        if (!args.positional().isEmpty()) throw new IllegalArgumentException();
        boolean show = args.flag("--show");
        ServerPlayer player = null;
        if (show) {
            player = requirePreviewPlayer(run.source(), rt);
            if (player == null) {
                run.action().failed(new IOException("preview"));
                return;
            }
        }
        var dimension = run.dimension();
        var level = show ? level(run) : null;
        CompletableFuture<Statused> future = rt.merging(dimension).thenCompose(state -> rt.status(dimension, args.flag("--full")).thenCompose(batch -> {
            if (!show) return CompletableFuture.completedFuture(new Statused(null, batch, state));
            return rt.plan(dimension, List.of(), true, false, level.getMinY(), level.getMaxY()).thenApply(plan -> new Statused(plan, batch, state));
        }));
        final ServerPlayer target = player;
        deliver(run, future, result -> {
            var lines = new ArrayList<Msg>();
            if (result.state() != null) lines.add(Msg.prefixed(MessageKeys.MERGE_STATUS, "remaining", result.state().remaining(), "total", result.state().regions().size()));
            lines.addAll(rt.messages().status(result.batch(), args.flag("--blocks")));
            if (result.plan() != null) lines.addAll(previewLines(rt, target, result.plan()));
            return lines;
        });
    }

    private static void diff(Run run) {
        var rt = run.rt();
        var args = parse(run, Set.of("--blocks", "--show"), Set.of());
        if (args.positional().size() > 2) {
            run.action().failed(new IOException("diff args"));
            Texts.failure(run.source(), rt, Messages.error(MessageKeys.ERROR_DIFF_ARGS));
            return;
        }
        var revisions = args.positional();
        boolean show = args.flag("--show");
        ServerPlayer player = null;
        if (show) {
            player = requirePreviewPlayer(run.source(), rt);
            if (player == null) {
                run.action().failed(new IOException("preview"));
                return;
            }
        }
        boolean blocks = args.flag("--blocks");
        var dimension = run.dimension();
        var level = show ? level(run) : null;
        var textFuture = rt.runRepo(() -> {
            var ops = rt.ops();
            var lines = new ArrayList<Msg>();
            lines.add(switch (revisions.size()) {
                case 0 -> Msg.prefixed(MessageKeys.RANGE_HEAD);
                case 1 -> Msg.prefixed(MessageKeys.RANGE_ONE, "from", revisions.get(0));
                default -> Msg.prefixed(MessageKeys.RANGE_TWO, "from", revisions.get(0), "to", revisions.get(1));
            });
            var summary = ops.diff(dimension, revisions, DiffEngine.Detail.SUMMARY, null);
            var c = summary.counts();
            long total = c.added() + c.removed() + c.modified() + c.conflict();
            boolean list = blocks && total > 0 && total <= 2000;
            if (list) summary = ops.diff(dimension, revisions, DiffEngine.Detail.BLOCKS, summary.chunks());
            else if (blocks && total > 2000) lines.add(Msg.of(MessageKeys.DIFF_TOO_LARGE, "dimension", dimension));
            lines.addAll(rt.messages().diff(summary, list));
            return lines;
        });
        if (!show) {
            deliver(run, textFuture, lines -> lines);
            return;
        }
        final ServerPlayer target = player;
        boolean ghostCapable = rt.handshake().supports(target.getUUID(), "ghost-render");
        var combined = textFuture.thenCompose(lines -> rt.plan(dimension, revisions, false, ghostCapable, level.getMinY(), level.getMaxY()).thenApply(plan -> Map.entry(lines, plan)));
        deliver(run, combined, e -> {
            var lines = new ArrayList<>(e.getKey());
            lines.addAll(previewLines(rt, target, e.getValue()));
            return lines;
        });
    }

    private static ServerPlayer requirePreviewPlayer(CommandSourceStack source, ServerRuntime rt) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Texts.failure(source, rt, Messages.error(MessageKeys.PREVIEW_PLAYER_ONLY));
            return null;
        }
        if (!rt.handshake().ready(player.getUUID())) {
            Texts.failure(source, rt, Messages.error(MessageKeys.PREVIEW_NO_MOD, "state", rt.handshake().state(player.getUUID()), "reason", rt.handshake().reason(player.getUUID())));
            return null;
        }
        return player;
    }

    private static List<Msg> previewLines(ServerRuntime rt, ServerPlayer player, PreviewPlanner.Plan plan) {
        rt.sendPreview(player, plan.packets());
        return switch (plan.mode()) {
            case EMPTY -> List.of(Msg.prefixed(MessageKeys.PREVIEW_EMPTY));
            case GHOST -> List.of(Msg.prefixed(MessageKeys.PREVIEW_GHOST, "cells", plan.cells()));
            case OUTLINE -> List.of(Msg.prefixed(MessageKeys.PREVIEW_OUTLINE, "count", plan.outlines()));
        };
    }

    private static void preview(Run run) {
        var rt = run.rt();
        if (run.text().trim().equals("off")) {
            clear(run);
            return;
        }
        var player = requirePreviewPlayer(run.source(), rt);
        if (player == null) {
            run.action().failed(new IOException("preview"));
            return;
        }
        var args = OperationArgs.parse(run.text(), Set.of(), Map.of("--radius", 1));
        if (args.positional().size() != 1) throw new IllegalArgumentException();
        Set<org.worldgit.core.model.ChunkPos> window = args.values().containsKey("--radius")
            ? Scope.chunkRadius((player.chunkPosition().getMinBlockX() >> 4), (player.chunkPosition().getMinBlockZ() >> 4), args.number("--radius")).chunks() : null;
        var level = level(run);
        deliver(run, rt.preview(run.dimension(), args.positional().getFirst(), window, rt.handshake().supports(player.getUUID(), "ghost-render"), level.getMinY(), level.getMaxY()),
            plan -> previewLines(rt, player, plan));
    }

    private static List<Msg> resultLines(WorldOperations.Result result, String locale, ServerRuntime rt) {
        var lines = new ArrayList<Msg>();
        int sections = result.dimensions().values().stream().mapToInt(v -> v.sections()).sum();
        int entities = result.dimensions().values().stream().mapToInt(v -> v.entityPuts() + v.entityRemoves()).sum();
        if (result.success()) {
            String state = Mini.plain(rt.catalog(), locale, Msg.of(result.state() == WorldOperations.State.DRY_RUN ? MessageKeys.APPLY_DRY_RUN
                : MessageKeys.phase(org.worldgit.platform.ApplyProgress.Phase.COMPLETE)));
            lines.add(Msg.prefixed(MessageKeys.APPLY_RESULT, "state", state, "sections", sections, "entities", entities));
        } else lines.add(Msg.prefixed(MessageKeys.APPLY_PARTIAL, "sections", sections, "entities", entities));
        if (result.error() != null) lines.add(Msg.of(MessageKeys.COMMON_ERROR, "message", rt.mask(result.error())));
        return lines;
    }

    private static List<Msg> operationResult(Run run, String locale, String command, List<String> positions, WorldOperations.Result result) {
        var rt = run.rt();
        if (!rt.server().isSingleplayer() && result.success() && result.state() != WorldOperations.State.DRY_RUN)
            rt.broadcast(Msg.prefixed(MessageKeys.APPLY_FINISHED, "target", command + " " + String.join(" ", positions)));
        return resultLines(result, locale, rt);
    }

    private record Applied(Object value, String head) implements OperationUi.Observed {
        @Override public Object observed() { return value; }
    }

    /** 動作完成後的 HEAD（分支 @ hash），在 repo 執行緒讀取。 */
    private static CompletableFuture<Applied> withHead(ServerRuntime rt, DimensionId dimension, CompletableFuture<?> future) {
        return future.thenCompose(value -> rt.runRepo(() -> new Applied(value, rt.headText(dimension))));
    }

    private static void operation(Run run, String command) {
        var rt = run.rt();
        var flags = switch (command) {
            case "restore" -> Set.of("--dry-run");
            case "switch" -> Set.of("--stash", "--force", "--dry-run");
            case "reset" -> Set.of("--hard", "--force", "--dry-run");
            default -> Set.<String>of();
        };
        var values = command.equals("restore") ? Map.of("--chunks", 1, "--box", 6) : Map.<String, Integer>of();
        var args = OperationArgs.parse(run.text(), flags, values);
        var positions = args.positional();
        String locale = Texts.locale(rt, run.player());
        var dimension = run.dimension();
        CompletableFuture<List<Msg>> future;
        switch (command) {
            case "restore" -> {
                if (positions.size() != 1) throw new IllegalArgumentException();
                var player = run.player();
                var center = player == null ? run.source().getPosition() : player.position();
                var scope = args.scope(net.minecraft.util.Mth.floor(center.x) >> 4, net.minecraft.util.Mth.floor(center.z) >> 4);
                // 以玩家位置為中心的 chunk 半徑只對玩家目前所在的維度有意義。
                if (args.values().containsKey("--chunks") && !dimension.equals(currentDimension(run.source()))) throw new IllegalArgumentException();
                var regionDimension = scope.kind() == Scope.Kind.ALL ? null : dimension;
                future = withHead(rt, dimension, rt.live(dimension, ops -> ops.restore(positions.getFirst(), regionDimension, scope, args.flag("--dry-run"), false)))
                    .thenApply(applied -> applyLines(run, locale, command, positions, applied));
            }
            case "switch" -> {
                if (positions.size() != 1) throw new IllegalArgumentException();
                future = withHead(rt, dimension, rt.live(dimension, ops -> ops.switchTo(positions.getFirst(), args.flag("--stash"), args.flag("--force"), args.flag("--dry-run"), false)))
                    .thenApply(applied -> applyLines(run, locale, command, positions, applied));
            }
            case "reset" -> {
                if (!args.flag("--hard") || positions.size() > 1) throw new IllegalArgumentException();
                future = withHead(rt, dimension, rt.live(dimension, ops -> ops.resetHard(positions.isEmpty() ? null : positions.getFirst(), args.flag("--force"), args.flag("--dry-run"))))
                    .thenApply(applied -> applyLines(run, locale, command, positions, applied));
            }
            case "branch" -> {
                if (positions.isEmpty() || positions.equals(List.of("list")))
                    future = rt.live(dimension, ops -> ops.branches().stream().map(b -> Msg.of(MessageKeys.BRANCH_ROW, "current", b.current() ? "*" : " ", "name", b.name(), "commits", b.commits())).toList())
                        .thenApply(rows -> { run.action().note(summary -> summary.count(rows.size())); return rows; });
                else {
                    boolean delete = positions.getFirst().equals("delete");
                    boolean create = positions.getFirst().equals("create");
                    int offset = delete || create ? 1 : 0;
                    if (positions.size() < offset + 1 || positions.size() > offset + 2 || delete && positions.size() != 2) throw new IllegalArgumentException();
                    String name = positions.get(offset), start = positions.size() > offset + 1 ? positions.get(offset + 1) : null;
                    future = rt.live(dimension, ops -> {
                        if (delete) ops.deleteBranch(name);
                        else ops.createBranch(name, start);
                        return List.of(Msg.prefixed(MessageKeys.BRANCH_DONE, "name", name));
                    });
                }
            }
            case "stash" -> {
                if (positions.isEmpty()) throw new IllegalArgumentException();
                String action = positions.getFirst();
                if (!action.equals("push") && positions.size() > 2 || action.equals("list") && positions.size() != 1) throw new IllegalArgumentException();
                int index = positions.size() > 1 && !action.equals("push") ? Integer.parseInt(positions.get(1)) : 0;
                future = rt.live(dimension, ops -> (Object) switch (action) {
                    case "push" -> ops.stashPush(positions.size() > 1 ? String.join(" ", positions.subList(1, positions.size())) : null, false);
                    case "pop" -> ops.stashPop(index, false);
                    case "drop" -> {
                        ops.stashDrop(index);
                        yield List.of(Msg.prefixed(MessageKeys.STASH_DROPPED, "index", index));
                    }
                    case "list" -> {
                        var list = ops.stashes();
                        var lines = new ArrayList<Msg>();
                        for (int i = 0; i < list.size(); i++) {
                            var stash = list.get(i);
                            lines.add(Msg.of(MessageKeys.STASH_ROW, "index", i, "id", stash.id(), "message", stash.message(), "time", stash.time()));
                        }
                        yield lines.isEmpty() ? List.of(Msg.of(MessageKeys.STASH_EMPTY)) : lines;
                    }
                    default -> throw new IllegalArgumentException();
                }).thenCompose(value -> value instanceof WorldOperations.Result result
                    ? withHead(rt, dimension, CompletableFuture.completedFuture(result)).thenApply(applied -> applyLines(run, locale, command, positions, applied))
                    : CompletableFuture.completedFuture((List<Msg>) value));
            }
            default -> throw new IllegalArgumentException();
        }
        deliver(run, future, v -> v);
    }

    /** 套用結果：含分支 @ hash 的終止摘要由動作記錄；這裡只產生訊息。 */
    private static List<Msg> applyLines(Run run, String locale, String command, List<String> positions, Applied applied) {
        var result = (WorldOperations.Result) applied.value();
        // 下面會轉成訊息清單；先保留核心結果，否則 PARTIAL／取消及 dry-run 摘要會遺失。
        run.action().observe(result);
        if (result.success() && result.state() != WorldOperations.State.DRY_RUN && applied.head() != null) {
            String head = applied.head();
            run.action().note(summary -> summary.head(branchOf(head), commitOf(head)));
        }
        return operationResult(run, locale, command, positions, result);
    }

    private static String branchOf(String head) { return head == null || !head.contains("@") ? null : head.substring(0, head.indexOf('@')); }

    private static String commitOf(String head) { return head == null ? null : head.contains("@") ? head.substring(head.indexOf('@') + 1) : head; }

    private static List<Msg> mergeLines(WorldOperations.MergeResult result) {
        var lines = new ArrayList<Msg>();
        lines.add(Msg.prefixed(MessageKeys.MERGE_RESULT, "state", result.state(), "remaining", result.merging() == null ? 0 : result.merging().remaining()));
        if (result.error() != null) lines.add(Msg.of(MessageKeys.COMMON_ERROR, "message", result.error()));
        return lines;
    }

    private static void merge(Run run, String command) {
        var rt = run.rt();
        var author = identity(run.source(), rt);
        var dimension = run.dimension();
        CompletableFuture<WorldOperations.MergeResult> future;
        if (command.equals("conflict-select")) {
            var pos = OperationArgs.parse(run.text(), Set.of(), Map.of()).positional();
            if (pos.size() != 2) throw new IllegalArgumentException();
            int id = pos.getFirst().equals("all") ? 0 : Integer.parseInt(pos.getFirst().replaceFirst("^#", ""));
            var choice = MergeReport.Choice.valueOf(pos.get(1).toUpperCase(Locale.ROOT));
            if (id < 0 || id == 0 && !pos.getFirst().equals("all")) throw new IllegalArgumentException();
            future = rt.region(dimension, ops -> ops.selectRegion(id, choice, false, false));
        } else {
            var args = MergeArgs.parse(command, run.text());
            if (command.equals("resolve"))
                future = rt.region(dimension, ops -> args.choice() == MergeReport.Choice.MANUAL
                    ? ops.markResolved(args.region(), true, args.dryRun()) : ops.selectRegion(args.region(), args.choice(), true, args.dryRun()));
            else future = rt.live(dimension, ops -> {
                if (args.abort()) return ops.abortMerge(args.dryRun());
                if (args.resume()) return ops.continueMerge(author, CommitMetadata.Source.MOD, args.dryRun());
                var result = switch (command) {
                    case "merge" -> ops.merge(args.revision(), args.options(author));
                    case "revert" -> ops.revert(args.revision(), args.options(author));
                    case "cherry-pick" -> ops.cherryPick(args.revision(), args.options(author));
                    default -> throw new IllegalArgumentException();
                };
                // Core already applied and verified; do not apply plans a second time or update shapes.
                if (result.success() && !args.dryRun() && !args.noCommit() && result.merging() != null && result.merging().remaining() == 0)
                    return ops.continueMerge(author, CommitMetadata.Source.MOD, false);
                return result;
            });
        }
        deliver(run, future, result -> {
            if (!rt.server().isSingleplayer() && result.success() && !result.state().equals("DRY_RUN"))
                rt.broadcast(Msg.prefixed(MessageKeys.MERGE_RESULT, "state", result.state(), "remaining", result.merging() == null ? 0 : result.merging().remaining()));
            return mergeLines(result);
        });
    }

    private static void conflicts(Run run, String command) {
        var rt = run.rt();
        var player = run.player();
        var dimension = run.dimension();
        if (command.equals("conflict-preview")) {
            if (player == null || !rt.handshake().supports(player.getUUID(), org.worldgit.protocol.MergeProtocol.CAPABILITY)) throw new IllegalArgumentException();
            var pos = OperationArgs.parse(run.text(), Set.of(), Map.of()).positional();
            if (pos.size() != 2) throw new IllegalArgumentException();
            int id = Integer.parseInt(pos.getFirst());
            var choice = MergeReport.Choice.valueOf(pos.get(1).toUpperCase(Locale.ROOT));
            if (id < 1 || choice == MergeReport.Choice.MANUAL) throw new IllegalArgumentException();
            deliver(run, rt.conflictPreview(dimension, id, choice), packets -> {
                rt.sendPreview(player, packets);
                return List.of();
            });
            return;
        }
        var args = OperationArgs.parse(run.text(), Set.of("--show"), Map.of("--teleport", 1));
        if (!args.positional().isEmpty() || args.values().containsKey("--teleport") && player == null) throw new IllegalArgumentException();
        int target = args.values().containsKey("--teleport") ? args.number("--teleport") : -1;
        deliver(run, rt.merging(dimension), state -> {
            if (player != null) rt.sendConflicts(player, dimension, state);
            var lines = new ArrayList<Msg>();
            if (state == null) {
                lines.add(Msg.of(MessageKeys.CONFLICT_EMPTY));
                return lines;
            }
            lines.add(Msg.prefixed(MessageKeys.MERGE_STATUS, "remaining", state.remaining(), "total", state.regions().size()));
            for (var r : state.regions())
                lines.add(Msg.of(MessageKeys.CONFLICT_ROW, "id", r.id(), "dimension", r.dimension(), "bounds", r.bounds(), "count", r.blockCount(),
                    "ours", String.join(", ", r.oursAuthors()), "theirs", String.join(", ", r.theirsAuthors()), "choice", r.choice(), "resolved", r.resolved(), "redstone", r.redstone()));
            if (target != -1) {
                var r = state.regions().stream().filter(v -> v.id() == target).findFirst().orElseThrow(() -> new IllegalArgumentException("未知區域"));
                if (r.bounds() == null) throw new IllegalArgumentException("此區域無世界座標");
                var b = r.bounds();
                var level = rt.server().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.Identifier.parse(r.dimension().value())));
                if (level == null) throw new IllegalArgumentException("維度尚未載入");
                player.teleportTo(level, b.minX() + 0.5, b.maxY() + 2, b.minZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
            }
            return lines;
        });
    }

    private static void remote(Run run, String command) {
        var rt = run.rt();
        String text = run.text();
        String[] args = text.isBlank() ? new String[0] : text.trim().split("\\s+");
        int level = RemoteAccess.writes(command, args) ? rt.config().writePermissionLevel() : rt.config().readPermissionLevel();
        if (!allowed(level).test(run.source())) {
            run.action().failed(new IOException("permission"));
            Texts.failure(run.source(), rt, Msg.of("fabric.remote.permission"));
            return;
        }
        rt.remote().run(run.source(), run.dimension(), command, args);
    }

    private static void clear(Run run) {
        var rt = run.rt();
        ServerPlayer player = run.player();
        if (player == null) {
            run.action().failed(new IOException("player only"));
            Texts.failure(run.source(), rt, Messages.error(MessageKeys.PREVIEW_PLAYER_ONLY));
            return;
        }
        rt.clearPreview(player);
        Texts.send(run.source(), rt, List.of(Msg.prefixed(MessageKeys.PREVIEW_CLEARED)));
    }

    private static void cancel(Run run) {
        var rt = run.rt();
        boolean found = rt.ui().cancelAll();
        found |= rt.cancelApply();
        Texts.send(run.source(), rt, List.of(Msg.prefixed(found ? MessageKeys.APPLY_CANCEL : MessageKeys.APPLY_IDLE)));
    }

    private static void reload(Run run) {
        deliver(run, run.rt().runRepo(() -> {
            run.rt().catalog().reload();
            run.rt().refreshPalette();
            return true;
        }), ok -> List.of(Msg.prefixed(MessageKeys.RELOAD_DONE)));
    }

    private static void info(Run run) {
        var rt = run.rt();
        var source = run.source();
        var lines = new ArrayList<Msg>();
        lines.add(Msg.prefixed(MessageKeys.INFO_TITLE, "version", Platform.MINECRAFT));
        lines.add(Msg.of(MessageKeys.INFO_WORLD, "path", rt.worldRoot()));
        lines.add(Msg.of(MessageKeys.INFO_REPO, "path", rt.repositoryRoot()));
        var player = source.getPlayer();
        if (player != null) lines.add(Msg.of(MessageKeys.INFO_HANDSHAKE, "state", rt.handshake().state(player.getUUID()), "reason", rt.handshake().reason(player.getUUID())));
        Texts.send(source, rt, lines);
        deliver(run, rt.tracked(), tracked -> List.of(tracked.isEmpty() ? Msg.of(MessageKeys.INFO_UNTRACKED)
            : Msg.of(MessageKeys.INFO_TRACKED, "dimensions", String.join(", ", tracked.keySet().stream().map(DimensionId::value).toList()))));
    }

    private static void tag(Run run) {
        var rt = run.rt();
        var words = run.text().isBlank() ? new String[0] : run.text().trim().split("\\s+");
        String action = words.length == 0 ? "list" : words[0];
        var dimension = run.dimension();
        if (!Set.of("list", "create", "delete").contains(action) || action.equals("list") && words.length > 1 || !action.equals("list") && words.length != 2) throw new IllegalArgumentException();
        if (!action.equals("list") && !permitted(run, rt.config().writePermissionLevel())) {
            run.action().failed(new IOException("permission"));
            return;
        }
        String name = words.length > 1 ? words[1] : null;
        String locale = Texts.locale(rt, run.player());
        var future = rt.runRepo(() -> {
            var path = new WorldRepositories(org.worldgit.core.anvil.WorldLayout.discover(rt.worldRoot())).tracked().get(dimension);
            if (path == null) throw new IOException("維度尚未 init：" + dimension);
            try (var repo = new DimensionRepository(path, dimension, false)) {
                var refs = repo.refs();
                switch (action) {
                    case "list" -> {
                        var rows = new ArrayList<Msg>();
                        refs.refsByPrefix("refs/tags/").forEach((ref, id) -> rows.add(Msg.of(MessageKeys.TAG_ROW, "name", ref.substring("refs/tags/".length()), "commit", ResultSummary.shortId(id))));
                        run.action().note(summary -> summary.count(rows.size()));
                        return rows.isEmpty() ? List.of(Msg.of(MessageKeys.TAG_EMPTY)) : rows;
                    }
                    case "delete" -> {
                        String old = refs.refsByPrefix("refs/tags/").get("refs/tags/" + name);
                        if (old == null) throw new IOException("tag 不存在：" + name);
                        refs.updateRef("refs/tags/" + name, old, null);
                    }
                    default -> {
                        org.worldgit.core.store.JGitStore.validateBranch(name);
                        refs.updateRef("refs/tags/" + name, null, refs.head());
                    }
                }
                return List.of(Msg.prefixed(MessageKeys.TAG_DONE, "action", Mini.plain(rt.catalog(), locale,
                    Msg.of(action.equals("create") ? MessageKeys.TAG_CREATED : MessageKeys.TAG_DELETED)), "name", name));
            }
        });
        deliver(run, future, rows -> rows);
    }

    private static void verify(Run run) {
        var rt = run.rt();
        var positions = OperationArgs.parse(run.text(), Set.of(), Map.of()).positional();
        if (positions.size() > 1) throw new IllegalArgumentException();
        String revision = positions.isEmpty() ? "HEAD" : positions.getFirst();
        var dimension = run.dimension();
        deliver(run, rt.live(dimension, ops -> ops.verify(revision, dimension, Scope.all(), true)), result -> result.success()
            ? List.of(Msg.prefixed(MessageKeys.VERIFY_DONE, "revision", revision)) : List.of(Msg.of(MessageKeys.COMMON_ERROR, "message", rt.mask(String.valueOf(result.error())))));
    }

    // ---- .wgignore --------------------------------------------------------------------

    private static void ignore(Run run) throws Exception {
        var rt = run.rt();
        var service = rt.ignoreService();
        var tokens = run.text().isBlank() ? new String[0] : run.text().trim().split("\\s+", 2);
        String mode = tokens.length == 0 ? "list" : tokens[0];
        String rest = tokens.length > 1 ? tokens[1].trim() : "";
        var dimension = run.dimension();
        var player = run.player();
        String owner = IgnoreService.owner(player);
        if (!permitted(run, rt.config().ignorePermissionLevel())) {
            run.action().failed(new IOException("permission"));
            return;
        }
        switch (mode) {
            case "list", "check" -> deliver(run, rt.runRepo(() -> service.read(dimension)), view -> {
                var lines = new ArrayList<Msg>();
                if (mode.equals("check")) lines.add(Msg.prefixed(MessageKeys.IGNORE_VALID, "count", view.document().lines().size()));
                else for (var line : view.document().entries()) {
                    String state = !line.rule() ? "#" : line.enabled() ? "+" : "-";
                    lines.add(Msg.of(MessageKeys.IGNORE_ROW, "number", line.number(), "state", state, "text", line.text()));
                }
                return lines;
            });
            case "gui" -> {
                if (player == null || !rt.uiServer().capable(player)) {
                    Texts.send(run.source(), rt, List.of(Msg.prefixed(MessageKeys.IGNORE_NO_SCREEN)));
                    return;
                }
                deliverRaw(run, rt.runRepo(() -> service.read(dimension)), view -> {
                    try {
                        rt.uiServer().sendView(player, System.nanoTime(), dimension, view, 0, true);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            case "test" -> {
                IgnoreService.Aim aim = null;
                if (rest.isBlank()) {
                    if (player == null) throw new IllegalArgumentException();
                    aim = IgnoreService.aim(player);
                    if (aim == null) {
                        run.action().failed(new IOException("no target"));
                        Texts.failure(run.source(), rt, Msg.prefixed(MessageKeys.IGNORE_NO_TARGET));
                        return;
                    }
                }
                final var target = aim;
                deliver(run, rt.runRepo(() -> service.test(dimension, target == null ? rest : target.selector(), target == null ? null : target.entity())),
                    test -> List.of(Msg.prefixed(MessageKeys.IGNORE_TEST, "excluded", test.result().excluded(), "line", test.result().line() == null ? "-" : test.result().line(),
                        "rule", test.result().rule() == null ? "-" : test.result().rule())));
            }
            case "cancel" -> Texts.send(run.source(), rt, List.of(service.cancel(owner) ? Msg.prefixed(MessageKeys.IGNORE_CANCELLED) : Msg.prefixed(MessageKeys.IGNORE_NO_PENDING)));
            case "confirm" -> {
                if (rest.isBlank()) throw new IllegalArgumentException();
                deliver(run, rt.runRepo(() -> {
                    if (!rt.onServer(() -> allowed(rt.config().ignorePermissionLevel()).test(run.source())))
                        throw new IgnoreService.Refused(Msg.prefixed(MessageKeys.IGNORE_PERMISSION));
                    service.confirm(owner, dimension, rest);
                    return true;
                }), ok -> List.of(Msg.prefixed(MessageKeys.IGNORE_WRITTEN)));
            }
            case "add", "remove", "move", "enable", "disable", "preview" -> {
                int line = 0, destination = 0;
                String text = "";
                var words = rest.isBlank() ? new String[0] : rest.split("\\s+");
                switch (mode) {
                    case "add" -> {
                        if (rest.isBlank()) throw new IllegalArgumentException();
                        text = rest;
                    }
                    case "remove", "enable", "disable" -> {
                        if (words.length != 1) throw new IllegalArgumentException();
                        line = Integer.parseInt(words[0]);
                    }
                    case "move" -> {
                        if (words.length != 2) throw new IllegalArgumentException();
                        line = Integer.parseInt(words[0]);
                        destination = Integer.parseInt(words[1]);
                    }
                    default -> {
                        if (!rest.isBlank()) throw new IllegalArgumentException();
                    }
                }
                final int fl = line, fd = destination;
                final String ft = text;
                String dimensionOption = dimension.value();
                deliverRaw(run, rt.runRepo(() -> service.propose(owner, dimension, mode, fl, fd, ft, null)), proposal -> {
                    var preview = proposal.preview();
                    var lines = new ArrayList<Msg>();
                    lines.add(Msg.prefixed(MessageKeys.IGNORE_PREVIEW, "blocks", ResultSummary.number(preview.blocks()), "bes", ResultSummary.number(preview.blockEntities()),
                        "entities", ResultSummary.number(preview.entities()), "biomes", ResultSummary.number(preview.biomeSamples()), "fields", ResultSummary.number(preview.metadataFields()),
                        "samples", preview.examples().isEmpty() ? "-" : String.join("; ", preview.examples())));
                    Texts.send(run.source(), rt, lines);
                    if (!mode.equals("preview")) {
                        String locale = Texts.locale(rt, run.player());
                        String command = "/wg ignore --dimension " + dimensionOption + " confirm " + proposal.code();
                        Texts.sendComponents(run.source(), rt, List.of(Texts.adventure(rt, locale, Msg.of(MessageKeys.IGNORE_CONFIRM, "code", proposal.code()))
                            .clickEvent(ClickEvent.runCommand(command))
                            .hoverEvent(HoverEvent.showText(Texts.adventure(rt, locale, Msg.of(MessageKeys.INIT_EXTRA_HOVER, "command", command))))));
                    }
                });
            }
            default -> throw new IllegalArgumentException();
        }
    }
}
