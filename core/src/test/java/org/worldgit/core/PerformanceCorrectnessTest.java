package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.capture.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.service.*;

class PerformanceCorrectnessTest {
  @TempDir Path temp;
  private WorldLayout init() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"),temp);
    var layout=WorldLayout.discover(temp);
    assertTrue(new WorldRepositories(layout).initAll("creative",WorldGitConfig.Track.ALL,RepositoryFixtureTest.AUTHOR,WorldGitConfig.Entities.ALL).success());
    return layout;
  }

  @Test void externalRewriteWithOldTimestampAndRestoredMtimeCannotReuseIndex() throws Exception {
    var layout=init();var world=new WorldRepositories(layout);var pos=new ChunkPos(0,0);
    var path=layout.dimensions().get(DimensionId.OVERWORLD).region().resolve(pos.regionName()+".mca");
    Nbt.Compound raw;try(var region=new RegionFile(path)) {raw=region.read(pos.regionIndex());}
    RegionFile.update(path,Map.of(pos.regionIndex(),raw),1);
    assertTrue(world.status(DimensionId.OVERWORLD,2,false).success());
    var mtime=Files.getLastModifiedTime(path);
    var attributes=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
    int location;try(var region=new RegionFile(path)) {raw=region.read(pos.regionIndex());location=region.location(pos.regionIndex());}
    var section=(Nbt.Compound)raw.list("sections").values().stream().filter(s->((Nbt.Compound)s).integer("Y",0)==9).findFirst().orElseThrow();
    var states=section.compound("block_states");var palette=new ArrayList<>(states.list("palette").values());
    var indices=ChunkNormalizer.unpack(states,palette.size(),4,4096);
    String previousName=((Nbt.Compound)palette.get(indices[0])).string("Name");
    palette.add(new Nbt.Compound().with("Name",previousName.equals("minecraft:gold_block") ? "minecraft:diamond_block" : "minecraft:gold_block"));indices[0]=palette.size()-1;
    states.put("palette",new Nbt.ListTag(10,palette));
    states.put("data",ChunkNormalizer.pack(indices,Math.max(4,ChunkNormalizer.ceilLog2(palette.size()))));
    var bytes=new ByteArrayOutputStream();
    try(var compressed=new java.util.zip.DeflaterOutputStream(bytes)) {compressed.write(Nbt.write(raw));}
    assertTrue(bytes.size()+5<=(location & 255)*4096);
    // 真正的 in-place 外部修改：inode、長度、sector、chunk 時間戳及 mtime 全部不變。
    try(var file=new RandomAccessFile(path.toFile(),"rw")) {
      file.seek((long)(location>>>8)*4096);file.writeInt(bytes.size()+1);file.writeByte(2);file.write(bytes.toByteArray());
    }
    Files.setLastModifiedTime(path,mtime);
    var rewritten=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
    assertEquals(attributes.fileKey(),rewritten.fileKey());assertEquals(attributes.size(),rewritten.size());
    var indexed=world.status(DimensionId.OVERWORLD,2,false).dimensions().get(DimensionId.OVERWORLD).value();
    assertEquals(1,indexed.diff().sections().size());
    var complete=world.status(DimensionId.OVERWORLD,2,true).dimensions().get(DimensionId.OVERWORLD).value();
    assertEquals(complete.diff().counts(),indexed.diff().counts());
  }

  @Test void verifyRejectsAnUnplannedChunkMutationAndDoesNotPublishHead() throws Exception {
    var layout=init();var world=new WorldRepositories(layout);
    try(var ops=new WorldOperations(layout)) {ops.createBranch("base",null);}
    TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,new ChunkPos(0,0),9,0);
    assertTrue(world.commit(DimensionId.OVERWORLD,"changed",RepositoryFixtureTest.AUTHOR,2).success());
    var writer=new OfflineApplier(layout);
    try(var ops=new WorldOperations(layout,(plan,lock)->{
      writer.apply(plan,lock);
      var other=new ChunkPos(-3,-14); // fixture 的另一個 region；不在套用計畫中。
      Path path=layout.dimensions().get(DimensionId.OVERWORLD).region().resolve(other.regionName()+".mca");
      Nbt.Compound raw;try(var r=new RegionFile(path)) {raw=r.read(other.regionIndex());}
      int y=((Nbt.Compound)raw.list("sections").values().stream().filter(s->!((Nbt.Compound)s).compound("block_states").isEmpty()).findFirst().orElseThrow()).integer("Y",0);
      TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,other,y,0);
    })) {
      var result=ops.switchTo("base",false,false,false,false);
      assertEquals(WorldOperations.State.PARTIAL,result.state());
      assertTrue(result.error().contains("驗證失敗"),result.error());
      assertTrue(ops.branches().stream().anyMatch(b->b.current() && b.name().equals("main")));
    }
  }

  @Test void cleanSameTreeSwitchMovesHeadWithoutCallingTheWorldWriter() throws Exception {
    var layout=init();
    try(var ops=new WorldOperations(layout,(plan,lock)->{throw new AssertionError("same tree must not be applied");})) {
      ops.createBranch("same",null);
      assertEquals(WorldOperations.State.COMPLETE,ops.switchTo("same",false,false,false,false).state());
      assertTrue(ops.branches().stream().anyMatch(b->b.current() && b.name().equals("same")));
      assertTrue(ops.verify("HEAD",null,Scope.all(),true).success());
    }
  }

  @Test void fullBypassesAFalseCleanSourceAndRestoresScopedOptions() throws Exception {
    var layout=init();var dim=layout.dimensions().get(DimensionId.OVERWORLD);
    try(var repo=new DimensionRepository(layout.repository(DimensionId.OVERWORLD),DimensionId.OVERWORLD,false);
        var disk=new OfflineSnapshotSource(layout,dim)) {
      var manifest=new WorldRepositories(layout).manifest();
      repo.workingTree(disk,manifest,2);
      TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,new ChunkPos(0,0),9,0);
      var falseClean=new SnapshotSource() {
        public DimensionId dimension() {return DimensionId.OVERWORLD;}
        public int dataVersion() throws IOException {return disk.dataVersion();}
        public String normalizationFingerprint() throws IOException {return disk.normalizationFingerprint();}
        public Map<String,byte[]> worldMetadata() throws IOException {return disk.worldMetadata();}
        public Scan scan(ScanIndex previous,boolean full) throws IOException {
          var scan=disk.scan(previous,full);
          return full ? scan : new Scan(scan.present(),Set.of(),previous.chunks(),0,scan.scannedAt());
        }
        public CompletionStage<Optional<ChunkSnapshot>> snapshot(ChunkPos p,IgnoreRules rules) {return disk.snapshot(p,rules);}
      };
      assertTrue(repo.status(falseClean,manifest,2,false).diff().empty());
      try(var options=new CaptureOptions(true)) {assertEquals(1,repo.status(falseClean,manifest,2,false).diff().sections().size());}
      assertFalse(CaptureOptions.full());
    }
  }

  @Test void paletteIdentityOptimizationPreservesEqualDistinctStatesAndProperties() throws Exception {
    var props=new TreeMap<String,String>();props.put("axis","x");
    var blocks=new ArrayList<BlockState>();
    for(int i=0;i<4096;i++) blocks.add(i%3==0 ? new BlockState("minecraft:oak_log",props) : BlockState.AIR);
    var shared=new BlockState("minecraft:oak_log",props);
    var compact=new ArrayList<BlockState>();for(int i=0;i<4096;i++) compact.add(i%3==0 ? shared : BlockState.AIR);
    assertArrayEquals(SnapshotCodec.section(new Section(blocks,Map.of())),SnapshotCodec.section(new Section(compact,Map.of())));
    assertEquals(blocks,SnapshotCodec.section(SnapshotCodec.section(new Section(blocks,Map.of()))).blocks());
    assertTrue(new BlockState("minecraft:cave_air").air());assertTrue(new BlockState("minecraft:void_air").air());
    assertFalse(new BlockState("minecraft:structure_void").air());
  }

  @Test void indexChecksumRejectsAValidLookingTreeIdBitFlip() throws Exception {
    var index=new ScanIndex("a".repeat(40),"policy","b".repeat(40),123,Map.of());
    var path=temp.resolve("index");index.save(path);
    assertEquals(index,ScanIndex.read(path));
    byte[] bytes=Files.readAllBytes(path);
    // 改成另一個合法十六進位字元，結構與 UTF 長度不變；不能只靠 parser 判斷損毀。
    for(int i=0;i<bytes.length-32;i++) if(bytes[i]=='b') {bytes[i]='c';break;}
    Files.write(path,bytes);
    assertTrue(assertThrows(IOException.class,()->ScanIndex.read(path)).getMessage().contains("checksum"));
  }

  @Test void cachedTreesStillConsumeTheAggregateObjectBudget() throws Exception {
    var layout=init();
    try(var repo=new DimensionRepository(layout.repository(DimensionId.OVERWORLD),DimensionId.OVERWORLD,false)) {
      String tree=repo.refs().readCommit(repo.refs().head()).tree();
      repo.objects().readTree(tree);
      try(var budget=new DecodeBudget(1_000_000,1_000_000,10_000,1,10_000).open()) {
        repo.objects().readTree(tree);
        assertThrows(DecodeBudget.Exceeded.class,()->repo.objects().readTree(tree));
      }
    }
  }

  @Test void emptyPaletteShortcutPreservesBiomesAndRejectsInvalidPackedIndices() throws Exception {
    var states=new Nbt.Compound().with("palette",new Nbt.ListTag(10,List.of(
        new Nbt.Compound().with("Name","minecraft:air"),
        new Nbt.Compound().with("Name","minecraft:cave_air")))).with("data",new long[256]);
    var section=new Nbt.Compound().with("Y",(byte)0).with("block_states",states)
        .with("biomes",new Nbt.Compound().with("palette",new Nbt.ListTag(8,List.of("minecraft:plains"))));
    var raw=new Nbt.Compound().with("Status","minecraft:full").with("DataVersion",4903)
        .with("sections",new Nbt.ListTag(10,List.of(section)));
    var normalizer=new ChunkNormalizer(IgnoreRules.none(),EntitySemantics.OFFLINE);
    var snapshot=normalizer.normalize(new ChunkPos(0,0),raw,List.of());
    assertTrue(snapshot.sections().isEmpty());
    assertEquals(Collections.nCopies(64,"minecraft:plains"),snapshot.biomes().get(0));
    ((long[])states.get("data"))[0]=2;
    var malformed=assertThrows(IOException.class,()->normalizer.normalize(new ChunkPos(0,0),raw,List.of()));
    assertInstanceOf(IllegalArgumentException.class,malformed.getCause());
    assertTrue(malformed.getMessage().contains("palette 索引越界"));
  }
}
