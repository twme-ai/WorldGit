package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.normalize.MetadataNormalizer;
import org.worldgit.core.service.*;

class MetadataNormalizerTest {
  @TempDir Path temp;

  @Test
  void platformMarkersDoNotChangePortablePacksOrTheirPriority() {
    var packs =
        new Nbt.Compound()
            .with(
                "Enabled",
                new Nbt.ListTag(
                    (byte) 8,
                    List.of(
                        "vanilla",
                        "paper",
                        "file/bukkit",
                        "fabric-convention-tags-v2",
                        "mod:worldgen")));
    var normalized = MetadataNormalizer.portablePacks(packs);
    assertEquals(
        List.of("vanilla", "file/bukkit", "mod:worldgen"), normalized.list("Enabled").values());
    assertEquals(5, packs.list("Enabled").values().size());
  }

  @Test
  void fieldSelectorsAndNegationPreserveOtherMetadata() throws Exception {
    byte[] map =
        Nbt.write(new Nbt.Compound().with("data", new Nbt.Compound().with("scale", (byte) 2)));
    var metadata =
        Map.of(
            "level.nbt",
            Nbt.write(new Nbt.Compound().with("SpawnX", 42).with("DataVersion", 4903)),
            "map_0.dat.nbt",
            map,
            "custom.bin",
            new byte[] {1, 2});
    var filtered =
        MetadataNormalizer.normalize(
            metadata, IgnoreRules.parse("field worldgit:level SpawnX\nfield worldgit:map *\n"));
    assertFalse(Nbt.read(filtered.get("level.nbt")).containsKey("SpawnX"));
    assertEquals(4903, Nbt.read(filtered.get("level.nbt")).integer("DataVersion", 0));
    assertFalse(filtered.containsKey("map_0.dat.nbt"));
    assertArrayEquals(new byte[] {1, 2}, filtered.get("custom.bin"));
    var included =
        MetadataNormalizer.normalize(
            metadata, IgnoreRules.parse("field worldgit:map *\n!field worldgit:map data\n"));
    assertArrayEquals(map, included.get("map_0.dat.nbt"));
  }

  @Test
  void ignoreChangeRemovesAlreadyTrackedMapFromNextCommit() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"), temp);
    var layout = WorldLayout.discover(temp);
    Path file = layout.world().resolve("data/map_0.dat");
    Files.createDirectories(file.getParent());
    try (var out = new GZIPOutputStream(Files.newOutputStream(file))) {
      out.write(
          Nbt.write(new Nbt.Compound().with("data", new Nbt.Compound().with("scale", (byte) 2))));
    }
    var repos = new WorldRepositories(layout);
    assertTrue(
        repos
            .init(null, "creative", WorldGitConfig.Track.ALL, RepositoryFixtureTest.AUTHOR)
            .success());
    Files.writeString(
        repos.tracked().get(DimensionId.OVERWORLD).resolve(".wgignore"), "field worldgit:map *\n");
    var status =
        repos
            .status(DimensionId.OVERWORLD, 2, false)
            .dimensions()
            .get(DimensionId.OVERWORLD)
            .value();
    assertTrue(status.ignoreChanged());
    assertTrue(
        status.diff().metadata().stream()
            .anyMatch(
                m ->
                    m.path().equals("world-meta/map_0.dat.nbt") && m.kind() == ChangeKind.REMOVED));
    assertTrue(
        repos
            .commit(DimensionId.OVERWORLD, "ignore maps", RepositoryFixtureTest.AUTHOR, 2)
            .success());
    assertTrue(
        repos
            .status(DimensionId.OVERWORLD, 2, false)
            .dimensions()
            .get(DimensionId.OVERWORLD)
            .value()
            .diff()
            .empty());
    assertTrue(Files.exists(file), "ignoring must not delete live world metadata");
  }
}
