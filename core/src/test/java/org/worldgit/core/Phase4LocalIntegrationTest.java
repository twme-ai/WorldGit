package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.apply.Scope;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.remote.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.JGitStore;

/** 完整 baseline 僅複製；真 HTTP/遊戲伺服器驗收另見 scripts/verify-phase4.py。 */
class Phase4LocalIntegrationTest {
  @TempDir Path temp;

  @Test
  void completeBaselineCopiesCloneAndExportBothVersions() throws Exception {
    Path root = Path.of(System.getProperty("worldgit.projectRoot"));
    var author = new CommitMetadata.Identity("phase4", "phase4@localhost");
    var credentials = new Credentials(Map.of(), null, null);
    for (String version : List.of("1.21.11", "26.2")) {
      Path baseline = root.resolve(".work/worlds/" + version + "/baseline");
      Assumptions.assumeTrue(Files.isDirectory(baseline), "缺少本機 baseline");
      Path work = temp.resolve(version);
      Path source = work.resolve("source");
      TestWorlds.copy(baseline, source);
      var layout = WorldLayout.discover(source);
      assertTrue(
          new WorldRepositories(layout)
              .initAll("creative", WorldGitConfig.Track.ALL, author, WorldGitConfig.Entities.ALL)
              .success());
      Path hosted = work.resolve("remote");
      Files.createDirectories(hosted);
      for (var d : layout.dimensions().keySet())
        try (var ignored = new JGitStore(hosted.resolve(d.directoryName() + ".git"), true)) {}
      String url = hosted.toUri() + "{dimension}.git";
      try (var group =
          new RepositoryGroup(layout.repositoryRoot(), new WorldRepositories(layout).tracked())) {
        group.tag("baseline", null, "完整 baseline", author, false, false);
      }
      try (var remotes = new WorldRemotes(layout, credentials)) {
        remotes.configure("add", "origin", url, false);
        var transfer = remotes.push("origin", "main", true, false, false, author);
        assertTrue(transfer.success(), transfer.error());
      }
      var copied =
          WorldClone.cloneWorld(
              RemoteSpec.parse(url),
              work.resolve("clone"),
              "main",
              null,
              credentials,
              WorldAssembler.Budget.defaults());
      try (var ops = new WorldOperations(WorldLayout.discover(copied.world()))) {
        var verify = ops.verify("baseline", null, Scope.all(), true);
        assertTrue(verify.success(), verify.error());
      }
      try (var group =
              new RepositoryGroup(
                  layout.repositoryRoot(), new WorldRepositories(layout).tracked());
          var out = Files.newOutputStream(work.resolve("world.zip"))) {
        var result =
            new WorldAssembler(WorldAssembler.Budget.defaults()).zip(group, "baseline", out, work);
        assertTrue(result.bytes() > 0);
      }
      WorldAssembler.deleteTree(work);
    }
  }
}
