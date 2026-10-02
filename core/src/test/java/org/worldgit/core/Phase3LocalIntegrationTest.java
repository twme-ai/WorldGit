package org.worldgit.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.WorldLayout;

class Phase3LocalIntegrationTest {
  @TempDir Path temp;

  @Test
  void completeBaselineCopiesBothVersions() throws Exception {
    Path root = Path.of(System.getProperty("worldgit.projectRoot"));
    for (String version : List.of("1.21.11", "26.2")) {
      Path baseline = root.resolve(".work/worlds/" + version + "/baseline");
      Assumptions.assumeTrue(Files.isDirectory(baseline), "缺少本機 baseline");
      Path copy = temp.resolve(version);
      TestWorlds.copy(baseline, copy);
      Phase3AcceptanceTool.roundtrip(WorldLayout.discover(copy));
    }
  }
}
