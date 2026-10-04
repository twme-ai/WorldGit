package org.worldgit.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.model.DimensionId;

class CommentsProtocolTest {
  private CommentsProtocol.Comment row(String text) {return new CommentsProtocol.Comment(UUID.randomUUID().toString(),"作者",text,8,64,0,10,66,2);}
  @Test void literalTextAndRangeRoundTrip() throws Exception {
    var row=row("<red><script>hi</script> §c emoji😀");
    var bytes=CommentsProtocol.encode(1,DimensionId.OVERWORLD,List.of(row)).getFirst();var p=CommentsProtocol.decode(bytes);
    assertEquals(List.of(row),p.comments());assertEquals(10,p.comments().getFirst().maxX());
  }
  @Test void boundedMultiPartAndClearFloor() throws Exception {
    var rows=new ArrayList<CommentsProtocol.Comment>();for(int i=0;i<64;i++)rows.add(row("😀".repeat(240)));
    var packets=CommentsProtocol.encode(2,DimensionId.OVERWORLD,rows);assertTrue(packets.size()>1);assertTrue(packets.stream().allMatch(p->p.length<=Protocol.MAX_PAYLOAD));
    var a=new CommentsProtocol.Assembler();Optional<CommentsProtocol.Snapshot> result=Optional.empty();var reversed=new ArrayList<>(packets);Collections.reverse(reversed);
    for(var p:reversed)result=a.accept(p,0);assertEquals(rows,result.orElseThrow().comments());
    var next=CommentsProtocol.encode(3,DimensionId.OVERWORLD,rows);a.accept(next.getFirst(),1);a.clear();
    for(var p:next)assertTrue(a.accept(p,2).isEmpty());
    var hide=CommentsProtocol.encode(4,DimensionId.OVERWORLD,List.of()).getFirst();assertTrue(a.accept(hide,3).orElseThrow().comments().isEmpty());
    for(var p:next)assertTrue(a.accept(p,4).isEmpty());
  }
  @Test void countTextPayloadAndDuplicateLimits() throws Exception {
    var rows=Collections.nCopies(65,row("x"));assertThrows(IOException.class,()->CommentsProtocol.encode(0,DimensionId.OVERWORLD,rows));
    assertThrows(IllegalArgumentException.class,()->row("x".repeat(242)));
    assertThrows(IOException.class,()->CommentsProtocol.decode(new byte[Protocol.MAX_PAYLOAD+1]));
    var p=CommentsProtocol.encode(1,DimensionId.OVERWORLD,List.of(row("hi"))).getFirst();var tail=Arrays.copyOf(p,p.length+1);assertThrows(IOException.class,()->CommentsProtocol.decode(tail));
    assertThrows(IOException.class,()->new CommentsProtocol.Assembler().accept(CommentsProtocol.encode(1,DimensionId.OVERWORLD,Collections.nCopies(2,row("x"))).getFirst(),0));
    var many=new ArrayList<CommentsProtocol.Comment>();for(int i=0;i<64;i++)many.add(row("字".repeat(240)));
    var chunks=CommentsProtocol.encode(3,DimensionId.OVERWORLD,many);var a=new CommentsProtocol.Assembler();a.accept(chunks.getFirst(),0);
    assertThrows(IOException.class,()->a.accept(chunks.getFirst(),1));
    for(var chunk:chunks)assertTrue(a.accept(chunk,6000).isEmpty());
    // 不等收齊就拒絕超過 envelope total 的 row 數，pending 也受 64 則上限。
    int totalOffset=2+8+2+DimensionId.OVERWORLD.value().length()+2;
    var bounded=new CommentsProtocol.Assembler();int firstCount=CommentsProtocol.decode(chunks.getFirst()).comments().size();
    var first=chunks.getFirst().clone();first[totalOffset]=(byte)firstCount;bounded.accept(first,0);
    var second=chunks.get(1).clone();second[totalOffset]=(byte)firstCount;
    assertThrows(IOException.class,()->bounded.accept(second,1));
  }
}
