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

class LocalIntegrationTest {
  @TempDir Path temp;

  @Test
  void completeBaselinesBothVersionsStableAndOneBlock() throws Exception {
    Path root = Path.of(System.getProperty("worldgit.projectRoot"));
    Assumptions.assumeTrue(
        Files.isDirectory(root.resolve(".work/worlds/1.21.11/baseline"))
            && Files.isDirectory(root.resolve(".work/worlds/26.2/baseline")),
        "本機沒有 baseline 世界");
    for (String version : List.of("1.21.11", "26.2")) {
      Path target = temp.resolve(version);
      TestWorlds.copy(root.resolve(".work/worlds/" + version + "/baseline"), target);
      var layout = WorldLayout.discover(target);
      var repos = new WorldRepositories(layout);
      var author = RepositoryFixtureTest.AUTHOR;
      long start = System.nanoTime();
      var init = repos.initAll("creative", WorldGitConfig.Track.ALL, author, WorldGitConfig.Entities.ALL);
      assertTrue(init.success(), init.toString());
      System.out.printf("BASELINE %s init=%.3fs%n", version, (System.nanoTime() - start) / 1e9);
      assertTrue(
          repos.status(null, 2, true).dimensions().values().stream()
              .allMatch(o -> o.success() && o.value().diff().empty()));
      assertTrue(
          repos.commit(null, "no edits", author, 2).dimensions().values().stream()
              .allMatch(o -> o.success() && !o.value().changed()));
      TestWorlds.oneBlock(layout, DimensionId.OVERWORLD, new ChunkPos(0, 0), 9, 0);
      var status = repos.status(null, 2, false);
      var diff = status.dimensions().get(DimensionId.OVERWORLD).value().diff();
      assertEquals(1, diff.sections().size());
      assertEquals(1, diff.counts().added() + diff.counts().removed() + diff.counts().modified());
      var commit = repos.commit(null, "one block", author, 2);
      assertEquals(
          1,
          commit.dimensions().values().stream()
              .filter(o -> o.success() && o.value().changed())
              .count());
    }
  }
}
