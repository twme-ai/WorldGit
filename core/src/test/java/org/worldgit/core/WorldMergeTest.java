package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.worldgit.core.merge.MergeReport.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.*;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

class WorldMergeTest {
  @TempDir Path temp;
  static final CommitMetadata.Identity AUTHOR =
      new CommitMetadata.Identity("Alice", "alice@example.com");

  WorldLayout init(String version) throws Exception {
    Path p = temp.resolve(version);
    TestWorlds.copy(TestWorlds.fixture(version), p);
    var layout = WorldLayout.discover(p);
    assertTrue(
        new WorldRepositories(layout)
            .init(null, "creative", WorldGitConfig.Track.ALL, AUTHOR)
            .success());
    return layout;
  }

  WorldOperations.MergeOptions opts(boolean noCommit, Choice strategy, boolean dryRun) {
    return new WorldOperations.MergeOptions(
        noCommit, strategy, 1, dryRun, AUTHOR, CommitMetadata.Source.CLI);
  }

  static void set(WorldLayout layout, DimensionId dim, int x, int y, int z, String name)
      throws Exception {
    var pos = new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    int sy = Math.floorDiv(y, 16), i = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
    var blocks = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
    blocks.set(i, new BlockState("minecraft:" + name));
    long[] mask = new long[64];
    mask[i >>> 6] |= 1L << (i & 63);
    var sections = new TreeMap<Integer, ApplyPlan.SectionOp>();
    sections.put(
        sy,
        new ApplyPlan.SectionOp(sy, SnapshotCodec.section(new Section(blocks, Map.of())), mask));
    var op = new ApplyPlan.ChunkOp(pos, false, sections, new TreeMap<>(), false, null, false, null);
    var plan =
        new ApplyPlan(
            dim,
            null,
            null,
            layout.dataVersion(),
            Scope.all(),
            List.of(op),
            List.of(),
            Map.of(),
            List.of());
    try (var lock = WorldSessionLock.acquire(layout)) {
      new OfflineApplier(layout).apply(plan, lock);
    }
  }

  String head(WorldLayout layout, DimensionId dim) throws Exception {
    try (var r =
        new DimensionRepository(new WorldRepositories(layout).tracked().get(dim), dim, false)) {
      return r.refs().head();
    }
  }

  void commit(WorldLayout layout, String message) throws Exception {
    assertTrue(new WorldRepositories(layout).commit(null, message, AUTHOR, 2).success());
  }

  void branches(WorldLayout layout, boolean conflict) throws Exception {
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("base", null);
      ops.createBranch("B", null);
    }
    set(layout, DimensionId.OVERWORLD, 0, 144, 0, "gold_block");
    commit(layout, "A");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("A", null);
      assertTrue(ops.switchTo("B", false, false, false, false).success());
    }
    set(layout, DimensionId.OVERWORLD, conflict ? 0 : 1, 144, 0, "diamond_block");
    commit(layout, "B");
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.switchTo("A", false, false, false, false).success());
    }
  }

  @Test
  void cleanMergeTwoParentsSameSnapshotAndVerifyBothVersions() throws Exception {
    for (String v : List.of("1.21.11", "26.2")) {
      var layout = init(v);
      branches(layout, false);
      String before = head(layout, DimensionId.OVERWORLD);
      try (var ops = new WorldOperations(layout)) {
        var dry = ops.merge("B", opts(false, null, true));
        assertEquals("DRY_RUN", dry.state());
        assertNull(ops.merging());
        assertEquals(
            before,
            ops.branches().stream()
                .filter(b -> b.current())
                .findFirst()
                .orElseThrow()
                .commits()
                .get(DimensionId.OVERWORLD));
        var result = ops.merge("B", opts(false, null, false));
        assertEquals("COMPLETE", result.state(), result.error());
        assertTrue(result.reports().values().stream().allMatch(r -> r.regions().isEmpty()));
        assertTrue(ops.verify("HEAD", null, Scope.all(), true).success());
      }
      UUID snapshot = null;
      for (var e : new WorldRepositories(layout).tracked().entrySet())
        try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false)) {
          var c = repo.refs().readCommit(repo.refs().head());
          if (e.getKey().equals(DimensionId.OVERWORLD)) assertEquals(2, c.parents().size());
          if (snapshot == null) snapshot = c.metadata().snapshot();
          else assertEquals(snapshot, c.metadata().snapshot());
          assertTrue(repo.refs().resolve("refs/worldgit/groups/" + snapshot).equals(c.id()));
        }
    }
  }

  @Test
  void durableChoicesManualAbortAndNoCommit() throws Exception {
    var layout = init("26.2");
    branches(layout, true);
    String before = head(layout, DimensionId.OVERWORLD);
    try (var ops = new WorldOperations(layout)) {
      var result = ops.merge("B", opts(false, null, false));
      assertEquals("MERGING", result.state(), result.error());
      assertEquals(1, ops.merging().remaining());
      assertThrows(
          IOException.class, () -> ops.continueMerge(AUTHOR, CommitMetadata.Source.CLI, false));
      assertThrows(IOException.class, () -> ops.switchTo("base", false, true, false, false));
      assertTrue(
          Files.exists(
              new WorldRepositories(layout)
                  .tracked()
                  .get(DimensionId.OVERWORLD)
                  .resolve("MERGE_HEAD")));
    }
    assertFalse(new WorldRepositories(layout).commit(null, "blocked", AUTHOR, 2).success());
    try (var ops = new WorldOperations(layout)) {
      assertEquals(1, ops.merging().remaining());
      int id = ops.merging().regions().getFirst().id();
      assertEquals(
          "minecraft:gold_block", ops.regionPreview(id, Choice.OURS).getFirst().state().name());
      var selection = ops.selectRegion(id, Choice.THEIRS, false, false);
      assertTrue(selection.success(), selection.error());
      assertEquals(Choice.THEIRS, ops.merging().regions().getFirst().choice());
      assertTrue(ops.verify("B", null, Scope.all(), true).success());
      assertTrue(ops.selectRegion(id, Choice.BASE, false, false).success());
      assertTrue(ops.verify("base", null, Scope.all(), true).success());
      assertTrue(ops.selectRegion(id, Choice.OURS, true, false).success());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
      assertEquals(0, ops.merging().remaining());
      // Set blocks reopens a resolved region; manual keeps the current live contents.
      assertTrue(ops.selectRegion(id, Choice.MANUAL, false, false).success());
      assertEquals(1, ops.merging().remaining());
      assertFalse(ops.merging().regions().getFirst().resolved());
      assertEquals(Choice.MANUAL, ops.merging().regions().getFirst().choice());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
      assertTrue(ops.abortMerge(false).success());
      assertNull(ops.merging());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
    }
    assertEquals(before, head(layout, DimensionId.OVERWORLD));
    try (var ops = new WorldOperations(layout)) {
      assertEquals("MERGING", ops.merge("B", opts(true, Choice.THEIRS, false)).state());
      assertEquals(0, ops.merging().remaining());
      assertEquals("COMPLETE", ops.continueMerge(AUTHOR, CommitMetadata.Source.CLI, false).state());
    }
  }

  @Test
  void manualCommitsCurrentWorldAndAbortDiscardsManualEdits() throws Exception {
    var layout = init("26.2");
    branches(layout, true);
    try (var ops = new WorldOperations(layout)) {
      ops.merge("B", opts(true, null, false));
    }
    set(layout, DimensionId.OVERWORLD, 0, 144, 0, "emerald_block");
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.markResolved(1, true, false).success());
      assertEquals(Choice.MANUAL, ops.merging().regions().getFirst().choice());
      assertEquals("COMPLETE", ops.continueMerge(AUTHOR, CommitMetadata.Source.CLI, false).state());
      assertTrue(ops.verify("HEAD", null, Scope.all(), true).success());
    }
  }

  @Test
  void cherryPickAndRevertCleanConflictAndUnchangedDimension() throws Exception {
    var layout = init("26.2");
    branches(layout, false);
    String a = head(layout, DimensionId.OVERWORLD);
    String b;
    try (var ops = new WorldOperations(layout)) {
      b =
          ops.branches().stream()
              .filter(r -> r.name().equals("B"))
              .findFirst()
              .orElseThrow()
              .commits()
              .get(DimensionId.OVERWORLD);
      assertEquals("COMPLETE", ops.cherryPick(b, opts(false, null, false)).state());
      assertEquals("COMPLETE", ops.revert(b, opts(false, null, false)).state());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
      assertEquals("COMPLETE", ops.revert(a, opts(false, null, false)).state());
      assertTrue(ops.verify("base", null, Scope.all(), true).success());
    }
    set(layout, DimensionId.OVERWORLD, 0, 144, 0, "emerald_block");
    commit(layout, "C");
    try (var ops = new WorldOperations(layout)) {
      assertEquals("MERGING", ops.cherryPick(a, opts(false, null, false)).state());
      assertTrue(ops.abortMerge(false).success());
      assertEquals("MERGING", ops.revert(a, opts(false, null, false)).state());
      assertTrue(ops.abortMerge(false).success());
    }
  }

  @Test
  void partialMergeAbortRestoresEntireGroup() throws Exception {
    var layout = init("26.2");
    branches(layout, false);
    var actual = new OfflineApplier(layout);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    try (var ops =
        new WorldOperations(
            layout,
            (plan, lock) -> {
              if (count.incrementAndGet() == 2) throw new IOException("injected");
              actual.apply(plan, lock);
            })) {
      assertEquals("PARTIAL", ops.merge("B", opts(false, null, false)).state());
      assertNotNull(ops.merging());
    }
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.abortMerge(false).success());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
      assertNull(ops.merging());
    }
  }

  @Test
  void rejectsDirtyDataVersionAndRuleConflictBeforeWritingWorld() throws Exception {
    var layout = init("26.2");
    branches(layout, false);
    set(layout, DimensionId.OVERWORLD, 0, 144, 1, "stone");
    try (var ops = new WorldOperations(layout)) {
      assertThrows(IOException.class, () -> ops.merge("B", opts(false, null, false)));
      assertNull(ops.merging());
      ops.resetHard(null, false, false);
    }
    var worlds = new WorldRepositories(layout);
    try (var repo =
        new DimensionRepository(
            worlds.tracked().get(DimensionId.OVERWORLD), DimensionId.OVERWORLD, false)) {
      var b = repo.refs().readCommit(repo.refs().branches().get("B"));
      var m = b.metadata();
      var wrong =
          new CommitMetadata(
              m.author(),
              m.committer(),
              m.message(),
              m.time(),
              4671,
              m.dimension(),
              m.source(),
              false,
              UUID.randomUUID(),
              m.contributions());
      String c = repo.refs().createCommit(b.tree(), b.id(), wrong);
      repo.refs().updateRef("refs/heads/B", b.id(), c);
    }
    try (var ops = new WorldOperations(layout)) {
      assertThrows(IOException.class, () -> ops.merge("B", opts(false, null, false)));
      assertNull(ops.merging());
    }
  }

  @Test
  void oneSidedMissingHistoryAndNoCommonHistoryAreExplicit() throws Exception {
    var layout = init("26.2");
    branches(layout, false);
    var nether = new DimensionId("minecraft:the_nether");
    var worlds = new WorldRepositories(layout);
    try (var repo = new DimensionRepository(worlds.tracked().get(nether), nether, false)) {
      String b = repo.refs().branches().get("B");
      repo.refs().updateRef("refs/heads/B", b, null);
    }
    try (var ops = new WorldOperations(layout)) {
      var result = ops.merge("B", opts(true, null, false));
      assertEquals("MERGING", result.state(), result.error());
      assertTrue(result.reports().get(nether).regions().isEmpty());
      assertTrue(ops.abortMerge(false).success());
    }
    try (var repo = new DimensionRepository(worlds.tracked().get(nether), nether, false)) {
      var old = repo.refs().readCommit(repo.refs().head());
      String fresh =
          repo.refs()
              .createCommit(
                  old.tree(),
                  (String) null,
                  new CommitMetadata(
                      AUTHOR,
                      AUTHOR,
                      "unrelated",
                      java.time.Instant.now(),
                      layout.dataVersion(),
                      nether,
                      CommitMetadata.Source.CLI,
                      false,
                      UUID.randomUUID(),
                      List.of()));
      repo.refs().updateRef("refs/heads/B", null, fresh);
    }
    try (var ops = new WorldOperations(layout)) {
      assertEquals("MERGING", ops.merge("B", opts(true, Choice.OURS, false)).state());
      assertNull(ops.merging().dimensions().get(nether).baseCommit());
      assertTrue(ops.abortMerge(false).success());
    }
  }

  @Test
  void rulesMergeAppliesSidecarKeepsExcludedRawAndAbortRestoresRules() throws Exception {
    var layout = init("26.2");
    branches(layout, false);
    var worlds = new WorldRepositories(layout);
    String original;
    try (var repo =
        new DimensionRepository(
            worlds.tracked().get(DimensionId.OVERWORLD), DimensionId.OVERWORLD, false)) {
      original = Files.readString(repo.ignorePath());
      var b = repo.refs().readCommit(repo.refs().branches().get("B"));
      String rules = original + "\narea 0 144 0 0 144 0\n";
      String tree = TreeFilter.filter(repo.objects(), b.tree(), rules, EntitySemantics.OFFLINE);
      var m = b.metadata();
      String c =
          repo.refs()
              .createCommit(
                  tree,
                  b.id(),
                  new CommitMetadata(
                      m.author(),
                      m.committer(),
                      "rules",
                      java.time.Instant.now(),
                      layout.dataVersion(),
                      m.dimension(),
                      m.source(),
                      false,
                      UUID.randomUUID(),
                      List.of()));
      repo.refs().updateRef("refs/heads/B", b.id(), c);
    }
    try (var ops = new WorldOperations(layout)) {
      var r = ops.merge("B", opts(true, null, false));
      assertEquals("MERGING", r.state(), r.error());
      assertEquals(1, r.reports().get(DimensionId.OVERWORLD).ruleDifferences().size());
      assertTrue(
          Files.readString(worlds.tracked().get(DimensionId.OVERWORLD).resolve(".wgignore"))
              .contains("area 0 144"));
      assertTrue(ops.abortMerge(false).success());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
    }
    assertEquals(
        original,
        Files.readString(worlds.tracked().get(DimensionId.OVERWORLD).resolve(".wgignore")));
  }

  @Test
  void abortRestoresPreviouslyIgnoredCellAfterRuleReinclusion() throws Exception {
    var layout = init("26.2");
    var worlds = new WorldRepositories(layout);
    var pos = new ChunkPos(0, 0);
    set(layout, DimensionId.OVERWORLD, 0, 144, 0, "gold_block");
    Path dir = worlds.tracked().get(DimensionId.OVERWORLD);
    String oldRules = Files.readString(dir.resolve(".wgignore"));
    Files.writeString(dir.resolve(".wgignore"), oldRules + "\narea 0 144 0 0 144 0\n");
    commit(layout, "ignore cell");
    try (var repo = new DimensionRepository(dir, DimensionId.OVERWORLD, false);
        var source =
            new OfflineSnapshotSource(layout, layout.dimensions().get(DimensionId.OVERWORLD))) {
      var head = repo.refs().readCommit(repo.refs().head());
      source.scan(org.worldgit.core.capture.ScanIndex.empty(), true);
      var snap =
          source.snapshot(pos, IgnoreRules.none()).toCompletableFuture().join().orElseThrow();
      var section = snap.sections().get(9);
      var blocks = new ArrayList<>(section.blocks());
      blocks.set(0, new BlockState("minecraft:diamond_block"));
      var editor = new TreeEditor(repo.objects(), head.tree());
      editor.putBlob(".wgignore", oldRules.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      editor.putBlob(
          "r.0.0/c.0.0/s.9.bin",
          SnapshotCodec.section(new Section(blocks, section.blockEntities())));
      var m = head.metadata();
      String c =
          repo.refs()
              .createCommit(
                  editor.write(),
                  head.id(),
                  new CommitMetadata(
                      AUTHOR,
                      AUTHOR,
                      "reinclude",
                      java.time.Instant.now(),
                      layout.dataVersion(),
                      DimensionId.OVERWORLD,
                      CommitMetadata.Source.CLI,
                      false,
                      UUID.randomUUID(),
                      List.of()));
      repo.refs().updateRef("refs/heads/B", null, c);
    }
    // 其他維度的 B 指向同一 HEAD，避免 hash 配對牽涉規則 sidecar。
    for (var e : worlds.tracked().entrySet())
      if (!e.getKey().equals(DimensionId.OVERWORLD))
        try (var r = new DimensionRepository(e.getValue(), e.getKey(), false)) {
          r.refs().updateRef("refs/heads/B", null, r.refs().head());
        }
    try (var ops = new WorldOperations(layout)) {
      var r = ops.merge("B", opts(true, null, false));
      assertEquals("MERGING", r.state(), r.error());
      assertEquals(1, ops.merging().remaining());
      assertTrue(ops.selectRegion(1, Choice.THEIRS, true, false).success());
      assertTrue(ops.abortMerge(false).success());
    }
    Nbt.Compound raw;
    try (var file =
        new RegionFile(
            layout.dimensions().get(DimensionId.OVERWORLD).region().resolve("r.0.0.mca"))) {
      raw = file.read(0);
    }
    var snap =
        new ChunkNormalizer(IgnoreRules.none(), EntitySemantics.OFFLINE)
            .normalize(pos, raw, List.of());
    assertEquals("minecraft:gold_block", snap.sections().get(9).block(0).name());
    assertTrue(Files.readString(dir.resolve(".wgignore")).contains("area 0 144"));
  }

  @Test
  void resolvingAllVerifiesEveryAffectedDimension() throws Exception {
    var layout = init("26.2");
    var nether = new DimensionId("minecraft:the_nether");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("B", null);
    }
    set(layout, DimensionId.OVERWORLD, 0, 144, 0, "gold_block");
    set(layout, nether, -16, 144, -16, "gold_block");
    commit(layout, "A both dimensions");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("A", null);
      ops.switchTo("B", false, false, false, false);
    }
    set(layout, DimensionId.OVERWORLD, 0, 144, 0, "diamond_block");
    set(layout, nether, -16, 144, -16, "diamond_block");
    commit(layout, "B both dimensions");
    try (var ops = new WorldOperations(layout)) {
      ops.switchTo("A", false, false, false, false);
      assertEquals("MERGING", ops.merge("B", opts(true, null, false)).state());
      assertEquals(2, ops.merging().remaining());
    }
    var actual = new OfflineApplier(layout);
    try (var ops =
        new WorldOperations(
            layout,
            (plan, lock) -> {
              if (plan.dimension().equals(DimensionId.OVERWORLD)) actual.apply(plan, lock);
            })) {
      var result = ops.selectRegion(0, Choice.THEIRS, true, false);
      assertEquals("PARTIAL", result.state());
      assertTrue(result.error().contains("minecraft:the_nether"), result.error());
    }
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.abortMerge(false).success());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
    }
  }

  @Test
  void onlyNetherChangedPatchByBranchAndHashSkipsOldOverworldCommit() throws Exception {
    var layout = init("26.2");
    var nether = new DimensionId("minecraft:the_nether");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("base", null);
    }
    // 使用 baseline 已追蹤的 chunk；switch 依 Phase 2 契約保留新增 chunk 為 untracked。
    set(layout, nether, -32, 144, -32, "gold_block");
    commit(layout, "nether only");
    String hash = head(layout, nether);
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("N", null);
      assertTrue(ops.switchTo("base", false, false, false, false).success());
      var picked = ops.cherryPick("N", opts(false, null, false));
      assertEquals("COMPLETE", picked.state(), picked.error());
      assertTrue(ops.verify("N", null, Scope.all(), true).success());
      var reverted = ops.revert(hash, opts(false, null, false));
      assertEquals("COMPLETE", reverted.state(), reverted.error());
    }
    // base 指標已被 patch 提交推進，驗證 snapshot 中只有 Nether 被反向套用。
    try (var repo =
        new DimensionRepository(
            new WorldRepositories(layout).tracked().get(DimensionId.OVERWORLD),
            DimensionId.OVERWORLD,
            false)) {
      var head = repo.refs().readCommit(repo.refs().head());
      var parent = repo.refs().readCommit(head.parents().getFirst());
      assertEquals(parent.tree(), head.tree());
    }
  }

  @Test
  void mergeCandidatesDoNotBreakLegacySnapshotFallback() throws Exception {
    var layout = init("26.2");
    branches(layout, true);
    String original = head(layout, DimensionId.OVERWORLD);
    try (var ops = new WorldOperations(layout)) {
      assertEquals("MERGING", ops.merge("B", opts(true, null, false)).state());
      assertTrue(ops.abortMerge(false).success());
    }
    for (Path dir : new WorldRepositories(layout).tracked().values()) {
      Path refs = dir.resolve("refs/worldgit/groups");
      if (Files.exists(refs))
        try (var paths = Files.walk(refs)) {
          for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("legacy", original);
      assertEquals(
          3,
          ops.branches().stream()
              .filter(b -> b.name().equals("legacy"))
              .findFirst()
              .orElseThrow()
              .commits()
              .size());
    }
  }

  @Test
  void regionCaptureIsBoundedAndPersistenceDoesNotRewriteBase() throws Exception {
    var layout = init("1.21.11");
    branches(layout, true);
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.merge("B", opts(true, null, false)).success());
    }
    Path statePath = layout.repositoryRoot().resolve("merge-state.bin");
    byte[] base = Files.readAllBytes(statePath);
    var touched = new ArrayList<Set<ChunkPos>>();
    var dimensions = new ArrayList<DimensionId>();
    var expected = Set.of(new ChunkPos(0, 0));
    try (var session = WorldSessionLock.acquire(layout)) {
      var access =
          new WorldOperations.LiveAccess() {
            public org.worldgit.core.capture.SnapshotSource source(
                WorldLayout.Dimension dimension) {
              throw new AssertionError("不可建立全維度來源");
            }

            public org.worldgit.core.capture.SnapshotSource source(
                WorldLayout.Dimension dimension, Set<ChunkPos> chunks) {
              assertEquals(expected, chunks);
              dimensions.add(dimension.id());
              touched.add(Set.copyOf(chunks));
              var disk = new OfflineSnapshotSource(layout, dimension);
              return new org.worldgit.core.capture.SnapshotSource() {
                public DimensionId dimension() {
                  return dimension.id();
                }

                public int dataVersion() throws IOException {
                  return layout.dataVersion();
                }

                public Scan scan(org.worldgit.core.capture.ScanIndex previous, boolean full) {
                  throw new AssertionError("區域選擇不可 scan 世界");
                }

                public java.util.concurrent.CompletionStage<Optional<ChunkSnapshot>> snapshot(
                    ChunkPos pos, IgnoreRules rules) {
                  assertTrue(chunks.contains(pos));
                  return disk.snapshot(pos, rules);
                }

                public void close() throws IOException {
                  disk.close();
                }
              };
            }

            public AutoCloseable lockChunks(Map<DimensionId, Set<ChunkPos>> chunks) {
              assertEquals(Map.of(DimensionId.OVERWORLD, expected), chunks);
              return () -> {};
            }

            public void validate(ApplyPlan plan) {
              assertTrue(plan.chunks().keySet().stream().allMatch(expected::contains));
              plan.chunks()
                  .values()
                  .forEach(
                      c ->
                          c.sections()
                              .values()
                              .forEach(section -> assertEquals(1, section.coveredCount())));
            }

            public void applyAll(Collection<ApplyPlan> plans) throws IOException {
              new OfflineApplier(layout).applyAll(plans, session);
            }
          };
      try (var ops = WorldOperations.live(layout, access)) {
        var first = ops.selectRegion(1, Choice.THEIRS, false, false);
        assertTrue(first.success(), first.error());
        long size = Files.size(MergeState.updatesPath(statePath));
        assertTrue(size < 2048);
        assertTrue(ops.markResolved(1, false, false).success());
        assertFalse(OperationState.partial(layout.repositoryRoot()));
        assertArrayEquals(base, Files.readAllBytes(statePath));
        var second = ops.selectRegion(1, Choice.BASE, true, false);
        assertTrue(second.success(), second.error());
        assertTrue(Files.size(MergeState.updatesPath(statePath)) - size < 2048);
        assertArrayEquals(base, Files.readAllBytes(statePath));
        assertEquals(Choice.BASE, ops.merging().regions().getFirst().choice());
        assertEquals(0, ops.merging().remaining());
      }
    }
    assertEquals(4, touched.size());
    assertTrue(dimensions.stream().allMatch(DimensionId.OVERWORLD::equals));
    try (var ops = new WorldOperations(layout)) {
      assertEquals(Choice.BASE, ops.merging().regions().getFirst().choice());
      assertTrue(ops.verify("base", null, Scope.all(), true).success());
      assertTrue(ops.abortMerge(false).success());
    }
  }

  @Test
  void persistenceFailureAfterRegionApplyIsPartialAndAbortRecovers() throws Exception {
    var layout = init("1.21.11");
    branches(layout, true);
    Path statePath = layout.repositoryRoot().resolve("merge-state.bin");
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.merge("B", opts(true, null, false)).success());
    }
    // 故障發生於世界已寫入且驗證成功、選擇狀態尚未 force 的窗口。
    try (var ops =
        new WorldOperations(
            layout,
            (plan, lock) -> {
              new OfflineApplier(layout).apply(plan, lock);
              Files.createDirectory(MergeState.updatesPath(statePath));
            })) {
      var selected = ops.selectRegion(1, Choice.THEIRS, true, false);
      assertEquals("PARTIAL", selected.state());
      assertTrue(OperationState.partial(layout.repositoryRoot()));
    }
    Files.delete(MergeState.updatesPath(statePath));
    try (var ops = new WorldOperations(layout)) {
      assertEquals(Choice.OURS, ops.merging().regions().getFirst().choice());
      assertThrows(IOException.class, () -> ops.selectRegion(1, Choice.BASE, false, false));
      assertTrue(ops.abortMerge(false).success());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
    }
  }

  @Test
  void tornFinalDeltaKeepsPreviousSelectionAndApplyingRequiresAbort() throws Exception {
    var layout = init("1.21.11");
    branches(layout, true);
    Path path = layout.repositoryRoot().resolve("merge-state.bin");
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.merge("B", opts(true, null, false)).success());
      assertTrue(ops.selectRegion(1, Choice.THEIRS, false, false).success());
    }
    OperationState.write(
        layout.repositoryRoot().resolve("apply-state.yml"),
        Map.of("state", "APPLYING", "mode", "merge-select"));
    // 20 bytes payload 的 header 已寫，但 payload 在當機時僅剩 3 bytes。
    Files.write(
        MergeState.updatesPath(path),
        java.nio.ByteBuffer.allocate(11).putInt(20).putInt(0).put(new byte[3]).array(),
        StandardOpenOption.APPEND);
    try (var ops = new WorldOperations(layout)) {
      assertEquals(Choice.THEIRS, ops.merging().regions().getFirst().choice());
      assertThrows(
          IOException.class, () -> ops.continueMerge(AUTHOR, CommitMetadata.Source.CLI, false));
      assertTrue(ops.abortMerge(false).success());
      assertNull(ops.merging());
      assertFalse(Files.exists(MergeState.updatesPath(path)));
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
    }
  }

  private void moveCow(WorldLayout layout, UUID id, double x, float health) throws Exception {
    int[] uuid = {
      (int) (id.getMostSignificantBits() >>> 32),
      (int) id.getMostSignificantBits(),
      (int) (id.getLeastSignificantBits() >>> 32),
      (int) id.getLeastSignificantBits()
    };
    var nbt =
        new Nbt.Compound()
            .with("id", "minecraft:cow")
            .with("UUID", uuid)
            .with("NoAI", (byte) 1)
            .with("Pos", new Nbt.ListTag(6, List.of(x, 145d, 2d)))
            .with("Health", health);
    var plan =
        new ApplyPlan(
            DimensionId.OVERWORLD,
            null,
            null,
            layout.dataVersion(),
            Scope.all(),
            List.of(),
            List.of(new ApplyPlan.EntityOp(id, null, new EntitySnapshot(id, nbt))),
            Map.of(),
            List.of());
    new OfflineApplier(layout).apply(plan);
  }

  @Test
  void entityRegionIncludesActualChunkAfterManualMove() throws Exception {
    checkEntityRegionAfterManualMove(true);
  }

  @Test
  void selectingEmptyBaseRemovesNewUuidAfterManualMove() throws Exception {
    checkEntityRegionAfterManualMove(false);
  }

  private void checkEntityRegionAfterManualMove(boolean baseHasEntity) throws Exception {
    var layout = init("1.21.11");
    var id = UUID.randomUUID();
    set(layout, DimensionId.OVERWORLD, 48, 144, 0, "dirt");
    if (baseHasEntity) moveCow(layout, id, 1, 20);
    commit(layout, "entity base");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("base", null);
      ops.createBranch("B", null);
    }
    moveCow(layout, id, 2, 10);
    commit(layout, "entity ours");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("A", null);
      assertTrue(ops.switchTo("B", false, false, false, false).success());
    }
    moveCow(layout, id, 4, 5);
    commit(layout, "entity theirs");
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.switchTo("A", false, false, false, false).success());
      assertTrue(ops.merge("B", opts(true, null, false)).success());
    }
    moveCow(layout, id, 50, 8);
    try (var ops = new WorldOperations(layout)) {
      var result =
          ops.selectRegion(
              ops.merging().regions().getFirst().id(),
              baseHasEntity ? Choice.THEIRS : Choice.BASE,
              true,
              false);
      assertTrue(result.success(), result.error());
      assertEquals(
          Set.of(new ChunkPos(0, 0), new ChunkPos(3, 0)),
          result.plans().get(DimensionId.OVERWORLD).scope().chunks());
      assertTrue(ops.verify(baseHasEntity ? "B" : "base", null, Scope.all(), true).success());
      assertTrue(ops.abortMerge(false).success());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
    }
  }

  private void ridingCow(WorldLayout layout, UUID parent, UUID passenger, double x, float health)
      throws Exception {
    moveCow(layout, parent, x, health);
    try (var source =
        new OfflineSnapshotSource(layout, layout.dimensions().get(DimensionId.OVERWORLD))) {
      var snapshot =
          source
              .snapshot(new ChunkPos((int) Math.floor(x / 16), 0), IgnoreRules.parse(""))
              .toCompletableFuture()
              .join()
              .orElseThrow();
      var data =
          (Nbt.Compound)
              Nbt.copy(
                  snapshot.entities().stream()
                      .filter(e -> e.uuid().equals(parent))
                      .findFirst()
                      .orElseThrow()
                      .data());
      var child = (Nbt.Compound) Nbt.copy(data);
      child.put(
          "UUID",
          new int[] {
            (int) (passenger.getMostSignificantBits() >>> 32),
            (int) passenger.getMostSignificantBits(),
            (int) (passenger.getLeastSignificantBits() >>> 32),
            (int) passenger.getLeastSignificantBits()
          });
      child.put("Health", 20f);
      data.put("Passengers", new Nbt.ListTag(10, List.of(child)));
      new OfflineApplier(layout)
          .apply(
              new ApplyPlan(
                  DimensionId.OVERWORLD,
                  null,
                  null,
                  layout.dataVersion(),
                  Scope.all(),
                  List.of(),
                  List.of(new ApplyPlan.EntityOp(parent, null, new EntitySnapshot(parent, data))),
                  Map.of(),
                  List.of()));
    }
  }

  @Test
  void passengerUuidScopeIncludesDetachedRootAndOtherVehicle() throws Exception {
    var layout = init("1.21.11");
    var parent = UUID.randomUUID();
    var child = UUID.randomUUID();
    var other = UUID.randomUUID();
    set(layout, DimensionId.OVERWORLD, 48, 144, 0, "dirt");
    set(layout, DimensionId.OVERWORLD, 64, 144, 0, "dirt");
    moveCow(layout, other, 50, 20);
    ridingCow(layout, parent, child, 1, 20);
    commit(layout, "passenger base");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("B", null);
    }
    ridingCow(layout, parent, child, 2, 10);
    commit(layout, "passenger ours");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("A", null);
      assertTrue(ops.switchTo("B", false, false, false, false).success());
    }
    ridingCow(layout, parent, child, 4, 5);
    commit(layout, "passenger theirs");
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.switchTo("A", false, false, false, false).success());
      assertTrue(ops.merge("B", opts(true, null, false)).success());
    }

    moveCow(layout, parent, 2, 10); // 玩家先拆離乘客，乘客成為其他 chunk 的 root。
    moveCow(layout, child, 66, 20);
    try (var ops = new WorldOperations(layout)) {
      int region = ops.merging().regions().getFirst().id();
      var result = ops.selectRegion(region, Choice.THEIRS, false, false);
      assertTrue(result.success(), result.error());
      assertEquals(
          Set.of(new ChunkPos(0, 0), new ChunkPos(4, 0)),
          result.plans().get(DimensionId.OVERWORLD).scope().chunks());
      assertTrue(ops.verify("B", null, Scope.all(), true).success());
    }

    moveCow(layout, parent, 4, 5);
    ridingCow(layout, other, child, 50, 20); // 乘客改騎未選中的載具，保留該載具的其他資料。
    try (var ops = new WorldOperations(layout)) {
      var result =
          ops.selectRegion(ops.merging().regions().getFirst().id(), Choice.THEIRS, true, false);
      assertTrue(result.success(), result.error());
      assertEquals(
          Set.of(new ChunkPos(0, 0), new ChunkPos(3, 0)),
          result.plans().get(DimensionId.OVERWORLD).scope().chunks());
      assertTrue(ops.verify("B", null, Scope.all(), true).success());
      assertTrue(ops.abortMerge(false).success());
      assertTrue(ops.verify("A", null, Scope.all(), true).success());
    }
  }
}
