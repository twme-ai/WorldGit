package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.EntitySnapshot;

class EntitySpawnDataTest {
  @Test void omittedNestedPassengerPositionsUseParentWithoutChangingSnapshot() {
    var nested=new Nbt.Compound().with("id","minecraft:chicken");
    var passenger=new Nbt.Compound().with("id","minecraft:pig").with("Passengers",new Nbt.ListTag(10,List.of(nested)));
    var data=new Nbt.Compound().with("Pos",new Nbt.ListTag(6,List.of(184d,70d,-184d)))
        .with("Passengers",new Nbt.ListTag(10,List.of(passenger)));
    var snapshot=new EntitySnapshot(UUID.randomUUID(),data);
    var prepared=EntitySpawnData.prepare(snapshot);
    var p=(Nbt.Compound)prepared.list("Passengers").values().getFirst();
    var n=(Nbt.Compound)p.list("Passengers").values().getFirst();
    assertEquals(prepared.list("Pos").values(),p.list("Pos").values());
    assertEquals(prepared.list("Pos").values(),n.list("Pos").values());
    assertFalse(((Nbt.Compound)snapshot.data().list("Passengers").values().getFirst()).containsKey("Pos"));
  }
}
