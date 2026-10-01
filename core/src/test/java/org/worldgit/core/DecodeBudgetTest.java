package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.normalize.*;
import org.worldgit.core.model.*;
import org.worldgit.core.store.CommitTrailers;

class DecodeBudgetTest {
  @Test void repeatedCompressedObjectsShareBudgetBeforeAllocationAndScopeRestores() throws Exception {
    var raw = new Nbt.Compound().with("padding", new byte[1 << 20]);
    byte[] blob = SnapshotCodec.nbt(5, Nbt.write(raw));
    assertTrue(blob.length < 1024, "高壓縮比 fixture");
    try (var scope = new DecodeBudget(1_500_000, 10_000_000, 1000, 1000, 1000).open()) {
      assertTrue(SnapshotCodec.nbt(5, blob, true).length > 1_000_000);
      assertThrows(DecodeBudget.Exceeded.class, () -> SnapshotCodec.nbt(5, blob, true));
    }
    assertDoesNotThrow(() -> SnapshotCodec.nbt(5, blob, true));
  }

  @Test void uniformSectionDiffChargesExpandedObjects() throws Exception {
    var stone = new Section(Collections.nCopies(4096, new BlockState("minecraft:stone", new TreeMap<>())), new TreeMap<>());
    try (var scope = new DecodeBudget(4096, 10000, 1000, 100, 10000).open()) {
      var failure = assertThrows(java.io.UncheckedIOException.class,
          () -> org.worldgit.core.diff.DiffEngine.blocks(new ChunkPos(0, 0), 0, Section.air(), stone));
      assertInstanceOf(DecodeBudget.Exceeded.class, failure.getCause());
    }
    assertEquals(4096, org.worldgit.core.diff.DiffEngine.blocks(new ChunkPos(0, 0), 0, Section.air(), stone).size());
  }

  @Test void nbtNodeBudgetIsSharedAcrossIndependentReads() throws Exception {
    byte[] nbt = Nbt.write(new Nbt.Compound().with("list", new Nbt.ListTag(8, List.of("a", "b", "c"))));
    try (var scope = new DecodeBudget(10000, 10000, 7, 100, 1000).open()) {
      Nbt.read(nbt);
      assertThrows(DecodeBudget.Exceeded.class, () -> Nbt.read(nbt));
    }
  }

  @Test void contributionsCountChunksBeforeDeduplicationAcrossTrailers() throws Exception {
    var who = new CommitMetadata.Identity("a", "a@example.test");
    var meta = new CommitMetadata(who, who, "test", Instant.now(), 4903, DimensionId.OVERWORLD,
        CommitMetadata.Source.CLI, false, UUID.randomUUID(), List.of());
    byte[] raw = Nbt.write(new Nbt.Compound().with("name", "a").with("email", "a@example.test")
        .with("chunks", new Nbt.ListTag(8, Collections.nCopies(50_001, "0,0"))));
    String trailer = "WorldGit-Contribution: " + Base64.getUrlEncoder().withoutPadding().encodeToString(raw) + "\n";
    var error = assertThrows(java.io.IOException.class,
        () -> CommitTrailers.parse(who, who, Instant.now(), CommitTrailers.message(meta) + trailer.repeat(2)));
    assertTrue(error.getMessage().contains("chunk 總數"), error.getMessage());
  }

  @Test void trailersHavePerCommitLimitAndShareBudgetAcrossCommits() throws Exception {
    var who = new CommitMetadata.Identity("a", "a@example.test");
    var meta = new CommitMetadata(who, who, "test", Instant.now(), 4903, DimensionId.OVERWORLD,
        CommitMetadata.Source.CLI, false, UUID.randomUUID(), List.of());
    String message = CommitTrailers.message(meta);
    assertThrows(java.io.IOException.class, () -> CommitTrailers.parse(who, who, Instant.now(), message + "Co-authored-by: a\n".repeat(1024)));
    try (var scope = new DecodeBudget(10000, message.length() * 3L, 1000, 100, 1000).open()) {
      CommitTrailers.parse(who, who, Instant.now(), message);
      assertThrows(DecodeBudget.Exceeded.class, () -> CommitTrailers.parse(who, who, Instant.now(), message));
    }
    assertDoesNotThrow(() -> CommitTrailers.parse(who, who, Instant.now(), message));
  }
}
