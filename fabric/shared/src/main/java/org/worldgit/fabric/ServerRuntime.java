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
    private final OperationUi ui = new OperationUi(this);
    private final TouchedEntities touchedEntities = new TouchedEntities(this);
    private volatile Map<DimensionId, String> cachedPalettes = Map.of();
    public OperationUi ui() { return ui; }
    private final IgnoreService ignoreService = new IgnoreService(this);
    private final UiServer uiServer = new UiServer(this, ignoreService);
    IgnoreService ignoreService() { return ignoreService; }
    UiServer uiServer() { return uiServer; }
    boolean isClosed() { return closed; }
    public TouchedEntities touched() { return touchedEntities; }
    /** 錯誤報告與 UI 用：已知秘密（環境 PAT、憑證檔）會在這裡遮罩。 */
    String mask(String text) { return remote.mask(text); }
    String modVersion() { return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("worldgit").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"); }
    String platformText() { return "Fabric Loader " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?") + (server.isDedicatedServer() ? " dedicated" : " integrated"); }
    String statusText(String locale, org.worldgit.core.operation.OperationResult.Status status) {
        return Mini.plain(catalog, locale, Msg.of(MessageKeys.status(status.name())));
    }
    String phaseText(String locale, String phase) {
        String key = phase == null ? null : MessageKeys.phaseKey(phase);
        return key == null ? String.valueOf(phase) : Mini.plain(catalog, locale, Msg.of(key));
    }
    private final Map<DimensionId, DirtyChunkTracker> trackers = new ConcurrentHashMap<>();
    private final Map<DimensionId, Set<ChunkPos>> loaded = new ConcurrentHashMap<>();
    private final HandshakeTracker handshake = new HandshakeTracker();
    private final Map<UUID, Deque<byte[]>> outbound = new ConcurrentHashMap<>();
    private final AtomicLong previewIds = new AtomicLong(1);
    private final AutoCommitPolicy policy;
    private volatile boolean closed;
    private volatile boolean autoRunning;
    /** 每個維度各自的 MERGING 狀態（獨立 repo）。 */
    private final Map<DimensionId, MergeState> mergeStates = new ConcurrentHashMap<>();
    private final RemoteCommands remote;
    public RemoteCommands remote() { return remote; }
    private final Map<UUID,Deque<byte[]>> commentsOutbound=new HashMap<>();
    void sendComments(ServerPlayer player,DimensionId dimension,List<org.worldgit.platform.remote.HubClient.Comment> rows) {
        if(!handshake.supports(player.getUUID(),org.worldgit.protocol.CommentsProtocol.CAPABILITY))return;
        try {
            var comments=rows.stream().map(c->{var p=c.pin();return new org.worldgit.protocol.CommentsProtocol.Comment(c.id(),
                org.worldgit.platform.remote.CommentText.plain(c.username(),32),org.worldgit.platform.remote.CommentText.plain(c.body(),240),
                p.x(),p.y(),p.z(),p.maxX(),p.maxY(),p.maxZ());}).toList();
            var queue=new ArrayDeque<>(org.worldgit.protocol.CommentsProtocol.encode(previewIds.getAndIncrement(),dimension,comments));
            commentsOutbound.put(player.getUUID(),queue);
        } catch(IOException | IllegalArgumentException e) { LOG.warn("WORLDGIT 留言封包編碼失敗（內容已遮罩）"); }
    }

    /** 啟動後讀取 durable 狀態；MERGING 不會因重啟變成一般 commit。 */
    void started() {
        runRepo(()->{
            refreshPalette();
            var layout=WorldLayout.discover(worldRoot());
            for(var entry:new WorldRepositories(layout).tracked().entrySet()) {
                var path=entry.getValue().resolve("apply-state.yml");
                var journal=org.worldgit.core.service.OperationState.read(path);
                if("APPLYING".equals(journal.get("state"))) {
                    var partial=new LinkedHashMap<String,Object>(journal);
                    partial.put("state","PARTIAL");
                    org.worldgit.core.service.OperationState.write(path,partial);
                    LOG.warn("WORLDGIT PARTIAL {}；合併用 /wg merge --abort，其他操作用 switch --force／reset --hard 恢復",entry.getKey());
                }
                var state=MergeState.read(entry.getValue().resolve("merge-state.bin"));
                if(state==null) mergeStates.remove(entry.getKey()); else mergeStates.put(entry.getKey(),state);
            }
            touchedEntities.refresh();
            try {remote.start();}catch(IOException e) {postToServer(()->Texts.failure(server.createCommandSourceStack(),this,Msg.of("fabric.remote.notification-failed")));}
            return null;
        }).exceptionally(e->{LOG.warn("WORLDGIT 讀取恢復狀態失敗",e);return null;});
    }

    void broadcast(Msg message) {
        postToServer(()->{
            Texts.send(server.createCommandSourceStack(),this,List.of(message));
            for(var player:server.getPlayerList().getPlayers())
                Texts.send(player.createCommandSourceStack(),this,List.of(message));
        });
    }

    ServerRuntime(MinecraftServer server, ServerConfig config, MessageCatalog catalog) {
        this.server = server;
        this.config = config;
        this.catalog = catalog;
        if(server.isSingleplayer() && !java.nio.file.Files.exists(worldRoot().getParent().resolve(".worldgit").resolve(worldRoot().getFileName()))) {
            try {java.nio.file.Files.createDirectories(worldRoot().resolve(".worldgit"));}catch(IOException e) {throw new java.io.UncheckedIOException(e);}
        }
        this.remote = new RemoteCommands(this,config.remote());
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
        return world.resolve(".worldgit");
    }

    public WorldOps ops() throws IOException {
        var layout = WorldLayout.discover(worldRoot());
        return new WorldOps(layout, dim -> {
            // init 前玩家已觸及的實體：此時 core 持有該維度 repo lock，寫入 sidecar 不會與 commit 競爭。
            touchedEntities.seed(dim.id(), layout.repository(dim.id()));
            return new FabricLiveWorld(this, layout, dim);
        });
    }

    public String paletteName() {
        var action = OperationUi.current();
        return cachedPalettes.getOrDefault(action == null || action.dimension == null ? DimensionId.OVERWORLD : action.dimension, "default");
    }

    void refreshPalette() throws IOException {
        var palettes = new TreeMap<DimensionId, String>();
        for (var entry : new WorldRepositories(WorldLayout.discover(worldRoot())).tracked().entrySet()) {
            try { palettes.put(entry.getKey(), WorldGitConfig.readLocal(entry.getValue().resolve("worldgit.yml")).palette()); }
            catch (IOException | RuntimeException error) { LOG.warn("WORLDGIT 色票設定無效 {}：{}", entry.getKey(), mask(String.valueOf(error.getMessage()))); }
        }
        cachedPalettes = Map.copyOf(palettes);
    }

    public DiffPalette palette() { return paletteName().equals("colorblind") ? DiffPalette.COLORBLIND : DiffPalette.DEFAULT; }

    private DiffPalette palette(DimensionId dimension) {
        return cachedPalettes.getOrDefault(dimension, "default").equals("colorblind") ? DiffPalette.COLORBLIND : DiffPalette.DEFAULT;
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

    /** 只排入指定 chunk 的 terrain／entity／POI；最後等待 IO queue（不掃其他 chunk）。 */
    void flushChunks(Map<DimensionId,Set<ChunkPos>> chunks) {
        var timings=org.worldgit.core.service.OperationTimings.current();
        onServer(()->{
            for(var entry:chunks.entrySet()) {
                var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(entry.getKey().value())));
                var entities=((org.worldgit.fabric.mixin.ServerLevelAccess)level).worldgit$entities();
                @SuppressWarnings("unchecked") var entityAccess=(org.worldgit.fabric.mixin.EntityManagerAccess<net.minecraft.world.entity.Entity>)(Object)entities;
                var map=level.getChunkSource().chunkMap;
                for(var pos:entry.getValue()) {
                    var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());
                    // entity／POI 可以仍在記憶體中，而 terrain 已卸載；三種 storage 分別保存。
                    if(!entityAccess.worldgit$store(((long)pos.x() & 0xffffffffL) | ((long)pos.z() << 32),e->{}))
                        throw new IOException("entity IO 尚未完成："+pos);
                    if(chunk!=null) {
                        // capture/apply 已等待 loaded entity chunks；未 ready 時不能假裝持久化完成。
                        ((org.worldgit.fabric.mixin.ChunkMapAccess)map).worldgit$save(chunk);
                    }
                    level.getPoiManager().flush(new net.minecraft.world.level.ChunkPos(pos.x(),pos.z()));
                }
                long ioStarted=System.nanoTime(); entityAccess.worldgit$storage().flush(false); map.synchronize(true).join();
                ((org.worldgit.fabric.mixin.SectionStorageAccess)level.getPoiManager()).worldgit$region().synchronize(true).join();
                if(timings!=null) timings.record("io-barrier",System.nanoTime()-ioStarted);
            }
            return null;
        });
    }

    <T> CompletableFuture<T> runRepo(Callable<T> task) {
        var future = new CompletableFuture<T>();
        if (closed) {
            future.completeExceptionally(new IOException("WorldGit 已停止"));
            return future;
        }
        var action = OperationUi.current();
        if (action != null) action.retain();
        try {
            repo.execute(
                    () -> OperationUi.within(action, () -> {
                        // 進度 context 與動作同一個 id；listener 只更新記憶體，UI 在伺服器 tick 呈現。
                        try (var progress = action == null ? null : new org.worldgit.core.operation.OperationProgress(action.id, action.operation, action::event)) {
                            if (action != null) {
                                action.progress = progress;
                                if (action.cancelled || action.parent != null && action.parent.cancelled) progress.cancel();
                                progress.publish(action.dimension, "start", 0, null, org.worldgit.core.operation.OperationProgress.Unit.OBJECT);
                            }
                            if (action != null) remote.refreshCredentialMask();
                            try {
                                org.worldgit.core.operation.OperationProgress.check();
                                future.complete(task.call());
                            } catch (Throwable t) {
                                future.completeExceptionally(t);
                            }
                        } finally {
                            if (action != null) {
                                action.progress = null;
                                action.release();
                            }
                        }
                        return null;
                    }));
        } catch (RejectedExecutionException e) {
            if (action != null) {
                action.failed(e);
                action.release();
            }
            future.completeExceptionally(e);
        }
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
    private int wasStepping;
    public boolean editsLocked() { return editLocks>0; }
    private volatile Map<DimensionId,Set<ChunkPos>> regionEditChunks;
    public boolean editsLocked(DimensionId dimension,ChunkPos chunk) {
        var scope=regionEditChunks;
        return editLocks>0 && (scope==null || scope.getOrDefault(dimension,Set.of()).contains(chunk));
    }
    public boolean editsLocked(DimensionId dimension) {
        var scope=regionEditChunks;
        return editLocks>0 && (scope==null || scope.containsKey(dimension));
    }
    AutoCloseable lockChunks(Map<DimensionId,Set<ChunkPos>> chunks) {
        var lock=lockWorld();
        regionEditChunks=chunks.values().stream().anyMatch(Set::isEmpty) ? null : Map.copyOf(chunks);
        return ()->{ regionEditChunks=null; lock.close(); };
    }
    public boolean internalMutation() { return server.isSameThread() && mutation; }
    @FunctionalInterface interface Mutation { void run() throws Exception; }
    void mutate(Mutation action) throws Exception {
        boolean before=mutation; mutation=true;
        try { action.run(); } finally { mutation=before; }
    }
    AutoCloseable lockWorld() {
        onServer(()->{
            if(editLocks++==0) {
                var ticks=server.tickRateManager();
                wasFrozen=ticks.isFrozen(); wasStepping=ticks.frozenTicksToRun();
                ticks.setFrozenTicksToRun(0); ticks.setFrozen(true);
                for(var player:server.getPlayerList().getPlayers()) player.closeContainer();
            }
            return null;
        });
        return ()->onServer(()->{ if(--editLocks==0) {
            var ticks=server.tickRateManager();ticks.setFrozen(wasFrozen);
            if(wasFrozen && wasStepping>0) ticks.stepGameIfPaused(wasStepping);
        } return null; });
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
        for(var group:protection.values()) for(var guard:group.values()) {
            if(guard.expires()<System.nanoTime() || !guard.protection().causes().contains(cause)) continue;
            if(guard.dimension().equals(dimension) && guard.protection().chunks().contains(corePos(player.chunkPosition()))) guard.seen().add(player.getUUID());
            if(guard.seen().contains(player.getUUID())) return true;
        }
        return false;
    }
    private volatile ApplyProgress lastProgress;
    public ApplyProgress lastProgress() { return lastProgress; }
    /** 線上套用的階段轉成共用的 OperationProgress（repo 執行緒）；UI 由伺服器 tick 的 OperationUi 呈現。 */
    void progress(ApplyProgress progress) {
        lastProgress=progress;
        var current=org.worldgit.core.operation.OperationProgress.current();
        String phase=switch(progress.phase()) { case LOCKING->"lock"; case APPLYING->"apply"; case LIGHTING->"lighting"; case SAVING->"saving"; default->null; };
        if(current!=null && phase!=null) {
            long total=Math.max(progress.totalSections(),progress.completedSections());
            current.publish(null,phase,progress.completedSections(),total>0 ? total : null,org.worldgit.core.operation.OperationProgress.Unit.SECTION);
        }
    }
    @FunctionalInterface public interface LiveAction<T> { T run(WorldOperations operations) throws IOException; }
    /** 單一維度的線上作業；沒有「預設維度」，呼叫端必須明確指定要操作的維度。 */
    public <T> CompletableFuture<T> live(DimensionId dimension,LiveAction<T> action) { return live(action,false,dimension); }
    public <T> CompletableFuture<T> region(DimensionId dimension,LiveAction<T> action) { return live(action,true,dimension); }
    private <T> CompletableFuture<T> live(LiveAction<T> action,boolean region,DimensionId dimension) {
        synchronized(this) {
            if(operation!=null) return CompletableFuture.failedFuture(new IOException("已有套用作業；可用 /wg cancel 取消"));
            operation=UUID.randomUUID(); cancel.set(false); lastProgress=null;
        }
        var future=runRepo(()->{
            UUID id=operation;
            try(var timing=org.worldgit.core.service.OperationTimings.start("fabric-live"); var editLock=region ? (AutoCloseable)()->{} : lockWorld()) {
                if(!region) try(var timingFlush=org.worldgit.core.service.OperationTimings.stage("flush")) { flushBlocking(); }
                if(cancel.get()) throw new IOException("作業已取消，尚未寫入世界");
                var layout=WorldLayout.discover(worldRoot());
                T result;
                try(var ops=WorldOperations.live(layout,new FabricOperations(this,layout),dimension)) { result=action.run(ops); }
                boolean partial=result instanceof WorldOperations.Result r && !r.success()
                    || result instanceof WorldOperations.MergeResult m && !m.success();
                if(result instanceof WorldOperations.Result r && r.state()!=WorldOperations.State.DRY_RUN) onServer(()->{
                    for(var player:server.getPlayerList().getPlayers()) clearPreview(player); return null;
                });
                if(result instanceof WorldOperations.MergeResult m && !m.state().equals("DRY_RUN")) try(var notification=org.worldgit.core.service.OperationTimings.stage("notification")) { onServer(()->{
                    if(m.merging()==null) mergeStates.remove(dimension); else mergeStates.put(dimension,m.merging());
                    for(var player:server.getPlayerList().getPlayers()) {
                        clearPreview(player); sendConflicts(player,dimension,m.merging());
                    }
                    return null;
                }); }
                try {touchedEntities.refresh();}catch(IOException e) {LOG.warn("WORLDGIT 重新讀取觸及實體集合失敗：{}",mask(String.valueOf(e.getMessage())));}
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
                    return null;
                });
                operation=null;
            }
        });
        operationFuture=future;
        return future;
    }

    /** 單一維度 repo 目前的 MERGING 狀態（沒有則 null）。 */
    public CompletableFuture<MergeState> merging(DimensionId dimension) {
        return runRepo(() -> readMerging(dimension));
    }

    private MergeState readMerging(DimensionId dimension) throws IOException {
        var path = new WorldRepositories(WorldLayout.discover(worldRoot())).tracked().get(dimension);
        return path == null ? null : MergeState.read(path.resolve("merge-state.bin"));
    }

    public CompletableFuture<List<byte[]>> conflictPreview(DimensionId dimension, int id, MergeReport.Choice choice) {
        long preview = previewIds.getAndIncrement();
        return runRepo(() -> {
            var layout = WorldLayout.discover(worldRoot());
            try (var ops = WorldOperations.live(layout, new FabricOperations(this, layout), dimension)) {
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

    /** 呼叫於 server owner；只有宣告能力的玩家收到新的 channel。state 為 null 表示該維度已沒有衝突。 */
    public void sendConflicts(ServerPlayer player, DimensionId dimension, MergeState state) {
        if (!handshake.supports(player.getUUID(), MergeProtocol.CAPABILITY) || !WgCommands.allowed(config.readPermissionLevel()).test(player.createCommandSourceStack())) return;
        try {
            if (state == null) {
                sendPreview(player, MergeProtocol.regions(previewIds.getAndIncrement(), dimension, List.of()));
            } else for (var entry : state.dimensions().entrySet()) {
                var report = entry.getValue().report();
                sendPreview(player, MergeProtocol.regions(previewIds.getAndIncrement(), entry.getKey(), report.regions(), report.updateShapes()));
            }
        } catch (IOException ex) { LOG.warn("WORLDGIT 衝突清單編碼失敗", ex); }
    }

    /** 玩家握手完成：每個仍在 MERGING 的維度各送一份；沒有任何衝突時清空玩家目前維度。 */
    void sendAllConflicts(ServerPlayer player, Map<DimensionId, MergeState> states) {
        if (states.isEmpty()) sendConflicts(player, dimensionId((ServerLevel) player.level()), null);
        else states.forEach((dimension, state) -> sendConflicts(player, dimension, state));
    }

    // ---- 世界操作 ---------------------------------------------------------------------

    /** 只 init 選定的維度（玩家所在維度、--dimension 或 --all 解析出的清單）；已 init 的維度略過。 */
    public CompletableFuture<WorldRepositories.Batch<DimensionRepository.CommitResult>> init(
            Collection<DimensionId> selected, String template, WorldGitConfig.Track track, CommitMetadata.Identity author) {
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

    /** 磁碟上存在、尚未 init 的維度（--all 的 init 目標），以及目前世界所有可 init 的維度。 */
    public CompletableFuture<List<WorldRepositories.Initializable>> initializable() {
        return runRepo(() -> new WorldRepositories(WorldLayout.discover(worldRoot())).initializable());
    }

    public CompletableFuture<SortedMap<DimensionId, java.nio.file.Path>> tracked() {
        return runRepo(() -> new WorldRepositories(WorldLayout.discover(worldRoot())).tracked());
    }

    public CompletableFuture<WorldRepositories.Batch<DimensionRepository.Status>> status(DimensionId dimension, boolean full) {
        return runRepo(() -> ops().status(dimension, full));
    }

    /** 單一維度的 commit 歷史（新到舊）。 */
    public CompletableFuture<List<org.worldgit.core.store.RefStore.Commit>> log(DimensionId dimension, int limit) {
        return runRepo(() -> {
            var path = new WorldRepositories(WorldLayout.discover(worldRoot())).tracked().get(dimension);
            if (path == null) throw new java.io.IOException("維度尚未 init：" + dimension);
            try (var repo = new DimensionRepository(path, dimension, false)) {
                return repo.log(limit);
            }
        });
    }

    /** 單一維度目前的 HEAD：分支 @ commit（repo 執行緒）。 */
    String headText(DimensionId dimension) throws IOException {
        var path = new WorldRepositories(WorldLayout.discover(worldRoot())).tracked().get(dimension);
        if (path == null) return null;
        try (var repo = new DimensionRepository(path, dimension, false)) {
            var head = repo.refs().headState();
            return (head.branch() == null ? "HEAD" : head.branch()) + "@" + head.commit();
        }
    }

    /** 單維度分支圖（core CommitGraph）；all 為真時含所有分支、tag、遠端追蹤 ref。 */
    public CompletableFuture<org.worldgit.core.graph.CommitGraph> graph(DimensionId dimension, int limit, boolean all) {
        return runRepo(() -> {
            var path = new WorldRepositories(WorldLayout.discover(worldRoot())).tracked().get(dimension);
            if (path == null) throw new java.io.IOException("維度尚未 init：" + dimension);
            try (var repo = new DimensionRepository(path, dimension, false)) {
                return org.worldgit.core.graph.CommitGraph.read(repo.refs(), Math.min(10000, Math.max(1, limit)), all);
            }
        });
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

    /**
     * 手動或自動 commit。selected 為 null 表示每個已 init 的維度各自提交（自動 commit 的便利批次）；
     * 否則只提交該維度。各維度成功後才有條件地清除該維度的 dirty／歸屬。
     */
    public CompletableFuture<WorldRepositories.Batch<DimensionRepository.CommitResult>> commit(
            DimensionId selected, String message, CommitMetadata.Identity requester, boolean auto, boolean flush) {
        return runRepo(
                () -> {
                    if (flush) flushBlocking();
                    var ops = ops();
                    if (!ops.initialized()) throw new WorldOps.NotInitializedException();
                    // 自動 commit 跳過仍在 MERGING 的維度（#60）；手動 commit 的 MERGING 由指令層走 continue。
                    var targets = new TreeSet<DimensionId>();
                    if (selected != null) targets.add(selected);
                    else for (var d : ops.tracked().keySet()) if (!auto || readMerging(d) == null) targets.add(d);
                    if (targets.isEmpty())
                        return new WorldRepositories.Batch<DimensionRepository.CommitResult>(UUID.randomUUID(), new TreeMap<>());
                    var attr = selected == null ? attribution.capture() : attribution.capture(selected);
                    var batches = new HashMap<DimensionId, DirtyChunkTracker.Batch>();
                    trackers.forEach((d, t) -> { if (targets.contains(d)) batches.put(d, t.capture()); });
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
                    var rows = new TreeMap<DimensionId, WorldRepositories.Outcome<DimensionRepository.CommitResult>>();
                    UUID snapshot = UUID.randomUUID();
                    for (var d : targets) {
                        var one = ops.commit(d, message, context);
                        rows.putAll(one.dimensions());
                        snapshot = one.snapshot();
                    }
                    var batch = new WorldRepositories.Batch<>(snapshot, rows);
                    // 每個成功的維度各自確認自己的 dirty 與歸屬；失敗的維度保留給下一次。
                    var acknowledged = new HashSet<DimensionId>();
                    batch.dimensions().forEach((d, outcome) -> {
                        if (!outcome.success()) return;
                        acknowledged.add(d);
                        var b = batches.get(d);
                        if (b != null) tracker(d).acknowledge(b);
                    });
                    if (!acknowledged.isEmpty()) attribution.acknowledge(attr, acknowledged);
                    return batch;
                });
    }

    // ---- 握手與預覽封包 ---------------------------------------------------------------

    void playerJoined(ServerPlayer player) {
        handshake.join(player.getUUID(), server.getTickCount());
    }

    void playerLeft(ServerPlayer player) {
        remote.quit(player);commentsOutbound.remove(player.getUUID());
        handshake.leave(player.getUUID());
        outbound.remove(player.getUUID());
        var mergeBar=mergeBars.remove(player.getUUID()); if(mergeBar!=null) mergeBar.removeAllPlayers();
        for(var group:protection.values()) for(var guard:group.values()) guard.seen().remove(player.getUUID());
    }

    void onHello(ServerPlayer player, byte[] bytes) {
        try {
            if (!(Protocol.decode(bytes) instanceof Protocol.Hello hello)) throw new IOException("不是 hello");
            if (handshake.reply(player.getUUID(), hello)) {
                LOG.info("WORLDGIT HANDSHAKE_OK player={} capabilities={} palette={}", player.nameAndId().name(), hello.capabilities(), hello.palette().equals(DiffPalette.COLORBLIND) ? "colorblind" : "default");
                // repo queue 排在進行中的操作之後，避免晚到的握手發布舊清單。
                runRepo(()->{
                    var states=new TreeMap<DimensionId,MergeState>();
                    for(var dimension:new WorldRepositories(WorldLayout.discover(worldRoot())).tracked().keySet()) {
                        var state=readMerging(dimension);
                        if(state!=null) states.put(dimension,state);
                    }
                    return states;
                }).thenAccept(states->postToServer(()->{
                    if(server.getPlayerList().getPlayer(player.getUUID())==player && handshake.ready(player.getUUID())) sendAllConflicts(player,states);
                }));
            }
            else LOG.warn("WORLDGIT HANDSHAKE_REJECTED player={} reason={}", player.nameAndId().name(), handshake.reason(player.getUUID()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("WORLDGIT HANDSHAKE_INVALID player={} error={}", player.nameAndId().name(), e.toString());
        }
    }

    /** 把預覽封包排入玩家的送出佇列（每 tick 最多 packets-per-tick 個，避免尖峰）。 */
    public void sendPreview(ServerPlayer player, List<byte[]> packets) {
        if(server.getPlayerList().getPlayer(player.getUUID())!=player) return;
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

    private static final String[] SERVER_CAPABILITIES = {"diff-preview", "status-outline", "revision-preview", MergeProtocol.CAPABILITY,MergeProtocol.SELECT_CAPABILITY,org.worldgit.protocol.CommentsProtocol.CAPABILITY,UiProtocol.CAPABILITY};

    private final Map<UUID,net.minecraft.server.level.ServerBossEvent> mergeBars=new HashMap<>();
    private void mergeProgress() {
        int remaining=0,total=0;
        for(var state:mergeStates.values()) {remaining+=state.remaining();total+=state.regions().size();}
        for(var player:server.getPlayerList().getPlayers()) {
            if(!server.isSingleplayer() && !mergeStates.isEmpty() && WgCommands.allowed(config.readPermissionLevel()).test(player.createCommandSourceStack())) {
                var bar=mergeBars.computeIfAbsent(player.getUUID(),id->{var b=Platform.bossbar(); b.setColor(net.minecraft.world.BossEvent.BossBarColor.PURPLE);b.addPlayer(player);return b;});
                bar.setName(Texts.component(this,Texts.locale(this,player),Msg.of(MessageKeys.MERGE_STATUS,"remaining",remaining,"total",total)));
                bar.setProgress(total==0 ? 1f : 1f-(float)remaining/total);
                bar.setVisible(!operationActive());
            } else { var bar=mergeBars.remove(player.getUUID()); if(bar!=null) bar.removeAllPlayers(); }
        }
    }

    void tick() {
        drainServerTasks();
        drainTickTasks(false);
        ui.tick();
        remote.tick();
        for(var e:commentsOutbound.entrySet()) {
            var player=server.getPlayerList().getPlayer(e.getKey());if(player==null)continue;
            for(int i=0;i<config.preview().packetsPerTick() && !e.getValue().isEmpty();i++)
                ServerPlayNetworking.send(player,Net.COMMENTS.of(e.getValue().poll()));
        }
        if(server.getTickCount()%10==0) mergeProgress();
        for(var group:protection.values()) for(var guard:group.values()) remember(guard);
        protection.values().forEach(group->group.values().removeIf(guard->guard.expires()<System.nanoTime()));
        protection.values().removeIf(Map::isEmpty);
        for (var send : handshake.tick(server.getTickCount())) {
            ServerPlayer player = server.getPlayerList().getPlayer(send.player());
            if (player == null || !ServerPlayNetworking.canSend(player, Net.HELLO.type())) continue;
            try {
                var hello = new Protocol.Hello(Protocol.VERSION, send.nonce(), List.of(SERVER_CAPABILITIES), palette(dimensionId((ServerLevel) player.level())));
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
        if (due) autoCommit(Msg.of(MessageKeys.COMMIT_AUTO_INTERVAL), "auto.commit");
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
        return autoCommit(reasonMessage, "auto.commit");
    }

    /** operation：auto.commit（定時）、logout.commit（登出）。每次都有終止結果，只通知有權限的玩家。 */
    public CompletableFuture<Void> autoCommit(Msg reasonMessage, String operation) {
        if (operationActive() || autoRunning) return CompletableFuture.completedFuture(null);
        autoRunning = true;
        String reason = commitText(reasonMessage);
        var action = ui.beginAutomatic(operation, null);
        return OperationUi.within(action, () -> commit(null, reason, Identities.server(config), true, false))
                .handle(
                        (batch, error) -> {
                            autoRunning = false;
                            if (error != null) {
                                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                                if (cause.getMessage() != null && cause.getMessage().contains("尚未 init")) {
                                    LOG.debug("WORLDGIT 自動 commit 略過：尚未 init");
                                    action.observe(new WorldRepositories.Batch<DimensionRepository.CommitResult>(UUID.randomUUID(), new TreeMap<>()));
                                } else {
                                    LOG.warn("WORLDGIT AUTO_COMMIT_FAILED reason={} error={}", reason, cause.toString());
                                    action.failed(cause);
                                }
                            } else {
                                long changed = batch.dimensions().values().stream().filter(o -> o.success() && o.value() != null && o.value().changed()).count();
                                LOG.info("WORLDGIT AUTO_COMMIT reason={} changedDimensions={} success={}", reason, changed, batch.success());
                                action.observe(batch);
                            }
                            action.release();
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
            autoCommit(Msg.of(MessageKeys.COMMIT_AUTO_LOGOUT, "player", player.nameAndId().name()), "logout.commit");
    }

    /** 伺服器關閉（或離開單人世界）時的最後一次自動 commit：阻塞伺服器執行緒，但持續處理排入的伺服器工作。 */
    void stopping() {
        remote.close();commentsOutbound.clear();
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
        var action = ui.beginAutomatic("shutdown.commit", null);
        OperationUi.within(action, () -> commit(null, commitText(Msg.of(MessageKeys.COMMIT_AUTO_STOP)), Identities.server(config), true, true))
                .handle(
                        (batch, error) -> {
                            autoRunning = false;
                            if (error != null) {
                                LOG.warn("WORLDGIT AUTO_COMMIT_FAILED reason=stop error={}", error.toString());
                                action.failed(error);
                            } else {
                                LOG.info("WORLDGIT AUTO_COMMIT reason=stop success={}", batch.success());
                                action.observe(batch);
                            }
                            action.release();
                            return null;
                        });
        // 終態訊息在伺服器執行緒送出後才算完成，停服流程才會等到它。
        return action.completion().thenApply(r -> null);
    }

    void shutdown() {
        remote.close();commentsOutbound.clear();
        ui.close();
        for(var bar:mergeBars.values()) bar.removeAllPlayers();mergeBars.clear();
        outbound.clear();protection.clear();
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
