package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.Scope;
import org.worldgit.core.capture.*;
import org.worldgit.core.config.*;
import org.worldgit.core.graph.CommitGraph;
import org.worldgit.core.model.*;
import org.worldgit.core.operation.*;
import org.worldgit.core.remote.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

class Phase5Test {
  @TempDir Path temp;
  static final DimensionId NETHER = new DimensionId("minecraft:the_nether");
  static final CommitMetadata.Identity AUTHOR = new CommitMetadata.Identity("test", "test@example.test");
  WorldLayout world(String version) throws Exception {
    TestWorlds.copy(TestWorlds.fixture(version), temp.resolve(version)); return WorldLayout.discover(temp.resolve(version));
  }
  @Test void scopedInitAndFoldersInBothLayouts() throws Exception {
    for (String version : List.of("1.21.11", "26.2")) {
      var layout = world(version); var worlds = new WorldRepositories(layout);
      assertEquals(DimensionId.OVERWORLD, layout.currentDimension());
      assertTrue(worlds.init(null, "creative", WorldGitConfig.Track.ALL, AUTHOR).success());
      assertEquals(Set.of(DimensionId.OVERWORLD), worlds.tracked().keySet());
      assertEquals(layout.world().resolve(".worldgit"), worlds.tracked().get(DimensionId.OVERWORLD));
      var nether = WorldLayout.discover(layout.dimensions().get(NETHER).directory());
      assertEquals(NETHER, nether.currentDimension());
      if (version.equals("1.21.11")) assertEquals(NETHER, WorldLayout.discover(nether.dimensions().get(NETHER).directory().getParent()).currentDimension());
      assertTrue(new WorldRepositories(nether).init(null, "creative", WorldGitConfig.Track.ALL, AUTHOR).success());
      assertEquals(nether.dimensions().get(NETHER).directory().resolve(".worldgit"), worlds.tracked().get(NETHER));
      assertEquals(1, worlds.initializable().stream().filter(d -> !d.initialized()).count());
    }
  }
  @Test void onlyNetherSwitchStashAndPartialLeaveOverworldBytesAndHead() throws Exception {
    var layout = world("26.2"); var worlds = new WorldRepositories(layout);
    assertTrue(worlds.initAll("creative", WorldGitConfig.Track.ALL, AUTHOR).success());
    var baseline = new TreeMap<String, byte[]>();
    for (Path path : RegionFile.list(layout.dimensions().get(DimensionId.OVERWORLD).region())) baseline.put(path.toString(), Files.readAllBytes(path));
    byte[] level = Files.readAllBytes(layout.world().resolve("level.dat"));
    String main; try (var repo = new DimensionRepository(layout.repository(DimensionId.OVERWORLD), DimensionId.OVERWORLD, false)) { main = repo.refs().head(); }
    try (var ops = WorldOperations.inDimension(layout, NETHER)) { ops.createBranch("nether-only", null); assertTrue(ops.switchTo("nether-only", false, false, false, false).success()); }
    try (var repo = new DimensionRepository(layout.repository(DimensionId.OVERWORLD), DimensionId.OVERWORLD, false)) {
      assertEquals(main, repo.refs().head()); assertEquals("main", repo.refs().headState().branch()); assertFalse(repo.refs().branches().containsKey("nether-only"));
    }
    try(var remotes=new WorldRemotes(layout,Credentials.system())) {
      assertEquals("main",remotes.branch());assertEquals("nether-only",remotes.branch(NETHER));
      assertFalse(remotes.hasBranch("nether-only"));assertTrue(remotes.hasBranch(NETHER,"nether-only"));
    }
    for (var entry : baseline.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(Path.of(entry.getKey())));
    assertArrayEquals(level, Files.readAllBytes(layout.world().resolve("level.dat")));
    assertFalse(Files.exists(layout.repository(DimensionId.OVERWORLD).resolve("apply-state.yml")));
    assertTrue(Files.exists(layout.repository(NETHER).resolve("apply-state.yml")));
    OperationState.write(layout.repository(NETHER).resolve("apply-state.yml"), Map.of("state", "PARTIAL"));
    assertTrue(worlds.commit(DimensionId.OVERWORLD, "main stays usable", AUTHOR, 2).success());
    assertFalse(worlds.commit(NETHER, "blocked", AUTHOR, 2).success());
  }
  @Test void copiedDimensionFolderWorksWithoutMainWorld() throws Exception {
    var layout=world("26.2");assertTrue(new WorldRepositories(layout).init(NETHER,"survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    Path copied=temp.resolve("portable-nether");TestWorlds.copy(layout.dimensions().get(NETHER).directory(),copied);
    var standalone=WorldLayout.discover(copied);assertEquals(NETHER,standalone.currentDimension());assertEquals(layout.dataVersion(),standalone.dataVersion());
    assertFalse(Files.exists(copied.resolve("level.dat")));assertEquals(Set.of(NETHER),standalone.dimensions().keySet());
    var worlds=new WorldRepositories(standalone);var status=worlds.status(null,2,true);assertTrue(status.success(),status.toString());var commit=worlds.commit(null,"portable no-op",AUTHOR,2);assertTrue(commit.success(),commit.toString());
    try(var ops=WorldOperations.inDimension(standalone,NETHER)) { ops.createBranch("portable",null);assertTrue(ops.switchTo("portable",false,false,false,false).success()); }
  }
  @Test void graphForkMergeDecorationsAndTruncation() throws Exception {
    Path path = temp.resolve("repo");
    try (var repo = new JGitStore(path, true)) {
      String tree = repo.writeTree(List.of()); repo.flush();
      var metadata = new CommitMetadata(AUTHOR, AUTHOR, "initial", Instant.now(), 4903, NETHER, CommitMetadata.Source.CLI, false, UUID.randomUUID(), List.of());
      String a = repo.commit(tree, null, metadata), b = repo.createCommit(tree, List.of(a), metadata, Map.of()), c = repo.createCommit(tree, List.of(a), metadata, Map.of());
      // Different messages avoid identical git commit objects at the same time.
      var other = new CommitMetadata(AUTHOR, AUTHOR, "fork", Instant.now(), 4903, NETHER, CommitMetadata.Source.CLI, false, UUID.randomUUID(), List.of());
      c = repo.createCommit(tree, List.of(a), other, Map.of());
      String merge = repo.createCommit(tree, List.of(b, c), other, Map.of());
      repo.updateRef("refs/heads/main", a, merge); repo.updateRef("refs/heads/feature", null, c); repo.updateRef("refs/remotes/origin/main", null, b); repo.createTag("v1", c, "tag", AUTHOR);
      var graph = CommitGraph.read(repo, 20, true); assertEquals(4, graph.nodes().size()); assertFalse(graph.truncated()); assertTrue(graph.lanes() >= 2);
      assertEquals(merge, graph.nodes().getFirst().id()); assertEquals(2, graph.nodes().getFirst().edges().size()); assertEquals(a, graph.nodes().getLast().id());
      assertTrue(graph.nodes().stream().anyMatch(n -> n.labels().stream().anyMatch(l -> l.kind().equals("tag"))));
      assertEquals("* ", org.worldgit.core.graph.GraphText.node(graph.nodes().getFirst()));
      assertTrue(org.worldgit.core.graph.GraphText.transition(graph.nodes().getFirst()).orElseThrow().contains("\\"));
      assertTrue(CommitGraph.read(repo, 2, true).truncated());
    }
  }
  @Test void netherStashResetAndMergingAreIndependent() throws Exception {
    var layout=world("26.2");var worlds=new WorldRepositories(layout);assertTrue(worlds.initAll("survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    Path mainPath=layout.repository(DimensionId.OVERWORLD);String mainHead;
    try(var repo=new JGitStore(mainPath,false)) { mainHead=repo.head(); }
    byte[] mainRegion=Files.readAllBytes(layout.dimensions().get(DimensionId.OVERWORLD).region().resolve("r.0.0.mca"));
    byte[] level=Files.readAllBytes(layout.world().resolve("level.dat"));
    ChunkPos pos=null;int section=0;
    try(var region=new RegionFile(RegionFile.list(layout.dimensions().get(NETHER).region()).getFirst())) {
      for(int i=0;i<1024;i++) if(region.has(i)) {
        pos=region.pos(i);section=region.read(i).list("sections").values().stream().map(Nbt.Compound.class::cast).filter(s->!s.compound("block_states").isEmpty()).findFirst().orElseThrow().integer("Y",0);break;
      }
    }
    assertNotNull(pos);TestWorlds.oneBlock(layout,NETHER,pos,section,0);
    try(var ops=WorldOperations.inDimension(layout,NETHER)) {
      assertTrue(ops.stashPush("nether only",false).success());assertEquals(1,ops.stashes().size());
      assertTrue(ops.stashPop(0,false).success());assertTrue(ops.stashes().isEmpty());
      assertTrue(ops.resetHard(null,false,false).success());ops.createBranch("theirs",null);
    }
    assertFalse(Files.exists(mainPath.resolve("stash.yml")));
    TestWorlds.oneBlock(layout,NETHER,pos,section,0);assertTrue(worlds.commit(NETHER,"ours",AUTHOR,2).success());
    try(var ops=WorldOperations.inDimension(layout,NETHER)) { assertTrue(ops.switchTo("theirs",false,false,false,false).success()); }
    TestWorlds.oneBlock(layout,NETHER,pos,section,0);TestWorlds.oneBlock(layout,NETHER,pos,section,0);assertTrue(worlds.commit(NETHER,"theirs",AUTHOR,2).success());
    try(var ops=WorldOperations.inDimension(layout,NETHER)) {
      assertTrue(ops.switchTo("main",false,false,false,false).success());
      assertEquals("MERGING",ops.merge("theirs",new WorldOperations.MergeOptions(false,null,1,false,AUTHOR,CommitMetadata.Source.CLI)).state());
      assertFalse(Files.exists(mainPath.resolve("merge-state.bin")));
      assertTrue(worlds.commit(DimensionId.OVERWORLD,"independent while merging",AUTHOR,2).success());
      assertTrue(ops.abortMerge(false).success());
    }
    try(var repo=new JGitStore(mainPath,false)) { assertEquals(mainHead,repo.head()); }
    assertArrayEquals(mainRegion,Files.readAllBytes(layout.dimensions().get(DimensionId.OVERWORLD).region().resolve("r.0.0.mca")));
    assertArrayEquals(level,Files.readAllBytes(layout.world().resolve("level.dat")));
  }
  @Test void ignorePreservesCommentsOrderDisabledRulesAndWinningLine() throws Exception {
    var doc = IgnoreEditor.parse("# note\n\nentity minecraft:item\n!entity minecraft:item in area 0 0 0 10 100 10\n");
    assertEquals("# note", doc.move(3, 4).lines().getFirst());
    assertEquals(doc.text(), doc.enabled(3, false).enabled(3, true).text());
    assertEquals(3, IgnoreEditor.test(doc, "entity minecraft:item 20,64,20", EntitySemantics.OFFLINE).line());
    var include = IgnoreEditor.test(doc, "entity minecraft:item 1,64,1", EntitySemantics.OFFLINE); assertFalse(include.excluded()); assertEquals(4, include.line());
    assertTrue(assertThrows(IOException.class, () -> IgnoreEditor.parse("# note\narea 1 2\n")).getMessage().contains("第 2 行"));
  }
  @Test void errorsMaskSecretsAndStayBounded() {
    var report = OperationResult.ErrorReport.create("WG_FAIL", UUID.randomUUID(), "push", NETHER, "0.1", "Paper 26.2",
        "https://alice:password@host/repo ghp_SUPERSECRET Authorization: Bearer PRIVATE\nwebhook-secret=HIDDEN PAT=TOKEN " + "x".repeat(10000));
    for (String secret : List.of("password@", "ghp_SUPERSECRET", "PRIVATE", "HIDDEN", "TOKEN")) assertFalse(report.text().contains(secret), secret);
    assertTrue(report.text().length() <= 8192); assertTrue(report.text().contains("UTC=")); assertTrue(report.text().contains("minecraft:the_nether"));
    var known = OperationResult.ErrorReport.create("WG_FAIL", UUID.randomUUID(), "fetch", NETHER, "0.1", "26.2", "CLI", "cause plain-secret-value", List.of("plain-secret-value"));
    assertFalse(known.text().contains("plain-secret-value")); assertFalse(known.message().contains("plain-secret-value"));
    assertEquals("26.2",known.minecraftVersion());assertTrue(known.text().contains("Minecraft=26.2"));
  }
  @Test void progressCoalescesOffThreadAndCancels() throws Exception {
    var events = new CopyOnWriteArrayList<OperationProgress.Event>(); var threads = new CopyOnWriteArrayList<Thread>(); Thread caller = Thread.currentThread();
    try (var progress = new OperationProgress("capture", event -> { events.add(event); threads.add(Thread.currentThread()); })) {
      for (int i = 0; i <= 1000; i++) OperationProgress.report(NETHER, "normalize", i, 1000L, OperationProgress.Unit.CHUNK);
      Thread.sleep(150); assertEquals(1, events.size()); assertNotEquals(caller, threads.getFirst());
      assertEquals(1000, events.getFirst().completed()); assertEquals(progress.id(), events.getFirst().operationId());
      progress.cancel(); assertThrows(InterruptedIOException.class, OperationProgress::check);
    }
  }
  @Test void entityPolicyOldDefaultsAndVersionedTouchSet() throws Exception {
    assertEquals(WorldGitConfig.Entities.ALL, WorldGitConfig.readRepo("track: all\n", "legacy").entities());
    var layout = world("26.2"); var worlds = new WorldRepositories(layout); assertTrue(worlds.init(null, "creative", WorldGitConfig.Track.ALL, AUTHOR).success());
    Path path = layout.repository(DimensionId.OVERWORLD);
    assertEquals(WorldGitConfig.Entities.PLAYER_TOUCHED, WorldGitConfig.readRepo(path.resolve("worldgit-repo.yml")).entities());
    try (var repo = new DimensionRepository(path, DimensionId.OVERWORLD, false)) {
      String tree = repo.refs().readCommit(repo.refs().head()).tree(); assertTrue(new org.worldgit.core.diff.DiffEngine(repo.objects()).entities(tree).isEmpty()); assertNotNull(TreeEditor.find(repo.objects(), tree, PlayerTouchedEntities.FILE));
    }
    UUID uuid = UUID.randomUUID(); PlayerTouchedEntities.touch(path, TestWorlds.entity(uuid, 0, true)); assertEquals(Set.of(uuid), PlayerTouchedEntities.read(path));
  }
  @Test void cloneUsesSymbolicHeadWhenTwoBranchesShareTip() throws Exception {
    var layout=world("26.2");assertTrue(new WorldRepositories(layout).init(null,"survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    try(var ops=new WorldOperations(layout)) { ops.createBranch("alias",null); }
    Path remote=temp.resolve("bare");Files.createDirectories(remote);
    try(var ignored=new JGitStore(remote.resolve("minecraft.overworld.git"),true)) {}
    try(var remotes=new WorldRemotes(layout,Credentials.system())) {
      remotes.configure("add","origin",remote.toUri().toString(),false);
      assertTrue(remotes.push("origin","main",false,false,false,AUTHOR).success());
      assertTrue(remotes.push("origin","alias",false,false,false,AUTHOR).success());
    }
    Path clone=temp.resolve("default-clone");WorldClone.cloneWorld(RemoteSpec.parse(remote.toUri().toString()),clone,Map.of(),Set.of(DimensionId.OVERWORLD),Credentials.system(),WorldAssembler.Budget.defaults());
    try(var repo=new JGitStore(clone.resolve(".worldgit"),false)) { assertEquals("main",repo.headState().branch()); }
  }
  @Test void cloneDiscoversNetherPublishedAfterOldMainManifest() throws Exception {
    var layout=world("26.2");var worlds=new WorldRepositories(layout);
    OperationState.write(layout.repositoryRoot().resolve("dimension-manifest.yml"),Map.of("dimensions",Map.of()));
    assertTrue(worlds.init(DimensionId.OVERWORLD,"survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    assertTrue(worlds.init(NETHER,"survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    try(var ops=WorldOperations.inDimension(layout,NETHER)) { ops.createBranch("late-nether",null);assertTrue(ops.switchTo("late-nether",false,false,false,false).success());ops.deleteBranch("main"); }
    Path remote=temp.resolve("late-remote");Files.createDirectories(remote);
    for(var id:List.of(DimensionId.OVERWORLD,NETHER)) try(var ignored=new JGitStore(remote.resolve(id.directoryName()+".git"),true)) {}
    try(var remotes=new WorldRemotes(layout,Credentials.system())) { remotes.configure("add","origin",remote.toUri().toString(),false);assertTrue(remotes.push("origin",null,false,false,false,AUTHOR).success()); }
    Path clone=temp.resolve("late-clone");var result=WorldClone.cloneWorld(RemoteSpec.parse(remote.toUri().toString()),clone,Map.of(),null,Credentials.system(),WorldAssembler.Budget.defaults());
    assertEquals(Set.of(DimensionId.OVERWORLD,NETHER),result.dimensions());
    var copied=WorldLayout.discover(clone);try(var repo=new JGitStore(copied.repository(NETHER),false)) { assertEquals("late-nether",repo.headState().branch()); }
  }
  @Test void partialVanillaAssemblyAndZipRetainEmptyDimensionDirectories() throws Exception {
    var layout=world("1.21.11");var worlds=new WorldRepositories(layout);
    assertTrue(worlds.init(null,"survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    try(var group=new RepositoryGroup(layout.repositoryRoot(),worlds.tracked())) {
      var commits=group.resolve("HEAD");Path assembled=temp.resolve("assembled");
      new WorldAssembler(WorldAssembler.Budget.defaults()).assemble(group,commits,assembled,Set.of(DimensionId.OVERWORLD));
      for(String name:List.of("DIM-1","DIM1")) {
        assertTrue(Files.isDirectory(assembled.resolve(name)));
        try(var contents=Files.list(assembled.resolve(name))) { assertEquals(0,contents.count()); }
      }
      var bytes=new ByteArrayOutputStream();
      new WorldAssembler(WorldAssembler.Budget.defaults()).zip(group,commits,bytes,temp.resolve("zip-temp"));
      var entries=new TreeSet<String>();
      try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) entries.add(entry.getName());
      }
      assertTrue(entries.containsAll(Set.of("DIM-1/","DIM1/")),entries.toString());
      assertFalse(entries.stream().anyMatch(name->name.contains(".worldgit")));
      assertFalse(entries.stream().anyMatch(name->name.startsWith("DIM-1/region/")||name.startsWith("DIM1/region/")));
    }
  }
  @Test void externalAndSingleplayerMigrationKeepHistoryAndResume() throws Exception {
    for (String version : List.of("1.21.11", "26.2")) {
      var layout = world(version); var worlds = new WorldRepositories(layout); assertTrue(worlds.init(null, "survival", WorldGitConfig.Track.ALL, AUTHOR).success());
      Path fresh = layout.repository(DimensionId.OVERWORLD); String head;
      try (var repo = new JGitStore(fresh, false)) { head = repo.head(); }
      Path legacy = version.equals("1.21.11") ? layout.server().resolve(".worldgit/world").resolve(DimensionId.OVERWORLD.directoryName()) : layout.world().resolve(".worldgit-old").resolve(DimensionId.OVERWORLD.directoryName());
      Files.createDirectories(legacy.getParent()); Files.move(fresh, legacy);
      if (version.equals("26.2")) { Files.move(legacy.getParent(), fresh); legacy = fresh.resolve(DimensionId.OVERWORLD.directoryName()); }
      assertEquals(legacy, worlds.tracked().get(DimensionId.OVERWORLD));
      assertEquals("COMPLETE", RepositoryMigration.migrate(layout, null, false).getFirst().state());
      assertEquals("NO_OP", RepositoryMigration.migrate(layout, null, false).getFirst().state());
      try (var repo = new JGitStore(fresh, false)) { assertEquals(head, repo.head()); }
      assertTrue(worlds.commit(null, "after migrate", AUTHOR, 2).success());
    }
  }
  @Test void cancelledNestedMigrationResumesFromRenamedBackup() throws Exception {
    var layout=world("26.2"); var worlds=new WorldRepositories(layout);
    assertTrue(worlds.init(null,"survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    Path root=layout.repository(DimensionId.OVERWORLD), stage=root.resolveSibling("old-root");
    Files.createDirectories(stage); Files.move(root,stage.resolve(DimensionId.OVERWORLD.directoryName())); Files.move(stage,root);
    try(var progress=new OperationProgress("migrate",event->{})) {
      progress.cancel(); assertThrows(InterruptedIOException.class,()->RepositoryMigration.migrate(layout,null,false));
    }
    assertTrue(Files.isDirectory(layout.world().resolve(".worldgit-legacy")));
    assertEquals("COMPLETE",RepositoryMigration.migrate(layout,null,false).getFirst().state());
    assertTrue(worlds.status(null,2,true).success());
  }
  @Test void legacyIncompleteWritesAreBlockedAndMigrationContinuesOtherDimensions() throws Exception {
    var layout=world("1.21.11");var worlds=new WorldRepositories(layout);
    assertTrue(worlds.initDimensions(Set.of(DimensionId.OVERWORLD,NETHER),"survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    Path oldRoot=layout.server().resolve(".worldgit/world");Files.createDirectories(oldRoot);
    var heads=new TreeMap<DimensionId,String>();
    for(var id:List.of(DimensionId.OVERWORLD,NETHER)) {
      Path fresh=layout.repository(id);try(var repo=new JGitStore(fresh,false)) { heads.put(id,repo.head()); }
      Files.move(fresh,oldRoot.resolve(id.directoryName()));
    }
    OperationState.write(oldRoot.resolve("apply-state.yml"),Map.of("state","PARTIAL"));
    assertTrue(worlds.status(null,2,true).success(),"舊 repo 未完成時仍可唯讀");
    assertFalse(worlds.commit(DimensionId.OVERWORLD,"must not bypass",AUTHOR,2).success());
    try(var ops=WorldOperations.inDimension(layout,NETHER)) { assertThrows(IOException.class,()->ops.createBranch("blocked",null)); }
    Path legacyMain=oldRoot.resolve(DimensionId.OVERWORLD.directoryName());
    try(var repo=new DimensionRepository(legacyMain,DimensionId.OVERWORLD,false)) {
      var edited=IgnoreEditor.read(repo.ignorePath()).add("entity minecraft:item");
      assertThrows(IOException.class,()->IgnoreEditor.write(repo,edited));
    }
    try(var remotes=new WorldRemotes(legacyMain,Map.of(DimensionId.OVERWORLD,legacyMain),Credentials.system())) { assertThrows(IOException.class,()->remotes.configure("add","origin",temp.resolve("remote").toUri().toString(),false)); }
    Files.delete(oldRoot.resolve("apply-state.yml"));
    Path link=oldRoot.resolve(DimensionId.OVERWORLD.directoryName()).resolve("unsafe-link");Files.createSymbolicLink(link,temp);
    var partial=RepositoryMigration.migrate(layout,null,false);
    assertEquals("FAILED",partial.stream().filter(m->m.dimension().equals(DimensionId.OVERWORLD)).findFirst().orElseThrow().state());
    assertTrue(partial.stream().filter(m->m.dimension().equals(DimensionId.OVERWORLD)).findFirst().orElseThrow().error().contains("符號連結"));
    assertEquals("COMPLETE",partial.stream().filter(m->m.dimension().equals(NETHER)).findFirst().orElseThrow().state());
    Files.delete(link);assertEquals("COMPLETE",RepositoryMigration.migrate(layout,DimensionId.OVERWORLD,false).getFirst().state());
    for(var id:heads.keySet()) try(var repo=new JGitStore(layout.repository(id),false)) { assertEquals(heads.get(id),repo.head()); }
  }
  @Test void touchedEntitiesSwitchMergeAndClonePreserveUntrackedNature() throws Exception {
    var layout = world("26.2"); var worlds = new WorldRepositories(layout);
    assertTrue(worlds.init(null, "creative", WorldGitConfig.Track.ALL, AUTHOR).success());
    var dimension = layout.dimensions().get(DimensionId.OVERWORLD); var position = new ChunkPos(0, 0);
    UUID tracked = UUID.randomUUID(), natural = UUID.randomUUID(), added = UUID.randomUUID();
    var first = TestWorlds.entity(tracked, 1, true).with("CustomName", "first");
    var cow = TestWorlds.entity(natural, 3, false).with("id", "minecraft:cow");
    writeEntities(layout, position, List.of(first, cow)); PlayerTouchedEntities.touch(layout.repository(DimensionId.OVERWORLD), first);
    assertTrue(worlds.commit(null, "first touch", AUTHOR, 2).success());
    try(var ops = new WorldOperations(layout)) { ops.createBranch("base", null); ops.createBranch("feature", null); assertTrue(ops.switchTo("feature", false, false, false, false).success()); }
    var changed = TestWorlds.entity(tracked, 1, true).with("CustomName", "changed");
    var newEntity = TestWorlds.entity(added, 7, true).with("id", "minecraft:armor_stand");
    var movedCow = TestWorlds.entity(natural, 5, false).with("id", "minecraft:cow");
    writeEntities(layout, position, List.of(changed, movedCow, newEntity)); PlayerTouchedEntities.touch(layout.repository(DimensionId.OVERWORLD), newEntity);
    assertTrue(worlds.commit(null, "player edit", AUTHOR, 2).success());
    try(var ops = new WorldOperations(layout)) { var result = ops.switchTo("base", false, false, false, false); assertTrue(result.success(), result.toString()); }
    var entities = readEntities(dimension.entities().resolve("r.0.0.mca"));
    assertTrue(Nbt.equal(movedCow, entities.get(natural)), "未追蹤生物不刪除、不移動");
    assertEquals("first", entities.get(tracked).string("CustomName")); assertFalse(entities.containsKey(added));
    assertEquals(Set.of(tracked), PlayerTouchedEntities.read(layout.repository(DimensionId.OVERWORLD)));
    TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,position,4,0);
    assertTrue(worlds.commit(DimensionId.OVERWORLD,"base disjoint edit",AUTHOR,2).success());
    try(var ops = new WorldOperations(layout)) { var result = ops.merge("feature", new WorldOperations.MergeOptions(false,null,1,false,AUTHOR,CommitMetadata.Source.CLI)); assertTrue(result.success(),result.toString()); }
    assertEquals(Set.of(tracked,added), PlayerTouchedEntities.read(layout.repository(DimensionId.OVERWORLD)));
    assertTrue(Nbt.equal(movedCow, readEntities(dimension.entities().resolve("r.0.0.mca")).get(natural)));
    Path bare = temp.resolve("remote"); Files.createDirectories(bare);
    try(var ignored=new JGitStore(bare.resolve("minecraft.overworld.git"),true)) {}
    try(var remotes = new WorldRemotes(layout, Credentials.system())) { remotes.configure("add","origin",bare.toUri().toString(),false); assertTrue(remotes.push("origin",null,false,false,false,AUTHOR).success()); }
    Path clone = temp.resolve("clone"); WorldClone.cloneWorld(RemoteSpec.parse(bare.toUri().toString()),clone,Map.of(DimensionId.OVERWORLD,"base"),Set.of(DimensionId.OVERWORLD),Credentials.system(),WorldAssembler.Budget.defaults());
    var cloned = WorldLayout.discover(clone);
    assertEquals(Set.of(tracked,added),PlayerTouchedEntities.read(cloned.repository(DimensionId.OVERWORLD)));
    assertEquals(Set.of(tracked,added),readEntities(cloned.dimensions().get(DimensionId.OVERWORLD).entities().resolve("r.0.0.mca")).keySet());
  }
  private static void writeEntities(WorldLayout layout, ChunkPos position, List<Nbt.Compound> entities) throws Exception {
    RegionFile.update(layout.dimensions().get(DimensionId.OVERWORLD).entities().resolve(position.regionName()+".mca"),Map.of(position.regionIndex(),new Nbt.Compound().with("DataVersion",layout.dataVersion()).with("Position",new int[]{position.x(),position.z()}).with("Entities",new Nbt.ListTag(10,new ArrayList<>(entities)))),1);
  }
  private static Map<UUID,Nbt.Compound> readEntities(Path file) throws Exception {
    var result = new TreeMap<UUID,Nbt.Compound>(); try(var region = new RegionFile(file)) {
      for(int i=0;i<1024;i++) if(region.has(i)) for(Object entity : region.read(i).list("Entities").values()) { var nbt=(Nbt.Compound)entity; result.put(org.worldgit.core.normalize.EntityNormalizer.uuid(nbt),nbt); }
    } return result;
  }
  @Test void savedDataAndIgnorePreviewRespectDimensionOwnership() throws Exception {
    var layout=world("26.2"); var nether=layout.dimensions().get(NETHER); Path data=nether.directory().resolve("data/test/custom.dat");
    Files.createDirectories(data.getParent()); try(var out=new java.util.zip.GZIPOutputStream(Files.newOutputStream(data))) { out.write(Nbt.write(new Nbt.Compound().with("value",1))); }
    var worlds=new WorldRepositories(layout); assertTrue(worlds.initDimensions(Set.of(DimensionId.OVERWORLD,NETHER),"survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    assertTrue(layout.dimensionMetadata(NETHER).keySet().stream().anyMatch(name->name.contains("saved.")));
    byte[] before=Files.readAllBytes(layout.world().resolve("level.dat"));
    try(var ops=WorldOperations.inDimension(layout,NETHER)) { ops.createBranch("saved",null); }
    try(var out=new java.util.zip.GZIPOutputStream(Files.newOutputStream(data))) { out.write(Nbt.write(new Nbt.Compound().with("value",2))); }
    assertTrue(worlds.commit(NETHER,"saved-data",AUTHOR,2).success());
    try(var ops=WorldOperations.inDimension(layout,NETHER)) { assertTrue(ops.switchTo("saved",false,false,false,false).success()); }
    assertEquals(1,WorldLayout.readGzip(data).integer("value",0)); assertArrayEquals(before,Files.readAllBytes(layout.world().resolve("level.dat")));
  }
  @Test void savedDataRuntimeClocksAreExcludedButMeaningfulFieldsAndApplyClockRemain() throws Exception {
    var layout=world("26.2"); Path file=layout.dimensions().get(NETHER).directory().resolve("data/minecraft/raids.dat");
    Files.createDirectories(file.getParent());
    var original=new Nbt.Compound().with("DataVersion",4903).with("data",new Nbt.Compound().with("tick",120).with("next_id",7));
    try(var out=new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) { out.write(Nbt.write(original)); }
    var encoded=new ArrayList<>(SavedData.capture(layout.dimensions().get(NETHER).directory(),NETHER.directoryName()+".").values()).getFirst();
    var captured=Nbt.read(encoded);assertFalse(captured.compound("data").containsKey("tick"));assertEquals(7,captured.compound("data").integer("next_id",0));
    var restored=SavedData.materialize(file,captured,original);assertEquals(120,restored.compound("data").integer("tick",0));
  }
  @Test void missingDragonGatewaysCanonicalizeWithoutLosingProgress() throws Exception {
    var layout=world("1.21.11"); Path file=layout.world().resolve("level.dat");var root=WorldLayout.readGzip(file);
    root.compound("Data").put("WorldGenSettings",root.compound("Data").compound("WorldGenSettings").with("seed",1234L));
    root.compound("Data").put("DragonFight",new Nbt.Compound().with("DragonKilled",(byte)0));
    try(var out=new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) { out.write(Nbt.write(root)); }
    var before=Nbt.read(layout.worldMetadata().get("level.nbt"));assertEquals(20,before.compound("DragonFight").list("Gateways").values().size());
    root.compound("Data").put("DragonFight",before.compound("DragonFight"));
    try(var out=new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) { out.write(Nbt.write(root)); }
    assertArrayEquals(Nbt.write(before),layout.worldMetadata().get("level.nbt"));
    root.compound("Data").compound("DragonFight").put("Gateways",new Nbt.ListTag(3,List.of(2,1)));
    try(var out=new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) { out.write(Nbt.write(root)); }
    assertEquals(List.of(2,1),Nbt.read(layout.worldMetadata().get("level.nbt")).compound("DragonFight").list("Gateways").values());
  }
  @Test void ignorePreviewCountsActualTrackedBlocks() throws Exception {
    var layout=world("26.2");TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,new ChunkPos(0,0),4,0);
    assertTrue(new WorldRepositories(layout).init(null,"survival",WorldGitConfig.Track.ALL,AUTHOR).success());
    try(var repo=new DimensionRepository(layout.repository(DimensionId.OVERWORLD),DimensionId.OVERWORLD,false)) {
      var preview=IgnoreEditor.preview(repo,IgnoreEditor.parse("area 0 64 0 0 64 0\n"),EntitySemantics.OFFLINE);
      assertEquals(1,preview.blocks());assertFalse(preview.examples().isEmpty());
    }
  }
  @Test void touchedEntityConflictSelectionKeepsTouchSetAndNaturalEntity() throws Exception {
    var layout=world("26.2");var worlds=new WorldRepositories(layout);var position=new ChunkPos(0,0);
    assertTrue(worlds.init(null,"creative",WorldGitConfig.Track.ALL,AUTHOR).success());
    UUID id=UUID.randomUUID(),natural=UUID.randomUUID();var cow=TestWorlds.entity(natural,3,false).with("id","minecraft:cow");
    var base=TestWorlds.entity(id,1,true).with("CustomName","base");
    writeEntities(layout,position,List.of(base,cow));PlayerTouchedEntities.touch(layout.repository(DimensionId.OVERWORLD),base);
    assertTrue(worlds.commit(null,"base",AUTHOR,2).success());
    try(var ops=new WorldOperations(layout)) { ops.createBranch("theirs",null); }
    writeEntities(layout,position,List.of(base.with("CustomName","ours"),cow));assertTrue(worlds.commit(null,"ours",AUTHOR,2).success());
    try(var ops=new WorldOperations(layout)) { assertTrue(ops.switchTo("theirs",false,false,false,false).success()); }
    writeEntities(layout,position,List.of(base.with("CustomName","theirs"),cow));assertTrue(worlds.commit(null,"theirs",AUTHOR,2).success());
    try(var ops=new WorldOperations(layout)) {
      assertTrue(ops.switchTo("main",false,false,false,false).success());
      assertEquals("MERGING",ops.merge("theirs",new WorldOperations.MergeOptions(false,null,1,false,AUTHOR,CommitMetadata.Source.CLI)).state());
      assertEquals(1,ops.merging().remaining());int region=ops.merging().regions().getFirst().id();
      var result=ops.selectRegion(region,org.worldgit.core.merge.MergeReport.Choice.THEIRS,true,false);assertTrue(result.success(),result.toString());
      assertEquals(0,ops.merging().remaining());assertTrue(ops.continueMerge(AUTHOR,CommitMetadata.Source.CLI,false).success());
    }
    assertEquals(Set.of(id),PlayerTouchedEntities.read(layout.repository(DimensionId.OVERWORLD)));
    var entities=readEntities(layout.dimensions().get(DimensionId.OVERWORLD).entities().resolve("r.0.0.mca"));
    assertEquals("theirs",entities.get(id).string("CustomName"));assertTrue(Nbt.equal(cow,entities.get(natural)));
  }
}
