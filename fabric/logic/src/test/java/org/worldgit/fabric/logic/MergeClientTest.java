package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.BlockBox;
import org.worldgit.core.merge.MergeReport.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.protocol.*;

class MergeClientTest {
  private static final DimensionId DIM=DimensionId.OVERWORLD;
  private static final Region REGION=new Region(1,DIM,new BlockBox(0,64,0,2,65,0),4,List.of("Alice"),List.of("Bob"),true,Choice.OURS,false,List.of());
  private static void list(ClientConflicts client,long id,DimensionId dimension,List<Region> regions) throws Exception {
    for(var p:MergeProtocol.regions(id,dimension,regions,List.of(new Cell(1,64,0)))) client.accept(MergeProtocol.REGIONS,p,0);
  }
  @Test void simulatedPaperAuthorHintsAndMultipartPreviewAreAtomic() throws Exception {
    var client=new ClientConflicts(); list(client,3,DIM,List.of(REGION));
    var row=client.regions().getFirst(); assertEquals(List.of("Alice"),row.info().oursAuthors());
    assertEquals(List.of("Bob"),row.info().theirsAuthors()); assertTrue(row.info().redstone()); assertEquals(1,row.hints().size());
    client.select(row.key()); client.request(Choice.THEIRS);
    var be=Nbt.write(new Nbt.Compound().with("id","minecraft:chest").with("text","a".repeat(60000)));
    var cells=List.of(new MergeProtocol.PreviewCell(new Cell(0,64,0),"minecraft:chest",be));
    var packets=new ArrayList<>(MergeProtocol.preview(4,DIM,1,Choice.THEIRS,cells));
    assertTrue(packets.size()>2); Collections.reverse(packets);
    for(int i=0;i<packets.size()-1;i++) { client.accept(MergeProtocol.PREVIEW,packets.get(i),0); assertNull(client.preview()); }
    client.accept(MergeProtocol.PREVIEW,packets.getLast(),0);
    assertEquals("minecraft:chest",client.preview().cells().getFirst().state()); assertArrayEquals(be,client.preview().cells().getFirst().blockEntity());
    assertThrows(IOException.class,()->client.accept(MergeProtocol.REGIONS,packets.getFirst(),1));
  }
  @Test void selectionAndClearRejectLatePacketsInAllDimensions() throws Exception {
    var client=new ClientConflicts(); list(client,3,DIM,List.of(REGION));
    client.select(client.regions().getFirst().key()); client.request(Choice.OURS);
    var ours=MergeProtocol.preview(4,DIM,1,Choice.OURS,List.of());
    client.request(Choice.BASE);
    for(var p:ours) assertTrue(client.accept(MergeProtocol.PREVIEW,p,0).isEmpty());
    assertNull(client.preview());
    client.clear(10);
    list(client,9,new DimensionId("minecraft:the_nether"),List.of()); assertTrue(client.regions().isEmpty());
    for(var p:ours) assertTrue(client.accept(MergeProtocol.PREVIEW,p,0).isEmpty());
    client.reset(); list(client,1,DIM,List.of(REGION)); assertEquals(1,client.regions().size());
  }
  @Test void oldEnvelopeBodiesAndOldPeersRemainCompatible() throws Exception {
    var body=Nbt.write(new Nbt.Compound().with("regions",new Nbt.ListTag(10,List.of(new Nbt.Compound()
        .with("id",1).with("count",0).with("choice","MANUAL").with("resolved",(byte)0).with("redstone",(byte)0)))));
    var old=MergeProtocol.encode(new MergeProtocol.Part(1,MergeProtocol.Type.REGIONS,1,0,1,body.length,DIM,body));
    var client=new ClientConflicts(); client.accept(MergeProtocol.REGIONS,old,0);
    assertTrue(client.regions().getFirst().info().oursAuthors().isEmpty()); assertTrue(client.regions().getFirst().hints().isEmpty());
    var tracker=new HandshakeTracker(()->42); var peer=UUID.randomUUID(); tracker.join(peer,0);
    assertTrue(tracker.reply(peer,new Protocol.Hello(Protocol.VERSION,42,Protocol.CAPABILITIES,DiffPalette.DEFAULT)));
    assertFalse(tracker.supports(peer,MergeProtocol.CAPABILITY));
    var hello=ClientHandshake.reply(new Protocol.Hello(2,42,List.of(),DiffPalette.DEFAULT),ClientConfig.defaults()).orElseThrow();
    assertTrue(hello.capabilities().contains(MergeProtocol.CAPABILITY));
    assertFalse(Protocol.CAPABILITIES.contains(MergeProtocol.CAPABILITY));
  }
  @Test void commandsRejectAmbiguousOrDestructiveArguments() {
    assertTrue(MergeArgs.parse("merge","--abort").abort());
    assertTrue(MergeArgs.parse("merge","--continue").resume());
    assertEquals(Choice.THEIRS,MergeArgs.parse("resolve","all --theirs").choice());
    assertEquals(Choice.MANUAL,MergeArgs.parse("resolve","1").choice());
    assertEquals(Choice.OURS,MergeArgs.parse("merge","topic --strategy-option ours --distance 0").strategy());
    for(var invalid:List.of("--abort --continue","topic --abort","topic --distance 17","topic --ours"))
      assertThrows(IllegalArgumentException.class,()->MergeArgs.parse("merge",invalid),invalid);
    for(var invalid:List.of("0 --ours","-1 --theirs","1 --ours --base","1 --continue"))
      assertThrows(IllegalArgumentException.class,()->MergeArgs.parse("resolve",invalid),invalid);
    assertThrows(IllegalArgumentException.class,()->MergeArgs.parse("revert","--abort"));
  }
}
