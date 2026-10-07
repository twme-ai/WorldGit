package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.ChunkNormalizer;
import org.worldgit.platform.OfflineWorld;
import org.worldgit.protocol.Protocol;

/** 用提交的真實世界 fixture 走完 init → 改一格 → status/diff/preview → commit → log。 */
class WorldOpsTest {
  @TempDir Path temp;

  private static final CommitMetadata.Identity ALICE = new CommitMetadata.Identity("Alice", "alice@example.org");

  private static Path fixture() {
    return Path.of(System.getProperty("worldgit.projectRoot"), "core/src/test/resources/fixtures/1.21.11");
  }

  private static void copy(Path source, Path target) throws IOException {
    try (var paths = Files.walk(source)) {
      for (Path p : paths.toList()) {
        Path dest = target.resolve(source.relativize(p));
        if (Files.isDirectory(p)) Files.createDirectories(dest);
        else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  /** 模擬玩家放一格方塊：改寫 region 中某 section 的第 0 格。 */
  private static void setFirstBlock(Path regionFile, ChunkPos pos, int sectionY) throws IOException {
    Nbt.Compound raw;
    try (var r = new RegionFile(regionFile)) {
      raw = r.read(pos.regionIndex());
    }
    for (Object o : raw.list("sections").values()) {
      var section = (Nbt.Compound) o;
      if (section.integer("Y", 0) != sectionY) continue;
      var bs = section.compound("block_states");
      var palette = new ArrayList<>(bs.list("palette").values());
      int[] indices = ChunkNormalizer.unpack(bs, palette.size(), 4, 4096);
      String old = ((Nbt.Compound) palette.get(indices[0])).string("Name");
      palette.add(new Nbt.Compound().with("Name", old.equals("minecraft:gold_block") ? "minecraft:diamond_block" : "minecraft:gold_block"));
      indices[0] = palette.size() - 1;
      bs.put("palette", new Nbt.ListTag(10, palette));
      bs.put("data", ChunkNormalizer.pack(indices, Math.max(4, ChunkNormalizer.ceilLog2(palette.size()))));
      RegionFile.update(regionFile, Map.of(pos.regionIndex(), raw), (int) (System.currentTimeMillis() / 1000));
      return;
    }
    throw new IOException("找不到 section " + sectionY);
  }

  @Test
  void netherEntityToleranceIsIndependentOfTheOverworldSetting() throws Exception {
    Path server = temp.resolve("tolerance");
    copy(fixture(), server);
    var layout = WorldLayout.discover(server);
    var nether = new DimensionId("minecraft:the_nether");
    ChunkPos position;
    try (var terrain = new RegionFile(RegionFile.list(layout.dimensions().get(nether).region()).getFirst())) {
      int index = 0;
      while (!terrain.has(index)) index++;
      position = terrain.pos(index);
    }
    var id = UUID.fromString("00000000-0000-0000-0000-000000000123");
    double x = position.x() * 16 + 1, z = position.z() * 16 + 1;
    var entity = new Nbt.Compound().with("id", "minecraft:cow")
        .with("UUID", new int[] {0, 0, 0, 0x123})
        .with("Pos", new Nbt.ListTag(6, List.of(x, 65.0, z)))
        .with("Rotation", new Nbt.ListTag(5, List.of(0f, 0f)));
    Path entities = layout.dimensions().get(nether).entities().resolve(position.regionName() + ".mca");
    var region = new Nbt.Compound().with("DataVersion", layout.dataVersion()).with("Position", new int[] {position.x(), position.z()})
        .with("Entities", new Nbt.ListTag(10, List.of(entity)));
    RegionFile.update(entities, Map.of(position.regionIndex(), region), 1);
    var ops = new WorldOps(layout, dimension -> {
      try { org.worldgit.core.capture.PlayerTouchedEntities.touch(layout.repository(dimension.id()), entity); }
      catch (IOException error) { throw new java.io.UncheckedIOException(error); }
      return new OfflineWorld(layout, dimension, ignored -> {});
    });
    assertTrue(ops.init(Set.of(nether), "creative", WorldGitConfig.Track.ALL, ALICE, "init").success());
    WorldGitConfig.write(layout.repository(nether).resolve("worldgit.yml"), WorldGitConfig.write(new WorldGitConfig.Local("default", 0)));
    WorldGitConfig.write(layout.repository(DimensionId.OVERWORLD).resolve("worldgit.yml"), WorldGitConfig.write(new WorldGitConfig.Local("default", 2)));
    entity.put("Pos", new Nbt.ListTag(6, List.of(x + 1, 65.0, z)));
    RegionFile.update(entities, Map.of(position.regionIndex(), region), 2);
    var difference = ops.diff(nether, List.of(), DiffEngine.Detail.SUMMARY, null);
    assertEquals(1, difference.entities().size(), "地獄 tolerance=0 應保留一格移動；不能讀主世界的 2");
    assertEquals(id, difference.entities().getFirst().uuid());
    assertEquals(1, ops.status(nether, true).dimensions().get(nether).value().diff().entities().size());
    WorldGitConfig.write(layout.repository(nether).resolve("worldgit.yml"), WorldGitConfig.write(new WorldGitConfig.Local("default", 2)));
    assertTrue(ops.diff(nether, List.of(), DiffEngine.Detail.SUMMARY, null).entities().isEmpty(), "地獄自身改為 2 才忽略移動");
  }

  @Test
  void selectedInitKeepsOtherDimensionsUntouchedAndPreservesModMetadata() throws Exception {
    Path server = temp.resolve("selected");
    copy(fixture(), server);
    var layout = WorldLayout.discover(server);
    var ops = new WorldOps(layout, dimension -> new OfflineWorld(layout, dimension, ignored -> {}));
    var nether = new DimensionId("minecraft:the_nether");
    var first = ops.init(Set.of(nether), "creative", WorldGitConfig.Track.ALL, ALICE, "nether only");
    assertTrue(first.success());
    assertEquals(Set.of(nether), ops.tracked().keySet());
    assertFalse(Files.exists(layout.repository(DimensionId.OVERWORLD).resolve("HEAD")));
    try (var repository = new org.worldgit.core.service.DimensionRepository(ops.tracked().get(nether), nether, false)) {
      var commit = repository.log(1).getFirst();
      assertEquals(CommitMetadata.Source.MOD, commit.metadata().source());
      assertEquals("nether only", commit.metadata().message());
      assertEquals(nether, commit.metadata().dimension());
    }
    var appended = ops.init(Set.of(DimensionId.OVERWORLD, nether), "survival", WorldGitConfig.Track.ALL, ALICE, "append");
    assertTrue(appended.success());
    assertNull(appended.dimensions().get(nether).value());
    assertEquals(Set.of(nether, DimensionId.OVERWORLD), ops.tracked().keySet());
  }

  @Test
  void initEditStatusPreviewCommitLog() throws Exception {
    Path server = temp.resolve("server");
    copy(fixture(), server);
    var layout = WorldLayout.discover(server.resolve("world"));
    var ops = new WorldOps(layout, dim -> new OfflineWorld(layout, dim, s -> {}));
    assertFalse(ops.initialized());
    assertThrows(IOException.class, () -> ops.status(null, false));

    var init = ops.init(layout.dimensions().keySet(), "creative", WorldGitConfig.Track.ALL, ALICE, "init");
    assertTrue(init.success(), init.toString());
    assertEquals(3, init.dimensions().size());
    assertTrue(init.dimensions().values().stream().allMatch(o -> o.value().changed()));
    assertTrue(ops.initialized());
    assertTrue(org.worldgit.core.config.WorldGitConfig.readRepo(layout.repository(DimensionId.OVERWORLD).resolve("worldgit-repo.yml")).entities()
        == WorldGitConfig.Entities.PLAYER_TOUCHED, "Fabric 的 creative init 預設 entities: player-touched");
    assertThrows(IOException.class, () -> ops.init(null, "creative", WorldGitConfig.Track.ALL, ALICE, "init"), "init 必須明確指定維度");
    // 再 init 一次：每個維度都略過，不失敗
    var again = ops.init(layout.dimensions().keySet(), "creative", WorldGitConfig.Track.ALL, ALICE, "init");
    assertTrue(again.success());
    assertTrue(again.dimensions().values().stream().allMatch(o -> o.value() == null));

    // 沒有變動
    assertTrue(ops.status(null, false).dimensions().values().stream().allMatch(o -> o.value().diff().empty()));
    assertTrue(ops.commit(null, "nothing", WorldOps.Context.of(ALICE)).dimensions().values().stream().noneMatch(o -> o.value().changed()));

    // 玩家改一格
    var dimension = layout.dimensions().get(DimensionId.OVERWORLD);
    ChunkPos pos;
    int sectionY;
    try (var r = new RegionFile(RegionFile.list(dimension.region()).getFirst())) {
      int index = 0;
      while (!r.has(index)) index++;
      pos = r.pos(index);
      sectionY =
          ((Nbt.Compound)
                  r.read(index).list("sections").values().stream()
                      .filter(o -> !((Nbt.Compound) o).compound("block_states").isEmpty())
                      .findFirst()
                      .orElseThrow())
              .integer("Y", 0);
    }
    setFirstBlock(RegionFile.list(dimension.region()).getFirst().getParent().resolve(pos.regionName() + ".mca"), pos, sectionY);

    var status = ops.status(null, false);
    var overworld = status.dimensions().get(DimensionId.OVERWORLD).value().diff();
    assertEquals(1, overworld.sections().size(), "恰好一個 section");
    assertEquals(1, overworld.counts().added() + overworld.counts().removed() + overworld.counts().modified());
    var messages = new Messages().status(status, false);
    assertEquals(MessageKeys.STATUS_DIRTY, messages.get(0).key());
    var sectionLine = messages.stream().filter(l -> l.key().equals(MessageKeys.STATUS_SECTION)).findFirst().orElseThrow();
    assertEquals(String.valueOf(pos.x()), sectionLine.args().get("x"));
    assertEquals(String.valueOf(pos.z()), sectionLine.args().get("z"));
    assertEquals(String.valueOf(sectionY), sectionLine.args().get("y"));
    assertEquals("1", String.valueOf(Long.parseLong(sectionLine.args().get("added")) + Long.parseLong(sectionLine.args().get("removed")) + Long.parseLong(sectionLine.args().get("modified"))));

    // preview：逐格鬼影，恰好一格
    var plan = PreviewPlanner.plan(1, ops, DimensionId.OVERWORLD, List.of(), false, true, ServerConfig.defaults().preview(), -64, 319);
    assertEquals(PreviewPlanner.Mode.GHOST, plan.mode());
    assertEquals(1, plan.cells());
    var decoded = (Protocol.DiffPart) Protocol.decode(plan.packets().getFirst());
    assertEquals(1, decoded.cells().size());
    // revision preview 的方向為目前工作世界 → 指定目標，和 status 相反。
    var revision = PreviewPlanner.revision(11, ops, DimensionId.OVERWORLD, "HEAD", Set.of(pos), true,
        ServerConfig.defaults().preview(), -64, 319);
    var reverse = (Protocol.DiffPart) Protocol.decode(revision.packets().getFirst());
    var forwardCell = decoded.cells().getFirst();
    var reverseCell = reverse.cells().getFirst();
    assertEquals(forwardCell.before(), reverseCell.after());
    assertEquals(forwardCell.after(), reverseCell.before());
    assertEquals(forwardCell.x(), reverseCell.x());
    assertEquals(forwardCell.y(), reverseCell.y());
    assertEquals(forwardCell.z(), reverseCell.z());
    assertEquals(1, ops.status(null, true).dimensions().get(DimensionId.OVERWORLD).value().diff().sections().size(),
        "preview 不得改動世界或 HEAD");
    var clipped = PreviewPlanner.revision(12, ops, DimensionId.OVERWORLD, "HEAD", Set.of(), true,
        ServerConfig.defaults().preview(), -64, 319);
    assertEquals(PreviewPlanner.Mode.EMPTY, clipped.mode());
    assertInstanceOf(Protocol.Clear.class, Protocol.decode(clipped.packets().getFirst()));
    // 沒有 ghost 能力時改送包圍盒
    var outline = PreviewPlanner.plan(2, ops, DimensionId.OVERWORLD, List.of(), false, false, ServerConfig.defaults().preview(), -64, 319);
    assertEquals(PreviewPlanner.Mode.OUTLINE, outline.mode());
    assertEquals(1, outline.outlines());
    // 超過上限時也改送包圍盒
    var tiny = new ServerConfig.Preview(1, 4);
    var limited = PreviewPlanner.plan(3, ops, DimensionId.OVERWORLD, List.of(), false, true, tiny, -64, 319);
    assertEquals(PreviewPlanner.Mode.GHOST, limited.mode(), "剛好 1 格不超過上限 1");
    var none = PreviewPlanner.plan(4, ops, DimensionId.OVERWORLD, List.of(), true, true, tiny, -64, 319);
    assertEquals(PreviewPlanner.Mode.OUTLINE, none.mode(), "outlineOnly 強制包圍盒");

    // commit：作者歸屬帶入 MOD 來源，只有主世界產生 commit
    var bob = new CommitMetadata.Identity("Bob", UUID.randomUUID() + "@players.worldgit.local");
    var contribution = new CommitMetadata.Contribution(bob, UUID.randomUUID(), Set.of(pos), "place");
    var context = new WorldOps.Context(ALICE, new CommitMetadata.Identity("WorldGit Server", "worldgit@localhost"), true, Map.of(DimensionId.OVERWORLD, List.of(contribution)));
    var commit = ops.commit(null, "auto: 一格", context);
    assertTrue(commit.success());
    assertEquals(1, commit.dimensions().values().stream().filter(o -> o.value().changed()).count());
    var log = ops.log(null, 10);
    assertEquals(4, log.size(), "獨立初始 commit 不再依共同 snapshot 合併成一列");
    assertEquals("auto: 一格", log.get(0).message());
    assertTrue(log.get(0).auto());
    assertEquals("Alice", log.get(0).author());
    assertEquals(Set.of(DimensionId.OVERWORLD), log.get(0).commits().keySet());
    assertEquals("init", log.get(1).message());
    assertTrue(log.stream().allMatch(row -> row.commits().size() == 1));
    assertEquals(3, log.stream().filter(row -> row.message().equals("init")).count());
    assertTrue(new Messages().log(log).stream().anyMatch(l -> l.key().equals(MessageKeys.LOG_ROW_AUTO)));
    // 歷史 diff（兩個 revision）以及 --blocks 視窗
    try (var repo = new org.worldgit.core.service.DimensionRepository(ops.tracked().get(DimensionId.OVERWORLD), DimensionId.OVERWORLD, false)) {
      var commits = repo.log(5);
      assertEquals(2, commits.size());
      assertEquals(org.worldgit.core.model.CommitMetadata.Source.MOD, commits.get(0).metadata().source());
      assertEquals(1, commits.get(0).metadata().contributions().size());
    }
    var history = ops.diff(DimensionId.OVERWORLD, List.of("HEAD~1", "HEAD"), DiffEngine.Detail.BLOCKS, Set.of(pos));
    assertEquals(1, history.sections().size());
    assertEquals(1, history.sections().get(0).blocks().size());
    assertTrue(ops.status(null, false).dimensions().values().stream().allMatch(o -> o.value().diff().empty()));
    assertThrows(IOException.class, () -> ops.diff(new DimensionId("minecraft:nowhere"), List.of(), DiffEngine.Detail.SUMMARY, null));
  }
}
