package org.worldgit.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.service.DimensionRepository;

class PlatformTest {
  @TempDir Path temp;

  @Test
  void dirtyAckDoesNotLoseLaterChanges() {
    var tracker = new DirtyChunkTracker();
    var pos = new ChunkPos(0, 0);
    tracker.mark(pos);
    var batch = tracker.capture();
    tracker.mark(pos);
    tracker.acknowledge(batch);
    assertTrue(tracker.chunks().contains(pos));
    tracker.acknowledge(tracker.capture());
    assertTrue(tracker.chunks().isEmpty());
  }

  @Test
  void liveDirtySourceCommitsWithoutAnvilAndPreservesContributors() throws Exception {
    var pos = new ChunkPos(0, 0);
    var dimension = new DimensionId("minecraft:the_nether");
    var tracker = new DirtyChunkTracker();
    var blocks = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
    blocks.set(0, new BlockState("minecraft:stone"));
    var captured = new ArrayList<ChunkPos>();
    LiveWorld live =
        new LiveWorld() {
          public DimensionId dimension() {
            return dimension;
          }

          public int dataVersion() {
            return 5000;
          }

          public Set<ChunkPos> knownChunks() {
            return Set.of(pos);
          }

          public Set<ChunkPos> dirtyChunks() {
            return tracker.chunks();
          }

          public Set<ChunkPos> entityChunks() {
            return Set.of();
          }

          public CompletionStage<Optional<ChunkSnapshot>> snapshot(
              ChunkPos chunk, IgnoreRules rules) {
            captured.add(chunk);
            return CompletableFuture.completedFuture(
                Optional.of(
                    new ChunkSnapshot(
                        chunk,
                        dataVersion(),
                        new TreeMap<>(Map.of(0, new Section(blocks, Map.of()))),
                        new TreeMap<>(),
                        List.of(),
                        null,
                        null)));
          }

          public CompletionStage<Void> apply(ChunkPatch patch) {
            return CompletableFuture.completedFuture(null);
          }

          public AutoCloseable lockEdits(Collection<ChunkPos> chunks, String reason) {
            return () -> {};
          }

          public CompletionStage<Void> flush() {
            return CompletableFuture.completedFuture(null);
          }

          public void notifyPlayers(String message) {}
        };
    var identity = new CommitMetadata.Identity("builder", "builder@example.test");
    var contribution =
        new CommitMetadata.Contribution(identity, UUID.randomUUID(), Set.of(pos), "block-place");
    var metadata =
        new CommitMetadata(
            identity,
            identity,
            "online",
            Instant.now(),
            live.dataVersion(),
            dimension,
            CommitMetadata.Source.PLUGIN,
            false,
            UUID.randomUUID(),
            List.of(contribution));
    try (var repo = new DimensionRepository(temp.resolve("repo"), dimension, true)) {
      repo.initialize("creative", WorldGitConfig.Track.ALL);
      assertTrue(repo.commit(live, Map.of(), metadata, 2).changed());
      captured.clear();
      assertTrue(repo.status(live, Map.of(), 2, false).diff().empty());
      assertTrue(captured.isEmpty(), "clean online status must not capture every chunk");
      blocks.set(0, new BlockState("minecraft:gold_block"));
      tracker.mark(pos);
      var batch = tracker.capture();
      var status = repo.status(live, Map.of(), 2, false);
      assertEquals(1, status.diff().counts().modified());
      assertTrue(tracker.chunks().contains(pos), "status must not acknowledge dirty state");
      var result = repo.commit(live, Map.of(), metadata, 2);
      assertTrue(result.changed());
      assertEquals(List.of(contribution), repo.log(1).getFirst().metadata().contributions());
      assertEquals(CommitMetadata.Source.PLUGIN, repo.log(1).getFirst().metadata().source());
      tracker.acknowledge(batch);
      assertTrue(tracker.chunks().isEmpty());
    }
  }

  @Test
  void sessionLockDetectsActiveAndAllowsStaleFile() throws Exception {
    Path path = temp.resolve("session.lock");
    Files.writeString(path, "stale");
    try (var ch = FileChannel.open(path, StandardOpenOption.WRITE);
        var lock = ch.lock()) {
      assertThrows(IOException.class, () -> SessionGuard.acquire(List.of(path)));
    }
    try (var guard = SessionGuard.acquire(List.of(path))) {
      assertThrows(IOException.class, () -> SessionGuard.acquire(List.of(path)));
    }
    try (var guard = SessionGuard.acquire(List.of(path))) {
      assertNotNull(guard);
    }
  }
}
