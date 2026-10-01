package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.TreeEditor;

class WorldOperationsTest {
  @TempDir Path temp;
  static final CommitMetadata.Identity AUTHOR=new CommitMetadata.Identity("test","test@example.com");
  private WorldLayout init(String version) throws Exception {
    Path target=temp.resolve(version);TestWorlds.copy(TestWorlds.fixture(version),target);
    var layout=WorldLayout.discover(target);assertTrue(new WorldRepositories(layout).init(null,"creative",WorldGitConfig.Track.ALL,AUTHOR).success());return layout;
  }
  @Test void branchesDetachedStashResetAndTwoVersionRoundTrip() throws Exception {
    for(String version:List.of("1.21.11","26.2")) {
      var layout=init(version);var worlds=new WorldRepositories(layout);
      try(var ops=new WorldOperations(layout)) { ops.createBranch("A",null); }
      TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,new ChunkPos(0,0),9,0);
      assertTrue(worlds.commit(null,"B",AUTHOR,2).success());
      String b;
      try(var repo=new DimensionRepository(worlds.tracked().get(DimensionId.OVERWORLD),DimensionId.OVERWORLD,false)) { b=repo.refs().head(); }
      try(var ops=new WorldOperations(layout)) {
        ops.createBranch("B",null);
        assertEquals(WorldOperations.State.DRY_RUN,ops.switchTo("A",false,false,true,false).state());
        assertTrue(ops.switchTo("A",false,false,false,false).success());
        assertTrue(ops.verify("A",null,Scope.all(),true).success());
        assertTrue(ops.switchTo("B",false,false,false,false).success());
        assertTrue(ops.verify("B",null,Scope.all(),true).success());
      }
      TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,new ChunkPos(0,0),9,0);
      try(var ops=new WorldOperations(layout)) {
        assertThrows(IOException.class,()->ops.switchTo("A",false,false,false,false));
        assertTrue(ops.stashPush("dirty",false).success());assertEquals(1,ops.stashes().size());
        assertTrue(ops.verify("B",null,Scope.all(),true).success());
        assertTrue(ops.stashPop(0,false).success());assertTrue(ops.stashes().isEmpty());
        assertFalse(ops.verify("B",null,Scope.all(),true).success());
        assertTrue(ops.resetHard(null,false,false).success());
        assertThrows(IOException.class,()->ops.resetHard("A",false,false));
        assertTrue(ops.resetHard("A",true,false).success());
        assertTrue(ops.verify("A",null,Scope.all(),true).success());
        assertTrue(ops.switchTo(b,false,true,false,false).success());
        assertTrue(ops.branches().stream().noneMatch(WorldOperations.Branch::current));
        ops.createBranch("detached-saved",null);
        assertTrue(ops.switchTo("main",false,false,false,false).success());
        ops.createBranch("temporary",null);ops.deleteBranch("temporary");
        assertEquals(3,ops.branches().getFirst().commits().size());
      }
    }
  }
  @Test void sectionBoundaryPreservesRawOutsideAndHead() throws Exception {
    var layout=init("26.2");var pos=new ChunkPos(0,0);
    TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,pos,9,0);
    TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,pos,9,17);
    var path=layout.dimensions().get(DimensionId.OVERWORLD).region().resolve("r.0.0.mca");
    Nbt.Compound before;try(var r=new RegionFile(path)) { before=r.read(0); }
    var beforeSnapshot=new ChunkNormalizer(IgnoreRules.none(),EntitySemantics.OFFLINE).normalize(pos,before,List.of());
    String head;
    try(var repo=new DimensionRepository(layout.repositoryRoot().resolve(DimensionId.OVERWORLD.directoryName()),DimensionId.OVERWORLD,false)) { head=repo.refs().head(); }
    try(var ops=new WorldOperations(layout)) {
      assertTrue(ops.restore("HEAD",DimensionId.OVERWORLD,Scope.box(0,144,0,0,144,0),false,false).success());
      assertFalse(ops.verify("HEAD",DimensionId.OVERWORLD,Scope.all(),true).success());
      assertTrue(ops.verify("HEAD",DimensionId.OVERWORLD,Scope.box(0,144,0,0,144,0),false).success());
    }
    Nbt.Compound after;try(var r=new RegionFile(path)) { after=r.read(0); }
    var afterSnapshot=new ChunkNormalizer(IgnoreRules.none(),EntitySemantics.OFFLINE).normalize(pos,after,List.of());
    for(var e:beforeSnapshot.sections().entrySet()) for(int i=0;i<4096;i++) if(e.getKey()!=9 || i!=0) assertEquals(e.getValue().block(i),afterSnapshot.sections().getOrDefault(e.getKey(),Section.air()).block(i));
    try(var repo=new DimensionRepository(layout.repositoryRoot().resolve(DimensionId.OVERWORLD.directoryName()),DimensionId.OVERWORLD,false)) { assertEquals(head,repo.refs().head()); }
    assertEquals(0,after.integer("isLightOn",1));assertFalse(after.containsKey("Heightmaps"));
  }
  @Test void partialLeavesHeadsAndCanRecover() throws Exception {
    var layout=init("26.2");
    try(var ops=new WorldOperations(layout)) { ops.createBranch("A",null); }
    TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,new ChunkPos(0,0),9,0);
    assertTrue(new WorldRepositories(layout).commit(null,"B",AUTHOR,2).success());
    var count=new AtomicInteger();var writer=new OfflineApplier(layout);
    try(var ops=new WorldOperations(layout,(plan,lock)-> { if(count.incrementAndGet()==2) throw new IOException("injected second dimension failure");writer.apply(plan,lock); })) {
      var result=ops.switchTo("A",false,false,false,false);
      assertEquals(WorldOperations.State.PARTIAL,result.state());assertEquals("PARTIAL",ops.journal().get("state"));
      assertTrue(ops.branches().stream().anyMatch(b->b.name().equals("main") && b.current()));
      assertThrows(IOException.class,()->ops.createBranch("blocked",null));
    }
    assertFalse(new WorldRepositories(layout).commit(null,"must reject",AUTHOR,2).success());
    try(var ops=new WorldOperations(layout)) { assertTrue(ops.switchTo("A",false,true,false,false).success());assertTrue(ops.verify("A",null,Scope.all(),true).success()); }
  }
  @Test void sessionLockPreventsAllMutatingWork() throws Exception {
    var layout=init("26.2");try(var lock=WorldSessionLock.acquire(layout)) { assertThrows(IOException.class,()->new WorldOperations(layout)); }
  }
  @Test void hashPairsDimensionThatChangedWhileAnchorWasUnchanged() throws Exception {
    var layout=init("26.2");var worlds=new WorldRepositories(layout);
    var nether=new DimensionId("minecraft:the_nether");
    try(var region=new RegionFile(RegionFile.list(layout.dimensions().get(nether).region()).getFirst())) {
      for(int i=0;i<1024;i++) if(region.has(i)) {
        int y=region.read(i).list("sections").values().stream().map(Nbt.Compound.class::cast)
            .filter(s->!s.compound("block_states").list("palette").values().isEmpty()).findFirst().orElseThrow().integer("Y",0);
        TestWorlds.oneBlock(layout,nether,region.pos(i),y,0);break;
      }
    }
    assertTrue(worlds.commit(null,"nether only",AUTHOR,2).success());
    String netherHead;
    try(var repo=new DimensionRepository(worlds.tracked().get(nether),nether,false)) { netherHead=repo.refs().head(); }
    TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,new ChunkPos(0,0),9,0);
    assertTrue(worlds.commit(null,"overworld only",AUTHOR,2).success());
    String hash;
    try(var repo=new DimensionRepository(worlds.tracked().get(DimensionId.OVERWORLD),DimensionId.OVERWORLD,false)) { hash=repo.refs().head(); }
    try(var ops=new WorldOperations(layout)) {
      ops.createBranch("hash-group",hash);
      assertEquals(netherHead,ops.branches().stream().filter(b->b.name().equals("hash-group")).findFirst().orElseThrow().commits().get(nether));
    }
  }
  @Test void untrackedSurvivesAutoCommitAndStashIncludesNewChunks() throws Exception {
    var layout=init("26.2");var worlds=new WorldRepositories(layout);var pos=new ChunkPos(5,5);
    try(var ops=new WorldOperations(layout)) { ops.createBranch("A",null); }
    var secs=new TreeMap<Integer,ApplyPlan.SectionOp>();
    secs.put(9,new ApplyPlan.SectionOp(9,SnapshotCodec.section(new Section(Collections.nCopies(4096,new BlockState("minecraft:gold_block")),Map.of())),null));
    var bios=new TreeMap<Integer,ApplyPlan.BiomeOp>();bios.put(9,new ApplyPlan.BiomeOp(9,Collections.nCopies(64,"minecraft:plains")));
    var op=new ApplyPlan.ChunkOp(pos,false,secs,bios,true,null,true,null);
    Path path=layout.dimensions().get(DimensionId.OVERWORLD).region().resolve("r.0.0.mca");
    RegionFile.update(path,Map.of(pos.regionIndex(),ChunkNbt.apply(null,op,4903,IgnoreRules.none())),2);
    assertTrue(worlds.commit(null,"new chunk",AUTHOR,2).success());
    try(var ops=new WorldOperations(layout)) { assertTrue(ops.switchTo("A",false,false,false,false).success()); }
    assertTrue(worlds.status(null,2,true).dimensions().get(DimensionId.OVERWORLD).value().diff().empty());
    TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,new ChunkPos(0,0),9,0);
    Path repoPath=worlds.tracked().get(DimensionId.OVERWORLD);
    try(var repo=new DimensionRepository(repoPath,DimensionId.OVERWORLD,false);
        var source=new OfflineSnapshotSource(layout,layout.dimensions().get(DimensionId.OVERWORLD))) {
      var auto=new CommitMetadata(AUTHOR,AUTHOR,"auto",java.time.Instant.now(),4903,DimensionId.OVERWORLD,CommitMetadata.Source.CLI,true,UUID.randomUUID(),List.of());
      assertTrue(repo.commit(source,worlds.manifest(),auto,2).changed());
      assertNull(TreeEditor.find(repo.objects(),repo.refs().readCommit(repo.refs().head()).tree(),pos.treePath()));
      assertTrue(WorldOperations.untracked(repoPath).contains(pos));
      var explicit=new CommitMetadata(AUTHOR,AUTHOR,"explicit",java.time.Instant.now(),4903,DimensionId.OVERWORLD,CommitMetadata.Source.CLI,false,UUID.randomUUID(),List.of());
      assertFalse(repo.commit(source,worlds.manifest(),explicit,2,s->false).changed());
      assertTrue(WorldOperations.untracked(repoPath).contains(pos));
    }
    // 即使工作區只剩保留的 untracked，push 應保存它並清除，再由 pop 放回。
    try(var ops=new WorldOperations(layout)) {
      assertTrue(ops.stashPush("new chunk",false).success());
      assertEquals(1,ops.stashes().size());
      try(var file=new RegionFile(path)) { assertFalse(file.has(pos.regionIndex())); }
      RegionFile.update(path,Map.of(pos.regionIndex(),ChunkNbt.apply(null,op,4903,IgnoreRules.none())),3);
      OperationState.write(repoPath.resolve("untracked.yml"),Map.of("chunks",List.of("5,5")));
      assertThrows(IOException.class,()->ops.stashPop(0,false));
      try(var file=new RegionWriter(path)) { file.delete(pos.regionIndex()); }
      Files.delete(repoPath.resolve("untracked.yml"));
      assertTrue(ops.stashPop(0,false).success());
      try(var file=new RegionFile(path)) { assertTrue(file.has(pos.regionIndex())); }
    }
  }
}
