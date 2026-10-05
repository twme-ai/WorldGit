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

class OfflineApplierTest {
  @TempDir Path temp;
  @Test void poiRemovalEntityDedupWithinDimensionPreservesOtherDimension() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"),temp);var layout=WorldLayout.discover(temp);var dim=layout.dimensions().get(DimensionId.OVERWORLD);
    UUID uuid=UUID.randomUUID(),passengerId=UUID.randomUUID(),other=UUID.randomUUID();
    var original=TestWorlds.entity(uuid,-1,true);
    var passenger=TestWorlds.entity(passengerId,-1,true);
    original.put("Passengers",new Nbt.ListTag(10,List.of(passenger)));
    var moved=TestWorlds.entity(uuid,17,true);moved.put("Passengers",new Nbt.ListTag(10,List.of(passenger)));
    for(var pos:List.of(new ChunkPos(-1,0),new ChunkPos(3,0)))
      RegionFile.update(dim.entities().resolve(pos.regionName()+".mca"),Map.of(pos.regionIndex(),new Nbt.Compound().with("DataVersion",4903).with("Position",new int[]{pos.x(),pos.z()}).with("Entities",new Nbt.ListTag(10,List.of(original,TestWorlds.entity(other,pos.x()*16+1,true))))),1);
    var nether=layout.dimensions().get(new DimensionId("minecraft:the_nether"));
    RegionFile.update(nether.entities().resolve("r.0.0.mca"),Map.of(0,new Nbt.Compound().with("DataVersion",4903).with("Position",new int[]{0,0}).with("Entities",new Nbt.ListTag(10,List.of(original)))),1);
    var pos=new ChunkPos(0,0);Path poi=dim.poi().resolve("r.0.0.mca");
    RegionFile.update(poi,Map.of(0,new Nbt.Compound().with("Sections",new Nbt.Compound()),1,new Nbt.Compound().with("outside",1)),1);
    var sections=new TreeMap<Integer,ApplyPlan.SectionOp>();
    long[] mask=new long[64];mask[0]=1;
    sections.put(9,new ApplyPlan.SectionOp(9,SnapshotCodec.section(new Section(Collections.nCopies(4096,new BlockState("minecraft:gold_block")),Map.of())),mask));
    var target=EntityNormalizer.normalize(moved,IgnoreRules.none());
    var plan=new ApplyPlan(DimensionId.OVERWORLD,null,null,4903,Scope.all(),List.of(new ApplyPlan.ChunkOp(pos,false,sections,new TreeMap<>(),false,null,false,null)),List.of(new ApplyPlan.EntityOp(uuid,new ChunkPos(-1,0),target)),Map.of(),List.of());
    new OfflineApplier(layout).apply(plan);
    try(var r=new RegionFile(poi)) { assertFalse(r.has(0));assertTrue(r.has(1)); }
    int found=0,passengers=0;
    for(var scannedDimension:layout.dimensions().values()) for(Path path:RegionFile.list(scannedDimension.entities())) try(var r=new RegionFile(path)) {
      for(int i=0;i<1024;i++) if(r.has(i)) for(var value:r.read(i).list("Entities").values()) {
        var e=(Nbt.Compound)value;
        if(EntityNormalizer.uuid(e).equals(uuid)) { found++; if(scannedDimension.id().equals(DimensionId.OVERWORLD)) assertEquals(new ChunkPos(1,0),r.pos(i)); else { assertEquals(nether.id(),scannedDimension.id()); assertEquals(new ChunkPos(0,0),r.pos(i)); assertTrue(Nbt.equal(original,e)); } }
        for(var p:e.list("Passengers").values()) if(EntityNormalizer.uuid((Nbt.Compound)p).equals(passengerId)) passengers++;
      }
    }
    assertEquals(2,found);assertEquals(2,passengers);
  }
  @Test void groupBarrierMovesEntityIntoEarlierDimensionBeforeSourceRemoval() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"),temp);var layout=WorldLayout.discover(temp);
    var nether=new DimensionId("minecraft:the_nether");var uuid=UUID.randomUUID();var pos=new ChunkPos(0,0);
    var entity=TestWorlds.entity(uuid,1,true);entity.put("Fire",(short)99);
    RegionFile.update(layout.dimensions().get(nether).entities().resolve("r.0.0.mca"),Map.of(0,new Nbt.Compound().with("DataVersion",4903).with("Position",new int[]{0,0}).with("Entities",new Nbt.ListTag(10,List.of(entity)))),1);
    var target=EntityNormalizer.normalize(entity,IgnoreRules.none());
    var put=new ApplyPlan(DimensionId.OVERWORLD,null,null,4903,Scope.all(),List.of(),List.of(new ApplyPlan.EntityOp(uuid,null,target)),Map.of(),List.of());
    var remove=new ApplyPlan(nether,null,null,4903,Scope.all(),List.of(),List.of(new ApplyPlan.EntityOp(uuid,pos,null)),Map.of(),List.of());
    try(var lock=WorldSessionLock.acquire(layout)) { new OfflineApplier(layout).applyAll(List.of(put,remove),lock); }
    int count=0;
    for(var dim:layout.dimensions().values()) for(Path path:RegionFile.list(dim.entities())) try(var r=new RegionFile(path)) {
      for(int i=0;i<1024;i++) if(r.has(i)) for(var value:r.read(i).list("Entities").values())
        if(EntityNormalizer.uuid((Nbt.Compound)value).equals(uuid)) { count++;assertEquals(DimensionId.OVERWORLD,dim.id()); }
    }
    assertEquals(1,count);
  }
  @Test void worldSettingsRestoreAndTransientValuesStay() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("1.21.11"),temp);var layout=WorldLayout.discover(temp);
    var metadata=layout.worldMetadata();var level=Nbt.read(metadata.get("level.nbt"));level.put("LevelName","restored-name");
    long time=((Number)WorldLayout.readGzip(layout.world().resolve("level.dat")).compound("Data").getOrDefault("Time",0L)).longValue();
    var plan=new ApplyPlan(DimensionId.OVERWORLD,null,null,4671,Scope.all(),List.of(),List.of(),Map.of("level.nbt",Nbt.write(level)),List.of());
    new OfflineApplier(layout).apply(plan);
    var after=WorldLayout.readGzip(layout.world().resolve("level.dat")).compound("Data");
    assertEquals("restored-name",after.string("LevelName"));assertEquals(time,((Number)after.getOrDefault("Time",0L)).longValue());
  }
  @Test void deletingChunkPreservesEntitiesExcludedByVersionTag() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"),temp);var layout=WorldLayout.discover(temp);
    var dim=layout.dimensions().get(DimensionId.OVERWORLD);var uuid=UUID.randomUUID();
    var entity=TestWorlds.entity(uuid,1,true);entity.put("id","minecraft:skeleton");
    RegionFile.update(dim.entities().resolve("r.0.0.mca"),Map.of(0,new Nbt.Compound().with("DataVersion",4903).with("Position",new int[]{0,0}).with("Entities",new Nbt.ListTag(10,List.of(entity)))),1);
    var deletion=new ApplyPlan.ChunkOp(new ChunkPos(0,0),true,new TreeMap<>(),new TreeMap<>(),false,null,false,null);
    var plan=new ApplyPlan(DimensionId.OVERWORLD,null,null,4903,Scope.all(),List.of(deletion),List.of(),Map.of(),List.of()).withRules("entity #minecraft:skeletons\n");
    new OfflineApplier(layout).apply(plan);
    try(var r=new RegionFile(dim.entities().resolve("r.0.0.mca"))) {
      assertTrue(r.read(0).list("Entities").values().stream().anyMatch(e->EntityNormalizer.uuid((Nbt.Compound)e).equals(uuid)));
    }
    try(var r=new RegionFile(dim.region().resolve("r.0.0.mca"))) { assertFalse(r.has(0)); }
  }
}
