package org.worldgit.fabric;

import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.worldgit.fabric.logic.Msg;
import org.worldgit.fabric.logic.MessageKeys;

/** Axiom handler 的同步玩家來源；try/finally 關閉，絕不以時間窗口猜測作者。 */
public final class AxiomEdits {
    private AxiomEdits() {}
    private record Scope(ServerPlayer player, boolean manipulate, Set<Entity> entities, Set<String> chunks) {}
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final Map<ServerPlayer, Long> NOTICES = new WeakHashMap<>();

    public static boolean allowed(MinecraftServer server, ServerPlayer player) {
        var rt = WorldGitMod.runtime(server);
        if (rt == null || !rt.editsLocked()) return true;
        long now = System.nanoTime();
        Long previous = NOTICES.get(player);
        if (previous != null && now - previous < 1_000_000_000L) return false;
        NOTICES.put(player, now);
        // Coordinate-free handler guard rejects all dimensions while any apply is active.
        Texts.send(player.createCommandSourceStack(), rt, List.of(Msg.of(MessageKeys.COMMON_AXIOM_LOCKED)));
        var level = player.level();
        int radius = Math.min(16, Math.max(2, player.clientInformation().viewDistance()));
        var pos = player.chunkPosition();
        for (int x=(pos.getMinBlockX() >> 4)-radius; x<=(pos.getMinBlockX() >> 4)+radius; x++) for (int z=(pos.getMinBlockZ() >> 4)-radius; z<=(pos.getMinBlockZ() >> 4)+radius; z++) {
            var chunk = level.getChunkSource().getChunkNow(x,z);
            if (chunk != null) player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getChunkSource().getLightEngine(), null, null));
        }
        return false;
    }

    public static AutoCloseable enter(ServerPlayer player, boolean manipulate) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Axiom edit outside server owner");
        Scope previous = CURRENT.get();
        var scope = new Scope(player, manipulate, new LinkedHashSet<>(), new HashSet<>());
        CURRENT.set(scope);
        TouchScope.enter(TouchScope.Kind.AXIOM);
        return () -> {
            try {
                for (var entity : scope.entities()) if (!entity.isRemoved()) {
                    WorldGitMod.touch(entity);
                    if (entity.level() instanceof ServerLevel level) record(scope, level, (entity.chunkPosition().getMinBlockX() >> 4), (entity.chunkPosition().getMinBlockZ() >> 4));
                }
            } finally {
                TouchScope.exit();
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        };
    }

    public static void chunk(LevelChunk chunk) {
        var scope = CURRENT.get();
        if (scope != null && chunk.getLevel() instanceof ServerLevel level) record(scope, level, (chunk.getPos().getMinBlockX() >> 4), (chunk.getPos().getMinBlockZ() >> 4));
    }
    private static void record(Scope scope, ServerLevel level, int x, int z) {
        var key = level.dimension().identifier()+":"+x+":"+z;
        if (!scope.chunks().add(key)) return;
        var rt = WorldGitMod.runtime(level.getServer());
        if (rt != null) rt.playerTouched(scope.player(), level, x << 4, z << 4, "axiom");
    }
    public static void observed(Entity entity) {
        var scope = CURRENT.get();
        if (scope != null && scope.manipulate() && entity != null) scope.entities().add(entity);
    }
    public static void spawned(Entity entity) {
        var scope = CURRENT.get();
        if (scope != null && entity != null) scope.entities().add(entity);
    }
}
