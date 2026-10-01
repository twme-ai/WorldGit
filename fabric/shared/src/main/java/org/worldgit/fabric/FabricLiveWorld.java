package org.worldgit.fabric;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import org.worldgit.core.anvil.OfflineSnapshotSource;
import org.worldgit.core.anvil.RegionFile;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.capture.ScanIndex;
import org.worldgit.core.config.EntityTagRegistry;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.ChunkNormalizer;
import org.worldgit.platform.ChunkPatch;
import org.worldgit.platform.LiveWorld;

/**
 * Fabric 端的 LiveWorld（一個維度）。
 *
 * <ul>
 *   <li><b>dirty 偵測</b>：已載入 chunk 的 {@code unsaved} 旗標 ∪ mixin 保留的 dirty generation ∪ 玩家事件 ∪
 *       含實體的 chunk；已存回磁碟的 chunk 由 region 時間戳／雜湊判斷（沿用 core 的離線 index），
 *       存檔清除原版旗標時不會清除 WorldGit 的 generation（doc 04 §2）。
 *   <li><b>快照</b>：已載入的 chunk 在<b>伺服器執行緒</b>用遊戲自己的序列化器複製成 NBT，正規化在背景；
 *       未載入的 chunk 讀磁碟上的 region（commit 前已 flush，不會與遊戲寫入競爭）。
 * </ul>
 *
 * 所有會碰遊戲狀態的方法都只在伺服器執行緒執行；本類別的 public 方法可由 repo executor 呼叫。
 */
final class FabricLiveWorld implements LiveWorld {
    private final ServerRuntime runtime;
    private final WorldLayout layout;
    private final WorldLayout.Dimension dimension;
    private final ServerLevel level;
    private final OfflineSnapshotSource disk;
    private volatile Set<ChunkPos> dirty = Set.of(), entityChunks = Set.of();
    private EntityTagRegistry registry;

    FabricLiveWorld(ServerRuntime runtime, WorldLayout layout, WorldLayout.Dimension dimension) {
        this.runtime = runtime;
        this.layout = layout;
        this.dimension = dimension;
        this.level =
                runtime.server()
                        .getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension.id().value())));
        this.disk = new OfflineSnapshotSource(layout, dimension, ModPacks.INSTANCE);
    }

    @Override
    public DimensionId dimension() {
        return dimension.id();
    }

    @Override
    public int dataVersion() throws IOException {
        return disk.dataVersion();
    }

    @Override
    public String normalizationFingerprint() throws IOException {
        return disk.normalizationFingerprint();
    }

    @Override
    public List<String> warnings() throws IOException {
        return disk.warnings();
    }

    @Override
    public Map<String, byte[]> worldMetadata() throws IOException {
        return disk.worldMetadata();
    }

    private synchronized EntityTagRegistry registry() throws IOException {
        if (registry == null) registry = EntityTagRegistry.load(layout.world(), dataVersion(), ModPacks.INSTANCE);
        return registry;
    }

    private record Gathered(Set<ChunkPos> loaded, Set<ChunkPos> unsaved, Set<ChunkPos> entities) {}

    /** 伺服器執行緒：目前載入的 chunk、其中未存檔者，以及含非玩家實體的 chunk。 */
    private Gathered gather() {
        var loaded = new HashSet<ChunkPos>();
        var unsaved = new HashSet<ChunkPos>();
        for (ChunkPos pos : runtime.loadedChunks(dimension.id())) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) continue;
            loaded.add(pos);
            if (chunk.isUnsaved()) unsaved.add(pos);
        }
        var entities = new HashSet<ChunkPos>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Player || entity.isRemoved()) continue;
            var c = entity.chunkPosition();
            entities.add(new ChunkPos(c.getMinBlockX() >> 4, c.getMinBlockZ() >> 4));
        }
        return new Gathered(loaded, unsaved, entities);
    }

    @Override
    public Scan scan(ScanIndex previous, boolean full) throws IOException {
        Scan base = disk.scan(previous, full);
        if (level == null) return base;
        Gathered g = runtime.onServer(this::gather);
        var present = new HashSet<>(base.present());
        present.addAll(g.loaded());
        var candidates = new HashSet<>(base.candidates());
        if (full) candidates.addAll(g.loaded());
        else {
            candidates.addAll(g.unsaved());
            candidates.addAll(g.entities());
            candidates.addAll(runtime.tracker(dimension.id()).chunks());
            // 新生成、尚未寫入磁碟（index 沒有紀錄）的 chunk
            for (ChunkPos pos : g.loaded()) if (!previous.chunks().containsKey(pos)) candidates.add(pos);
        }
        candidates.retainAll(present);
        // 已不存在的 chunk 仍要交給 capture 刪除
        for (ChunkPos pos : previous.chunks().keySet()) if (!present.contains(pos)) candidates.add(pos);
        ServerRuntime.LOG.debug("WORLDGIT SCAN {} full={} loaded={} unsaved={} entityChunks={} tracker={} candidates={} previous={}", dimension.id(), full, g.loaded().size(), g.unsaved().size(), g.entities().size(), runtime.tracker(dimension.id()).chunks().size(), candidates.size(), previous.chunks().size());
        dirty = Set.copyOf(g.unsaved());
        entityChunks = Set.copyOf(g.entities());
        return new Scan(present, candidates, base.stamps(), base.payloadsRead(), base.scannedAt());
    }

    @Override
    public CompletionStage<Optional<ChunkSnapshot>> snapshot(ChunkPos pos, IgnoreRules rules) {
        if (level == null) return disk.snapshot(pos, rules);
        var captured = new CompletableFuture<Optional<ChunkCapture.Raw>>();
        runtime.postToServer(
                () -> {
                    try {
                        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
                        captured.complete(chunk == null ? Optional.empty() : Optional.of(ChunkCapture.capture(level, chunk)));
                    } catch (Throwable t) {
                        captured.completeExceptionally(t);
                    }
                });
        // 沒載入就讀磁碟；已載入則在背景（ForkJoin 公共池，不是伺服器執行緒）正規化。
        return captured.thenCompose(
                raw -> {
                    if (raw.isEmpty()) return disk.snapshot(pos, rules);
                    return CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    var chunk = ChunkCapture.toCore(raw.get().chunk());
                                    if (!ChunkNormalizer.full(chunk)) return Optional.<ChunkSnapshot>empty();
                                    var entities = new ArrayList<org.worldgit.core.anvil.Nbt.Compound>();
                                    for (var e : raw.get().entities()) entities.add(ChunkCapture.toCore(e));
                                    return Optional.of(new ChunkNormalizer(rules, registry()).normalize(pos, chunk, entities));
                                } catch (IOException e) {
                                    throw new CompletionException(e);
                                }
                            });
                });
    }

    @Override
    public Set<ChunkPos> knownChunks() throws IOException {
        var result = new HashSet<ChunkPos>();
        for (var path : RegionFile.list(dimension.region()))
            try (var r = new RegionFile(path)) {
                for (int i = 0; i < 1024; i++) if (r.has(i)) result.add(r.pos(i));
            }
        if (level != null) result.addAll(runtime.onServer(this::gather).loaded());
        return result;
    }

    @Override
    public Set<ChunkPos> dirtyChunks() {
        var all = new HashSet<>(dirty);
        all.addAll(runtime.tracker(dimension.id()).chunks());
        return all;
    }

    @Override
    public Set<ChunkPos> entityChunks() {
        return entityChunks;
    }

    @Override
    public CompletionStage<Void> apply(ChunkPatch patch) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Phase 2 才提供 apply（restore/switch）"));
    }

    @Override
    public AutoCloseable lockEdits(Collection<ChunkPos> chunks, String reason) {
        return () -> {};
    }

    /** 把所有維度與世界資料寫回磁碟並等待 IO 完成；在伺服器執行緒執行。 */
    @Override
    public CompletionStage<Void> flush() {
        return CompletableFuture.runAsync(runtime::flushBlocking);
    }

    @Override
    public void notifyPlayers(String message) {
        runtime.postToServer(() -> runtime.server().getPlayerList().broadcastSystemMessage(Component.literal(message), false));
    }

    @Override
    public void close() throws IOException {
        disk.close();
    }
}
