package org.worldgit.platform;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.worldgit.core.anvil.*;
import org.worldgit.core.capture.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.normalize.SnapshotCodec;

/** CLI 的 LiveWorld adapter；持有 session.lock 才可套用 patch；逐批與整個計畫皆可寫回。 */
public final class OfflineWorld implements LiveWorld {
  private final WorldLayout layout;
  private SessionGuard editGuard;
  private volatile Set<ChunkPos> lastCandidates = Set.of();
  private final OfflineSnapshotSource source;
  private final WorldLayout.Dimension dimension;
  private final Consumer<String> notify;

  public OfflineWorld(
      WorldLayout layout, WorldLayout.Dimension dimension, Consumer<String> notify) {
    this.layout = layout;
    this.source = new OfflineSnapshotSource(layout, dimension);
    this.dimension = dimension;
    this.notify = Objects.requireNonNull(notify);
  }

  @Override
  public DimensionId dimension() {
    return source.dimension();
  }

  @Override
  public int dataVersion() throws IOException {
    return source.dataVersion();
  }

  @Override
  public String normalizationFingerprint() throws IOException {
    return source.normalizationFingerprint();
  }

  @Override
  public List<String> warnings() throws IOException {
    return source.warnings();
  }

  @Override
  public Scan scan(ScanIndex index, boolean full) throws IOException {
    Scan scan = source.scan(index, full);
    lastCandidates = scan.candidates();
    return scan;
  }

  @Override
  public CompletionStage<Optional<ChunkSnapshot>> snapshot(ChunkPos pos, IgnoreRules rules) {
    return source.snapshot(pos, rules);
  }

  @Override
  public Map<String, byte[]> worldMetadata() throws IOException {
    return source.worldMetadata();
  }

  @Override
  public Set<ChunkPos> knownChunks() throws IOException {
    return source.scan(ScanIndex.empty(), true).present();
  }

  @Override
  public Set<ChunkPos> dirtyChunks() {
    return lastCandidates;
  }

  @Override
  public Set<ChunkPos> entityChunks() {
    return Set.of();
  }

  @Override
  public CompletionStage<Void> apply(ChunkPatch patch) {
    try(AutoCloseable ownedLock=editGuard==null ? lockEdits(List.of(patch.chunk()),"WorldGit legacy apply") : null) {
      if (patch.expectedChunkTree() != null)
        throw new IOException("legacy ChunkPatch 的 expectedChunkTree 不可略過；請使用由全量 capture 產生的 ApplyPlan");
      source.scan(ScanIndex.empty(), true);
      var current = source.snapshot(patch.chunk(), IgnoreRules.none()).toCompletableFuture().join();
      var sections = new TreeMap<Integer, ApplyPlan.SectionOp>();
      var biomes = new TreeMap<Integer, ApplyPlan.BiomeOp>();
      var entities = new ArrayList<ApplyPlan.EntityOp>();
      if (current.isPresent()) {
        for (int y : current.get().sections().keySet()) sections.put(y, new ApplyPlan.SectionOp(y, null, null));
        for (var e : current.get().entities()) entities.add(new ApplyPlan.EntityOp(e.uuid(), patch.chunk(), null));
      }
      if (patch.replacement() != null) {
        if (!patch.chunk().equals(patch.replacement().pos())) throw new IOException("replacement chunk 不符");
        DataVersions.requireSame(patch.replacement().dataVersion(), dataVersion());
        for (var e : patch.replacement().sections().entrySet())
          sections.put(e.getKey(), new ApplyPlan.SectionOp(e.getKey(), SnapshotCodec.section(e.getValue()), null));
        for (var e : patch.replacement().biomes().entrySet()) biomes.put(e.getKey(), new ApplyPlan.BiomeOp(e.getKey(), e.getValue()));
        var targetIds = new HashSet<UUID>();
        for (var e : patch.replacement().entities()) targetIds.add(e.uuid());
        entities.removeIf(e -> targetIds.contains(e.uuid()));
        for (var e : patch.replacement().entities()) entities.add(new ApplyPlan.EntityOp(e.uuid(), patch.chunk(), e));
      }
      var replacement = patch.replacement();
      var op = new ApplyPlan.ChunkOp(patch.chunk(), patch.delete(), sections, biomes, true,
          replacement == null ? null : replacement.ticks(), true, replacement == null ? null : replacement.structures());
      return apply(new ApplyPlan(dimension(), null, null, dataVersion(), Scope.chunkSet(List.of(patch.chunk())),
          List.of(op), entities, Map.of(), List.of()), ApplyBudget.DEFAULT);
    } catch (Exception ex) { return CompletableFuture.failedFuture(ex); }
  }

  @Override
  public CompletionStage<Void> apply(ApplyPlan plan, ApplyBudget budget) {
    try {
      if (!plan.dimension().equals(dimension())) throw new IOException("apply 維度不符");
      source.close();
      var applier = new OfflineApplier(layout);
      applier.validateMetadata(plan);
      if (editGuard == null) applier.apply(plan); else applier.apply(plan, editGuard.coreLock());
      return CompletableFuture.completedFuture(null);
    } catch (Exception ex) { return CompletableFuture.failedFuture(ex); }
  }

  @Override
  public AutoCloseable lockEdits(Collection<ChunkPos> chunks, String reason) throws IOException {
    if (editGuard != null) throw new IOException("世界已被此 adapter 鎖定");
    editGuard = SessionGuard.acquire(layout);
    return () -> { var guard = editGuard; editGuard = null; if (guard != null) guard.close(); };
  }
  @Override public CompletionStage<Void> nextApplyTick(ApplyPlan batch) { return CompletableFuture.completedFuture(null); }
  @Override public CompletionStage<Void> finishApply(Collection<ChunkPos> chunks) { return CompletableFuture.completedFuture(null); }
  @Override public CompletionStage<Void> protectPlayers(PlayerProtection protection) { return CompletableFuture.completedFuture(null); }

  @Override
  public CompletionStage<Void> flush() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void notifyPlayers(String message) {
    notify.accept(message);
  }

  @Override
  public void close() throws IOException {
    source.close();
    if (editGuard != null) { editGuard.close(); editGuard = null; }
  }
}
