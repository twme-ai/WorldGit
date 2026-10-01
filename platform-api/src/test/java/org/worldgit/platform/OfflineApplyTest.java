package org.worldgit.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.SnapshotCodec;

class OfflineApplyTest {
  @TempDir Path temp;
  @Test void applyUsesOwnedSessionLockAndRejectsExpectedTreeThatItCannotCheck() throws Exception {
    var fixture=Path.of(System.getProperty("worldgit.projectRoot"),"core/src/test/resources/fixtures/26.2");
    try(var files=Files.walk(fixture)) { for(var p:files.toList()) {
      Path dest=temp.resolve(fixture.relativize(p));if(Files.isDirectory(p)) Files.createDirectories(dest);else Files.copy(p,dest);
    } }
    var layout=WorldLayout.discover(temp);var dim=layout.dimensions().get(DimensionId.OVERWORLD);var pos=new ChunkPos(0,0);
    var sections=new TreeMap<Integer,ApplyPlan.SectionOp>();
    sections.put(9,new ApplyPlan.SectionOp(9,SnapshotCodec.section(new Section(Collections.nCopies(4096,new BlockState("minecraft:gold_block")),Map.of())),null));
    var op=new ApplyPlan.ChunkOp(pos,false,sections,new TreeMap<>(),false,null,false,null);
    var plan=new ApplyPlan(DimensionId.OVERWORLD,null,null,4903,Scope.all(),List.of(op),List.of(),Map.of(),List.of());
    try(var world=new OfflineWorld(layout,dim,s->{})) {
      try(var lock=world.lockEdits(List.of(pos),"test")) { world.apply(plan,ApplyBudget.DEFAULT).toCompletableFuture().get(); }
      try(var r=new RegionFile(dim.region().resolve("r.0.0.mca"))) {
        var raw=r.read(0);assertEquals(0,raw.integer("isLightOn",1));
        var section=raw.list("sections").values().stream().map(Nbt.Compound.class::cast).filter(s->s.integer("Y",0)==9).findFirst().orElseThrow();
        assertEquals("minecraft:gold_block",ChunkNbt.blocks(section).block(0).name());
      }
      assertThrows(ExecutionException.class,()->world.apply(new ChunkPatch(pos,"0000000000000000000000000000000000000000",null,true)).toCompletableFuture().get());
      try(var serverLock=SessionGuard.acquire(layout)) { assertThrows(ExecutionException.class,()->world.apply(plan,ApplyBudget.DEFAULT).toCompletableFuture().get()); }
    }
  }
}
