package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;

class LegacyEquipmentRemnantsTest {
  @Test void legacyItemsUseTheSameDfuAndOnlyEquivalentArraysAreRemovedIncludingPassengers() throws Exception {
    var oldItem=new Nbt.Compound().with("id","minecraft:diamond_sword").with("Count",(byte)1)
        .with("tag",new Nbt.Compound().with("opaque",new Nbt.Compound().with("keep",42)));
    var modern=new Nbt.Compound().with("id","minecraft:diamond_sword").with("count",1)
        .with("components",new Nbt.Compound().with("minecraft:custom_data",new Nbt.Compound().with("opaque",new Nbt.Compound().with("keep",42))));
    var stand=new Nbt.Compound().with("id","minecraft:armor_stand")
        .with("HandItems",new Nbt.ListTag(10,List.of(oldItem,new Nbt.Compound())))
        .with("equipment",new Nbt.Compound().with("mainhand",modern));
    int[] calls={0};
    NbtUpgrader dfu=(type,item,from,to)->{calls[0]++;assertEquals("ITEM_STACK",type);assertEquals(3465,from);assertEquals(5023,to);assertArrayEquals(Nbt.write(oldItem),Nbt.write(item));return Nbt.copy(modern);};
    var root=new Nbt.Compound().with("id","minecraft:oak_boat").with("Passengers",new Nbt.ListTag(10,List.of(stand)));
    var result=NativeNbtForms.dfuEntity(root,3465,5023,dfu);
    var passenger=(Nbt.Compound)result.list("Passengers").values().getFirst();
    assertFalse(passenger.containsKey("HandItems"));assertArrayEquals(Nbt.write(modern),Nbt.write(passenger.compound("equipment").compound("mainhand")));
    assertTrue(stand.containsKey("HandItems"));assertEquals(1,calls[0]);
    var mismatch=Nbt.copy(stand);mismatch.compound("equipment").compound("mainhand").put("count",2);
    assertArrayEquals(Nbt.write(mismatch),Nbt.write(NativeNbtForms.dfuEntity(mismatch,3465,5023,dfu)));
    assertSame(stand,NativeNbtForms.dfuEntity(stand,3839,5023,dfu));assertEquals(2,calls[0]);
    var custom=Nbt.copy(stand);custom.put("id","custom:armor_stand");
    assertSame(custom,NativeNbtForms.dfuEntity(custom,3465,5023,dfu));assertEquals(2,calls[0]);
  }
}
