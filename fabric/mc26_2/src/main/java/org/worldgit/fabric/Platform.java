package org.worldgit.fabric;

import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** 26.2 的版本轉接（伺服端／共用）：payload registry 改名為 clientboundPlay／serverboundPlay。 */
public final class Platform {
    private Platform() {}
    static int lightDampening(net.minecraft.world.level.block.state.BlockState state) {return state.getLightDampening();}

    public static final String MINECRAFT = "26.2";

    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c() {
        return PayloadTypeRegistry.clientboundPlay();
    }

    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s() {
        return PayloadTypeRegistry.serverboundPlay();
    }

    public record Payload(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<Payload> type, byte[] bytes)
        implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {}
    static net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<Payload> type(Net.Channel channel) {
        return new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(net.minecraft.resources.Identifier.parse(channel.id()));
    }
    private static boolean registered;
    public static synchronized void registerPayloads() {
        if (registered) return;
        for (var channel : java.util.List.of(Net.HELLO, Net.UI, Net.DIFF, Net.STATUS, Net.CLEAR, Net.COMMENTS, Net.CONFLICTS, Net.CONFLICT_PREVIEW)) {
            var type = type(channel);
            var codec = new net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, Payload>() {
                public Payload decode(RegistryFriendlyByteBuf buf) {
                    if (buf.readableBytes() > org.worldgit.protocol.Protocol.MAX_PAYLOAD) throw new IllegalArgumentException("oversize payload");
                    byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes); return new Payload(type, bytes);
                }
                public void encode(RegistryFriendlyByteBuf buf, Payload payload) { Net.check(payload.bytes()); buf.writeBytes(payload.bytes()); }
            };
            s2c().register(type, codec);
            if (channel.equals(Net.HELLO) || channel.equals(Net.UI)) c2s().register(type, codec);
        }
        registered = true;
    }
    public static void registerServer(java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer, byte[]> hello,
        java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer, byte[]> ui) {
        registerPayloads();
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(type(Net.HELLO), (payload, context) -> hello.accept(context.player(), payload.bytes()));
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(type(Net.UI), (payload, context) -> ui.accept(context.player(), payload.bytes()));
    }
    public static boolean canSend(net.minecraft.server.level.ServerPlayer player, Net.Channel channel) {
        return net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, type(channel));
    }
    public static void send(net.minecraft.server.level.ServerPlayer player, Net.Channel channel, byte[] bytes) {
        Net.check(bytes); net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new Payload(type(channel), bytes));
    }
    public static net.minecraft.network.chat.Component text(net.minecraft.server.MinecraftServer server, net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.platform.modcommon.MinecraftServerAudiences.of(server).asNative(component);
    }
    public static String currentVersion() { return net.minecraft.SharedConstants.getCurrentVersion().id(); }
    /** 已載入 chunk 的追蹤事件；兩版的 CHUNK_LOAD 簽章不同。 */
    public static void registerChunkEvents(BiConsumer<ServerLevel, LevelChunk> load, BiConsumer<ServerLevel, LevelChunk> unload) {
        // 26.2 的 CHUNK_LOAD 多一個 generated 參數
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> load.accept(level, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> unload.accept(level, chunk));
    }
    public static void applyLevelMetadata(net.minecraft.server.MinecraftServer server,net.minecraft.nbt.CompoundTag tag) {
        var level=server.overworld();
        if(tag.contains("spawn")) level.setRespawnData(net.minecraft.world.level.storage.LevelData.RespawnData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,tag.get("spawn")).getOrThrow());
        else if(tag.contains("SpawnX")) level.setRespawnData(net.minecraft.world.level.storage.LevelData.RespawnData.of(level.dimension(),new net.minecraft.core.BlockPos(tag.getIntOr("SpawnX",0),tag.getIntOr("SpawnY",64),tag.getIntOr("SpawnZ",0)),tag.getFloatOr("SpawnAngle",0),0));
        if(tag.contains("game_rules") || tag.contains("GameRules")) {
            var rules=net.minecraft.world.level.gamerules.GameRules.codec(server.getWorldData().getDataConfiguration().enabledFeatures())
                .parse(net.minecraft.nbt.NbtOps.INSTANCE,tag.get(tag.contains("game_rules") ? "game_rules" : "GameRules")).getOrThrow();
            level.getGameRules().setAll(rules,server);
        }
        if(tag.contains("Difficulty")) server.setDifficulty(net.minecraft.world.Difficulty.byId(tag.getByteOr("Difficulty",(byte)2)),true);
        if(tag.contains("DifficultyLocked")) server.setDifficultyLocked(tag.getBooleanOr("DifficultyLocked",false));
        var border=level.getWorldBorder();
        if(tag.contains("BorderCenterX")) border.setCenter(tag.getDoubleOr("BorderCenterX",0),tag.getDoubleOr("BorderCenterZ",0));
        if(tag.contains("BorderSize")) border.setSize(tag.getDoubleOr("BorderSize",59999968));
        if(tag.contains("BorderSafeZone")) border.setSafeZone(tag.getDoubleOr("BorderSafeZone",5));
        if(tag.contains("BorderDamagePerBlock")) border.setDamagePerBlock(tag.getDoubleOr("BorderDamagePerBlock",0.2));
        if(tag.contains("BorderWarningBlocks")) border.setWarningBlocks(tag.getIntOr("BorderWarningBlocks",5));
        if(tag.contains("BorderWarningTime")) border.setWarningTime(tag.getIntOr("BorderWarningTime",15));
    }
    public static net.minecraft.server.level.ServerBossEvent bossbar() {
        return new net.minecraft.server.level.ServerBossEvent(java.util.UUID.randomUUID(),net.minecraft.network.chat.Component.empty(),net.minecraft.world.BossEvent.BossBarColor.BLUE,net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
    }
    static java.util.List<java.util.concurrent.CompletableFuture<?>> saveMetadata(net.minecraft.server.MinecraftServer server,java.util.Collection<net.minecraft.server.level.ServerLevel> levels) {
        var waits=new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
        server.getScoreboard().storeToSaveDataIfDirty(server.getDataStorage().computeIfAbsent(net.minecraft.world.scores.ScoreboardSaveData.TYPE));
        for(var level:levels) {
            ((org.worldgit.fabric.mixin.ServerLevelAccess)level).worldgit$saveLevelData(false);
            waits.add(((org.worldgit.fabric.mixin.SavedDataAccess)level.getDataStorage()).worldgit$pendingWrite());
        }
        server.getDataStorage().scheduleSave();waits.add(((org.worldgit.fabric.mixin.SavedDataAccess)server.getDataStorage()).worldgit$pendingWrite());
        var profile=server.getSingleplayerProfile();
        ((org.worldgit.fabric.mixin.ServerStorageAccess)server).worldgit$storage().saveDataTag(server.getWorldData(),profile==null ? null : profile.id());
        return waits;
    }

    public static org.worldgit.core.version.NbtUpgrader dataFixer(net.minecraft.server.MinecraftServer server) throws java.io.IOException {
        org.worldgit.core.version.NbtUpgrader backend = (type, input, from, to) -> {
            if (to != ChunkCapture.currentDataVersion() || from > to) throw new java.io.IOException("DFU target does not match server DataVersion");
      com.mojang.datafixers.DSL.TypeReference reference=switch(type) {
        case "CHUNK"->net.minecraft.util.datafix.fixes.References.CHUNK;
        case "ENTITY_CHUNK"->net.minecraft.util.datafix.fixes.References.ENTITY_CHUNK;
        case "ITEM_STACK"->net.minecraft.util.datafix.fixes.References.ITEM_STACK;
        case "LEVEL"->net.minecraft.util.datafix.fixes.References.LEVEL;
        case "SAVED_DATA_MAP_DATA"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_MAP_DATA;
        case "SAVED_DATA_SCOREBOARD"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_SCOREBOARD;
        case "SAVED_DATA_COMMAND_STORAGE"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_COMMAND_STORAGE;
        case "SAVED_DATA_MAP_INDEX"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_MAP_INDEX;
        case "SAVED_DATA_TICKETS"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_TICKETS;
        case "SAVED_DATA_RAIDS"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_RAIDS;
        case "SAVED_DATA_RANDOM_SEQUENCES"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_RANDOM_SEQUENCES;
        case "SAVED_DATA_CUSTOM_BOSS_EVENTS"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_CUSTOM_BOSS_EVENTS;
        case "SAVED_DATA_ENDER_DRAGON_FIGHT"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_ENDER_DRAGON_FIGHT;
        case "SAVED_DATA_GAME_RULES"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_GAME_RULES;
        case "SAVED_DATA_SCHEDULED_EVENTS"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_SCHEDULED_EVENTS;
        case "SAVED_DATA_STOPWATCHES"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_STOPWATCHES;
        case "SAVED_DATA_STRUCTURE_FEATURE_INDICES"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_STRUCTURE_FEATURE_INDICES;
        case "SAVED_DATA_WANDERING_TRADER"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_WANDERING_TRADER;
        case "SAVED_DATA_WEATHER"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_WEATHER;
        case "SAVED_DATA_WORLD_BORDER"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_WORLD_BORDER;
        case "SAVED_DATA_WORLD_CLOCKS"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_WORLD_CLOCKS;
        case "SAVED_DATA_WORLD_GEN_SETTINGS"->net.minecraft.util.datafix.fixes.References.SAVED_DATA_WORLD_GEN_SETTINGS;
        default->throw new java.io.IOException("Unsupported server DFU type: "+type);
      };
            var fixed = net.minecraft.util.datafix.DataFixers.getDataFixer().update(reference,
                new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, FabricApply.vanilla(input)), from, to);
            return ChunkCapture.toCore((net.minecraft.nbt.CompoundTag) fixed.getValue());
        };
        var features = server.getWorldData().enabledFeatures();
        var rules = net.minecraft.world.level.gamerules.GameRules.codec(features).encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,
            new net.minecraft.world.level.gamerules.GameRules(features)).getOrThrow();
        return org.worldgit.fabric.logic.FabricMetadataProjection.wrap(backend, ChunkCapture.currentDataVersion(),
            ChunkCapture.toCore((net.minecraft.nbt.CompoundTag) rules), server.getPackRepository().getAvailableIds(),
            server.getWorldData().getDataConfiguration().dataPacks().getEnabled(),
            server.getWorldData().getDataConfiguration().dataPacks().getDisabled());
    }
    public static boolean allowed(net.minecraft.commands.CommandSourceStack source, int level) {
        var check = switch (level) {
            case 0 -> net.minecraft.commands.Commands.LEVEL_ALL; case 1 -> net.minecraft.commands.Commands.LEVEL_MODERATORS;
            case 2 -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS; case 3 -> net.minecraft.commands.Commands.LEVEL_ADMINS;
            default -> net.minecraft.commands.Commands.LEVEL_OWNERS;
        };
        var player = source.getPlayer(); var server = source.getServer();
        return net.minecraft.commands.Commands.hasPermission(check).test(source)
            || player != null && server.isSingleplayer() && server.isSingleplayerOwner(player.nameAndId());
    }
    public static int viewDistance(net.minecraft.server.level.ServerPlayer player) { return player.clientInformation().viewDistance(); }
    public static String language(net.minecraft.server.level.ServerPlayer player) { return player.clientInformation().language(); }
    public static void teleport(net.minecraft.server.level.ServerPlayer player, net.minecraft.server.level.ServerLevel level, double x, double y, double z) {
        player.teleportTo(level,x,y,z,java.util.Set.of(),player.getYRot(),player.getXRot(), true);
    }
}
