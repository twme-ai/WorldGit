package org.worldgit.protocol;

import java.util.*;
import java.io.IOException;
public final class ProtocolCheck {
    private static void require(boolean b) { if (!b) throw new AssertionError(); }
    private static void rejected(byte[] b) { try { Protocol.part(b); throw new AssertionError("accepted malformed"); } catch (IOException expected) {} }
    public static void main(String[] args) throws Exception {
        var hello = new Protocol.Hello(1,987654L,Protocol.CAPABILITIES); require(hello.equals(Protocol.hello(Protocol.hello(hello))));
        require(Protocol.clear(Protocol.clear(765L))==765L);
        for (int n : new int[]{64,10000,100000}) {
            var entries = new ArrayList<Protocol.Entry>();
            for (int i=0;i<n;i++) entries.add(new Protocol.Entry(i%64-33, (i/4096)-4, (i/64)%64-31, DiffType.values()[i%4], i%4==0 ? "" : "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"));
            var packets = new ArrayList<>(Protocol.split(10,"minecraft:overworld",entries));
            int max=packets.stream().mapToInt(b->b.length).max().orElseThrow();require(max<=Protocol.MAX_PAYLOAD);
            var assembler = new Assembler(); Collections.reverse(packets); List<Protocol.Entry> result=null;
            for (byte[] b: packets) { var r=assembler.accept(Protocol.part(b),1000); if(r!=null) result=r; }
            require(result!=null && new HashSet<>(result).equals(new HashSet<>(entries)));
            assembler.clear(20); require(assembler.accept(Protocol.part(packets.getFirst()),1001)==null);
            byte[] truncated=Arrays.copyOf(packets.getFirst(),packets.getFirst().length-1);rejected(truncated);
            byte[] trailing=Arrays.copyOf(packets.getFirst(),packets.getFirst().length+1);rejected(trailing);
            var dup=new Assembler();dup.accept(Protocol.part(packets.getFirst()),1000);
            if(packets.size()>1) { try {dup.accept(Protocol.part(packets.getFirst()),1001);throw new AssertionError();}catch(IOException expected){} }
            System.out.println("PROTOCOL_OK n="+n+" packets="+packets.size()+" maxBytes="+max);
        }
        // 大量獨特狀態驗證 palette 本身超大時仍能安全分包。
        var unique=new ArrayList<Protocol.Entry>(); for(int i=0;i<4096;i++) unique.add(new Protocol.Entry(i&15,i>>8,(i>>4)&15,DiffType.REMOVED,"test:block_"+i+"[property="+"x".repeat(400)+"]"));
        for(byte[] b:Protocol.split(30,"test:world",unique)) require(b.length<=Protocol.MAX_PAYLOAD);
        System.out.println("PROTOCOL_MALFORMED_AND_LARGE_PALETTE_OK");
    }
}
