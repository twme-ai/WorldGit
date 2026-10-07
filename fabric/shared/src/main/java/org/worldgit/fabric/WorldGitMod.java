package org.worldgit.fabric;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import org.worldgit.fabric.logic.ServerConfig;
import org.worldgit.i18n.MessageCatalog;

/**
 * WorldGit 的伺服端進入點：專用伺服器與單人世界（整合伺服器）都走這裡。
 * 客戶端顯示在 {@code org.worldgit.fabric.client.WorldGitClient}。
 */
public final class WorldGitMod implements ModInitializer {
    private static final Map<MinecraftServer, ServerRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private static volatile ServerConfig config;

    public static ServerRuntime runtime(MinecraftServer server) {
        return RUNTIMES.get(server);
    }

    /** 玩家造成的實體異動（mixin／事件呼叫）；沒有 runtime（例如測試世界）時忽略。 */
    public static void touch(net.minecraft.world.entity.Entity entity) {
        if (entity != null && entity.level() instanceof ServerLevel level) {
            var rt = runtime(level.getServer());
            if (rt != null) rt.touched().touch(entity);
        }
    }

    /** 變形：舊 UUID 的觸及資格繼承到新實體。 */
    public static void converted(net.minecraft.world.entity.Entity from, net.minecraft.world.entity.Entity to) {
        if (from != null && to != null && from.level() instanceof ServerLevel level) {
            var rt = runtime(level.getServer());
            if (rt != null) rt.touched().converted(from, to);
        }
    }

    /** 玩家或已具資格的實體騎乘成功後，把完整載具閉包一併記錄。 */
    public static void mounted(net.minecraft.world.entity.Entity rider, net.minecraft.world.entity.Entity vehicle) {
        if (vehicle.level() instanceof ServerLevel level) {
            var rt = runtime(level.getServer());
            if (rt != null && (rider instanceof net.minecraft.world.entity.player.Player
                    || rt.touched().known(rider.getUUID()) || rt.touched().known(vehicle.getUUID()))) rt.touched().touch(vehicle);
        }
    }

    /** Called by the chunk mixin for commands, block entities and natural changes alike. */
    public static void chunkChanged(net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel level) {
            var rt = runtime(level.getServer());
            if (rt != null) rt.chunkChanged(level, chunk);
        }
    }

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        try {
            config = ServerConfig.load(configDir);
        } catch (IOException e) {
            // 設定錯誤要讓管理員看見，但不能讓伺服器起不來：退回預設值並明確記錄。
            ServerRuntime.LOG.error("WorldGit 設定檔無效，改用預設值：{}", e.getMessage());
            config = ServerConfig.defaults();
        }
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity,damage,amount)->{
            if(entity instanceof ServerPlayer player) {
                var rt=runtime(player.level().getServer());
                if(rt!=null && rt.protects(player,damage)) return false;
            } else if(entity.level() instanceof ServerLevel level) {
                var rt=runtime(level.getServer());
                if(rt!=null && !rt.internalMutation() && rt.editsLocked(ServerRuntime.dimensionId(level),ServerRuntime.corePos(entity.chunkPosition()))) return false;
            }
            return true;
        });
        Net.register();
        ServerPlayNetworking.registerGlobalReceiver(
                Net.HELLO.type(),
                (payload, context) -> {
                    var rt = runtime(context.server());
                    if (rt != null) rt.onHello(context.player(), payload.bytes());
                });
        ServerPlayNetworking.registerGlobalReceiver(
                Net.UI.type(),
                (payload, context) -> {
                    var rt = runtime(context.server());
                    if (rt != null) rt.uiServer().onPacket(context.player(), payload.bytes());
                });
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> WgCommands.register(dispatcher, config));
        // 裸 /git 在其他模組註冊之後才檢查，已存在時跳過並記錄。
        var late = net.minecraft.resources.Identifier.parse("worldgit:late");
        CommandRegistrationCallback.EVENT.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, late);
        CommandRegistrationCallback.EVENT.register(late, (dispatcher, buildContext, selection) -> WgCommands.registerGit(dispatcher, config));
        // 玩家互動的實體（命名、拴繩、馴服、裝備、染色、騎乘、展示框內容…）算 player-touched。
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer && level instanceof ServerLevel && !player.isSpectator()) touch(entity);
            return InteractionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            var rt = runtime(level.getServer());
            if (rt != null) rt.touched().loaded(entity, level);
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(server -> TouchScope.reset());

        // Register before worlds load so the initial spawn chunks are tracked too.
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                RUNTIMES.put(server, new ServerRuntime(server, config, MessageCatalog.withOverrides(configDir.resolve("worldgit").resolve("lang")))));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerRuntime.LOG.info("WorldGit {} 已啟動（{}）", Platform.MINECRAFT, server.isDedicatedServer() ? "專用伺服器" : "整合伺服器");
            RUNTIMES.get(server).started();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var rt = RUNTIMES.get(server);
            if (rt != null) rt.stopping();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            var rt = RUNTIMES.remove(server);
            if (rt != null) rt.shutdown();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var rt = RUNTIMES.get(server);
            if (rt != null) rt.tick();
        });
        Platform.registerChunkEvents(
                (level, chunk) -> {
                    var rt = RUNTIMES.get(level.getServer());
                    if (rt != null) rt.chunkLoaded(level, chunk);
                },
                (level, chunk) -> {
                    var rt = RUNTIMES.get(level.getServer());
                    if (rt != null) rt.chunkUnloaded(level, chunk);
                });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var rt = RUNTIMES.get(server);
            if (rt != null) rt.playerJoined(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var rt = RUNTIMES.get(server);
            if (rt == null) return;
            rt.playerLoggedOut(handler.getPlayer());
            rt.playerLeft(handler.getPlayer());
        });
        // 作者歸屬：玩家破壞／放置方塊（chunk 粒度）
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
                var rt = RUNTIMES.get(sl.getServer());
                if (rt != null) rt.playerTouched(sp, sl, pos.getX(), pos.getZ(), "break");
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl && sp.getItemInHand(hand).getItem() instanceof BlockItem) {
                var rt = RUNTIMES.get(sl.getServer());
                var target = hit.getBlockPos().relative(hit.getDirection());
                if (rt != null) rt.playerTouched(sp, sl, target.getX(), target.getZ(), "place");
            }
            return InteractionResult.PASS;
        });
    }
}
