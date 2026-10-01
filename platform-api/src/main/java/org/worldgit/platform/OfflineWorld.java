package org.worldgit.platform;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.worldgit.core.anvil.*;
import org.worldgit.core.capture.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;

/** CLI 的 LiveWorld adapter；Phase 1 提供讀取，Phase 2 才開放 patch 套用。 */
public final class OfflineWorld implements LiveWorld {
  private final WorldLayout layout;
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
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("Phase 2 才提供 offline apply"));
  }

  @Override
  public AutoCloseable lockEdits(Collection<ChunkPos> chunks, String reason) throws IOException {
    return SessionGuard.acquire(layout);
  }

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
  }
}
