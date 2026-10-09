package org.worldgit.paper;

import org.worldgit.core.service.OperationTimings;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.OfflineSnapshotSource;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.capture.ScanIndex;
import org.worldgit.core.config.EntityTagRegistry;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.ChunkNormalizer;
import org.worldgit.platform.*;
import org.worldgit.core.apply.*;
import org.worldgit.platform.LiveWorld;

/**
 * 一個維度的線上快照來源，混合兩條路徑（見 docs/04 §2）：
 *
 * <ul>
 *   <li><b>已載入的 chunk</b>（dirty 旗標／事件／WorldEdit 標記、含實體者，以及全量 init 時的全部）：在擁有執行緒複製，背景編碼；
 *   <li><b>其餘 chunk</b>：沿用 core 的離線來源（region 時間戳＋payload 雜湊，同秒不確定窗）讀磁碟。已卸載的 chunk 在卸載時已存檔，
 *       磁碟就是真相；存檔尚在排隊時的短暫落後會在下一次 scan 因時間戳改變而被補上。
 * </ul>
 *
 * <p>index（worldgit.index）與離線來源共用同一格式，所以 CLI 能直接讀插件建立的 repo、反之亦然。
 */
final class PaperLiveWorld implements LiveWorld {
  private final WorldGitPlugin plugin;
  private final DimensionState state;
  private final World world;
  private final WorldLayout layout;
  private final OfflineSnapshotSource offline;
  private final boolean inline;
  private final LiveCopier.Stats stats = new LiveCopier.Stats();
  private LiveCopier copier;
  private EntityTagRegistry registry;
  private final Map<UUID, ChunkPos> claimed = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicInteger duplicateEntities = new java.util.concurrent.atomic.AtomicInteger();
  private volatile Set<ChunkPos> liveSet = Set.of();
  private volatile Set<ChunkPos> lastLoaded = Set.of();
  // core 對 player-touched 會用同一份快照測試忽略前的 UUID；只保留最後一份副本。
  private ChunkPos copiedPosition;
  private Optional<NmsBridge.RawChunk> copiedRaw;
  private Map<ChunkPos,NmsBridge.RawChunk> receipts=Map.of();
  void receipts(Map<ChunkPos,NmsBridge.RawChunk> receipts) {this.receipts=receipts;}

  PaperLiveWorld(WorldGitPlugin plugin, DimensionState state, World world, WorldLayout layout, boolean inline) {
    this.plugin = plugin;
    this.state = state;
    this.world = world;
    this.layout = layout;
    this.inline = inline;
    this.offline = new OfflineSnapshotSource(layout, layout.dimensions().get(state.id()));
  }

  LiveCopier.Stats stats() {
    return stats;
  }

  /** 此次 scan 以「線上複製」處理的 chunk 數（含 init 的全部已載入 chunk）。 */
  int liveCount() {
    return liveSet.size();
  }

  @Override
  public DimensionId dimension() {
    return state.id();
  }

  @Override
  public int dataVersion() {
    // 線上以伺服器實際版本為準；level.dat 在第一次存檔前可能還是舊值。
    return Bukkit.getUnsafe().getDataVersion();
  }

  @Override
  public String normalizationFingerprint() throws IOException {
    // 與離線來源（CLI）完全相同，否則兩端交替使用會讓 index 互相失效。
    return offline.normalizationFingerprint();
  }

  @Override
  public List<String> warnings() throws IOException {
    return offline.warnings();
  }

  @Override
  public Map<String, byte[]> worldMetadata() throws IOException {
    if (dataVersion() >= 4903) saveMetadata();
    var metadata = new TreeMap<>(offline.worldMetadata());
    if (!dimension().equals(DimensionId.OVERWORLD)) {
      if (dataVersion() >= 4903) metadata.put(dimension().directoryName()+".game_rules.dat.nbt",Nbt.write(new Nbt.Compound().with("DataVersion",dataVersion()).with("data",liveGameRules(world))));
      return metadata;
    }
    // gamerule 是受追蹤的設定，不能以「底噪」排除。level.dat / saved data 可能要到 shutdown 才寫回，
    // 因此在全域排程器取一份活資料，避免線上 commit 偷偷保存舊 gamerule。
    if (plugin.bridge().minecraftVersion().equals("1.21.11")) {
      var level = Nbt.read(metadata.get("level.nbt"));
      level.put("game_rules", liveGameRules(world));
      metadata.put("level.nbt", Nbt.write(level));
    } else {
      var data = new Nbt.Compound().with("DataVersion", dataVersion()).with("data", liveGameRules(world));
      byte[] encoded = Nbt.write(data);
      metadata.put(DimensionId.OVERWORLD.directoryName()+".game_rules.dat.nbt",encoded);
      metadata.put("game_rules.dat.nbt",encoded);
    }
    return metadata;
  }

  private void saveMetadata() throws IOException {
    var result = new CompletableFuture<Void>();
    Runnable save = () -> {
      try {
        plugin.bridge().saveMetadata(world).whenComplete((ignored, error) -> {
          if (error == null) result.complete(null);
          else result.completeExceptionally(error);
        });
      } catch (Throwable t) { result.completeExceptionally(t); }
    };
    if (inline) save.run();
    else plugin.platform().global(save);
    try { result.get(plugin.settings().commitTimeoutSeconds(), TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("saved-data 保存中斷", e); }
    catch (ExecutionException | TimeoutException e) { throw new IOException("saved-data 保存失敗", e); }
  }

  private Nbt.Compound liveGameRules(World target) throws IOException {
    var result = new CompletableFuture<Nbt.Compound>();
    Runnable copy = () -> {
      try { result.complete(plugin.bridge().copyGameRules(target)); }
      catch (Throwable t) { result.completeExceptionally(t); }
    };
    if (inline) copy.run();
    else plugin.platform().global(copy);
    try { return result.get(plugin.settings().commitTimeoutSeconds(), TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("gamerule 快照中斷", e); }
    catch (ExecutionException | TimeoutException e) { throw new IOException("gamerule 快照失敗", e); }
  }

  EntityTagRegistry registry() throws IOException {
    if (registry == null) registry = EntityTagRegistry.load(layout.world(), layout.dataVersion());
    return registry;
  }

  @Override
  public Scan scan(ScanIndex previous, boolean full) throws IOException {
    claimed.clear();
    copiedPosition=null; copiedRaw=null;
    Scan disk = offline.scan(previous, full);
    DimensionState.Census census = state.refresh(plugin.bridge(), world);
    capturedDirty=state.dirty().indexBatch();
    lastLoaded = census.loaded();
    var live = new TreeSet<ChunkPos>();
    live.addAll(capturedDirty.generations().keySet());
    live.addAll(census.entityChunks());
    if (full) live.addAll(census.loaded());
    var candidates = new TreeSet<>(disk.candidates());
    candidates.addAll(live);
    live.retainAll(census.loaded());
    for(var pos:disk.candidates()) if(census.loaded().contains(pos))live.add(pos);
    liveSet = Collections.unmodifiableSet(live);
    var present = new HashSet<>(disk.present());
    present.addAll(census.loaded());
    var settings = plugin.settings();
    copier = new LiveCopier(plugin.platform(), plugin.bridge(), world, settings.chunksPerTick(), settings.snapshotWindow(), inline, stats);
    copier.start(live);
    return new Scan(present, candidates, receiptStamps(disk.stamps()), disk.payloadsRead(), disk.scannedAt());
  }

  private org.worldgit.platform.DirtyChunkTracker.Batch capturedDirty;
  // Receipts precede resumed gameplay and later saves. Never certify those later disk bytes
  // with an earlier receipt, nor acknowledge dirty generations collected after that receipt.
  private Map<ChunkPos,ScanIndex.Stamp> receiptStamps(Map<ChunkPos,ScanIndex.Stamp> stamps) {
    if(receipts.isEmpty())return stamps;
    var trusted=new HashMap<>(stamps);receipts.keySet().forEach(trusted::remove);return trusted;
  }
  @Override public void indexSaved() {
    if(capturedDirty==null)return;
    var certified=new HashMap<>(capturedDirty.generations());receipts.keySet().forEach(certified::remove);
    state.dirty().indexed(new org.worldgit.platform.DirtyChunkTracker.Batch(certified));
  }

  void captureOnly(Set<ChunkPos> chunks) {
    claimed.clear();
    copiedPosition=null; copiedRaw=null;
    var settings=plugin.settings();
    copier=new LiveCopier(plugin.platform(),plugin.bridge(),world,settings.chunksPerTick(),settings.snapshotWindow(),inline,stats);
    var loaded=state.refresh(plugin.bridge(),world).loaded();
    copier.start(chunks.stream().filter(loaded::contains).toList());
  }

  CompletionStage<Void> flush(Collection<ChunkPos> chunks) {
    var timings=OperationTimings.current();
    var loaded=state.refresh(plugin.bridge(),world).loaded();
    var tasks=new ArrayList<CompletableFuture<Void>>();
    var scheduling=new ChunkLockQueue(plugin);
    for(var pos:chunks) if(loaded.contains(pos)) tasks.add(scheduling.submit(world,pos,()->plugin.bridge().saveChunk(world,pos.x(),pos.z())));
    return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).thenRunAsync(()->{
      long started=System.nanoTime(); plugin.bridge().flushIo(world);
      if(timings!=null) timings.record("io-barrier",System.nanoTime()-started);
    });
  }

  @Override
  public CompletionStage<Optional<ChunkSnapshot>> snapshot(ChunkPos pos, IgnoreRules rules) {
    return snapshotRaw(pos, rules).thenApply(o -> o.map(c -> dedupeEntities(pos, c)));
  }

  @Override public boolean entityCensusAvailable() { return true; }
  @Override public Set<UUID> unchangedEntityIds(Set<ChunkPos> captured) throws IOException {
    // 線上含實體的 chunk 及上一輪來源 chunk 全部是候選，剩下的原始 UUID 在磁碟普查。
    return offline.unchangedEntityIds(captured);
  }

  /**
   * 各 chunk 在不同 tick 複製，會移動的實體可能在複製中跨越 chunk 邊界而同時出現在兩個 chunk；
   * 同一次掃描內每個 UUID 只歸屬第一個宣稱它的 chunk（core 的快照模型要求 UUID 全域唯一）。
   */
  private ChunkSnapshot dedupeEntities(ChunkPos pos, ChunkSnapshot c) {
    if (c.entities().isEmpty()) return c;
    var kept = new ArrayList<EntitySnapshot>(c.entities().size());
    for (var e : c.entities()) {
      ChunkPos owner = claimed.putIfAbsent(e.uuid(), pos);
      if (owner == null || owner.equals(pos)) kept.add(e);
      else duplicateEntities.incrementAndGet();
    }
    if (kept.size() == c.entities().size()) return c;
    return new ChunkSnapshot(c.pos(), c.dataVersion(), c.sections(), c.biomes(), kept, c.ticks(), c.structures());
  }

  private CompletionStage<Optional<ChunkSnapshot>> snapshotRaw(ChunkPos pos, IgnoreRules rules) {
    try {
      if(receipts.containsKey(pos)) {
        var raw=receipts.get(pos);
        return CompletableFuture.completedFuture(Optional.of(new ChunkNormalizer(rules,registry()).normalize(pos,raw.terrain(),raw.entities())));
      }
      if (copier != null && (copier.has(pos) || pos.equals(copiedPosition))) {
        Optional<NmsBridge.RawChunk> raw = pos.equals(copiedPosition) ? copiedRaw : copier.take(pos, plugin.settings().commitTimeoutSeconds());
        copiedPosition=pos; copiedRaw=raw;
        if (raw != null && raw.isPresent()) {
          var chunk = raw.get();
          Nbt.Compound terrain = chunk.terrain();
          if (!ChunkNormalizer.full(terrain)) return CompletableFuture.completedFuture(Optional.empty());
          return CompletableFuture.completedFuture(
              Optional.of(new ChunkNormalizer(rules, registry()).normalize(pos, terrain, chunk.entities())));
        }
        // 複製時 chunk 已卸載：它已存檔，改讀磁碟。
      }
      return offline.snapshot(pos, rules);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public Set<ChunkPos> knownChunks() throws IOException {
    var all = new HashSet<>(offline.scan(ScanIndex.empty(), true).present());
    all.addAll(state.census().loaded());
    return all;
  }

  @Override
  public Set<ChunkPos> dirtyChunks() {
    return state.dirty().chunks();
  }

  @Override
  public Set<ChunkPos> entityChunks() {
    return state.census().entityChunks();
  }

  Set<ChunkPos> storedEntityChunks() throws IOException { return storedEntityChunks(null); }
  Set<ChunkPos> storedEntityChunks(Set<UUID> ids) throws IOException {
    var result=new TreeSet<ChunkPos>();
    var dir=layout.dimensions().get(dimension()).directory().resolve("entities");
    if(java.nio.file.Files.isDirectory(dir)) try(var files=java.nio.file.Files.list(dir)) {
      for(var path:files.filter(p->p.getFileName().toString().endsWith(".mca")).toList()) {
        var pair=path.getFileName().toString().split("\\.");
        int rx=Integer.parseInt(pair[1]),rz=Integer.parseInt(pair[2]);
        try(var region=new org.worldgit.core.anvil.RegionFile(path)) {
          for(int slot=0;slot<1024;slot++) if(region.has(slot)) {
            if(ids==null || region.read(slot).list("Entities").values().stream().anyMatch(n->containsId((Nbt.Compound)n,ids)))
              result.add(new ChunkPos(rx*32+(slot&31),rz*32+(slot>>5)));
          }
        }
      }
    }
    return result;
  }
  private static boolean containsId(Nbt.Compound n,Set<UUID> ids) {
    if(n.get("UUID") instanceof int[] a && a.length==4 && ids.contains(new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL)))) return true;
    return n.list("Passengers").values().stream().anyMatch(c->containsId((Nbt.Compound)c,ids));
  }
  @Override public CompletionStage<Void> apply(ChunkPatch patch) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("請使用全量 capture 的 ApplyPlan"));
  }
  @Override public CompletionStage<Void> apply(ApplyPlan plan,ApplyBudget budget) {
    if(!plan.dimension().equals(dimension())) return CompletableFuture.failedFuture(new IOException("套用計畫的維度不符"));
    if(!plan.worldMeta().isEmpty() || plan.chunks().values().stream().anyMatch(ApplyPlan.ChunkOp::delete))
      return CompletableFuture.failedFuture(new IOException("線上 world-meta／chunk 刪除需要離線還原"));
    var queue=new ApplyQueue(plugin);
    var tasks=new ArrayList<ApplyQueue.Task>();
    final IgnoreRules rules;
    try { rules=IgnoreRules.parse(plan.ignoreRules()); } catch(IOException e) { return CompletableFuture.failedFuture(e); }
    for(var op:plan.chunks().values()) tasks.add(new ApplyQueue.Task(world,op.pos(),(w,c)->queue.apply(w,op,rules,budget)));
    return queue.run(tasks,budget,false).thenCompose(v->{
      var ids=new HashSet<UUID>(); plan.entities().forEach(e->{ ids.add(e.uuid()); if(e.target()!=null) targetIds(e.target().data(),ids); });
      if(ids.isEmpty()) return CompletableFuture.completedFuture(null);
      var removals=new ArrayList<ApplyQueue.Task>();
      try { var candidates=storedEntityChunks(ids); candidates.addAll(entityChunks());
        plan.entities().forEach(e->{ if(e.hint()!=null) candidates.add(e.hint()); });
        for(var pos:candidates) removals.add(new ApplyQueue.Task(world,pos,(w,c)->{ plugin.bridge().removeEntities(w,pos.x(),pos.z(),ids); return CompletableFuture.completedFuture(null); }));
      } catch(IOException e) { return CompletableFuture.failedFuture(e); }
      return queue.run(removals,budget,false);
    }).thenCompose(v->{
      var puts=new ArrayList<ApplyQueue.Task>();
      for(var op:plan.entities()) if(op.target()!=null) puts.add(new ApplyQueue.Task(world,op.targetChunk(),(w,c)->{ plugin.edits().spawn(w,op.target()); return CompletableFuture.completedFuture(null); }));
      return queue.run(puts,budget,false);
    });
  }
  private static void targetIds(Nbt.Compound n,Set<UUID> ids) {
    if(n.get("UUID") instanceof int[] a && a.length==4) ids.add(new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL)));
    for(Object child:n.list("Passengers").values()) targetIds((Nbt.Compound)child,ids);
  }
  @Override public CompletionStage<Void> nextApplyTick(ApplyPlan plan) {
    var tasks=new ArrayList<CompletableFuture<Void>>();
    var chunks=new TreeSet<ChunkPos>(plan.chunks().keySet());
    plan.entities().forEach(e->{ if(e.hint()!=null) chunks.add(e.hint()); if(e.target()!=null) chunks.add(e.targetChunk()); });
    for(var pos:chunks) { var f=new CompletableFuture<Void>(); tasks.add(f);
      try { plugin.platform().regionDelayed(world,pos.x(),pos.z(),1,()->f.complete(null)); }
      catch(Throwable e) { f.completeExceptionally(e); }
    }
    return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
  }
  @Override public CompletionStage<Void> finishApply(Collection<ChunkPos> chunks) {
    var queue=new ApplyQueue(plugin);
    var tasks=new ArrayList<ApplyQueue.Task>();
    for(var pos:chunks) tasks.add(new ApplyQueue.Task(world,pos,(w,c)->
        plugin.bridge().finishChunk(w,pos.x(),pos.z()).thenCompose(v->{
          var done=new CompletableFuture<Void>();
          try { plugin.platform().region(w,pos.x(),pos.z(),()->{
            try { w.refreshChunk(pos.x(),pos.z()); done.complete(null); }
            catch(Throwable e) { done.completeExceptionally(e); }
          }); } catch(Throwable e) { done.completeExceptionally(e); }
          return done;
        })));
    var budget=Bukkit.getOnlinePlayers().isEmpty() ? ApplyBudget.DEFAULT : ApplyBudget.WITH_PLAYERS;
    return queue.run(tasks,budget,true).thenCompose(v->flush());
  }
  @Override public CompletionStage<Void> protectPlayers(PlayerProtection protection) {
    plugin.edits().protect(world,protection);
    var tasks=new ArrayList<CompletableFuture<Void>>();
    for(var player:Bukkit.getOnlinePlayers()) {
      var f=new CompletableFuture<Void>(); tasks.add(f);
      try { plugin.platform().entity(player,()->{ plugin.edits().observe(player); f.complete(null); },()->f.complete(null)); }
      catch(Throwable e) { f.completeExceptionally(e); }
    }
    return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
  }
  @Override public AutoCloseable lockEdits(Collection<ChunkPos> chunks,String reason) throws IOException {
    if(chunks.isEmpty()) return ()->{};
    var scope=new TreeSet<ChunkPos>();
    for(var pos:chunks) for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) scope.add(new ChunkPos(pos.x()+dx,pos.z()+dz));
    var edit=plugin.edits().lock(world,scope);
    var held=new ConcurrentHashMap<ChunkPos,AutoCloseable>();
    var jobs=new ArrayList<CompletableFuture<Void>>();
    plugin.edits().registerTicks(world,scope,held);
    try {
      plugin.bridge().entityBoundary(world,scope);
      var census=state.refresh(plugin.bridge(),world);var loaded=census.loaded();
      var scheduling=new ChunkLockQueue(plugin);
      if(!plugin.platform().folia())jobs.add(scheduling.submit(world,chunks.iterator().next(),()->plugin.quiesceAxiom(world)));
      for(var pos:census.entityChunks())jobs.add(scheduling.submit(world,pos,()->plugin.bridge().guardEntities(world,pos.x(),pos.z())));
      for(var pos:scope) if(loaded.contains(pos)) jobs.add(scheduling.submit(world,pos,()->plugin.edits().ensureChunkTicks(world,pos.x(),pos.z())));
      CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(plugin.settings().commitTimeoutSeconds(),TimeUnit.SECONDS);
      return ()->{
        plugin.edits().unregisterTicks(world);
        var restored=new ArrayList<CompletableFuture<Void>>();
        var schedulingRestore=new ChunkLockQueue(plugin);
        for(var entry:held.entrySet()) restored.add(schedulingRestore.submit(world,entry.getKey(),()->{entry.getValue().close();plugin.bridge().saveChunk(world,entry.getKey().x(),entry.getKey().z());}));
        try {CompletableFuture.allOf(restored.toArray(CompletableFuture[]::new)).get(plugin.settings().commitTimeoutSeconds(),TimeUnit.SECONDS);plugin.bridge().flushIo(world);}
        finally {try {restoreEntityBoundary();}finally {edit.close();}}
      };
    } catch(Exception error) {
      plugin.edits().unregisterTicks(world);
      // Every already-acquired owner lock must be restored on that owner, including failed acquisition.
      for(var entry:held.entrySet()) {var pos=entry.getKey();plugin.platform().region(world,pos.x(),pos.z(),()->{try {entry.getValue().close();}catch(Exception e){plugin.getLogger().severe(e.toString());}});}
      try {restoreEntityBoundary();}catch(Exception cleanup){error.addSuppressed(cleanup);}
      try {edit.close();}catch(Exception cleanup){error.addSuppressed(cleanup);}
      var cause=error;while(cause.getCause() instanceof Exception next)cause=next;
      throw new IOException("無法鎖定受影響 chunk："+cause.getMessage(),error);
    }
  }
  private CompletableFuture<Void> restoreBoundaryEntity(org.bukkit.entity.Entity entity,int attempt) {
    var done=new CompletableFuture<Void>();var at=entity.getLocation();
    plugin.platform().region(at.getWorld(),at.getBlockX()>>4,at.getBlockZ()>>4,()->{
      try {
        if(entity.isValid() && !Bukkit.isOwnedByCurrentRegion(entity)) {
          if(attempt>=128)throw new IllegalStateException("Entity owner kept moving during guard cleanup");
          restoreBoundaryEntity(entity,attempt+1).whenComplete((ignored,error)->{if(error==null)done.complete(null);else done.completeExceptionally(error);});return;
        }
        plugin.bridge().restoreEntity(entity);done.complete(null);
      }catch(Throwable error){done.completeExceptionally(error);}
    });return done;
  }
  private void restoreEntityBoundary() throws Exception {
    var jobs=new ArrayList<CompletableFuture<Void>>();
    for(var entity:plugin.bridge().clearEntityBoundary(world))jobs.add(restoreBoundaryEntity(entity,0));
    CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(plugin.settings().commitTimeoutSeconds(),TimeUnit.SECONDS);
  }
  @Override public CompletionStage<Void> flush() {
    return flush(state.refresh(plugin.bridge(),world).loaded());
  }

  @Override
  public void notifyPlayers(String message) {
    Bukkit.getConsoleSender().sendMessage(message);
    for(var player:Bukkit.getOnlinePlayers()) plugin.platform().entity(player,()->{
      if(player.getWorld().equals(world)) player.sendMessage(message);
    },()->{});
  }

  @Override
  public void close() throws IOException {
    if (copier != null) copier.cancel();
    offline.close();
  }
}
