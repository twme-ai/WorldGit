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

}
