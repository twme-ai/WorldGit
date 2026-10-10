package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.capture.SnapshotSource;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;

/** 決策 #163：預檢（鎖外）與上鎖之間世界變動時，以鎖後的權威擷取重算，並保護未提交變動。 */
class PreflightRaceTest {
  @TempDir Path temp;

  /** guardApply 被呼叫時（預檢之後、套用之前）執行 window，模擬世界在窗口內變動。 */
  private static final class Access implements WorldOperations.LiveAccess {
    final WorldLayout layout;
    final WorldSessionLock host;
    final Runnable window;
    final List<Map<DimensionId, Set<ChunkPos>>> extras = new ArrayList<>();
    Map<DimensionId, Set<ChunkPos>> locked;
    int guards;

    Access(WorldLayout layout, WorldSessionLock host, Runnable window) {
      this.layout = layout;
      this.host = host;
      this.window = window;
    }

    public SnapshotSource source(WorldLayout.Dimension dimension) {
      return new OfflineSnapshotSource(layout, dimension);
    }

    public void validate(ApplyPlan plan) {}

    public AutoCloseable guardApply(
        Collection<ApplyPlan> plans, Map<DimensionId, Set<ChunkPos>> extra, boolean allowAtomic) {
      if (guards++ == 0) window.run();
      extras.add(extra);
      locked = new TreeMap<>();
      for (var plan : plans) {
        var chunks = new TreeSet<>(LiveApplyVerification.affected(plan));
        chunks.addAll(extra.getOrDefault(plan.dimension(), Set.of()));
        locked.put(plan.dimension(), chunks);
      }
      return () -> {};
    }

    public Map<DimensionId, Set<ChunkPos>> guarded() {
      return locked;
    }

    public void applyAll(Collection<ApplyPlan> plans) throws IOException {
      new OfflineApplier(layout).applyAll(plans, host);
    }
  }

  private WorldLayout fixture() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"), temp.resolve("server"));
    var layout = WorldLayout.discover(temp.resolve("server"));
    var worlds = new WorldRepositories(layout);
    var author = new CommitMetadata.Identity("test", "test@example.org");
    assertTrue(worlds.initAll("creative", WorldGitConfig.Track.ALL, author, WorldGitConfig.Entities.ALL).success());
    try (var ops = new WorldOperations(layout)) { ops.createBranch("A", null); }
    TestWorlds.oneBlock(layout, DimensionId.OVERWORLD, new ChunkPos(0, 0), 9, 0);
    assertTrue(worlds.commit(null, "B", author, 2).success());
    return layout;
  }

  private static Runnable change(WorldLayout layout, ChunkPos pos, int index) {
    return () -> {
      try { TestWorlds.oneBlock(layout, DimensionId.OVERWORLD, pos, 9, index); }
      catch (IOException e) { throw new UncheckedIOException(e); }
    };
  }

  @Test void restoreReplansFromLockedCaptureInsteadOfFailing() throws Exception {
    var layout = fixture();
    try (var host = WorldSessionLock.acquire(layout)) {
      var access = new Access(layout, host, change(layout, new ChunkPos(0, 0), 5));
      try (var ops = WorldOperations.live(layout, access)) {
        var result = ops.restore("A", null, Scope.all(), false, false);
        assertTrue(result.success(), String.valueOf(result.error()));
        assertTrue(ops.verify("A", null, Scope.all(), true).success(), "鎖後狀態重算後必須零差異");
      }
    }
  }

  @Test void changesOutsideTheFootprintAreNotWindowChanges() throws Exception {
    var layout = fixture();
    try (var host = WorldSessionLock.acquire(layout)) {
      var access = new Access(layout, host, change(layout, new ChunkPos(1, 1), 3));
      try (var ops = WorldOperations.live(layout, access)) {
        var result = ops.restore("A", null, Scope.all(), false, false);
        assertTrue(result.success(), String.valueOf(result.error()));
        assertEquals(1, access.guards, "足跡外的變動維持為工作區變動，不觸發重算");
      }
    }
  }

  @Test void switchRefusesPlayerBlockAppearingInTheWindowAndListsIt() throws Exception {
    var layout = fixture();
    try (var host = WorldSessionLock.acquire(layout)) {
      var access = new Access(layout, host, change(layout, new ChunkPos(0, 0), 7));
      try (var ops = WorldOperations.live(layout, access)) {
        var error = assertThrows(PreflightChangedException.class, () -> ops.switchTo("A", false, false, false, false));
        assertEquals(new ChunkPos(0, 0), error.chunks().getFirst().chunk());
        assertTrue(error.chunks().getFirst().blocks() > 0);
        assertTrue(error.getMessage().contains("[0,0]") && error.getMessage().contains("--force"), error.getMessage());
        assertTrue(error.attempts() >= 2, "拒絕前必須先自動重新預檢");
        assertTrue(ops.journal().isEmpty(), "拒絕時尚未寫入");
      }
      // 玩家的方塊仍在：下一次預檢就是 dirty。
      try (var ops = WorldOperations.live(layout, new Access(layout, host, () -> {}))) {
        assertTrue(assertThrows(IOException.class, () -> ops.switchTo("A", false, false, false, false)).getMessage().contains("未提交"));
      }
    }
  }

  @Test void switchStashSavesWindowChangesUnderLockAndForceOverwrites() throws Exception {
    var layout = fixture();
    try (var host = WorldSessionLock.acquire(layout)) {
      var access = new Access(layout, host, change(layout, new ChunkPos(0, 0), 7));
      try (var ops = WorldOperations.live(layout, access)) {
        var result = ops.switchTo("A", true, false, false, false);
        assertTrue(result.success(), String.valueOf(result.error()));
        assertEquals(1, ops.stashes().size(), "窗口內的新變動必須進 stash，不可被靜默蓋掉");
        assertTrue(ops.verify("A", null, Scope.all(), true).success());
      }
    }
  }

  @Test void quietWorldTakesTheFastPathWithoutReplanning() throws Exception {
    var layout = fixture();
    try (var host = WorldSessionLock.acquire(layout)) {
      var access = new Access(layout, host, () -> {});
      try (var ops = WorldOperations.live(layout, access)) {
        assertTrue(ops.switchTo("A", false, false, false, false).success());
        assertEquals(1, access.guards);
      }
    }
  }

  @Test void summaryListsAtMostLimitChunksAndKinds() {
    var all = new ArrayList<PreflightChangedException.ChunkChange>();
    for (int i = 0; i < 12; i++) all.add(new PreflightChangedException.ChunkChange(new ChunkPos(i, -i), i + 1, 0, new TreeMap<>(Map.of("minecraft:cow", 2))));
    var error = new PreflightChangedException(all.subList(0, PreflightChangedException.LIMIT), all.size(), 3);
    assertEquals(PreflightChangedException.LIMIT, error.chunks().size());
    assertTrue(error.getMessage().contains("共 12 個 chunk") && error.getMessage().contains("另 4 個") && error.getMessage().contains("minecraft:cow×2"));
    assertTrue(error.getMessage().contains("已自動重試 2 次"));
    assertTrue(error.getMessage().contains("/wg status") && error.getMessage().contains("stash push") && error.getMessage().contains("--force"));
  }
}
