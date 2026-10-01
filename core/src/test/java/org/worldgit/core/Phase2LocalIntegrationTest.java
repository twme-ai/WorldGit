package org.worldgit.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.WorldLayout;

class Phase2LocalIntegrationTest {
  @TempDir Path temp;
  @Test void fullBaselinesRoundTripAndRanges() throws Exception {
    Path root=Path.of(System.getProperty("worldgit.projectRoot"));
    for(String version:List.of("1.21.11","26.2")) {
      Path baseline=root.resolve(".work/worlds/"+version+"/baseline");
      Assumptions.assumeTrue(Files.isDirectory(baseline),"本機 baseline 不存在");
      Path copy=temp.resolve(version);TestWorlds.copy(baseline,copy);
      Phase2AcceptanceTool.roundtrip(WorldLayout.discover(copy));
    }
  }
}
