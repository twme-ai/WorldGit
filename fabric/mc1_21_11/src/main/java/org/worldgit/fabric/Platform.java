package org.worldgit.fabric;

import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** 1.21.11 的版本轉接（伺服端／共用）：payload registry 的名稱。 */
public final class Platform {
    private Platform() {}

    public static final String MINECRAFT = "1.21.11";

    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c() {
        return PayloadTypeRegistry.playS2C();
    }

    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s() {
        return PayloadTypeRegistry.playC2S();
    }

    /** 已載入 chunk 的追蹤事件；兩版的 CHUNK_LOAD 簽章不同。 */
    public static void registerChunkEvents(BiConsumer<ServerLevel, LevelChunk> load, BiConsumer<ServerLevel, LevelChunk> unload) {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> load.accept(level, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> unload.accept(level, chunk));
    }
}
