package org.worldgit.fabric;

import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** 1.20.1 的原生 API／legacy payload 轉接。 */
public final class Platform {
    private Platform() {}
    

    public static final String MINECRAFT = "1.20.1";

    public static void registerPayloads() {}
    private static net.minecraft.resources.ResourceLocation channel(Net.Channel channel) { return new net.minecraft.resources.ResourceLocation(channel.id()); }
    public static void registerServer(java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer, byte[]> hello,
        java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer, byte[]> ui) {
        for (var channel : java.util.List.of(Net.HELLO, Net.UI))
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(channel(channel), (server, player, handler, buf, sender) -> {
                if (buf.readableBytes() > org.worldgit.protocol.Protocol.MAX_PAYLOAD) return;
                byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes);
                server.execute(() -> (channel.equals(Net.HELLO) ? hello : ui).accept(player, bytes));
            });
    }
    public static boolean canSend(net.minecraft.server.level.ServerPlayer player, Net.Channel channel) {
        return net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, channel(channel));
    }
    public static void send(net.minecraft.server.level.ServerPlayer player, Net.Channel channel, byte[] bytes) {
        Net.check(bytes); var buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create(); buf.writeBytes(bytes);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, channel(channel), buf);
    }
    public static net.minecraft.network.chat.Component text(net.minecraft.server.MinecraftServer server, net.kyori.adventure.text.Component component) {
        return net.minecraft.network.chat.Component.Serializer.fromJson(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.builder().options(net.kyori.adventure.text.serializer.json.JSONOptions.byDataVersion().at(ChunkCapture.currentDataVersion())).build().serialize(component));
    }
    public static String currentVersion() { return net.minecraft.SharedConstants.getCurrentVersion().getId(); }
    /** 已載入 chunk 的追蹤事件；兩版的 CHUNK_LOAD 簽章不同。 */
    public static void registerChunkEvents(BiConsumer<ServerLevel, LevelChunk> load, BiConsumer<ServerLevel, LevelChunk> unload) {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> load.accept(level, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> unload.accept(level, chunk));
    }
    public static void applyLevelMetadata(net.minecraft.server.MinecraftServer server, net.minecraft.nbt.CompoundTag tag) {
        var level=server.overworld();
        if(tag.contains("SpawnX")) level.setDefaultSpawnPos(new net.minecraft.core.BlockPos(tag.getInt("SpawnX"),tag.getInt("SpawnY"),tag.getInt("SpawnZ")),tag.getFloat("SpawnAngle"));
        if(tag.contains("GameRules")) level.getGameRules().assignFrom(new net.minecraft.world.level.GameRules(new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE,tag.get("GameRules"))),server);
        if(tag.contains("Difficulty")) server.setDifficulty(net.minecraft.world.Difficulty.byId(tag.getByte("Difficulty")),true);
        if(tag.contains("DifficultyLocked")) server.setDifficultyLocked(tag.getBoolean("DifficultyLocked"));
        var border=level.getWorldBorder();
        if(tag.contains("BorderCenterX")) border.setCenter(tag.getDouble("BorderCenterX"),tag.getDouble("BorderCenterZ"));
        if(tag.contains("BorderSize")) border.setSize(tag.getDouble("BorderSize"));
        if(tag.contains("BorderSafeZone")) border.setDamageSafeZone(tag.getDouble("BorderSafeZone"));
        if(tag.contains("BorderDamagePerBlock")) border.setDamagePerBlock(tag.getDouble("BorderDamagePerBlock"));
        if(tag.contains("BorderWarningBlocks")) border.setWarningBlocks(tag.getInt("BorderWarningBlocks"));
        if(tag.contains("BorderWarningTime")) border.setWarningTime(tag.getInt("BorderWarningTime"));
    }
    public static net.minecraft.server.level.ServerBossEvent bossbar() {
        return new net.minecraft.server.level.ServerBossEvent(net.minecraft.network.chat.Component.empty(),net.minecraft.world.BossEvent.BossBarColor.BLUE,net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
    }
    static java.util.List<java.util.concurrent.CompletableFuture<?>> saveMetadata(net.minecraft.server.MinecraftServer server, java.util.Collection<net.minecraft.server.level.ServerLevel> levels) {
        for(var level:levels) { ((org.worldgit.fabric.mixin.ServerLevelAccess)level).worldgit$saveLevelData(); level.getDataStorage().save(); }
        var data=server.getWorldData(); data.setCustomBossEvents(server.getCustomBossEvents().save());
        ((org.worldgit.fabric.mixin.ServerStorageAccess)server).worldgit$storage().saveDataTag(server.registryAccess(), data, server.getPlayerList().getSingleplayerData());
        return java.util.List.of();
    }
    public static org.worldgit.core.version.NbtUpgrader dataFixer(net.minecraft.server.MinecraftServer server) throws java.io.IOException {
        org.worldgit.core.version.NbtUpgrader backend = (type, input, from, to) -> {
            if (to != ChunkCapture.currentDataVersion() || from > to) throw new java.io.IOException("DFU target does not match server DataVersion");
            com.mojang.datafixers.DSL.TypeReference reference=switch(type) {
                case "CHUNK" -> net.minecraft.util.datafix.fixes.References.CHUNK;
                case "ENTITY_CHUNK" -> net.minecraft.util.datafix.fixes.References.ENTITY_CHUNK;
                case "ITEM_STACK" -> net.minecraft.util.datafix.fixes.References.ITEM_STACK;
                case "LEVEL" -> net.minecraft.util.datafix.fixes.References.LEVEL;
                case "SAVED_DATA_MAP_DATA", "SAVED_DATA_SCOREBOARD", "SAVED_DATA_COMMAND_STORAGE", "SAVED_DATA_MAP_INDEX", "SAVED_DATA_TICKETS", "SAVED_DATA_RAIDS", "SAVED_DATA_RANDOM_SEQUENCES" -> net.minecraft.util.datafix.fixes.References.SAVED_DATA;
                default -> throw new java.io.IOException("Unsupported server DFU type: " + type);
            };
            var fixed = net.minecraft.util.datafix.DataFixers.getDataFixer().update(reference,
                new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, FabricApply.vanilla(input)), from, to);
            return ChunkCapture.toCore((net.minecraft.nbt.CompoundTag) fixed.getValue());
        };
        return org.worldgit.fabric.logic.FabricMetadataProjection.wrap(backend, ChunkCapture.currentDataVersion(),
            ChunkCapture.toCore(new net.minecraft.world.level.GameRules().createTag()), server.getPackRepository().getAvailableIds(),
            server.getWorldData().getDataConfiguration().dataPacks().getEnabled(),
            server.getWorldData().getDataConfiguration().dataPacks().getDisabled());
    }
    public static boolean allowed(net.minecraft.commands.CommandSourceStack source, int level) {
        var player=source.getPlayer(); var server=source.getServer();
        return source.hasPermission(level) || player != null && server.isSingleplayer() && server.isSingleplayerOwner(player.getGameProfile());
    }
    public static int viewDistance(net.minecraft.server.level.ServerPlayer player) { return ((org.worldgit.fabric.PlayerLanguageAccess)player).worldgit$viewDistance(); }
    public static String language(net.minecraft.server.level.ServerPlayer player) { return ((org.worldgit.fabric.PlayerLanguageAccess)player).worldgit$language(); }
    public static java.util.concurrent.CompletableFuture<Void> lightBarrier(net.minecraft.server.level.ServerLevel level, int x, int z) {
        var light=level.getChunkSource().getLightEngine(); var done=new java.util.concurrent.CompletableFuture<Void>();
        ((org.worldgit.fabric.mixin.LightQueueAccess)light).worldgit$enqueue(x,z,net.minecraft.server.level.ThreadedLevelLightEngine.TaskType.POST_UPDATE,()->done.complete(null));
        light.tryScheduleUpdate(); return done;
    }
    public static void teleport(net.minecraft.server.level.ServerPlayer player, net.minecraft.server.level.ServerLevel level, double x, double y, double z) {
        player.teleportTo(level,x,y,z,java.util.Set.of(),player.getYRot(),player.getXRot());
    }
}
