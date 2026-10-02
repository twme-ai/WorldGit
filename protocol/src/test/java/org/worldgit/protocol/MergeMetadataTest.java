package org.worldgit.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.apply.BlockBox;
import org.worldgit.core.merge.MergeReport.*;
import org.worldgit.core.model.DimensionId;

class MergeMetadataTest {
  @Test void optionalMetadataSurvivesFragmentsAndOldConstructorStillWorks() throws Exception {
    var regions=new ArrayList<Region>();
    for(int i=1;i<=1000;i++) regions.add(new Region(i,DimensionId.OVERWORLD,new BlockBox(i,64,0,i,64,0),1,
        List.of("Alice "+i),List.of("Bob "+i),i==1,Choice.THEIRS,false,List.of()));
    var hints=List.of(new Cell(15,64,0),new Cell(16,64,0));
    var packets=MergeProtocol.regions(9,DimensionId.OVERWORLD,regions,hints); assertTrue(packets.size()>1);
    var assembler=new MergeProtocol.Assembler(); MergeProtocol.Completed complete=null;
    for(var packet:packets) { assertTrue(packet.length<=28000); var got=assembler.accept(MergeProtocol.decode(packet),0);if(got.isPresent()) complete=got.get(); }
    assertNotNull(complete); assertEquals(hints,complete.updateShapes()); assertEquals(List.of("Alice 1"),complete.regions().getFirst().oursAuthors());
    assertTrue(new MergeProtocol.RegionInfo(1,null,0,Choice.MANUAL,false,false).oursAuthors().isEmpty());
  }
}
