package org.worldgit.fabric;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.worldgit.core.anvil.Nbt;

/**
 * 在伺服器執行緒上把已載入的 chunk 與其實體複製成 NBT（與遊戲存檔使用同一個序列化器，所以內容與
 * region 檔一致）。複製完立刻離開伺服器執行緒；正規化與雜湊在背景做。
 */
final class ChunkCapture {
    private ChunkCapture() {}

    record Raw(CompoundTag chunk, List<CompoundTag> entities) {}

    static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    /** 必須在伺服器執行緒呼叫。 */
    static Raw capture(ServerLevel level, LevelChunk chunk) {
        CompoundTag tag = ChunkSerializer.write(level, chunk);
        if (!tag.contains("DataVersion")) tag.putInt("DataVersion", currentDataVersion());
        var pos = chunk.getPos();
        long elapsed=ChunkTickLocks.elapsed(level,ServerRuntime.corePos(pos));
        for(var key:List.of("block_ticks","fluid_ticks")) for(var value:tag.getList(key, 10)) if(value instanceof CompoundTag tick)
            tick.putInt("t",Math.toIntExact(tick.getInt("t")+elapsed));
        int minX = pos.getMinBlockX(), minZ = pos.getMinBlockZ();
        var entities = new ArrayList<CompoundTag>();
        var manager = (org.worldgit.fabric.mixin.EntityManagerAccess<Entity>) (Object)
            ((org.worldgit.fabric.mixin.ServerLevelAccess) level).worldgit$entities();
        // 原版可見清單不含傳送至未 ticking chunk 的實體，存檔的 section census 才完整。
        for (Entity entity : manager.worldgit$sections().getExistingSectionsInChunk(Integer.toUnsignedLong(minX >> 4) | ((long) (minZ >> 4) << 32))
                .flatMap(section -> section.getEntities()).toList()) {
            if (entity instanceof Player || !entity.shouldBeSaved()) continue;
            if (entity.chunkPosition().getMinBlockX() != minX || entity.chunkPosition().getMinBlockZ() != minZ) continue;
            var out = new CompoundTag();
            if (entity.save(out)) entities.add(out);
        }
        return new Raw(tag, entities);
    }

    /** vanilla NBT → core 的 canonical NBT（以 NbtIo 的標準位元組格式橋接，不洩漏 MC 型別）。 */
    static Nbt.Compound toCore(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return Nbt.read(bytes.toByteArray());
    }
}
