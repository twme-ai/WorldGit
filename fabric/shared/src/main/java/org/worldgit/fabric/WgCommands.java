package org.worldgit.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;
import org.worldgit.fabric.logic.*;

/**
 * /wg init | status | commit | log | diff | clear | info | reload。輸出是語言鍵＋參數，依玩家客戶端語言與色票渲染
 * （MiniMessage）。指令解析在伺服器執行緒，實際工作交給 repo executor，完成後回到伺服器執行緒送訊息。
 */
public final class WgCommands {
    private WgCommands() {}

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
    private static Predicate<CommandSourceStack> allowed(int level) {
        var check = Commands.hasPermission(check(level));
        return source -> {
            if (check.test(source)) return true;
            var server = source.getServer();
            var player = source.getPlayer();
            return server != null && player != null && server.isSingleplayer() && server.isSingleplayerOwner(player.nameAndId());
        };
    }

    private static final SuggestionProvider<CommandSourceStack> FLAG_SUGGESTIONS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining();
                String last = remaining.substring(remaining.lastIndexOf(' ') + 1);
                var offset = builder.createOffset(builder.getStart() + remaining.length() - last.length());
                for (String s : List.of("--show", "--blocks", "--full", "--template", "--track", "--dimension"))
                    if (s.startsWith(last)) offset.suggest(s);
                return offset.buildFuture();
            };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ServerConfig config) {
        var write = allowed(config.writePermissionLevel());
        var read = allowed(config.readPermissionLevel());
        var root = Commands.literal("wg").requires(read).executes(ctx -> help(ctx));
        root.then(Commands.literal("init")
                .requires(write)
                .executes(ctx -> init(ctx, ""))
                .then(Commands.argument("options", StringArgumentType.greedyString())
                        .suggests(FLAG_SUGGESTIONS)
                        .executes(ctx -> init(ctx, StringArgumentType.getString(ctx, "options")))));
        root.then(Commands.literal("status")
                .executes(ctx -> status(ctx, ""))
                .then(Commands.argument("options", StringArgumentType.greedyString())
                        .suggests(FLAG_SUGGESTIONS)
                        .executes(ctx -> status(ctx, StringArgumentType.getString(ctx, "options")))));
        root.then(Commands.literal("commit")
                .requires(write)
                .then(Commands.literal("-m")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> commit(ctx, StringArgumentType.getString(ctx, "message"))))));
        root.then(Commands.literal("log")
                .executes(ctx -> log(ctx, 10))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50)).executes(ctx -> log(ctx, IntegerArgumentType.getInteger(ctx, "count")))));
        root.then(Commands.literal("diff")
                .executes(ctx -> diff(ctx, ""))
                .then(Commands.argument("options", StringArgumentType.greedyString())
                        .suggests(FLAG_SUGGESTIONS)
                        .executes(ctx -> diff(ctx, StringArgumentType.getString(ctx, "options")))));
        root.then(Commands.literal("clear").executes(WgCommands::clear));
        root.then(Commands.literal("info").executes(WgCommands::info));
        root.then(Commands.literal("reload").requires(write).executes(WgCommands::reload));
        dispatcher.register(root);
    }

    // ---- 共用 -------------------------------------------------------------------------

    private static ServerRuntime runtime(CommandContext<CommandSourceStack> ctx) {
        var rt = WorldGitMod.runtime(ctx.getSource().getServer());
        if (rt == null) throw new IllegalStateException("WorldGit is not running");
        return rt;
    }

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

    private static Msg failure(Throwable error) {
        Throwable t = root(error);
        if (t instanceof WorldOps.NotInitializedException) return Messages.error(MessageKeys.ERROR_NOT_INITIALIZED);
        String text = t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
        return Msg.of(MessageKeys.COMMON_ERROR, "message", text);
    }

    private static <T> void deliver(CommandContext<CommandSourceStack> ctx, ServerRuntime rt, CompletableFuture<T> future, Function<T, List<Msg>> render) {
        var source = ctx.getSource();
        future.whenComplete(
                (value, error) ->
                        rt.postToServer(
                                        () -> {
                                            if (error != null) {
                                                ServerRuntime.LOG.warn("WORLDGIT command failed: {}", root(error).toString());
                                                Texts.failure(source, rt, failure(error));
                                            } else
                                                try {
                                                    Texts.send(source, rt, render.apply(value));
                                                } catch (RuntimeException e) {
                                                    Texts.failure(source, rt, failure(e));
                                                }
                                        }));
    }

    private static CommandArgs parse(CommandContext<CommandSourceStack> ctx, ServerRuntime rt, String text, Set<String> flags, Set<String> values) {
        try {
            return CommandArgs.parse(text, flags, values);
        } catch (CommandArgs.Invalid e) {
            Texts.failure(ctx.getSource(), rt, e.msg());
            return null;
        }
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        var rt = runtime(ctx);
        Texts.send(
                ctx.getSource(),
                rt,
                List.of(
                        Msg.prefixed(MessageKeys.HELP_TITLE),
                        Msg.of(MessageKeys.HELP_INIT),
                        Msg.of(MessageKeys.HELP_STATUS),
                        Msg.of(MessageKeys.HELP_COMMIT),
                        Msg.of(MessageKeys.HELP_LOG),
                        Msg.of(MessageKeys.HELP_DIFF),
                        Msg.of(MessageKeys.HELP_CLEAR),
                        Msg.of(MessageKeys.HELP_INFO),
                        Msg.of(MessageKeys.HELP_RELOAD)));
        return 1;
    }

    // ---- 指令 -------------------------------------------------------------------------

    private static int init(CommandContext<CommandSourceStack> ctx, String text) {
        var rt = runtime(ctx);
        var args = parse(ctx, rt, text, Set.of(), Set.of("--template", "--track", "--dimension"));
        if (args == null) return 0;
        String template = args.value("--template").orElse(rt.config().defaultTemplate());
        var track = args.value("--track").map(v -> v.equals("modified-only") ? WorldGitConfig.Track.MODIFIED_ONLY : WorldGitConfig.Track.ALL).orElse(rt.config().track());
        if (!Set.of("creative", "survival").contains(template) || args.value("--track").filter(v -> !Set.of("all", "modified-only").contains(v)).isPresent()) {
            Texts.failure(ctx.getSource(), rt, Messages.error(MessageKeys.ERROR_TEMPLATE));
            return 0;
        }
        DimensionId selected;
        try {
            selected = args.value("--dimension").map(DimensionId::new).orElse(null);
        } catch (IllegalArgumentException e) {
            Texts.failure(ctx.getSource(), rt, Messages.error(MessageKeys.ERROR_INVALID_DIMENSION, "message", e.getMessage()));
            return 0;
        }
        Texts.send(ctx.getSource(), rt, List.of(Msg.prefixed(MessageKeys.INIT_PROGRESS)));
        deliver(ctx, rt, rt.init(selected, template, track, identity(ctx.getSource(), rt)), batch -> rt.messages().commit(batch, true));
        return 1;
    }

    private static int commit(CommandContext<CommandSourceStack> ctx, String message) {
        var rt = runtime(ctx);
        if (message.isBlank()) {
            Texts.failure(ctx.getSource(), rt, Messages.error(MessageKeys.ERROR_EMPTY_MESSAGE));
            return 0;
        }
        deliver(ctx, rt, rt.commit(message, identity(ctx.getSource(), rt), false, true), batch -> rt.messages().commit(batch, false));
        return 1;
    }

    private static int log(CommandContext<CommandSourceStack> ctx, int n) {
        var rt = runtime(ctx);
        deliver(ctx, rt, rt.log(n), rows -> rt.messages().log(rows));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx, String text) {
        var rt = runtime(ctx);
        var args = parse(ctx, rt, text, Set.of("--full", "--blocks", "--show"), Set.of());
        if (args == null) return 0;
        var source = ctx.getSource();
        boolean show = args.flag("--show");
        ServerPlayer player = null;
        if (show) {
            player = requirePreviewPlayer(source, rt);
            if (player == null) return 0;
        }
        var statusFuture = rt.status(args.flag("--full"));
        if (!show) {
            deliver(ctx, rt, statusFuture, batch -> rt.messages().status(batch, args.flag("--blocks")));
            return 1;
        }
        final ServerPlayer target = player;
        final ServerLevel level = (ServerLevel) target.level();
        var combined =
                statusFuture.thenCompose(
                        batch ->
                                rt.plan(ServerRuntime.dimensionId(level), List.of(), true, false, level.getMinY(), level.getMaxY())
                                        .thenApply(plan -> Map.entry(batch, plan)));
        deliver(ctx, rt, combined, e -> {
            var lines = new ArrayList<>(rt.messages().status(e.getKey(), args.flag("--blocks")));
            lines.addAll(previewLines(rt, target, e.getValue()));
            return lines;
        });
        return 1;
    }

    private static int diff(CommandContext<CommandSourceStack> ctx, String text) {
        var rt = runtime(ctx);
        var args = parse(ctx, rt, text, Set.of("--blocks", "--show"), Set.of());
        if (args == null) return 0;
        if (args.positional().size() > 2) {
            Texts.failure(ctx.getSource(), rt, Messages.error(MessageKeys.ERROR_DIFF_ARGS));
            return 0;
        }
        var revisions = args.positional();
        var source = ctx.getSource();
        boolean show = args.flag("--show");
        ServerPlayer player = null;
        if (show) {
            player = requirePreviewPlayer(source, rt);
            if (player == null) return 0;
        }
        boolean blocks = args.flag("--blocks");
        var textFuture =
                rt.runRepo(
                        () -> {
                            var ops = rt.ops();
                            var lines = new ArrayList<Msg>();
                            lines.add(
                                    switch (revisions.size()) {
                                        case 0 -> Msg.prefixed(MessageKeys.RANGE_HEAD);
                                        case 1 -> Msg.prefixed(MessageKeys.RANGE_ONE, "from", revisions.get(0));
                                        default -> Msg.prefixed(MessageKeys.RANGE_TWO, "from", revisions.get(0), "to", revisions.get(1));
                                    });
                            for (var dim : ops.tracked().keySet()) {
                                var summary = ops.diff(dim, revisions, DiffEngine.Detail.SUMMARY, null);
                                var c = summary.counts();
                                long total = c.added() + c.removed() + c.modified() + c.conflict();
                                boolean list = blocks && total > 0 && total <= 2000;
                                if (list) summary = ops.diff(dim, revisions, DiffEngine.Detail.BLOCKS, summary.chunks());
                                else if (blocks && total > 2000) lines.add(Msg.of(MessageKeys.DIFF_TOO_LARGE, "dimension", dim));
                                lines.addAll(rt.messages().diff(summary, list));
                            }
                            return lines;
                        });
        if (!show) {
            deliver(ctx, rt, textFuture, lines -> lines);
            return 1;
        }
        final ServerPlayer target = player;
        final ServerLevel level = (ServerLevel) target.level();
        boolean ghostCapable = rt.handshake().supports(target.getUUID(), "ghost-render");
        var combined =
                textFuture.thenCompose(
                        lines ->
                                rt.plan(ServerRuntime.dimensionId(level), revisions, false, ghostCapable, level.getMinY(), level.getMaxY())
                                        .thenApply(plan -> Map.entry(lines, plan)));
        deliver(ctx, rt, combined, e -> {
            var lines = new ArrayList<>(e.getKey());
            lines.addAll(previewLines(rt, target, e.getValue()));
            return lines;
        });
        return 1;
    }

    private static ServerPlayer requirePreviewPlayer(CommandSourceStack source, ServerRuntime rt) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Texts.failure(source, rt, Messages.error(MessageKeys.PREVIEW_PLAYER_ONLY));
            return null;
        }
        if (!rt.handshake().ready(player.getUUID())) {
            Texts.failure(
                    source,
                    rt,
                    Messages.error(MessageKeys.PREVIEW_NO_MOD, "state", rt.handshake().state(player.getUUID()), "reason", rt.handshake().reason(player.getUUID())));
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

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        var rt = runtime(ctx);
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            Texts.failure(ctx.getSource(), rt, Messages.error(MessageKeys.PREVIEW_PLAYER_ONLY));
            return 0;
        }
        rt.clearPreview(player);
        Texts.send(ctx.getSource(), rt, List.of(Msg.prefixed(MessageKeys.PREVIEW_CLEARED)));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        var rt = runtime(ctx);
        rt.catalog().reload();
        Texts.send(ctx.getSource(), rt, List.of(Msg.prefixed(MessageKeys.RELOAD_DONE)));
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        var rt = runtime(ctx);
        var source = ctx.getSource();
        var lines = new ArrayList<Msg>();
        lines.add(Msg.prefixed(MessageKeys.INFO_TITLE, "version", Platform.MINECRAFT));
        lines.add(Msg.of(MessageKeys.INFO_WORLD, "path", rt.worldRoot()));
        lines.add(Msg.of(MessageKeys.INFO_REPO, "path", rt.repositoryRoot()));
        var player = source.getPlayer();
        if (player != null)
            lines.add(Msg.of(MessageKeys.INFO_HANDSHAKE, "state", rt.handshake().state(player.getUUID()), "reason", rt.handshake().reason(player.getUUID())));
        Texts.send(source, rt, lines);
        deliver(ctx, rt, rt.runRepo(() -> {
            var ops = rt.ops();
            return List.of(
                    ops.tracked().isEmpty()
                            ? Msg.of(MessageKeys.INFO_UNTRACKED)
                            : Msg.of(MessageKeys.INFO_TRACKED, "dimensions", String.join(", ", ops.tracked().keySet().stream().map(DimensionId::value).toList())));
        }), l -> l);
        return 1;
    }
}
