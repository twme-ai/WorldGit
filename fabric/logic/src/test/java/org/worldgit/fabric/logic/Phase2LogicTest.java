package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.*;
import org.worldgit.core.model.*;

class Phase2LogicTest {
  @Test void scopedArgumentsAndConflictingOptions() {
    var flags=Set.of("--dry-run","--force","--stash");
    var values=Map.of("--chunks",1,"--box",6);
    var radius=OperationArgs.parse("A --chunks=0 --dry-run",flags,values);
    assertTrue(radius.flag("--dry-run"));
    assertEquals(Set.of(new ChunkPos(-1,2)),radius.scope(-1,2).chunks());
    var box=OperationArgs.parse("--box 3 -60 3 3 -60 3 A",flags,values).scope(0,0);
    assertTrue(box.containsBlock(3,-60,3)); assertFalse(box.containsBlock(4,-60,3));
    for(String bad:List.of("A --force --stash","A --chunks 1 --box 0 0 0 1 1 1",
        "A --box 0 0","A --dry-run=false","A --chunks=","A --chunks 1 --chunks 2","A --unknown"))
      assertThrows(IllegalArgumentException.class,()->OperationArgs.parse(bad,flags,values),bad);
    assertThrows(IllegalArgumentException.class,()->OperationArgs.parse("A --chunks 257",flags,values).scope(0,0));
  }

  @Test void entityBatchesKeepOrderRulesAndBoundTickets() {
    var entities=new ArrayList<ApplyPlan.EntityOp>();
    for(int i=0;i<64;i++) {
      var uuid=new UUID(0,i+1);
      var nbt=new Nbt.Compound().with("id","minecraft:armor_stand")
          .with("Pos",new Nbt.ListTag(6,List.of(i*16.0,64.0,0.0)));
      entities.add(new ApplyPlan.EntityOp(uuid,new ChunkPos(i,1),new EntitySnapshot(uuid,nbt)));
    }
    var plan=new ApplyPlan(DimensionId.OVERWORLD,null,null,4903,Scope.all(),List.of(),entities,Map.of(),List.of())
        .withRules("field minecraft:armor_stand CustomName\n");
    var batches=LiveBatches.limitTickets(plan,16);
    assertEquals(8,batches.size());
    assertEquals(entities,batches.stream().flatMap(p->p.entities().stream()).toList());
    for(var batch:batches) {
      var chunks=new HashSet<ChunkPos>();
      for(var entity:batch.entities()) {chunks.add(entity.hint());chunks.add(entity.targetChunk());}
      assertTrue(chunks.size()<=16); assertEquals(plan.ignoreRules(),batch.ignoreRules());
    }
    assertThrows(IllegalArgumentException.class,()->LiveBatches.limitTickets(plan,1));
  }
}
