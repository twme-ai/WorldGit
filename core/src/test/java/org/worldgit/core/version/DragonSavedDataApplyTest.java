package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.model.*;

class DragonSavedDataApplyTest {
  @TempDir Path temp;
  private static final DimensionId END = new DimensionId("minecraft:the_end");
  private static final String KEY = "minecraft.the_end.saved." + Base64.getUrlEncoder()
      .withoutPadding().encodeToString("data/minecraft/ender_dragon_fight.dat".getBytes(StandardCharsets.UTF_8)) + ".nbt";
  private WorldLayout world(boolean endTerrain) throws Exception {
    Path world = temp.resolve("world");
    Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
    if (endTerrain) Files.createDirectories(world.resolve("dimensions/minecraft/the_end/region"));
    try (var out = new GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))) {
      out.write(Nbt.write(new Nbt.Compound().with("Data", new Nbt.Compound().with("DataVersion", 4903))));
    }
    return WorldLayout.discover(world);
  }
  private byte[] dragon(int killed) throws Exception {
    return Nbt.write(new Nbt.Compound().with("DataVersion", 4903)
        .with("data", new Nbt.Compound().with("dragon_killed", (byte) killed)));
  }
  private ApplyPlan plan(DimensionId id, byte[] value) {
    return new ApplyPlan(id, null, null, 4903, Scope.all(), List.of(), List.of(), Map.of(KEY, value), List.of());
  }
  @Test void appliesAndRecapturesDragonBeforeTheEndHasTerrainOrRepository() throws Exception {
    var layout = world(false);
    assertFalse(layout.dimensions().containsKey(END));
    byte[] value = dragon(1);
    new OfflineApplier(layout).apply(plan(DimensionId.OVERWORLD, value));
    Path end = layout.world().resolve("dimensions/minecraft/the_end");
    assertEquals(1, WorldLayout.readGzip(end.resolve("data/minecraft/ender_dragon_fight.dat"))
        .compound("data").integer("dragon_killed", 0));
    assertArrayEquals(value, layout.worldMetadata().get(KEY));
    assertFalse(Files.exists(end.resolve("region")));
    assertFalse(Files.exists(end.resolve(".worldgit")));
  }
  @Test void missingEndFallbackRejectsSymlinksBeforeWriting() throws Exception {
    var layout = world(false);
    Path outside = temp.resolve("outside");Files.createDirectory(outside);
    Files.createSymbolicLink(layout.world().resolve("dimensions/minecraft/the_end"), outside);
    assertThrows(IOException.class, () -> new OfflineApplier(layout).apply(plan(DimensionId.OVERWORLD, dragon(1))));
    assertFalse(Files.exists(outside.resolve("data")));
  }
  @Test void inconsistentWorldAndEndAliasesFailBeforeEitherWrite() throws Exception {
    var layout = world(true);
    try (var lock = WorldSessionLock.acquire(layout)) {
      assertThrows(IOException.class, () -> new OfflineApplier(layout).applyAll(
          List.of(plan(DimensionId.OVERWORLD, dragon(1)), plan(END, dragon(0))), lock));
    }
    assertFalse(Files.exists(layout.world().resolve("dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat")));
  }
  @Test void namespaceSymlinkCannotEscapeMissingEndFallback() throws Exception {
    var layout = world(false);
    Path namespace = layout.world().resolve("dimensions/minecraft"), outside = temp.resolve("outside");
    Files.move(namespace, outside);
    Files.createSymbolicLink(namespace, outside);
    assertThrows(IOException.class, () -> new OfflineApplier(layout).apply(plan(DimensionId.OVERWORLD, dragon(1))));
    assertFalse(Files.exists(outside.resolve("the_end/data")));
  }
  @Test void migrationCarriesUngeneratedDragonAndJournalsBothMoves() throws Exception {
    var layout = world(false);
    Path stage = temp.resolve("stage"), backup = temp.resolve("backup"),
        original = layout.world().resolve("dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat"),
        built = stage.resolve("dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat"),
        journalPath = layout.world().resolve(".worldgit/version-state.yml");
    Files.createDirectories(original.getParent());Files.write(original, dragon(0));
    Files.createDirectories(built.getParent());Files.write(built, dragon(1));
    var moves = new ArrayList<Path[]>();var journal = new LinkedHashMap<String, Object>();
    WorldVersionMigration.moveUngeneratedDragon(layout, stage, backup, 5023, moves, journal, journalPath);
    assertArrayEquals(dragon(1), Files.readAllBytes(original));
    assertArrayEquals(dragon(0), Files.readAllBytes(backup.resolve("ungenerated-end/ender_dragon_fight.dat")));
    assertEquals(2, ((List<?>) org.worldgit.core.service.OperationState.read(journalPath).get("moves")).size());
    assertFalse(Files.exists(original.getParent().getParent().getParent().resolve("region")));
    for (int i = moves.size() - 1; i >= 0; i--) Files.move(moves.get(i)[1], moves.get(i)[0]);
    assertArrayEquals(dragon(0), Files.readAllBytes(original));
    assertArrayEquals(dragon(1), Files.readAllBytes(built));
  }
  @Test void downgradeRemovesUngeneratedModernDragonAfterItsLevelConversion() throws Exception {
    var layout = world(false);
    Path original = layout.world().resolve("dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat");
    Files.createDirectories(original.getParent());Files.write(original, dragon(1));
    WorldVersionMigration.moveUngeneratedDragon(layout, temp.resolve("stage"), temp.resolve("backup"),
        3465, new ArrayList<>(), new LinkedHashMap<>(), layout.world().resolve(".worldgit/version-state.yml"));
    assertFalse(Files.exists(original));
    assertFalse(Files.exists(layout.world().resolve("DIM1")));
  }
}
