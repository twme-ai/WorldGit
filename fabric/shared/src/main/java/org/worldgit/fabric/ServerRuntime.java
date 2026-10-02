package org.worldgit.fabric;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.model.*;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.core.apply.*;
import org.worldgit.platform.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.worldgit.fabric.logic.*;
import org.worldgit.i18n.MessageCatalog;
import org.worldgit.platform.DirtyChunkTracker;
import org.worldgit.protocol.DiffPalette;
import org.worldgit.protocol.Protocol;
import org.worldgit.protocol.MergeProtocol;
import org.worldgit.core.merge.MergeState;
import org.worldgit.core.merge.MergeReport;

/**
 * 一個 MinecraftServer（專用伺服器，或單人世界的整合伺服器）上的 WorldGit 執行期。
 *
 * <p>執行緒模型：伺服器執行緒只做指令解析、事件記錄與 chunk 複製；所有 repo 操作（JGit、雜湊、正規化）
 * 都在單一的背景 repo executor 上依序執行，結果再回到伺服器執行緒送訊息。repo 狀態（HEAD、index、鎖）
 * 因此永遠只有一個寫入者。
 */
public final class ServerRuntime {
    public static final Logger LOG = LoggerFactory.getLogger("WorldGit");

    private final MinecraftServer server;
    private final ServerConfig config;
    private final MessageCatalog catalog;
    private final Messages messages = new Messages();
    private final ExecutorService repo =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "WorldGit-repo");
                        t.setDaemon(true);
                        return t;
                    });
    private final Attribution attribution = new Attribution();
    private final Map<DimensionId, DirtyChunkTracker> trackers = new ConcurrentHashMap<>();
    private final Map<DimensionId, Set<ChunkPos>> loaded = new ConcurrentHashMap<>();
    private final HandshakeTracker handshake = new HandshakeTracker();
    private final Map<UUID, Deque<byte[]>> outbound = new ConcurrentHashMap<>();
    private final AtomicLong previewIds = new AtomicLong(1);
    private final AutoCommitPolicy policy;
    private volatile boolean closed;
    private volatile boolean autoRunning;

    ServerRuntime(MinecraftServer server, ServerConfig config, MessageCatalog catalog) {
        this.server = server;
        this.config = config;
        this.catalog = catalog;
        this.policy = new AutoCommitPolicy(config.autoCommit(), System.currentTimeMillis());
    }

    public MinecraftServer server() {
        return server;
    }

    public ServerConfig config() {
        return config;
    }

    public MessageCatalog catalog() {
        return catalog;
    }

    public Messages messages() {
        return messages;
    }

    /** 寫進 git 歷史的訊息：用伺服器設定的語言（歷史是共用資料，不依個別玩家語言）。 */
    String commitText(Msg msg) {
        return Mini.plain(catalog, config.locale(), msg);
    }

    public HandshakeTracker handshake() {
        return handshake;
    }

    public Attribution attribution() {
        return attribution;
    }

    DirtyChunkTracker tracker(DimensionId dimension) {
        return trackers.computeIfAbsent(dimension, k -> new DirtyChunkTracker());
    }

    Set<ChunkPos> loadedChunks(DimensionId dimension) {
        return Set.copyOf(loaded.getOrDefault(dimension, Set.of()));
    }

    // ---- 事件 -------------------------------------------------------------------------

    static DimensionId dimensionId(ServerLevel level) {
        return new DimensionId(level.dimension().identifier().toString());
    }

    static ChunkPos corePos(net.minecraft.world.level.ChunkPos pos) {
        return new ChunkPos(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
    }

    void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        loaded.computeIfAbsent(dimensionId(level), k -> ConcurrentHashMap.newKeySet()).add(corePos(chunk.getPos()));
        // Recheck loaded chunks even if they were saved while WorldGit was stopped.
        chunkChanged(level, chunk);
    }

    void chunkChanged(ServerLevel level, LevelChunk chunk) {
        tracker(dimensionId(level)).mark(corePos(chunk.getPos()));
    }

    void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        var set = loaded.get(dimensionId(level));
        if (set != null) set.remove(corePos(chunk.getPos()));
    }

    /** 玩家放置／破壞：標記 chunk dirty 並記錄歸屬。伺服器執行緒。 */
    void playerTouched(ServerPlayer player, ServerLevel level, int blockX, int blockZ, String cause) {
        var dim = dimensionId(level);
        var chunk = new ChunkPos(blockX >> 4, blockZ >> 4);
        tracker(dim).mark(chunk);
        attribution.record(dim, chunk, player.getUUID(), player.nameAndId().name(), cause);
    }

    // ---- 路徑 -------------------------------------------------------------------------

    public Path worldRoot() {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    /** 與 WorldLayout.repositoryRoot() 相同的規則，但不需要 level.dat 已存在。 */
    public Path repositoryRoot() {
        Path world = worldRoot();
        return world.getParent().resolve(".worldgit").resolve(world.getFileName().toString());
    }

    public WorldOps ops() throws IOException {
        var layout = WorldLayout.discover(worldRoot());
        return new WorldOps(layout, dim -> new FabricLiveWorld(this, layout, dim));
    }

    public String paletteName() {
        try {
            return WorldGitConfig.readLocal(repositoryRoot().resolve("worldgit.yml")).palette();
        } catch (IOException | RuntimeException e) {
            return "default";
        }
    }

    public DiffPalette palette() {
        return paletteName().equals("colorblind") ? DiffPalette.COLORBLIND : DiffPalette.DEFAULT;
    }

    // ---- 執行緒 -----------------------------------------------------------------------

    private final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();

    /**
     * 在伺服器執行緒上排程工作。不直接用 {@code server.execute}：伺服器開始關閉後，原版的 execute 會
     * 「在呼叫者的執行緒立刻執行」，結果 chunk 存檔會在 repo 執行緒上與伺服器關閉流程並行（chunk map 損毀）。
     * 這裡自己維護佇列，只由伺服器執行緒清空（每個 tick、execute 排程、以及關閉時的 managedBlock）。
     */
    void postToServer(Runnable task) {
        serverTasks.add(task);
        server.execute(this::drainServerTasks);
    }

    /** 只在伺服器執行緒執行；被 execute 在別的執行緒立刻呼叫時什麼都不做（工作留在佇列）。 */
    void drainServerTasks() {
        if (!server.isSameThread()) return;
        Runnable task;
        while ((task = serverTasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.warn("WORLDGIT 伺服器執行緒工作失敗", t);
            }
        }
    }

    /** 從 repo 執行緒（或其他背景執行緒）在伺服器執行緒上執行並等待結果。 */
    <T> T onServer(Callable<T> task) {
        if (server.isSameThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }
        if (closed) throw new CompletionException(new IOException("WorldGit 已停止"));
        var future = new CompletableFuture<T>();
        postToServer(
                () -> {
                    try {
                        future.complete(task.call());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
        try {
            return future.get(120, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new CompletionException(e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new CompletionException(e);
        }
    }

    /** 把所有維度與世界資料寫回磁碟並等待寫入完成（等同 /save-all flush）。 */
    void flushBlocking() {
        onServer(() -> server.saveEverything(true, true, true));
    }

    <T> CompletableFuture<T> runRepo(Callable<T> task) {
        var future = new CompletableFuture<T>();
        if (closed) {
            future.completeExceptionally(new IOException("WorldGit 已停止"));
            return future;
        }
        repo.execute(
                () -> {
                    try {
                        future.complete(task.call());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
        return future;
    }

    private record Delayed(int tick, Runnable task) {}
    private final Queue<Delayed> tickTasks=new ConcurrentLinkedQueue<>();
    void nextTick(Runnable task) { tickTasks.add(new Delayed(server.getTickCount()+1,task)); }
    private void drainTickTasks(boolean all) {
        int count=tickTasks.size();
        for(int i=0;i<count;i++) {
            var task=tickTasks.poll(); if(task==null) break;
            if(all || task.tick()<=server.getTickCount()) task.task().run(); else tickTasks.add(task);
        }
    }
    private volatile int editLocks;
    private boolean mutation, wasFrozen;
    public boolean editsLocked() { return editLocks>0; }
    public boolean internalMutation() { return server.isSameThread() && mutation; }
    @FunctionalInterface interface Mutation { void run() throws Exception; }
    void mutate(Mutation action) throws Exception {
        boolean before=mutation; mutation=true;
        try { action.run(); } finally { mutation=before; }
    }
    AutoCloseable lockWorld() {
        onServer(()->{
            if(editLocks++==0) {
                wasFrozen=server.tickRateManager().isFrozen(); server.tickRateManager().setFrozen(true);
                for(var player:server.getPlayerList().getPlayers()) player.closeContainer();
            }
            return null;
        });
        return ()->onServer(()->{ if(--editLocks==0) server.tickRateManager().setFrozen(wasFrozen); return null; });
    }
    private volatile UUID operation;
    private final AtomicBoolean cancel=new AtomicBoolean();
    private volatile CompletableFuture<?> operationFuture;
    public boolean operationActive() { return operation!=null; }
    public CompletableFuture<?> lastOperation() { return operationFuture; }
    UUID operationId() { return operation; }
    boolean cancelRequested() { return cancel.get(); }
    public boolean cancelApply() { if(operation==null) return false; cancel.set(true); return true; }
    private record Policy(DimensionId dimension, PlayerProtection protection, long expires, Set<UUID> seen) {}
    private final Map<UUID,Map<DimensionId,Policy>> protection=new HashMap<>();
    CompletionStage<Void> protect(DimensionId dimension, PlayerProtection policy) {
        var future=new CompletableFuture<Void>();
        postToServer(()->{
            var id=policy.operation()==null ? UUID.randomUUID() : policy.operation();
            var group=protection.computeIfAbsent(id,k->new HashMap<>());
            var previous=group.get(dimension);
            var seen=previous==null ? new HashSet<UUID>() : previous.seen();
            var guard=new Policy(dimension,policy,policy.active() ? Long.MAX_VALUE : System.nanoTime()+policy.duration().toNanos(),seen);
            group.put(dimension,guard); remember(guard); future.complete(null);
        });
        return future;
    }
    private void remember(Policy guard) {
        for(var player:server.getPlayerList().getPlayers()) if(dimensionId((ServerLevel)player.level()).equals(guard.dimension())
            && guard.protection().chunks().contains(corePos(player.chunkPosition()))) guard.seen().add(player.getUUID());
    }
    public boolean protects(ServerPlayer player,net.minecraft.world.damagesource.DamageSource source) {
        PlayerProtection.Damage cause=source.is(net.minecraft.world.damagesource.DamageTypes.FALL) ? PlayerProtection.Damage.FALL
            : source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) ? PlayerProtection.Damage.SUFFOCATION
            : source.is(net.minecraft.world.damagesource.DamageTypes.DROWN) ? PlayerProtection.Damage.DROWNING : null;
        if(cause==null) return false;
        var dimension=dimensionId((ServerLevel)player.level());
        for(var group:protection.values()) {
            var guard=group.get(dimension);
            if(guard==null || guard.expires()<System.nanoTime() || !guard.protection().causes().contains(cause)) continue;
            if(guard.protection().chunks().contains(corePos(player.chunkPosition()))) guard.seen().add(player.getUUID());
            if(guard.seen().contains(player.getUUID())) return true;
        }
        return false;
    }
    private final Map<UUID,net.minecraft.server.level.ServerBossEvent> bars=new HashMap<>();
    private volatile ApplyProgress lastProgress;
    public ApplyProgress lastProgress() { return lastProgress; }
    void progress(ApplyProgress progress) {
        lastProgress=progress;
        postToServer(()->{
            for(var player:server.getPlayerList().getPlayers()) {
                var bar=bars.computeIfAbsent(player.getUUID(),k->{
                    var b=Platform.bossbar();
                    b.addPlayer(player); return b;
                });
                String phase=Mini.plain(catalog,Texts.locale(this,player),Msg.of(MessageKeys.phase(progress.phase())));
                bar.setName(Texts.component(this,Texts.locale(this,player),Msg.of(MessageKeys.APPLY_PROGRESS,"phase",phase,"done",progress.completedSections(),"total",progress.totalSections())));
                bar.setProgress(progress.totalBatches()==0 ? 1 : (float)progress.completedBatches()/progress.totalBatches());
            }
        });
    }
    @FunctionalInterface public interface LiveAction<T> { T run(WorldOperations operations) throws IOException; }
    public <T> CompletableFuture<T> live(LiveAction<T> action) {
        if(!server.isSingleplayer()) return CompletableFuture.failedFuture(new IOException("世界切換指令目前只支援單人世界"));
        synchronized(this) {
            if(operation!=null) return CompletableFuture.failedFuture(new IOException("已有套用作業；可用 /wg cancel 取消"));
            operation=UUID.randomUUID(); cancel.set(false); lastProgress=null;
        }
        var future=runRepo(()->{
            UUID id=operation;
            try(var editLock=lockWorld()) {
                flushBlocking();
                if(cancel.get()) throw new IOException("作業已取消，尚未寫入世界");
                var layout=WorldLayout.discover(worldRoot());
                T result;
                try(var ops=WorldOperations.live(layout,new FabricOperations(this,layout))) { result=action.run(ops); }
                boolean partial=result instanceof WorldOperations.Result r && !r.success()
                    || result instanceof WorldOperations.MergeResult m && !m.success();
                if(result instanceof WorldOperations.Result r && r.state()!=WorldOperations.State.DRY_RUN) onServer(()->{
                    for(var player:server.getPlayerList().getPlayers()) clearPreview(player); return null;
                });
                if(result instanceof WorldOperations.MergeResult m && !m.state().equals("DRY_RUN")) onServer(()->{
                    for(var player:server.getPlayerList().getPlayers()) {
                        clearPreview(player); sendConflicts(player,m.merging());
                    }
                    return null;
                });
                var prior=lastProgress;
                progress(new ApplyProgress(id,partial ? ApplyProgress.Phase.PARTIAL : ApplyProgress.Phase.COMPLETE,
                    prior==null ? 0 : prior.completedBatches(),prior==null ? 0 : prior.totalBatches(),prior==null ? 0 : prior.completedSections(),prior==null ? 0 : prior.totalSections(),cancel.get()));
                return result;
            } finally {
                onServer(()->{
                    var group=protection.get(id);
                    if(group!=null) for(var entry:new ArrayList<>(group.entrySet())) {
                        var old=entry.getValue(); remember(old);
                        var policy=old.protection();
                        group.put(entry.getKey(),new Policy(old.dimension(),new PlayerProtection(policy.chunks(),policy.duration(),policy.causes(),id,false),System.nanoTime()+policy.duration().toNanos(),old.seen()));
                    }
                    for(var bar:bars.values()) bar.removeAllPlayers(); bars.clear(); return null;
                });
                operation=null;
            }
        });
        operationFuture=future;
        return future;
    }

    public CompletableFuture<MergeState> merging() {
        return runRepo(() -> MergeState.read(repositoryRoot().resolve("merge-state.bin")));
    }

    public CompletableFuture<List<byte[]>> conflictPreview(int id, MergeReport.Choice choice) {
        long preview = previewIds.getAndIncrement();
        return runRepo(() -> {
            var layout = WorldLayout.discover(worldRoot());
            try (var ops = WorldOperations.live(layout, new FabricOperations(this, layout))) {
                var state = ops.merging();
                if (state == null) throw new IOException("世界不在 MERGING");
                var region = state.regions().stream().filter(r -> r.id() == id).findFirst()
                    .orElseThrow(() -> new IOException("未知衝突區域：" + id));
                var cells = ops.regionPreview(id, choice).stream().map(b ->
                    new MergeProtocol.PreviewCell(b.position(), b.state().canonical(), b.blockEntity())).toList();
                return MergeProtocol.preview(preview, region.dimension(), id, choice, cells);
            }
        });
    }

    /** 呼叫於 server owner；只有宣告能力的玩家收到新的 channel。 */
    public void sendConflicts(ServerPlayer player, MergeState state) {
        if (!handshake.supports(player.getUUID(), MergeProtocol.CAPABILITY)) return;
        try {
            if (state == null) {
                sendPreview(player, MergeProtocol.regions(previewIds.getAndIncrement(), dimensionId((ServerLevel)player.level()), List.of()));
            } else for (var entry : state.dimensions().entrySet()) {
                var report = entry.getValue().report();
                sendPreview(player, MergeProtocol.regions(previewIds.getAndIncrement(), entry.getKey(), report.regions(), report.updateShapes()));
            }
        } catch (IOException ex) { LOG.warn("WORLDGIT 衝突清單編碼失敗", ex); }
    }

    // ---- 世界操作 ---------------------------------------------------------------------

    public CompletableFuture<WorldRepositories.Batch<DimensionRepository.CommitResult>> init(
            DimensionId selected, String template, WorldGitConfig.Track track, CommitMetadata.Identity author) {
        return runRepo(
                () -> {
                    var batches = new HashMap<DimensionId, DirtyChunkTracker.Batch>();
                    trackers.forEach((d, t) -> batches.put(d, t.capture()));
                    flushBlocking();
                    var result = ops().init(selected, template, track, author, commitText(Msg.of(MessageKeys.COMMIT_INIT)));
                    result.dimensions().forEach((dimension, outcome) -> {
                        // A skipped, previously initialized repo must retain its pending changes.
                        var captured = batches.get(dimension);
                        if (outcome.success() && outcome.value() != null && captured != null)
                            tracker(dimension).acknowledge(captured);
                    });
                    return result;
                });
    }

    public CompletableFuture<WorldRepositories.Batch<DimensionRepository.Status>> status(boolean full) {
        return runRepo(() -> ops().status(null, full));
    }

    public CompletableFuture<List<WorldOps.LogRow>> log(int limit) {
        return runRepo(() -> ops().log(null, limit));
    }

    public CompletableFuture<PreviewPlanner.Plan> plan(
            DimensionId dimension,
            List<String> revisions,
            boolean outlineOnly,
            boolean ghostCapable,
            int minY,
            int maxY) {
        long id = previewIds.getAndIncrement();
        return runRepo(
                () -> PreviewPlanner.plan(id, ops(), dimension, revisions, outlineOnly, ghostCapable, config.preview(), minY, maxY));
    }

    public CompletableFuture<PreviewPlanner.Plan> preview(DimensionId dimension,String revision,Set<ChunkPos> window,boolean ghosts,int minY,int maxY) {
        long id=previewIds.getAndIncrement();
        return runRepo(()->PreviewPlanner.revision(id,ops(),dimension,revision,window,ghosts,config.preview(),minY,maxY));
    }

    /** 手動或自動 commit。成功（所有維度沒有失敗）後才有條件地清除 dirty／歸屬。 */
    public CompletableFuture<WorldRepositories.Batch<DimensionRepository.CommitResult>> commit(
            String message, CommitMetadata.Identity requester, boolean auto, boolean flush) {
        return runRepo(
                () -> {
                    if (auto && MergeState.read(repositoryRoot().resolve("merge-state.bin")) != null)
                        return new WorldRepositories.Batch<DimensionRepository.CommitResult>(UUID.randomUUID(), new TreeMap<>());
                    if (flush) flushBlocking();
                    var ops = ops();
                    var attr = attribution.capture();
                    var batches = new HashMap<DimensionId, DirtyChunkTracker.Batch>();
                    trackers.forEach((d, t) -> batches.put(d, t.capture()));
                    var serverId = Identities.server(config);
                    var author = requester;
                    if (auto) {
                        var players = attr.players();
                        author =
                                players.size() == 1
                                        ? Identities.player(attr.playerNames().get(players.iterator().next()), players.iterator().next(), config.identity().playerEmailDomain())
                                        : serverId;
                    }
                    var context =
                            new WorldOps.Context(
                                    author,
                                    auto ? serverId : requester,
                                    auto,
                                    attr.contributions(ops.layout().dimensions().keySet(), config.identity().playerEmailDomain()));
                    var batch = ops.commit(null, message, context);
                    if (batch.success()) {
                        attribution.acknowledge(attr);
                        trackers.forEach((d, t) -> {
                            var b = batches.get(d);
                            if (b != null) t.acknowledge(b);
                        });
                    }
                    return batch;
                });
    }

    // ---- 握手與預覽封包 ---------------------------------------------------------------

    void playerJoined(ServerPlayer player) {
        handshake.join(player.getUUID(), server.getTickCount());
    }

    void playerLeft(ServerPlayer player) {
        handshake.leave(player.getUUID());
        outbound.remove(player.getUUID());
    }

    void onHello(ServerPlayer player, byte[] bytes) {
        try {
            if (!(Protocol.decode(bytes) instanceof Protocol.Hello hello)) throw new IOException("不是 hello");
            if (handshake.reply(player.getUUID(), hello))
                LOG.info("WORLDGIT HANDSHAKE_OK player={} capabilities={} palette={}", player.nameAndId().name(), hello.capabilities(), hello.palette().equals(DiffPalette.COLORBLIND) ? "colorblind" : "default");
            else LOG.warn("WORLDGIT HANDSHAKE_REJECTED player={} reason={}", player.nameAndId().name(), handshake.reason(player.getUUID()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("WORLDGIT HANDSHAKE_INVALID player={} error={}", player.nameAndId().name(), e.toString());
        }
    }

    /** 把預覽封包排入玩家的送出佇列（每 tick 最多 packets-per-tick 個，避免尖峰）。 */
    public void sendPreview(ServerPlayer player, List<byte[]> packets) {
        outbound.computeIfAbsent(player.getUUID(), k -> new ConcurrentLinkedDeque<>()).addAll(packets);
    }

    public void clearPreview(ServerPlayer player) {
        var queue = outbound.computeIfAbsent(player.getUUID(), k -> new ConcurrentLinkedDeque<>());
        queue.clear();
        try {
            queue.add(Protocol.encode(new Protocol.Clear(previewIds.getAndIncrement())));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String[] SERVER_CAPABILITIES = {"diff-preview", "status-outline", "revision-preview", MergeProtocol.CAPABILITY};

    void tick() {
        drainServerTasks();
        drainTickTasks(false);
        for(var group:protection.values()) for(var guard:group.values()) remember(guard);
        protection.values().forEach(group->group.values().removeIf(guard->guard.expires()<System.nanoTime()));
        protection.values().removeIf(Map::isEmpty);
        for (var send : handshake.tick(server.getTickCount())) {
            ServerPlayer player = server.getPlayerList().getPlayer(send.player());
            if (player == null || !ServerPlayNetworking.canSend(player, Net.HELLO.type())) continue;
            try {
                var hello = new Protocol.Hello(Protocol.VERSION, send.nonce(), List.of(SERVER_CAPABILITIES), palette());
                ServerPlayNetworking.send(player, Net.HELLO.of(Protocol.encode(hello)));
            } catch (IOException e) {
                LOG.warn("WORLDGIT hello 編碼失敗", e);
            }
        }
        for (var e : outbound.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) continue;
            for (int i = 0; i < config.preview().packetsPerTick(); i++) {
                byte[] packet = e.getValue().poll();
                if (packet == null) break;
                ServerPlayNetworking.send(player, Net.forPacket(packet).of(packet));
            }
        }
        if (server.getTickCount() % 100 == 0) autoCommitTick();
    }

    // ---- 自動 commit ------------------------------------------------------------------

    private void autoCommitTick() {
        long now = System.currentTimeMillis();
        if (operationActive() || autoRunning || !policy.intervalElapsed(now)) return;
        int changed = estimateChanged();
        boolean due = policy.intervalDue(now, changed);
        policy.ran(now);
        if (due) autoCommit(Msg.of(MessageKeys.COMMIT_AUTO_INTERVAL));
    }

    /** 候選 chunk 的粗估：dirty 標記 ∪ 已載入且未存檔的 chunk（伺服器執行緒）。 */
    private int estimateChanged() {
        var set = new HashSet<String>();
        trackers.forEach((d, t) -> t.chunks().forEach(c -> set.add(d + "/" + c)));
        for (ServerLevel level : server.getAllLevels()) {
            var dim = dimensionId(level);
            for (ChunkPos pos : loadedChunks(dim)) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
                if (chunk != null && chunk.isUnsaved()) set.add(dim + "/" + pos);
            }
        }
        return set.size();
    }

    /** 自動 commit；沒有 init 過的世界、已有操作進行中時安靜略過。 */
    public CompletableFuture<Void> autoCommit(Msg reasonMessage) {
        if (operationActive() || autoRunning) return CompletableFuture.completedFuture(null);
        autoRunning = true;
        String reason = commitText(reasonMessage);
        return commit(reason, Identities.server(config), true, false)
                .handle(
                        (batch, error) -> {
                            autoRunning = false;
                            if (error != null) {
                                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                                if (cause.getMessage() != null && cause.getMessage().contains("尚未 init")) LOG.debug("WORLDGIT 自動 commit 略過：尚未 init");
                                else LOG.warn("WORLDGIT AUTO_COMMIT_FAILED reason={} error={}", reason, cause.toString());
                            } else {
                                long changed = batch.dimensions().values().stream().filter(o -> o.success() && o.value() != null && o.value().changed()).count();
                                LOG.info("WORLDGIT AUTO_COMMIT reason={} changedDimensions={} success={}", reason, changed, batch.success());
                            }
                            return null;
                        });
    }

    public boolean worldIsInitialized() {
        try {
            return ops().initialized();
        } catch (IOException e) {
            return false;
        }
    }

    void playerLoggedOut(ServerPlayer player) {
        boolean touched = attribution.capture().touchedBy(player.getUUID());
        if (policy.onLogout(server.isSingleplayer(), touched))
            autoCommit(Msg.of(MessageKeys.COMMIT_AUTO_LOGOUT, "player", player.nameAndId().name()));
    }

    /** 伺服器關閉（或離開單人世界）時的最後一次自動 commit：阻塞伺服器執行緒，但持續處理排入的伺服器工作。 */
    void stopping() {
        if(operationActive()) {
            cancelApply();
            var pending=operationFuture;
            if(pending!=null) server.managedBlock(()->{drainServerTasks();drainTickTasks(true);return pending.isDone();});
        }
        if (policy.onStop() && !autoRunning && worldIsInitialized()) {
            var done = autoCommitStop();
            // 關閉流程中仍由伺服器執行緒處理 repo 執行緒排入的工作（chunk 存檔／複製）。
            server.managedBlock(
                    () -> {
                        drainServerTasks();
                        return done.isDone();
                    });
            try {
                done.get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.warn("WORLDGIT 關閉時的自動 commit 失敗", e);
            }
        }
    }

    private CompletableFuture<Void> autoCommitStop() {
        autoRunning = true;
        return commit(commitText(Msg.of(MessageKeys.COMMIT_AUTO_STOP)), Identities.server(config), true, true)
                .handle(
                        (batch, error) -> {
                            autoRunning = false;
                            if (error != null) LOG.warn("WORLDGIT AUTO_COMMIT_FAILED reason=stop error={}", error.toString());
                            else LOG.info("WORLDGIT AUTO_COMMIT reason=stop success={}", batch.success());
                            return null;
                        });
    }

    void shutdown() {
        closed = true;
        repo.shutdown();
        try {
            if (!repo.awaitTermination(30, TimeUnit.SECONDS)) repo.shutdownNow();
        } catch (InterruptedException e) {
            repo.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
