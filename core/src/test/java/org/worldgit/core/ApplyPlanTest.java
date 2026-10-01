package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

class ApplyPlanTest {
  @TempDir Path temp;
  private String tree(JGitStore store,ChunkSnapshot snapshot) throws Exception {
    var editor=new TreeEditor(store,null); editor.replaceTree(snapshot.pos().treePath(),SnapshotCodec.chunkFiles(snapshot));
    String id=editor.write();store.flush();return id;
  }
  private ChunkSnapshot snapshot(BlockState block,byte[] be) {
    var blocks=new ArrayList<>(Collections.nCopies(4096,BlockState.AIR));blocks.set(0,block);blocks.set(17,block);
    return new ChunkSnapshot(new ChunkPos(-1,0),4903,new TreeMap<>(Map.of(4,new Section(blocks,be==null ? Map.of() : Map.of(17,be)))),new TreeMap<>(),List.of(),null,null);
  }
  @Test void masksSerializationShortCircuitAndBatches() throws Exception {
    try(var store=new JGitStore(temp.resolve("repo"),true)) {
      String a=tree(store,snapshot(new BlockState("minecraft:stone"),null));
      byte[] be=Nbt.write(new Nbt.Compound().with("id","minecraft:chest").with("test",1));
      String b=tree(store,snapshot(new BlockState("minecraft:gold_block"),be));
      var options=new ApplyPlanner.Options(null,null,null,2,false,false,4903);
      assertTrue(ApplyPlanner.plan(store,DimensionId.OVERWORLD,a,a,Scope.all(),options).empty());
      var plan=ApplyPlanner.plan(store,DimensionId.OVERWORLD,a,b,Scope.box(-15,64,1,-15,64,1),options);
      assertEquals(1,plan.stats().sections());
      var op=plan.chunks().get(new ChunkPos(-1,0)).sections().get(4);
      assertEquals(1,op.coveredCount());
      var merged=op.mergeInto(snapshot(new BlockState("minecraft:stone"),null).sections().get(4));
      assertEquals("minecraft:stone",merged.block(0).name());
      assertEquals("minecraft:gold_block",merged.block(17).name());
      assertArrayEquals(be,merged.blockEntities().get(17));
      var decoded=ApplyPlan.fromBytes(plan.toBytes());
      assertEquals(plan.stats(),decoded.stats());
      assertEquals(1,decoded.chunks().get(new ChunkPos(-1,0)).sections().get(4).coveredCount());
      var full=ApplyPlanner.plan(store,DimensionId.OVERWORLD,a,b,Scope.all(),options);
      assertEquals(1,full.only(Scope.box(-15,64,1,-15,64,1)).chunks().get(new ChunkPos(-1,0)).sections().get(4).coveredCount());
      assertTrue(full.only(Scope.box(0,64,0,0,64,0)).empty());
      var sections=new TreeMap<Integer,ApplyPlan.SectionOp>();
      for(int y=0;y<25;y++)sections.put(y,new ApplyPlan.SectionOp(y,null,null));
      var many=new ApplyPlan(DimensionId.OVERWORLD,a,b,4903,Scope.all(),List.of(new ApplyPlan.ChunkOp(new ChunkPos(0,0),false,sections,new TreeMap<>(),true,null,true,null)),List.of(),Map.of(),List.of());
      assertEquals(4,many.batches(8).size());
      assertTrue(many.batches(8).stream().allMatch(p->p.stats().sections()<=8));
      assertEquals(1,many.batches(8).stream().filter(p->p.chunks().values().stream().anyMatch(ApplyPlan.ChunkOp::setTicks)).count());
    }
  }
  @Test void untrackedPolicyAndVersions() throws Exception {
    try(var store=new JGitStore(temp.resolve("repo"),true)) {
      String a=tree(store,snapshot(new BlockState("minecraft:stone"),null));
      var keep=ApplyPlanner.plan(store,DimensionId.OVERWORLD,a,null,Scope.all(),new ApplyPlanner.Options(null,null,null,2,false,false,4903));
      assertTrue(keep.empty());assertEquals(1,keep.stats().untrackedKept());
      var delete=ApplyPlanner.plan(store,DimensionId.OVERWORLD,a,null,Scope.all(),new ApplyPlanner.Options(null,null,null,2,true,false,4903));
      assertEquals(1,delete.stats().chunkDeletes());
      assertThrows(java.io.IOException.class,()->DataVersions.requireSame(4903,4671));
      assertThrows(java.io.IOException.class,()->DataVersions.requireSame(4671,4903));
    }
  }
  @Test void entityOutsideBoxIsNeverSpawnedOutside() throws Exception {
    var id=UUID.randomUUID(); var old=EntityNormalizer.normalize(TestWorlds.entity(id,0,true),IgnoreRules.none());
    var target=EntityNormalizer.normalize(TestWorlds.entity(id,20,true),IgnoreRules.none());
    try(var store=new JGitStore(temp.resolve("repo"),true)) {
      var editor=new TreeEditor(store,null);
      editor.replaceTree(new ChunkPos(0,0).treePath(),SnapshotCodec.chunkFiles(new ChunkSnapshot(new ChunkPos(0,0),4903,new TreeMap<>(),new TreeMap<>(),List.of(old),null,null)));
      String a=editor.write();store.flush();
      editor=new TreeEditor(store,null);
      editor.replaceTree(new ChunkPos(1,0).treePath(),SnapshotCodec.chunkFiles(new ChunkSnapshot(new ChunkPos(1,0),4903,new TreeMap<>(),new TreeMap<>(),List.of(target),null,null)));
      // 保留來源 chunk 的空樹標記，避免被預設 untracked 策略保留。
      editor.putBlob(new ChunkPos(0,0).treePath()+"/biomes.bin",SnapshotCodec.biomes(Map.of(4,Collections.nCopies(64,"minecraft:plains"))));
      String b=editor.write();store.flush();
      var plan=ApplyPlanner.plan(store,DimensionId.OVERWORLD,a,b,Scope.box(0,69,0,1,71,1),new ApplyPlanner.Options(null,null,null,0,false,false,4903));
      assertEquals(1,plan.entities().size());assertNull(plan.entities().getFirst().target());
      var full=ApplyPlanner.plan(store,DimensionId.OVERWORLD,a,b,Scope.all(),new ApplyPlanner.Options(null,null,null,0,false,false,4903));
      var clipped=full.only(Scope.box(0,69,0,1,71,1));
      assertEquals(1,clipped.entities().size());assertNull(clipped.entities().getFirst().target());
      var removal=ApplyPlanner.plan(store,DimensionId.OVERWORLD,a,null,Scope.all(),new ApplyPlanner.Options(null,null,null,0,true,false,4903));
      assertEquals(1,ApplyPlan.fromBytes(removal.toBytes()).only(Scope.box(0,69,0,1,71,1)).entities().size());
    }
  }
}
