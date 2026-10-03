package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.*;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.remote.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

class RemoteTest {
  @TempDir Path temp;
  final CommitMetadata.Identity author = new CommitMetadata.Identity("tester", "tester@local");
  final Credentials credentials = new Credentials(Map.of(), null, null);

  WorldLayout init(String name, String version) throws Exception {
    Path path = temp.resolve(name);
    TestWorlds.copy(TestWorlds.fixture(version), path);
    var layout = WorldLayout.discover(path);
    assertTrue(
        new WorldRepositories(layout)
            .init(null, "creative", WorldGitConfig.Track.ALL, author)
            .success());
    return layout;
  }

  RemoteSpec remote(WorldLayout layout, String name) throws Exception {
    Path path = temp.resolve(name);
    Files.createDirectories(path);
    for (var id : layout.dimensions().keySet())
      try (var repo = new JGitStore(path.resolve(id.directoryName() + ".git"), true)) {}
    var spec = RemoteSpec.parse(path.toUri().toString() + "{dimension}.git");
    try (var r = new WorldRemotes(layout, credentials)) {
      r.configure("add", "origin", spec.url(), false);
    }
    return spec;
  }

  void push(WorldLayout layout) throws Exception {
    try (var r = new WorldRemotes(layout, credentials)) {
      var out = r.push("origin", null, true, false, false, author);
      assertTrue(out.success(), out.error());
    }
  }

  WorldLayout clone(RemoteSpec remote, String name) throws Exception {
    var result =
        WorldClone.cloneWorld(
            remote,
            temp.resolve(name),
            "main",
            null,
            credentials,
            WorldAssembler.Budget.defaults());
    var layout = WorldLayout.discover(result.world());
    try (var ops = new WorldOperations(layout)) {
      assertTrue(ops.verify("HEAD", null, Scope.all(), true).success());
    }
    return layout;
  }

  void edit(WorldLayout layout, int x, String block) throws Exception {
    edit(layout, DimensionId.OVERWORLD, x, block);
  }

  void edit(WorldLayout layout, DimensionId dimension, int x, String block) throws Exception {
    int i = x & 15;
    var state = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
    state.set(i, new BlockState("minecraft:" + block, new TreeMap<>()));
    var mask = new BitSet(4096);
    mask.set(i);
    var section = new org.worldgit.core.model.Section(state, Map.of());
    var patch =
        new ApplyPlan.SectionOp(
            9,
            org.worldgit.core.normalize.SnapshotCodec.section(section),
            Arrays.copyOf(mask.toLongArray(), 64));
    var op =
        new ApplyPlan.ChunkOp(
            new ChunkPos(0, 0),
            false,
            new TreeMap<>(Map.of(9, patch)),
            new TreeMap<>(),
            false,
            null,
            false,
            null);
    var plan =
        new ApplyPlan(
            dimension,
            null,
            null,
            layout.dataVersion(),
            Scope.all(),
            List.of(op),
            List.of(),
            Map.of(),
            List.of());
    new OfflineApplier(layout).apply(plan);
  }

  void commit(WorldLayout layout) throws Exception {
    assertTrue(new WorldRepositories(layout).commit(null, "edit", author, 0).success());
  }

  WorldOperations.PullPreview pull(WorldLayout layout, boolean ffOnly) throws Exception {
    SortedMap<DimensionId, String> targets;
    try (var r = new WorldRemotes(layout, credentials)) {
      var fetch = r.fetch("origin", false);
      assertTrue(fetch.success(), fetch.error());
      targets = r.trackingHeads("origin", "main");
    }
    try (var ops = new WorldOperations(layout)) {
      return ops.pull(
          targets,
          null,
          ffOnly,
          new WorldOperations.MergeOptions(
              false, null, 1, false, author, CommitMetadata.Source.CLI));
    }
  }

  @Test
  void expandsAndRejectsCredentials() throws Exception {
    assertEquals(
        "https://hub.example/git/alice/castle/minecraft.overworld.git",
        RemoteSpec.parse("https://hub.example/alice/castle").expand(DimensionId.OVERWORLD));
    assertEquals(
        "https://host/repos/mod.a%252Fb.git",
        RemoteSpec.parse("https://host/repos/{dimension}.git").expand(new DimensionId("mod:a/b")));
    assertThrows(IOException.class, () -> RemoteSpec.parse("https://token:secret@host/a/b"));
    assertThrows(IOException.class, () -> RemoteSpec.parse("https://host/a/b?token=secret"));
    var manifest =
        RemoteSpec.fromManifest("dimensions:\n  minecraft:overworld: https://host/repo.git\n");
    assertEquals("https://host/repo.git", manifest.expand(DimensionId.OVERWORLD));
    assertThrows(IOException.class, () -> RemoteSpec.fromManifest("dimensions: {}\n"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteSpec("https://token:secret@host/a/b", new TreeMap<>())
                .expand(DimensionId.OVERWORLD));
  }

  @Test
  void credentialsSourcesAndMasking() throws Exception {
    Path file = temp.resolve("credentials.yml");
    Files.writeString(
        file, "credentials:\n  https://host:\n    mode: bearer\n    token: secret-value\n");
    Files.setPosixFilePermissions(
        file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    var source =
        new Credentials(
            Map.of(),
            file,
            (r, u) ->
                new Credentials.Secret(Credentials.Mode.BASIC, "platform", "platform-secret"));
    var secret = source.resolve("origin", "https://host/repo");
    assertEquals("Bearer secret-value", secret.authorization());
    assertFalse(secret.toString().contains("secret-value"));
    assertFalse(secret.redact("Bearer secret-value URL secret-value").contains("secret-value"));
    var basic = new Credentials.Secret(Credentials.Mode.BASIC, "user", "private-token");
    assertEquals("[REDACTED]", basic.redact(basic.authorization().substring(6)));
    assertEquals(
        "Basic " + Base64.getEncoder().encodeToString("u:env-secret".getBytes()),
        new Credentials(Map.of("WGIT_TOKEN", "env-secret", "WGIT_USERNAME", "u"), file, null)
            .resolve("origin", "https://host/r")
            .authorization());
    Files.setPosixFilePermissions(
        file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(IOException.class, () -> source.resolve("origin", "https://host/r"));
    assertEquals(
        "Basic YW5vbnltb3VzOg==", credentials.resolve("origin", "https://host/r").authorization());
  }

  @Test
  void cloneBothVersionsTagsZipAndPartialDimensions() throws Exception {
    for (String version : List.of("1.21.11", "26.2")) {
      Path source = temp.resolve("a" + version);
      TestWorlds.copy(TestWorlds.fixture(version), source);
      var empty = new DimensionId("test:empty");
      var sourceLayout = WorldLayout.discover(source);
      Path sourceWorld = sourceLayout.world();
      int dataVersion = sourceLayout.dataVersion();
      Files.createDirectories(
          WorldAssembler.dimensionPath(sourceWorld, empty, dataVersion).resolve("region"));
      var a = WorldLayout.discover(source);
      assertTrue(
          new WorldRepositories(a)
              .init(null, "creative", WorldGitConfig.Track.ALL, author)
              .success());
      var remote = remote(a, "remote" + version);
      try (var g = new RepositoryGroup(a.repositoryRoot(), new WorldRepositories(a).tracked())) {
        g.tag("v1", null, "release", author, false, false);
      }
      push(a);
      var b = clone(remote, "b" + version);
      try (var group =
          new RepositoryGroup(b.repositoryRoot(), new WorldRepositories(b).tracked())) {
        assertEquals(Set.of("v1"), group.tags());
        for (var repo : group.repos().values())
          assertEquals(1, repo.refs().allCommits().size(), "synthetic refs 不可混入世界歷史");
        var out = new ByteArrayOutputStream();
        new WorldAssembler(WorldAssembler.Budget.defaults()).zip(group, "v1", out, temp);
        var names = new HashSet<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
          java.util.zip.ZipEntry e;
          while ((e = zip.getNextEntry()) != null) names.add(e.getName());
        }
        assertTrue(names.contains("level.dat"));
        String emptyRegion =
            sourceWorld
                    .relativize(
                        WorldAssembler.dimensionPath(sourceWorld, empty, dataVersion)
                            .resolve("region"))
                    .toString()
                    .replace(java.io.File.separatorChar, '/')
                + "/";
        assertTrue(names.contains(emptyRegion), "ZIP 不可遺失空維度：" + names + "，期待 " + emptyRegion);
        Path exported = temp.resolve("exported" + version);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
          java.util.zip.ZipEntry entry;
          while ((entry = zip.getNextEntry()) != null) {
            Path target = exported.resolve(entry.getName());
            if (entry.isDirectory()) Files.createDirectories(target);
            else {
              Files.createDirectories(target.getParent());
              Files.copy(zip, target);
            }
          }
        }
        assertEquals(a.dimensions().keySet(), WorldLayout.discover(exported).dimensions().keySet());
        assertTrue(
            names.stream()
                .noneMatch(
                    n ->
                        n.contains(".worldgit")
                            || n.endsWith("session.lock")
                            || n.contains("/poi/")));
        assertThrows(
            IOException.class,
            () ->
                new WorldAssembler(new WorldAssembler.Budget(1, Duration.ofMinutes(1)))
                    .zip(group, "HEAD", new ByteArrayOutputStream(), temp));
        var timedOut =
            assertThrows(
                IOException.class,
                () ->
                    new WorldAssembler(new WorldAssembler.Budget(1L << 30, Duration.ofNanos(1)))
                        .zip(group, "HEAD", new ByteArrayOutputStream(), temp));
        assertTrue(timedOut.getMessage().contains("時間預算"));
      }
      var partial =
          WorldClone.cloneWorld(
              remote,
              temp.resolve("partial" + version),
              "main",
              Set.of(new DimensionId("minecraft:the_nether")),
              credentials,
              WorldAssembler.Budget.defaults());
      assertEquals(
          1, new WorldRepositories(WorldLayout.discover(partial.world())).tracked().size());
      var mainOnly =
          WorldClone.cloneWorld(
              remote,
              temp.resolve("mainonly" + version),
              "main",
              Set.of(DimensionId.OVERWORLD),
              credentials,
              WorldAssembler.Budget.defaults());
      try (var ops = new WorldOperations(WorldLayout.discover(mainOnly.world()))) {
        var v = ops.verify("HEAD", null, Scope.all(), true);
        assertTrue(v.success(), v.toString());
      }
      try (var r = new WorldRemotes(WorldLayout.discover(mainOnly.world()), credentials)) {
        assertThrows(IOException.class, () -> r.push("origin", null, false, false, false, author));
      }
      String initial;
      try (var group =
          new RepositoryGroup(b.repositoryRoot(), new WorldRepositories(b).tracked())) {
        initial = group.repos().get(DimensionId.OVERWORLD).refs().head();
        for (var repo : group.repos().values())
          for (var pin : repo.refs().refsByPrefix("refs/worldgit/groups/").entrySet())
            repo.refs().updateRef(pin.getKey(), pin.getValue(), null);
      }
      try (var ops = new WorldOperations(b)) {
        assertTrue(ops.verify(initial, null, Scope.all(), true).success(), "舊 Phase 1 snapshot 配對");
      }
    }
  }

  @Test
  void pushDivergencePullMergeFastForwardAndConflict() throws Exception {
    var a = init("a", "1.21.11");
    var remote = remote(a, "remote");
    push(a);
    var b = clone(remote, "b");
    edit(a, 0, "gold_block");
    commit(a);
    edit(b, 4, "diamond_block");
    commit(b);
    push(a);
    try (var r = new WorldRemotes(b, credentials)) {
      assertThrows(IOException.class, () -> r.push("origin", null, false, false, false, author));
    }
    assertThrows(IOException.class, () -> pull(b, true));
    var merge = pull(b, false);
    assertFalse(merge.fastForward());
    assertEquals("COMPLETE", merge.result().state());
    push(b);
    var ff = pull(a, true);
    assertTrue(ff.fastForward());
    assertEquals("COMPLETE", ff.result().state());
    edit(a, 8, "stone");
    commit(a);
    edit(b, 8, "dirt");
    commit(b);
    push(a);
    var conflict = pull(b, false);
    assertEquals("MERGING", conflict.result().state());
    assertTrue(conflict.result().merging().remaining() > 0);
    try (var ops = new WorldOperations(b)) {
      assertTrue(ops.selectRegion(0, MergeReport.Choice.THEIRS, true, false).success());
      assertTrue(ops.continueMerge(author, CommitMetadata.Source.CLI, false).success());
    }
    push(b);
    assertTrue(pull(a, true).result().success());
  }

  @Test
  void partialPushRefusesCloneAndRetriesExactTargets() throws Exception {
    var a = init("a", "26.2");
    var remote = remote(a, "remote");
    try (var r =
        new WorldRemotes(
            a.repositoryRoot(),
            new WorldRepositories(a).tracked(),
            credentials,
            d -> {
              if (!d.equals(DimensionId.OVERWORLD))
                throw new IOException("injected network disconnect");
            })) {
      var result = r.push("origin", null, true, false, false, author);
      assertEquals("PARTIAL", result.state());
    }
    assertThrows(IOException.class, () -> clone(remote, "broken"));
    assertFalse(Files.exists(temp.resolve("broken")));
    try (var r = new WorldRemotes(a, credentials)) {
      assertThrows(IOException.class, () -> r.configure("set-url", "origin", remote.url(), false));
      assertThrows(IOException.class, () -> r.configure("remove", "origin", null, false));
    }
    push(a);
    clone(remote, "good");
    assertEquals(
        "COMPLETE", OperationState.read(a.repositoryRoot().resolve("push-state.yml")).get("state"));
  }

  @Test
  void forceLeaseRequiresFetchedTipAndDirtyPullRefuses() throws Exception {
    var a = init("a", "1.21.11");
    var remote = remote(a, "remote");
    push(a);
    var b = clone(remote, "b");
    edit(a, 0, "gold_block");
    commit(a);
    push(a);
    try (var r = new WorldRemotes(b, credentials)) {
      assertThrows(IOException.class, () -> r.push("origin", null, false, true, false, author));
      assertTrue(r.fetch("origin", false).success());
      assertTrue(r.push("origin", null, false, true, false, author).success());
    }
    edit(a, 4, "stone");
    assertThrows(IOException.class, () -> pull(a, false));
  }

  @Test
  void modifiedOnlySparseSnapshotKeepsCompleteSeedAndGenerationSettings() throws Exception {
    for (String version : List.of("1.21.11", "26.2")) {
      var a = init("sparse" + version, version);
      var remote = remote(a, "sparse-remote" + version);
      UUID snapshot = UUID.randomUUID();
      try (var group =
          new RepositoryGroup(a.repositoryRoot(), new WorldRepositories(a).tracked())) {
        for (var entry : group.repos().entrySet()) {
          var repo = entry.getValue();
          String head = repo.refs().head();
          if (entry.getKey().equals(DimensionId.OVERWORLD)) {
            var old = repo.refs().readCommit(head);
            var editor = new TreeEditor(repo.objects(), old.tree());
            for (var root : repo.objects().readTree(old.tree()).values())
              if (root.name().startsWith("r.")) editor.remove(root.name());
            editor.putBlob("world-meta/worldgit.yml", "track: modified-only\n".getBytes());
            var m = old.metadata();
            String id =
                repo.refs()
                    .createCommit(
                        editor.write(),
                        head,
                        new CommitMetadata(
                            author,
                            author,
                            "modified-only sparse",
                            Instant.now(),
                            m.mcDataVersion(),
                            m.dimension(),
                            m.source(),
                            false,
                            snapshot,
                            List.of()));
            repo.refs().updateRef("refs/heads/main", head, id);
            head = id;
          }
          repo.refs().updateRef("refs/worldgit/groups/" + snapshot, null, head);
        }
      }
      push(a);
      var b = clone(remote, "sparse-clone" + version);
      assertTrue(RegionFile.list(b.dimensions().get(DimensionId.OVERWORLD).region()).isEmpty());
      var before = a.worldMetadata();
      var after = b.worldMetadata();
      String field = version.equals("26.2") ? "world_gen_settings.dat.nbt" : "level.nbt";
      var original = Nbt.read(before.get(field));
      var copied = Nbt.read(after.get(field));
      if (version.equals("1.21.11")) {
        original = original.compound("WorldGenSettings");
        copied = copied.compound("WorldGenSettings");
      }
      assertTrue(Nbt.equal(original, copied));
    }
  }

  @Test
  void bareMergeChoicesAndLease() throws Exception {
    var a = init("a", "26.2");
    try (var ops = new WorldOperations(a)) {
      ops.createBranch("topic", null);
    }
    edit(a, 0, "gold_block");
    commit(a);
    try (var ops = new WorldOperations(a)) {
      assertTrue(ops.switchTo("topic", false, false, false, false).success());
    }
    edit(a, 4, "diamond_block");
    commit(a);
    try (var bare = new BareWorldMerge(a.repositoryRoot(), new WorldRepositories(a).tracked())) {
      var preview = bare.preview("main", "topic", 1);
      assertTrue(preview.canMerge());
      assertFalse(preview.fastForward());
      assertThrows(
          IOException.class,
          () -> bare.merge(preview, Map.of(), 2, author, "changed grouping", false));
      var result = bare.merge(preview, Map.of(), 1, author, "PR merge", false);
      assertEquals("COMPLETE", result.state(), result.error());
      assertEquals(3, result.commits().size());
      assertThrows(
          IOException.class, () -> bare.merge(preview, Map.of(), 1, author, "stale", false));
    }
    try (var ops = new WorldOperations(a)) {
      assertTrue(ops.switchTo("main", false, true, false, false).success());
      ops.createBranch("conflict", null);
    }
    edit(a, 8, "dirt");
    commit(a);
    try (var ops = new WorldOperations(a)) {
      assertTrue(ops.switchTo("conflict", false, false, false, false).success());
    }
    edit(a, 8, "stone");
    commit(a);
    try (var bare = new BareWorldMerge(a.repositoryRoot(), new WorldRepositories(a).tracked())) {
      var preview = bare.preview("main", "conflict", 1);
      assertFalse(preview.canMerge());
      var report = bare.merge(preview, Map.of(), 1, author, "conflict", false);
      assertEquals("CONFLICTS", report.state());
      var choices = new HashMap<Integer, MergeReport.Choice>();
      preview
          .dimensions()
          .values()
          .forEach(
              c -> c.report().regions().forEach(r -> choices.put(r.id(), MergeReport.Choice.BASE)));
      assertEquals("COMPLETE", bare.merge(preview, choices, 1, author, "resolved", false).state());
    }
  }

  @Test
  void modifiedOnlyCaptureAndIncomingChunksRemainTracked() throws Exception {
    var a = init("modified", "26.2");
    Path path = new WorldRepositories(a).tracked().get(DimensionId.OVERWORLD);
    Files.writeString(path.resolve("worldgit-repo.yml"), "track: modified-only\n");
    org.worldgit.core.capture.ModifiedChunks.write(path, Set.of());
    commit(a);
    try (var repo = new DimensionRepository(path, DimensionId.OVERWORLD, false)) {
      assertTrue(
          org.worldgit.core.capture.ModifiedChunks.tree(
                  repo.objects(), repo.refs().readCommit(repo.refs().head()).tree())
              .isEmpty());
    }
    var remote = remote(a, "modified-remote");
    push(a);
    var b = clone(remote, "modified-clone");
    org.worldgit.core.capture.ModifiedChunks.write(path, Set.of(new ChunkPos(0, 0)));
    edit(a, 0, "gold_block");
    commit(a);
    push(a);
    assertTrue(pull(b, true).result().success());
    Path copied = new WorldRepositories(b).tracked().get(DimensionId.OVERWORLD);
    assertTrue(
        org.worldgit.core.capture.ModifiedChunks.read(copied)
            .orElseThrow()
            .contains(new ChunkPos(0, 0)));
    try (var ops = new WorldOperations(b)) {
      assertTrue(ops.verify("HEAD", null, Scope.all(), true).success());
    }
  }

  @Test
  void interruptedTagPublicationRecoversBeforeNextGroupOperation() throws Exception {
    var layout = init("tag-recovery", "1.21.11");
    var changes = new ArrayList<Map<String, Object>>();
    try (var group =
        new RepositoryGroup(layout.repositoryRoot(), new WorldRepositories(layout).tracked())) {
      for (var e : group.repos().entrySet()) {
        var refs = e.getValue().refs();
        String tip = refs.head();
        var row = new LinkedHashMap<String, Object>();
        row.put("dimension", e.getKey().value());
        row.put("ref", "refs/tags/interrupted");
        row.put("old", null);
        row.put("target", tip);
        changes.add(row);
      }
      OperationState.write(
          layout.repositoryRoot().resolve("tag-state.yml"),
          Map.of("state", "PUBLISHING", "changes", changes));
      var first = group.repos().get(group.repos().firstKey());
      first.refs().updateRef("refs/tags/interrupted", null, first.refs().head());
    }
    try (var group =
        new RepositoryGroup(layout.repositoryRoot(), new WorldRepositories(layout).tracked())) {
      assertFalse(group.tags().contains("interrupted"));
      group.tag("light", null, null, author, false, false);
      assertTrue(group.tags().contains("light"));
      group.tag("light", null, null, author, true, false);
      assertFalse(group.tags().contains("light"));
    }
  }

  @Test
  void interruptedFetchPublicationRollsBackAndRetriesWholeGroup() throws Exception {
    var a = init("fetch-recovery", "26.2");
    var remote = remote(a, "fetch-recovery-remote");
    push(a);
    var b = clone(remote, "fetch-recovery-clone");
    edit(a, 1, "gold_block");
    commit(a);
    push(a);
    var changes = new ArrayList<Map<String, Object>>();
    try (var group = new RepositoryGroup(b.repositoryRoot(), new WorldRepositories(b).tracked())) {
      for (var e : group.repos().entrySet()) {
        String old = e.getValue().refs().resolve("refs/remotes/origin/main");
        String tip;
        try (var hosted =
            new JGitStore(
                temp.resolve("fetch-recovery-remote").resolve(e.getKey().directoryName() + ".git"),
                false)) {
          tip = hosted.resolve("main");
        }
        String url = remote.expand(e.getKey());
        try (var transfer =
            new GitTransfer(e.getValue().directory(), url, credentials.resolve("origin", url))) {
          transfer.fetchStages("refs/worldgit/incoming/origin/");
          transfer.fetch("refs/heads/main", "refs/worldgit/incoming/origin/heads/main");
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("dimension", e.getKey().value());
        row.put("ref", "refs/remotes/origin/main");
        row.put("old", old);
        row.put("target", tip);
        changes.add(row);
      }
      OperationState.write(
          b.repositoryRoot().resolve("fetch-state.yml"),
          Map.of("state", "PUBLISHING", "changes", changes));
      var row = changes.get(0);
      group
          .repos()
          .get(new DimensionId((String) row.get("dimension")))
          .refs()
          .updateRef((String) row.get("ref"), (String) row.get("old"), (String) row.get("target"));
    }
    try (var r = new WorldRemotes(b, credentials)) {
      var fetched = r.fetch("origin", false);
      assertTrue(fetched.success(), fetched.error());
      assertEquals(1, r.tracking().getFirst().behind());
    }
    assertEquals(
        "COMPLETE",
        OperationState.read(b.repositoryRoot().resolve("fetch-state.yml")).get("state"));
    assertTrue(pull(b, true).result().success());
  }

  @Test
  void fetchRejectsCollidingSnapshotIdentityBeforePublishingRefs() throws Exception {
    var a = init("collision-source", "26.2");
    var remote = remote(a, "collision-remote");
    push(a);
    var b = clone(remote, "collision-clone");
    String replacement, pin;
    try (var group = new RepositoryGroup(b.repositoryRoot(), new WorldRepositories(b).tracked())) {
      var refs = group.repos().get(DimensionId.OVERWORLD).refs();
      var old = refs.readCommit(refs.head());
      var m = old.metadata();
      replacement =
          refs.createCommit(
              old.tree(),
              old.id(),
              new CommitMetadata(
                  m.author(),
                  m.committer(),
                  "different history with same UUID",
                  m.time().plusSeconds(1),
                  m.mcDataVersion(),
                  m.dimension(),
                  m.source(),
                  m.auto(),
                  m.snapshot(),
                  m.contributions()));
      pin = "refs/worldgit/groups/" + m.snapshot();
      refs.updateRef("refs/heads/main", old.id(), replacement);
      refs.updateRef(pin, old.id(), replacement);
    }
    try (var remotes = new WorldRemotes(b, credentials)) {
      var result = remotes.fetch("origin", false);
      assertFalse(result.success());
      assertTrue(result.error().contains("snapshot group"));
    }
    try (var group = new RepositoryGroup(b.repositoryRoot(), new WorldRepositories(b).tracked())) {
      var refs = group.repos().get(DimensionId.OVERWORLD).refs();
      assertEquals(replacement, refs.head());
      assertEquals(replacement, refs.resolve(pin));
    }
  }

  @Test
  void netherOnlyChangeStillPairsTagsCloneAndPullAsOneWorld() throws Exception {
    var a = init("nether-only-source", "26.2");
    var remote = remote(a, "nether-only-remote");
    push(a);
    var b = clone(remote, "nether-only-before");
    String oldMain;
    try (var group = new RepositoryGroup(a.repositoryRoot(), new WorldRepositories(a).tracked())) {
      oldMain = group.heads().get(DimensionId.OVERWORLD);
    }
    var nether = new DimensionId("minecraft:the_nether");
    edit(a, nether, 0, "gold_block");
    commit(a);
    try (var group = new RepositoryGroup(a.repositoryRoot(), new WorldRepositories(a).tracked())) {
      assertEquals(oldMain, group.heads().get(DimensionId.OVERWORLD));
      String netherTip = group.heads().get(nether);
      assertEquals(netherTip, group.resolve(netherTip).get(nether).id());
      group.tag("nether-release", netherTip, "地獄改動", author, false, false);
    }
    push(a);
    clone(remote, "nether-only-after");
    assertTrue(pull(b, true).result().success());
    try (var group = new RepositoryGroup(b.repositoryRoot(), new WorldRepositories(b).tracked())) {
      assertEquals(group.heads().get(nether), group.resolve("nether-release").get(nether).id());
    }
  }

  @Test
  void stagedHistoryWithNewerPublicationParentsDoesNotResendLargeTree() throws Exception {
    Path source = temp.resolve("staged-source.git"),
        target = temp.resolve("staged-target.git"),
        copied = temp.resolve("staged-copy.git");
    String main, publication;
    Instant before = Instant.now().minusSeconds(1000);
    var metadata =
        new CommitMetadata(
            author,
            author,
            "world",
            before,
            4903,
            DimensionId.OVERWORLD,
            CommitMetadata.Source.CLI,
            false,
            UUID.randomUUID(),
            List.of());
    try (var store = new JGitStore(source, true);
        var ignored = new JGitStore(target, true);
        var unused = new JGitStore(copied, true)) {
      var tree = new TreeEditor(store, null);
      var random = new Random(84);
      for (int i = 0; i < 30; i++) {
        byte[] data = new byte[20000];
        random.nextBytes(data);
        tree.putBlob("r.0.0/c." + i + ".0/section.bin", data);
      }
      main = store.commit(tree.write(), null, metadata);
      var marker = new TreeEditor(store, null);
      marker.putBlob("publication.yml", new byte[] {1, 2, 3});
      var newer =
          new CommitMetadata(
              author,
              author,
              "marker",
              Instant.now(),
              4903,
              DimensionId.OVERWORLD,
              CommitMetadata.Source.CLI,
              false,
              UUID.randomUUID(),
              List.of());
      publication = store.createCommit(marker.write(), List.of(), newer, Map.of());
    }
    String url = target.toUri().toString();
    long limit = 50000;
    var refs = Map.of("refs/heads/main", main, "refs/worldgit/publications/main", publication);
    try (var transfer = new GitTransfer(source, url, credentials.resolve("origin", url), limit)) {
      transfer.stage(refs, UUID.randomUUID(), metadata);
      transfer.publish(
          refs, Map.of("refs/heads/main", "", "refs/worldgit/publications/main", ""), true);
      assertTrue(transfer.sizes().size() > 10);
      assertTrue(transfer.sizes().stream().allMatch(p -> p.preparedBytes() <= limit));
    }
    try (var transfer = new GitTransfer(copied, url, credentials.resolve("origin", url), limit)) {
      transfer.fetchStages("refs/worldgit/incoming/origin/");
      transfer.fetch("refs/heads/main", "refs/remotes/origin/main");
      assertTrue(transfer.sizes().stream().allMatch(p -> p.preparedBytes() <= limit));
    }
    try (var store = new JGitStore(target, false)) {
      for (String id : store.refsByPrefix(GitTransfer.STAGES).values())
        assertEquals(DimensionId.OVERWORLD, store.readCommit(id).metadata().dimension());
    }
  }
}
