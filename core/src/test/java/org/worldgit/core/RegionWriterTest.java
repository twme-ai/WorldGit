package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;

class RegionWriterTest {
  @TempDir Path temp;
  @Test void untouchedSectorAndTimestampAreStableAndFreeSectorsReused() throws Exception {
    Path p=temp.resolve("r.-1.0.mca");
    RegionFile.update(p,Map.of(0,new Nbt.Compound().with("v",1),1,new Nbt.Compound().with("v",2)),77);
    int location;
    try(var r=new RegionFile(p)) { location=r.location(1); }
    byte[] before=Files.readAllBytes(p);int offset=(location>>>8)*4096;
    RegionFile.update(p,Map.of(0,new Nbt.Compound().with("v",3)),88);
    byte[] after=Files.readAllBytes(p);
    assertArrayEquals(Arrays.copyOfRange(before,offset,offset+4096),Arrays.copyOfRange(after,offset,offset+4096));
    try(var r=new RegionFile(p)) { assertEquals(location,r.location(1));assertEquals(77,r.timestamp(1));assertEquals(3,r.read(0).integer("v",0)); }
    var delete=new HashMap<Integer,Nbt.Compound>();delete.put(0,null);RegionFile.update(p,delete,0);
    try(var writer=new RegionWriter(p)) { writer.write(2,new Nbt.Compound().with("v",4),99); }
    try(var r=new RegionFile(p)) { assertFalse(r.has(0));assertEquals(2,r.location(2)>>>8); }
  }
  @Test void externalChunkTransitionsAndLz4Input() throws Exception {
    Path p=temp.resolve("r.0.0.mca");byte[] data=new byte[1_100_000];new Random(12).nextBytes(data);
    var big=new Nbt.Compound().with("data",data);
    RegionFile.update(p,Map.of(0,big),1);
    assertTrue(Files.exists(temp.resolve("c.0.0.mcc")));
    try(var r=new RegionFile(p)) { assertArrayEquals(data,(byte[])r.read(0).get("data")); }
    var bytes=new ByteArrayOutputStream();
    try(var out=new net.jpountz.lz4.LZ4BlockOutputStream(bytes)) { out.write(Nbt.write(new Nbt.Compound().with("v",9))); }
    try(var writer=new RegionWriter(p)) { writer.writePayload(1,4,bytes.toByteArray(),2);writer.write(0,new Nbt.Compound().with("small",1),3); }
    assertFalse(Files.exists(temp.resolve("c.0.0.mcc")));
    try(var r=new RegionFile(p)) { assertEquals(9,r.read(1).integer("v",0)); }
    RegionFile.update(p,Map.of(0,big),4);
    var deletion=new HashMap<Integer,Nbt.Compound>();deletion.put(0,null);RegionFile.update(p,deletion,0);
    assertFalse(Files.exists(temp.resolve("c.0.0.mcc")));
  }
  @Test void invalidIndexCannotMutateFile() throws Exception {
    Path p=temp.resolve("r.0.0.mca");
    assertThrows(IOException.class,()->RegionFile.update(p,Map.of(-1,new Nbt.Compound()),0));
    assertFalse(Files.exists(p));
  }
}
