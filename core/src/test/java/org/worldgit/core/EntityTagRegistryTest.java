package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;

class EntityTagRegistryTest {
  @TempDir Path temp;

  private Path world(String version) throws Exception {
    Path target = temp.resolve(version);
    TestWorlds.copy(TestWorlds.fixture(version), target);
    return target.resolve("world");
  }

  private static void packs(Path world, String... enabled) throws IOException {
    var root = WorldLayout.readGzip(world.resolve("level.dat"));
    root.compound("Data")
        .put(
            "DataPacks",
            new Nbt.Compound()
                .with("Enabled", new Nbt.ListTag(8, new ArrayList<>(List.of(enabled))))
                .with("Disabled", new Nbt.ListTag(8, List.of("file/disabled"))));
    try (var out = new GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))) {
      out.write(Nbt.write(root));
    }
  }

  private static void tag(Path pack, String id, String json) throws IOException {
    String[] names = id.split(":", 2);
    Path file = pack.resolve("data/" + names[0] + "/tags/entity_type/" + names[1] + ".json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, json);
  }

  @Test
  void vanillaBothVersionsAndUnknownTagDiagnostic() throws Exception {
    for (String version : List.of("1.21.11", "26.2")) {
      Path world = world(version);
      packs(world, "vanilla");
      var registry = EntityTagRegistry.load(world, WorldLayout.discover(world).dataVersion());
      assertTrue(registry.inTag("minecraft:skeletons", "minecraft:skeleton"));
      assertTrue(registry.inTag("minecraft:undead", "minecraft:zombie"));
      assertFalse(registry.inTag("minecraft:arrows", "minecraft:zombie"));
      assertThrows(
          IllegalArgumentException.class, () -> registry.inTag("missing:tag", "minecraft:zombie"));
    }
  }

  @Test
  void enabledFolderZipPriorityReplaceAndLateReferences() throws Exception {
    Path world = world("26.2"), root = world.resolve("datapacks");
    tag(root.resolve("low"), "demo:builders", "{\"values\":[\"#minecraft:skeletons\"]}");
    tag(
        root.resolve("disabled"),
        "demo:builders",
        "{\"replace\":true,\"values\":[\"minecraft:creeper\"]}");
    try (var zip = new ZipOutputStream(Files.newOutputStream(root.resolve("high.zip")))) {
      zip.putNextEntry(new ZipEntry("data/minecraft/tags/entity_type/skeletons.json"));
      zip.write(
          "{\"replace\":true,\"values\":[\"demo:sculpture\"]}".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    packs(world, "vanilla", "file/low", "file/high.zip");
    var registry = EntityTagRegistry.load(world, 4903);
    assertTrue(registry.inTag("demo:builders", "demo:sculpture"));
    assertFalse(registry.inTag("demo:builders", "minecraft:skeleton"));
    assertFalse(registry.inTag("demo:builders", "minecraft:creeper"));
    assertTrue(
        registry.inTag("minecraft:undead", "demo:sculpture"),
        "vanilla references must see later pack overrides");
    tag(
        root.resolve("low"),
        "demo:builders",
        "{\"values\":[\"zombie\",{\"id\":\"#demo:missing\",\"required\":false}]}");
    var changed = EntityTagRegistry.load(world, 4903);
    assertTrue(changed.inTag("demo:builders", "minecraft:zombie"));
    assertNotEquals(registry.fingerprint(), changed.fingerprint());
  }

  @Test
  void cyclesRequiredReferencesAndSchemaErrors() throws Exception {
    Path world = world("26.2"), pack = world.resolve("datapacks/custom");
    packs(world, "vanilla", "file/custom");
    tag(pack, "demo:bad", "{\"values\":[\"#demo:bad\"]}");
    assertTrue(
        assertThrows(IOException.class, () -> EntityTagRegistry.load(world, 4903))
            .getMessage()
            .contains("循環"));
    tag(pack, "demo:bad", "{\"values\":[\"#demo:missing\"]}");
    assertTrue(
        assertThrows(IOException.class, () -> EntityTagRegistry.load(world, 4903))
            .getMessage()
            .contains("必要參照"));
    tag(pack, "demo:bad", "{\"replace\":\"yes\",\"values\":[]}");
    assertTrue(
        assertThrows(IOException.class, () -> EntityTagRegistry.load(world, 4903))
            .getMessage()
            .contains("布林"));
    packs(world, "vanilla", "file/../../outside");
    assertTrue(
        assertThrows(IOException.class, () -> EntityTagRegistry.load(world, 4903))
            .getMessage()
            .contains("越界"));
  }

  @Test
  void registryEditInvalidatesIndexWithoutChangingAnvil() throws Exception {
    Path world = world("26.2"), pack = world.resolve("datapacks/custom");
    tag(pack, "demo:omit", "{\"values\":[]}");
    packs(world, "vanilla", "file/custom");
    var layout = WorldLayout.discover(world);
    Path entityFile =
        layout.dimensions().get(DimensionId.OVERWORLD).entities().resolve("r.0.0.mca");
    Nbt.Compound raw;
    try (var region = new RegionFile(entityFile)) {
      raw =
          region.has(0)
              ? region.read(0)
              : new Nbt.Compound()
                  .with("DataVersion", 4903)
                  .with("Position", new int[] {0, 0})
                  .with("Entities", new Nbt.ListTag(10, List.of()));
    }
    var entities = new ArrayList<>(raw.list("Entities").values());
    UUID uuid = UUID.randomUUID();
    entities.add(TestWorlds.entity(uuid, 0.5, true));
    raw.put("Entities", new Nbt.ListTag(10, entities));
    RegionFile.update(entityFile, Map.of(0, raw), (int) (System.currentTimeMillis() / 1000));
    var repos = new WorldRepositories(layout);
    assertTrue(
        repos
            .init(
                DimensionId.OVERWORLD,
                "creative",
                WorldGitConfig.Track.ALL,
                RepositoryFixtureTest.AUTHOR)
            .success());
    Files.writeString(
        repos.tracked().get(DimensionId.OVERWORLD).resolve(".wgignore"), "entity #demo:omit\n");
    assertTrue(
        repos.commit(DimensionId.OVERWORLD, "rule", RepositoryFixtureTest.AUTHOR, 2).success());
    tag(pack, "demo:omit", "{\"values\":[\"minecraft:zombie\"]}");
    var result =
        repos.status(DimensionId.OVERWORLD, 2, false).dimensions().get(DimensionId.OVERWORLD);
    assertTrue(result.success(), result.error());
    assertTrue(result.value().candidates() > 0);
    assertFalse(result.value().ignoreChanged());
    assertTrue(
        result.value().diff().entities().stream()
            .anyMatch(e -> e.uuid().equals(uuid) && e.kind() == ChangeKind.REMOVED));
    assertTrue(
        repos
            .commit(DimensionId.OVERWORLD, "tag changed", RepositoryFixtureTest.AUTHOR, 2)
            .success());
    assertTrue(
        repos
            .status(DimensionId.OVERWORLD, 2, false)
            .dimensions()
            .get(DimensionId.OVERWORLD)
            .value()
            .diff()
            .empty());
  }
}
