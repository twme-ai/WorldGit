package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

class DiffTrailerTest {
  @TempDir Path temp;

  @Test
  void blocksBeAndFluidClassification() {
    var blocks = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
    blocks.set(0, new BlockState("minecraft:water"));
    blocks.set(1, new BlockState("minecraft:stone"));
    blocks.set(2, new BlockState("minecraft:chest"));
    var after = new ArrayList<>(blocks);
    after.set(0, new BlockState("minecraft:stone"));
    after.set(1, BlockState.AIR);
    var a = new Section(blocks, Map.of(2, Nbt.write(new Nbt.Compound().with("v", 1))));
    var b = new Section(after, Map.of(2, Nbt.write(new Nbt.Compound().with("v", 2))));
    var diff = DiffEngine.blocks(new ChunkPos(-1, 2), -4, a, b);
    assertEquals(3, diff.size());
    assertEquals(
        List.of(ChangeKind.ADDED, ChangeKind.REMOVED, ChangeKind.MODIFIED),
        diff.stream().map(c -> c.kind()).toList());
    assertEquals(-16, diff.getFirst().pos().x());
    assertEquals(-64, diff.getFirst().pos().y());
  }

  @Test
  void treeShortCircuitAndGlobalUuid() throws Exception {
    try (var store = new JGitStore(temp.resolve("repo"), true)) {
      var a = new TreeEditor(store, null);
      var e =
          EntityNormalizer.normalize(
              TestWorlds.entity(UUID.randomUUID(), 0, false), IgnoreRules.none());
      a.putBlob("r.0.0/c.0.0/entities.bin", SnapshotCodec.entities(List.of(e)));
      String at = a.write();
      store.flush();
      var b = new TreeEditor(store, at);
      b.remove("r.0.0/c.0.0/entities.bin");
      var data = e.data();
      data.put("Pos", new Nbt.ListTag(6, List.of(64.0, 70.0, 0.0)));
      var moved = EntityNormalizer.normalize(data, IgnoreRules.none());
      b.putBlob("r.0.0/c.4.0/entities.bin", SnapshotCodec.entities(List.of(moved)));
      String bt = b.write();
      store.flush();
      var engine = new DiffEngine(store);
      assertTrue(engine.compare(DimensionId.OVERWORLD, at, at, 2).empty());
      var diff = engine.compare(DimensionId.OVERWORLD, at, bt, 2);
      assertEquals(1, diff.entities().size());
      assertEquals(ChangeKind.MODIFIED, diff.entities().getFirst().kind());
      var scoped =
          engine.compare(
              DimensionId.OVERWORLD,
              at,
              bt,
              2,
              DiffEngine.Detail.BLOCKS,
              Set.of(new ChunkPos(4, 0)));
      assertEquals(1, scoped.entities().size());
      assertEquals(ChangeKind.MODIFIED, scoped.entities().getFirst().kind());
      assertEquals(new ChunkPos(0, 0), scoped.entities().getFirst().beforeChunk());
      assertTrue(
          engine
              .compare(
                  DimensionId.OVERWORLD,
                  at,
                  bt,
                  2,
                  DiffEngine.Detail.BLOCKS,
                  Set.of(new ChunkPos(99, 99)))
              .empty());
    }
  }

  @Test
  void trailerRoundTripAndCas() throws Exception {
    var author = new CommitMetadata.Identity("Alice", "alice@example.test");
    var coauthor = new CommitMetadata.Identity("王小明", "wang@example.test");
    var time = Instant.parse("2026-10-01T10:00:00.123456789Z");
    var m =
        new CommitMetadata(
            author,
            author,
            "標題\n\nWorldGit-Auto: fake\n內文",
            time,
            4903,
            DimensionId.OVERWORLD,
            CommitMetadata.Source.PLUGIN,
            true,
            UUID.randomUUID(),
            List.of(
                new CommitMetadata.Contribution(
                    coauthor, UUID.randomUUID(), Set.of(new ChunkPos(1, -2)), "WorldEdit")));
    assertEquals(m, CommitTrailers.parse(author, author, time, CommitTrailers.message(m)));
    var legacy=CommitTrailers.message(m).replace("WorldGit-Time: "+time+"\n","");
    var gitTime=time.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    assertEquals(gitTime,CommitTrailers.parse(author,author,gitTime,legacy).time());
    assertThrows(java.io.IOException.class,()->CommitTrailers.parse(author,author,gitTime.plusSeconds(1),CommitTrailers.message(m)));
    try (var store = new JGitStore(temp.resolve("repo"), true)) {
      String tree = store.writeTree(List.of());
      String id = store.commit(tree, null, m);
      assertEquals(m, store.readCommit(id).metadata());
      assertThrows(java.io.IOException.class, () -> store.commit(tree, null, m));
      assertEquals(
          "95000000",
          Files.readString(temp.resolve("repo/config"))
              .lines()
              .filter(s -> s.contains("packSizeLimit"))
              .findFirst()
              .orElseThrow()
              .split("=")[1]
              .trim());
    }
  }

  @Test
  void dimensionNamesAvoidCollision() {
    assertNotEquals(
        new DimensionId("mod:a/b").directoryName(), new DimensionId("mod:a.b").directoryName());
    assertThrows(IllegalArgumentException.class, () -> new DimensionId("mod:../x"));
    assertNotEquals(
        new DimensionId("a.b:c").directoryName(), new DimensionId("a:b.c").directoryName());
  }

  @Test
  void biomeIgnoreMarkersAreAddedRemovedRatherThanModified() throws Exception {
    try (var store = new JGitStore(temp.resolve("biomes"), true)) {
      var before = new ArrayList<>(Collections.nCopies(64, ""));
      var after = new ArrayList<>(before);
      after.set(0, "minecraft:plains");
      var a = new TreeEditor(store, null);
      a.putBlob("r.0.0/c.0.0/biomes.bin", SnapshotCodec.biomes(Map.of(0, before)));
      String at = a.write();
      store.flush();
      var b = new TreeEditor(store, at);
      b.putBlob("r.0.0/c.0.0/biomes.bin", SnapshotCodec.biomes(Map.of(0, after)));
      String bt = b.write();
      store.flush();
      var engine = new DiffEngine(store);
      var added = engine.compare(DimensionId.OVERWORLD, at, bt, 2).biomes();
      assertEquals(1, added.size());
      assertEquals(ChangeKind.ADDED, added.getFirst().kind());
      assertNull(added.getFirst().before());
      assertEquals(0, added.getFirst().sampleIndex());
      var removed =
          engine.compare(DimensionId.OVERWORLD, bt, at, 2, DiffEngine.Detail.SUMMARY).biomes();
      assertEquals(ChangeKind.REMOVED, removed.getFirst().kind());
      assertEquals(1, removed.getFirst().count());
      assertEquals(-1, removed.getFirst().sampleIndex());
    }
  }
}
