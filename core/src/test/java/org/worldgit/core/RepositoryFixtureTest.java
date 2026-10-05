package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

class RepositoryFixtureTest {
  @TempDir Path temp;
  static final CommitMetadata.Identity AUTHOR =
      new CommitMetadata.Identity("test", "test@example.test");

  @Test
  void bothLayoutsIncrementalIgnoreAndDimensionIsolation() throws Exception {
    for (String version : List.of("1.21.11", "26.2")) {
      Path dir = temp.resolve(version);
      TestWorlds.copy(TestWorlds.fixture(version), dir);
      var layout = WorldLayout.discover(dir);
      var world = new WorldRepositories(layout);
      var init = world.initAll("creative", WorldGitConfig.Track.ALL, AUTHOR, WorldGitConfig.Entities.ALL);
      assertTrue(init.success(), init.toString());
      assertEquals(3, init.dimensions().size());
      var snapshots = new HashSet<UUID>();
      for (var e : world.tracked().entrySet())
        try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false)) {
          assertTrue(snapshots.add(repo.log(1).getFirst().metadata().snapshot()), "每維度獨立 snapshot 身分");
          assertEquals(layout.repository(e.getKey()), e.getValue());
          assertTrue(
              repo.objects().readTree(repo.log(1).getFirst().tree()).keySet().stream()
                  .noneMatch(k -> k.equals("minecraft")));
        }
      assertTrue(
          world.status(null, 2, true).dimensions().values().stream()
              .allMatch(o -> o.success() && o.value().diff().empty()));
      assertTrue(
          world.commit(null, "unchanged", AUTHOR, 2).dimensions().values().stream()
              .allMatch(o -> o.success() && !o.value().changed()));
      WorldLayout.Dimension d = layout.dimensions().get(DimensionId.OVERWORLD);
      ChunkPos pos;
      int y;
      try (var r = new RegionFile(RegionFile.list(d.region()).getFirst())) {
        int index = 0;
        while (!r.has(index)) index++;
        pos = r.pos(index);
        y =
            ((Nbt.Compound)
                    r.read(index).list("sections").values().stream()
                        .filter(o -> !((Nbt.Compound) o).compound("block_states").isEmpty())
                        .findFirst()
                        .orElseThrow())
                .integer("Y", 0);
      }
      TestWorlds.oneBlock(layout, DimensionId.OVERWORLD, pos, y, 0);
      var status = world.status(null, 2, false);
      var diff = status.dimensions().get(DimensionId.OVERWORLD).value().diff();
      assertEquals(1, diff.sections().size());
      assertEquals(1, diff.counts().added() + diff.counts().removed() + diff.counts().modified());
      var cached = world.status(null, 2, false);
      assertEquals(0, cached.dimensions().get(DimensionId.OVERWORLD).value().candidates());
      var commit = world.commit(null, "one block", AUTHOR, 2);
      assertTrue(commit.success());
      assertTrue(commit.dimensions().get(DimensionId.OVERWORLD).value().changed());
      assertEquals(
          1, commit.dimensions().values().stream().filter(o -> o.value().changed()).count());
      Path ignore = world.tracked().get(DimensionId.OVERWORLD).resolve(".wgignore");
      Files.writeString(
          ignore,
          "area "
              + pos.x() * 16
              + " "
              + y * 16
              + " "
              + pos.z() * 16
              + " "
              + (pos.x() * 16 + 15)
              + " "
              + (y * 16 + 15)
              + " "
              + (pos.z() * 16 + 15)
              + "\n");
      var ignored =
          world
              .status(DimensionId.OVERWORLD, 2, false)
              .dimensions()
              .get(DimensionId.OVERWORLD)
              .value();
      assertTrue(ignored.ignoreChanged());
      assertTrue(ignored.diff().counts().removed() > 0);
      assertFalse(ignored.warnings().isEmpty());
      assertTrue(world.commit(DimensionId.OVERWORLD, "ignore", AUTHOR, 2).success());
      assertTrue(
          world
              .status(DimensionId.OVERWORLD, 2, false)
              .dimensions()
              .get(DimensionId.OVERWORLD)
              .value()
              .diff()
              .empty());
    }
  }

  @Test
  void sameSecondRewriteAndCorruptIndexRecover() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"), temp);
    var layout = WorldLayout.discover(temp);
    var repos = new WorldRepositories(layout);
    var dim = layout.dimensions().get(DimensionId.OVERWORLD);
    Path path = RegionFile.list(dim.region()).getFirst();
    int index = 0, y;
    ChunkPos chunk;
    Nbt.Compound raw;
    try (var r = new RegionFile(path)) {
      while (!r.has(index)) index++;
      raw = r.read(index);
      chunk = r.pos(index);
      y =
          ((Nbt.Compound)
                  raw.list("sections").values().stream()
                      .filter(o -> !((Nbt.Compound) o).compound("block_states").isEmpty())
                      .findFirst()
                      .orElseThrow())
              .integer("Y", 0);
    }
    RegionFile.update(path, Map.of(index, raw), (int) (System.currentTimeMillis() / 1000));
    assertTrue(
        repos.init(DimensionId.OVERWORLD, "creative", WorldGitConfig.Track.ALL, AUTHOR).success());
    var mtime = Files.getLastModifiedTime(path);
    TestWorlds.oneBlock(layout, DimensionId.OVERWORLD, chunk, y, 0);
    Files.setLastModifiedTime(path, mtime);
    var status =
        repos
            .status(DimensionId.OVERWORLD, 2, false)
            .dimensions()
            .get(DimensionId.OVERWORLD)
            .value();
    assertEquals(1, status.diff().sections().size());
    assertEquals(
        1,
        status.diff().counts().added()
            + status.diff().counts().removed()
            + status.diff().counts().modified());
    Files.write(
        repos.tracked().get(DimensionId.OVERWORLD).resolve("worldgit.index"), new byte[] {1, 2});
    var recovered =
        repos
            .status(DimensionId.OVERWORLD, 2, false)
            .dimensions()
            .get(DimensionId.OVERWORLD)
            .value();
    assertEquals(1, recovered.diff().sections().size());
    assertTrue(recovered.warnings().stream().anyMatch(w -> w.contains("快取無效")));
  }

  @Test
  void fullScanRemovesChunksWhenIndexIsMissing() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"), temp);
    var layout = WorldLayout.discover(temp);
    var world = new WorldRepositories(layout);
    assertTrue(world.initAll("creative", WorldGitConfig.Track.ALL, AUTHOR, WorldGitConfig.Entities.ALL).success());
    for (Path file : RegionFile.list(layout.dimensions().get(DimensionId.OVERWORLD).region()))
      Files.delete(file);
    Files.delete(world.tracked().get(DimensionId.OVERWORLD).resolve("worldgit.index"));
    var status =
        world
            .status(DimensionId.OVERWORLD, 2, false)
            .dimensions()
            .get(DimensionId.OVERWORLD)
            .value();
    assertTrue(status.diff().counts().removed() > 0);
    assertTrue(world.commit(DimensionId.OVERWORLD, "remove chunks", AUTHOR, 2).success());
    assertTrue(
        world
            .status(DimensionId.OVERWORLD, 2, false)
            .dimensions()
            .get(DimensionId.OVERWORLD)
            .value()
            .diff()
            .empty());
  }

  @Test
  void partialFailureAndModifiedOnlyConfig() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"), temp);
    var world = new WorldRepositories(WorldLayout.discover(temp));
    assertTrue(world.initAll("survival", WorldGitConfig.Track.MODIFIED_ONLY, AUTHOR, WorldGitConfig.Entities.ALL).success());
    Files.writeString(
        world.tracked().get(new DimensionId("minecraft:the_nether")).resolve(".wgignore"),
        "bad syntax");
    var result = world.commit(null, "partial", AUTHOR, 2);
    assertFalse(result.success());
    assertFalse(result.dimensions().get(new DimensionId("minecraft:the_nether")).success());
    assertTrue(result.dimensions().get(DimensionId.OVERWORLD).success());
    assertTrue(
        world
            .status(DimensionId.OVERWORLD, 2, false)
            .dimensions()
            .get(DimensionId.OVERWORLD)
            .value()
            .warnings()
            .stream()
            .anyMatch(w -> w.contains("缺少完整的曾編輯 chunk 集合") && w.contains("保守追蹤")));
    var failing =
        new WorldRepositories(
                WorldLayout.discover(temp),
                dimension -> {
                  throw new IllegalStateException();
                })
            .status(null, 2, false);
    assertFalse(failing.success());
    assertTrue(
        failing.dimensions().values().stream()
            .allMatch(o -> !o.success() && "IllegalStateException".equals(o.error())));
  }

  @Test
  void repositoryGateRejectsCommitAndWindowExpandsBlocksOnlyInsideWindow() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"), temp);
    var layout = WorldLayout.discover(temp);
    var world = new WorldRepositories(layout);
    assertTrue(world.init(DimensionId.OVERWORLD, "creative", WorldGitConfig.Track.ALL, AUTHOR).success());
    var dimension = layout.dimensions().get(DimensionId.OVERWORLD);
    ChunkPos pos = new ChunkPos(0, 0);
    int sectionY = 0;
    try (var region = new RegionFile(dimension.region().resolve(pos.regionName() + ".mca"))) {
      var raw = region.read(pos.regionIndex());
      sectionY = raw.list("sections").values().stream()
          .map(o -> ((Nbt.Compound) o).integer("Y", 0))
          .filter(y -> y == 0)
          .findFirst().orElseThrow();
    }
    TestWorlds.oneBlock(layout, DimensionId.OVERWORLD, pos, sectionY, 0);
    var repoPath = world.tracked().get(DimensionId.OVERWORLD);
    var metadata = new CommitMetadata(AUTHOR, AUTHOR, "gate", java.time.Instant.now(), layout.dataVersion(),
        DimensionId.OVERWORLD, CommitMetadata.Source.PLUGIN, false, UUID.randomUUID(), List.of());
    try (var repo = new DimensionRepository(repoPath, DimensionId.OVERWORLD, false);
        var source = new OfflineSnapshotSource(layout, dimension)) {
      var window = repo.status(source, world.manifest(), 2, false, org.worldgit.core.diff.DiffEngine.Detail.BLOCKS, Set.of(pos));
      assertEquals(1, window.diff().sections().size());
      assertEquals(1, window.diff().counts().added() + window.diff().counts().removed() + window.diff().counts().modified());
      var rejected = repo.commit(source, world.manifest(), metadata, 2, ignored -> false);
      assertFalse(rejected.changed());
      assertEquals(1, repo.log(10).size());
      var accepted = repo.commit(source, world.manifest(), metadata, 2, ignored -> true);
      assertTrue(accepted.changed());
      assertEquals(2, repo.log(10).size());
    }
  }
}
