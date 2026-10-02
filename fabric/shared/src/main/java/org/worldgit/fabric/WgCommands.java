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
import org.worldgit.core.apply.Scope;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.core.merge.MergeReport;
import org.worldgit.core.merge.MergeState;
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
        root.then(Commands.literal("preview")
                .then(Commands.argument("options",StringArgumentType.greedyString()).executes(ctx->preview(ctx,StringArgumentType.getString(ctx,"options")))));
        for(String command:List.of("restore","switch","branch","stash","reset")) root.then(Commands.literal(command).requires(write)
                .executes(ctx->operation(ctx,command,""))
                .then(Commands.argument("options",StringArgumentType.greedyString()).suggests(FLAG_SUGGESTIONS).executes(ctx->operation(ctx,command,StringArgumentType.getString(ctx,"options")))));
        root.then(Commands.literal("cancel").requires(write).executes(ctx->{
            var rt=runtime(ctx); Texts.send(ctx.getSource(),rt,List.of(Msg.prefixed(rt.cancelApply() ? MessageKeys.APPLY_CANCEL : MessageKeys.APPLY_IDLE))); return 1;
        }));
        for (String command : List.of("merge", "resolve", "revert", "cherry-pick", "conflict-select"))
            root.then(Commands.literal(command).requires(write)
                .then(Commands.argument("options", StringArgumentType.greedyString()).executes(c -> merge(c, command, StringArgumentType.getString(c,"options")))));
        for (String command : List.of("conflicts", "conflict-preview"))
            root.then(Commands.literal(command).executes(c -> conflicts(c, command, ""))
                .then(Commands.argument("options", StringArgumentType.greedyString()).executes(c -> conflicts(c, command, StringArgumentType.getString(c,"options")))));
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
        if(text.contains("工作區有未提交")) return Msg.of(MessageKeys.ERROR_DIRTY);
        if(text.contains("世界為 PARTIAL")) return Msg.of(MessageKeys.ERROR_PARTIAL);
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
                        Msg.of(MessageKeys.HELP_RELOAD),
                        Msg.of(MessageKeys.HELP_PHASE2), Msg.of(MessageKeys.HELP_PHASE3)));
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
        deliver(ctx, rt, rt.merging().thenCompose(state -> state == null
            ? rt.commit(message, identity(ctx.getSource(), rt), false, true).thenApply(batch -> rt.messages().commit(batch, false))
            : rt.live(ops -> ops.commitMerge(identity(ctx.getSource(),rt), CommitMetadata.Source.MOD, message, false)).thenApply(WgCommands::mergeLines)), v -> v);
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
        deliver(ctx, rt, rt.merging(), state -> state == null ? List.of() : List.of(Msg.prefixed(MessageKeys.MERGE_STATUS,
            "remaining", state.remaining(), "total", state.regions().size())));
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

    private static int preview(CommandContext<CommandSourceStack> ctx,String text) {
        var rt=runtime(ctx);
        if(text.trim().equals("off")) return clear(ctx);
        var player=requirePreviewPlayer(ctx.getSource(),rt); if(player==null) return 0;
        try {
            var args=OperationArgs.parse(text,Set.of(),Map.of("--radius",1));
            if(args.positional().size()!=1) throw new IllegalArgumentException();
            Set<org.worldgit.core.model.ChunkPos> window=args.values().containsKey("--radius")
                ? Scope.chunkRadius((player.chunkPosition().getMinBlockX()>>4),(player.chunkPosition().getMinBlockZ()>>4),args.number("--radius")).chunks() : null;
            var level=(ServerLevel)player.level();
            deliver(ctx,rt,rt.preview(ServerRuntime.dimensionId(level),args.positional().getFirst(),window,rt.handshake().supports(player.getUUID(),"ghost-render"),level.getMinY(),level.getMaxY()),plan->previewLines(rt,player,plan));
            return 1;
        } catch(IllegalArgumentException ex) { Texts.failure(ctx.getSource(),rt,Msg.of(MessageKeys.ERROR_PHASE2_ARGS,"usage","/wg preview <rev> [--radius 0..256] | off")); return 0; }
    }
    private static List<Msg> resultLines(ServerRuntime rt,String locale,WorldOperations.Result result) {
        String state=Mini.plain(rt.catalog(),locale,Msg.of(result.state()==WorldOperations.State.DRY_RUN ? MessageKeys.APPLY_DRY_RUN
            : MessageKeys.phase(result.success() ? org.worldgit.platform.ApplyProgress.Phase.COMPLETE : org.worldgit.platform.ApplyProgress.Phase.PARTIAL)));
        int sections=result.dimensions().values().stream().mapToInt(v->v.sections()).sum();
        int entities=result.dimensions().values().stream().mapToInt(v->v.entityPuts()+v.entityRemoves()).sum();
        var lines=new ArrayList<Msg>(); lines.add(Msg.prefixed(MessageKeys.APPLY_RESULT,"state",state,"sections",sections,"entities",entities));
        if(result.error()!=null) lines.add(Msg.of(MessageKeys.COMMON_ERROR,"message",result.error()));
        return lines;
    }
    private static int operation(CommandContext<CommandSourceStack> ctx,String command,String text) {
        var rt=runtime(ctx);
        if(!rt.server().isSingleplayer()) { Texts.failure(ctx.getSource(),rt,Msg.of(MessageKeys.ERROR_SINGLEPLAYER)); return 0; }
        try {
            var flags=switch(command) { case "restore"->Set.of("--dry-run"); case "switch"->Set.of("--stash","--force","--dry-run"); case "reset"->Set.of("--hard","--force","--dry-run"); default->Set.<String>of(); };
            var values=command.equals("restore") ? Map.of("--chunks",1,"--box",6) : Map.<String,Integer>of();
            var args=OperationArgs.parse(text,flags,values); var positions=args.positional();
            String locale=Texts.locale(rt,ctx.getSource().getPlayer());
            CompletableFuture<List<Msg>> future;
            switch(command) {
                case "restore" -> {
                    if(positions.size()!=1) throw new IllegalArgumentException();
                    var player=ctx.getSource().getPlayer();
                    if(!values.isEmpty() && !args.values().isEmpty() && player==null) throw new IllegalArgumentException();
                    var scope=args.scope(player==null ? 0 : (player.chunkPosition().getMinBlockX()>>4),player==null ? 0 : (player.chunkPosition().getMinBlockZ()>>4));
                    var dimension=scope.kind()==Scope.Kind.ALL ? null : ServerRuntime.dimensionId((ServerLevel)player.level());
                    future=rt.live(ops->ops.restore(positions.getFirst(),dimension,scope,args.flag("--dry-run"),false)).thenApply(result->resultLines(rt,locale,result));
                }
                case "switch" -> {
                    if(positions.size()!=1) throw new IllegalArgumentException();
                    future=rt.live(ops->ops.switchTo(positions.getFirst(),args.flag("--stash"),args.flag("--force"),args.flag("--dry-run"),false)).thenApply(result->resultLines(rt,locale,result));
                }
                case "reset" -> {
                    if(!args.flag("--hard") || positions.size()>1) throw new IllegalArgumentException();
                    future=rt.live(ops->ops.resetHard(positions.isEmpty() ? null : positions.getFirst(),args.flag("--force"),args.flag("--dry-run"))).thenApply(result->resultLines(rt,locale,result));
                }
                case "branch" -> {
                    if(positions.isEmpty() || positions.equals(List.of("list"))) future=rt.live(ops->ops.branches().stream().map(b->Msg.of(MessageKeys.BRANCH_ROW,"current",b.current() ? "*" : " ","name",b.name(),"commits",b.commits())).toList());
                    else {
                        boolean delete=positions.getFirst().equals("delete"); boolean create=positions.getFirst().equals("create"); int offset=delete || create ? 1 : 0;
                        if(positions.size()<offset+1 || positions.size()>offset+2 || delete && positions.size()!=2) throw new IllegalArgumentException();
                        String name=positions.get(offset),start=positions.size()>offset+1 ? positions.get(offset+1) : null;
                        future=rt.live(ops->{if(delete) ops.deleteBranch(name); else ops.createBranch(name,start); return List.of(Msg.prefixed(MessageKeys.BRANCH_DONE,"name",name));});
                    }
                }
                case "stash" -> {
                    if(positions.isEmpty()) throw new IllegalArgumentException();
                    String action=positions.getFirst();
                    if(!action.equals("push") && positions.size()>2 || action.equals("list") && positions.size()!=1) throw new IllegalArgumentException();
                    int index=positions.size()>1 && !action.equals("push") ? Integer.parseInt(positions.get(1)) : 0;
                    future=rt.live(ops->switch(action) {
                        case "push" -> ops.stashPush(positions.size()>1 ? String.join(" ",positions.subList(1,positions.size())) : null,false);
                        case "pop" -> ops.stashPop(index,false);
                        case "drop" -> {ops.stashDrop(index);yield List.of(Msg.prefixed(MessageKeys.STASH_DROPPED,"index",index));}
                        case "list" -> {
                            var list=ops.stashes(); var lines=new ArrayList<Msg>();
                            for(int i=0;i<list.size();i++) {var stash=list.get(i);lines.add(Msg.of(MessageKeys.STASH_ROW,"index",i,"id",stash.id(),"message",stash.message(),"time",stash.time()));}
                            yield lines.isEmpty() ? List.of(Msg.of(MessageKeys.STASH_EMPTY)) : lines;
                        }
                        default -> throw new IllegalArgumentException();
                    }).thenApply(value->value instanceof WorldOperations.Result result ? resultLines(rt,locale,result) : (List<Msg>)value);
                }
                default -> throw new IllegalArgumentException();
            }
            deliver(ctx,rt,future,v->v); return 1;
        } catch(IllegalArgumentException ex) { Texts.failure(ctx.getSource(),rt,Msg.of(MessageKeys.ERROR_PHASE2_ARGS,"usage","/wg "+command+" ( /wg )")); return 0; }
    }

    private static List<Msg> mergeLines(WorldOperations.MergeResult result) {
        var lines = new ArrayList<Msg>();
        lines.add(Msg.prefixed(MessageKeys.MERGE_RESULT, "state", result.state(), "remaining", result.merging()==null ? 0 : result.merging().remaining()));
        if (result.error()!=null) lines.add(Msg.of(MessageKeys.COMMON_ERROR,"message",result.error()));
        return lines;
    }

    private static int merge(CommandContext<CommandSourceStack> ctx, String command, String text) {
        var rt=runtime(ctx);
        if(!rt.server().isSingleplayer()) { Texts.failure(ctx.getSource(),rt,Msg.of(MessageKeys.ERROR_SINGLEPLAYER)); return 0; }
        try {
            var author=identity(ctx.getSource(),rt);
            CompletableFuture<WorldOperations.MergeResult> future;
            if(command.equals("conflict-select")) {
                var pos=OperationArgs.parse(text,Set.of(),Map.of()).positional();
                if(pos.size()!=2) throw new IllegalArgumentException();
                int id=Integer.parseInt(pos.getFirst()); var choice=MergeReport.Choice.valueOf(pos.get(1).toUpperCase(Locale.ROOT));
                if(id<1 || choice==MergeReport.Choice.MANUAL) throw new IllegalArgumentException();
                future=rt.live(ops -> ops.selectRegion(id,choice,false,false));
            } else {
                var args=MergeArgs.parse(command,text);
                future=rt.live(ops -> {
                    if(args.abort()) return ops.abortMerge(args.dryRun());
                    if(args.resume()) return ops.continueMerge(author,CommitMetadata.Source.MOD,args.dryRun());
                    if(command.equals("resolve")) return args.choice()==MergeReport.Choice.MANUAL
                        ? ops.markResolved(args.region(),true,args.dryRun()) : ops.selectRegion(args.region(),args.choice(),true,args.dryRun());
                    var result=switch(command) {
                        case "merge" -> ops.merge(args.revision(),args.options(author));
                        case "revert" -> ops.revert(args.revision(),args.options(author));
                        case "cherry-pick" -> ops.cherryPick(args.revision(),args.options(author));
                        default -> throw new IllegalArgumentException();
                    };
                    // Core already applied and verified; do not apply plans a second time or update shapes.
                    if(result.success() && !args.dryRun() && !args.noCommit() && result.merging()!=null && result.merging().remaining()==0)
                        return ops.continueMerge(author,CommitMetadata.Source.MOD,false);
                    return result;
                });
            }
            deliver(ctx,rt,future,WgCommands::mergeLines); return 1;
        } catch(IllegalArgumentException ex) { Texts.failure(ctx.getSource(),rt,Msg.of(MessageKeys.ERROR_PHASE2_ARGS,"usage","/wg "+command+" <rev|id> [--ours|--theirs|--base|--manual|--abort|--continue]")); return 0; }
    }

    private static int conflicts(CommandContext<CommandSourceStack> ctx,String command,String text) {
        var rt=runtime(ctx); var player=ctx.getSource().getPlayer();
        try {
            if(command.equals("conflict-preview")) {
                if(player==null || !rt.handshake().supports(player.getUUID(),org.worldgit.protocol.MergeProtocol.CAPABILITY)) throw new IllegalArgumentException();
                var pos=OperationArgs.parse(text,Set.of(),Map.of()).positional();
                if(pos.size()!=2) throw new IllegalArgumentException();
                int id=Integer.parseInt(pos.getFirst()); var choice=MergeReport.Choice.valueOf(pos.get(1).toUpperCase(Locale.ROOT));
                if(id<1 || choice==MergeReport.Choice.MANUAL) throw new IllegalArgumentException();
                deliver(ctx,rt,rt.conflictPreview(id,choice), packets -> {rt.sendPreview(player,packets);return List.of();}); return 1;
            }
            var args=OperationArgs.parse(text,Set.of("--show"),Map.of("--teleport",1));
            if(!args.positional().isEmpty() || args.values().containsKey("--teleport") && player==null) throw new IllegalArgumentException();
            int target=args.values().containsKey("--teleport") ? args.number("--teleport") : -1;
            deliver(ctx,rt,rt.merging(),state -> {
                if(player!=null) rt.sendConflicts(player,state);
                var lines=new ArrayList<Msg>();
                if(state==null) { lines.add(Msg.of(MessageKeys.CONFLICT_EMPTY)); return lines; }
                lines.add(Msg.prefixed(MessageKeys.MERGE_STATUS,"remaining",state.remaining(),"total",state.regions().size()));
                for(var r:state.regions()) lines.add(Msg.of(MessageKeys.CONFLICT_ROW,"id",r.id(),"dimension",r.dimension(),
                    "bounds",r.bounds(),"count",r.blockCount(),"ours",String.join(", ",r.oursAuthors()),"theirs",String.join(", ",r.theirsAuthors()),
                    "choice",r.choice(),"resolved",r.resolved(),"redstone",r.redstone()));
                if(target!=-1) {
                    var r=state.regions().stream().filter(v -> v.id()==target).findFirst().orElseThrow(() -> new IllegalArgumentException("未知區域"));
                    if(r.bounds()==null) throw new IllegalArgumentException("此區域無世界座標");
                    var b=r.bounds(); var level=rt.server().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.Identifier.parse(r.dimension().value())));
                    if(level==null) throw new IllegalArgumentException("維度尚未載入");
                    player.teleportTo(level,b.minX()+0.5,b.maxY()+2,b.minZ()+0.5,Set.of(),player.getYRot(),player.getXRot(),true);
                }
                return lines;
            }); return 1;
        } catch(IllegalArgumentException ex) { Texts.failure(ctx.getSource(),rt,Msg.of(MessageKeys.ERROR_PHASE2_ARGS,"usage","/wg conflicts [--show|--teleport id] | conflict-preview id ours|theirs|base"));return 0; }
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
