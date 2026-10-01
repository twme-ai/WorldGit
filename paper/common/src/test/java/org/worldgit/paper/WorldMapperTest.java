package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldMapperTest {
  @Test
  void resolvesLegacyAnd26DimensionFolders(@TempDir Path dir) throws Exception {
    Path root = Files.createDirectory(dir.resolve("world"));
    Files.write(root.resolve("level.dat"), new byte[0]);
    assertEquals(root, WorldMapper.worldRoot(root));
    for (String dimension : new String[] {"minecraft/overworld", "minecraft/the_nether", "custom/nested/moon"}) {
      Path folder = Files.createDirectories(root.resolve("dimensions/" + dimension));
      assertEquals(root, WorldMapper.worldRoot(folder));
    }
    Path unsupported = Files.createDirectory(dir.resolve("other"));
    assertThrows(IOException.class, () -> WorldMapper.worldRoot(unsupported));
  }
}
