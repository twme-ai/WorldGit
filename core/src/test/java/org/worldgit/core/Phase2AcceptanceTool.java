package org.worldgit.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.service.*;

/** 可重跑的本機工具；只能傳入 baseline／server 的複本。 */
public final class Phase2AcceptanceTool {
  static final CommitMetadata.Identity AUTHOR=new CommitMetadata.Identity("Phase 2 acceptance","worldgit@localhost");
  static final UUID ENTITY=UUID.fromString("00000000-0000-4000-8000-000000000025");
  static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    var layout=WorldLayout.discover(Path.of(args[1]));
    switch(args[0]) {
      case "roundtrip" -> roundtrip(layout);
      case "synthetic" -> synthetic(layout,Integer.parseInt(args[2]));
      case "mutate" -> mutate(layout,Integer.parseInt(args[2]));
      case "inspect" -> inspect(layout);
      case "benchmark-prepare" -> benchmarkPrepare(layout);
      case "benchmark-switch" -> {
        long start=System.nanoTime();
        try(var ops=new WorldOperations(layout)) {
          var result=ops.switchTo("A",false,false,false,false);
          check(result.success(),result.toString());
          System.out.printf(Locale.ROOT,"switch_seconds=%.3f chunks=%d sections=%d%n",(System.nanoTime()-start)/1e9,
              result.dimensions().get(DimensionId.OVERWORLD).chunks(),result.dimensions().get(DimensionId.OVERWORLD).sections());
        }
      }
      default -> throw new IllegalArgumentException("未知命令");
    }
  }
  public static void roundtrip(WorldLayout layout) throws Exception {
    var worlds=new WorldRepositories(layout);
    check(worlds.init(null,"creative",WorldGitConfig.Track.ALL,AUTHOR).success(),"init");
    try(var ops=new WorldOperations(layout)) { ops.createBranch("A",null); }
    mutate(layout,14);
    check(worlds.commit(null,"B: blocks/BE/entity/cross-chunk",AUTHOR,2).success(),"commit B");
    try(var ops=new WorldOperations(layout)) {
      ops.createBranch("B",null);
      check(ops.switchTo("A",false,false,false,false).success(),"switch A");
      check(ops.verify("A",null,Scope.all(),true).success(),"verify A");
      check(ops.switchTo("B",false,false,false,false).success(),"switch B");
      check(ops.verify("B",null,Scope.all(),true).success(),"verify B");
    }
    var pos=new ChunkPos(0,0);
    var before=read(layout,pos);
    try(var ops=new WorldOperations(layout)) {
      check(ops.restore("A",DimensionId.OVERWORLD,Scope.box(0,224,0,0,224,0),false,false).success(),"box restore");
      check(ops.verify("A",DimensionId.OVERWORLD,Scope.box(0,224,0,0,224,0),false).success(),"box verify");
    }
    var after=read(layout,pos);
    for(var entry:before.sections().entrySet()) {
      var a=entry.getValue();var b=after.sections().getOrDefault(entry.getKey(),Section.air());
      for(int i=0;i<4096;i++) if(entry.getKey()!=14 || i!=0) {
        check(a.block(i).equals(b.block(i)),"box outside block");
        check(Arrays.equals(a.blockEntities().get(i),b.blockEntities().get(i)),"box outside BE");
      }
    }
    var neighbor=SnapshotCodec.chunkFiles(read(layout,new ChunkPos(1,0)));
    try(var ops=new WorldOperations(layout)) {
      check(ops.restore("A",DimensionId.OVERWORLD,Scope.chunkRadius(0,0,0),false,false).success(),"radius restore");
      check(ops.verify("A",DimensionId.OVERWORLD,Scope.chunkRadius(0,0,0),false).success(),"radius verify");
    }
    var neighborAfter=SnapshotCodec.chunkFiles(read(layout,new ChunkPos(1,0)));
    check(neighbor.keySet().equals(neighborAfter.keySet()),"radius outside files");
    for(String key:neighbor.keySet()) check(Arrays.equals(neighbor.get(key),neighborAfter.get(key)),"radius outside "+key);
    try(var ops=new WorldOperations(layout)) { check(ops.resetHard(null,false,false).success(),"reset B"); }
    System.out.println("roundtrip A/B, block/BE/entity/cross-chunk, box outside every cell, radius outside: PASS");
  }
  static ChunkSnapshot read(WorldLayout layout,ChunkPos pos) throws Exception {
    var dim=layout.dimensions().get(DimensionId.OVERWORLD);
    try(var source=new OfflineSnapshotSource(layout,dim)) {
      source.scan(org.worldgit.core.capture.ScanIndex.empty(),true);
      return source.snapshot(pos,IgnoreRules.none()).toCompletableFuture().join().orElseThrow();
    }
  }
  static Nbt.Compound raw(WorldLayout layout,ChunkPos pos) throws Exception {
    try(var file=new RegionFile(layout.dimensions().get(DimensionId.OVERWORLD).region().resolve(pos.regionName()+".mca"))) { return file.read(pos.regionIndex()); }
  }
  static void write(WorldLayout layout,ChunkPos pos,Nbt.Compound raw) throws Exception {
    RegionFile.update(layout.dimensions().get(DimensionId.OVERWORLD).region().resolve(pos.regionName()+".mca"),Map.of(pos.regionIndex(),raw),(int)(System.currentTimeMillis()/1000));
  }
  static BlockState chest() { return new BlockState("minecraft:chest",new TreeMap<>(Map.of("facing","north","type","single","waterlogged","false"))); }
  static byte[] chestNbt(int count) {
    return Nbt.write(new Nbt.Compound().with("id","minecraft:chest").with("Items",new Nbt.ListTag(10,List.of(
        new Nbt.Compound().with("Slot",(byte)0).with("id","minecraft:diamond").with("count",count)))));
  }
  static void mutate(WorldLayout layout,int sy) throws Exception {
    int n=0;
    for(var pos:List.of(new ChunkPos(0,0),new ChunkPos(1,0),new ChunkPos(-1,-1))) {
      var current=raw(layout,pos);
      var snap=new ChunkNormalizer(IgnoreRules.none(),EntitySemantics.OFFLINE).normalize(pos,current,List.of());
      var section=snap.sections().getOrDefault(sy,Section.air());
      var blocks=new ArrayList<>(section.blocks());var bes=section.blockEntities();
      blocks.set(0,new BlockState(n++==0 ? "minecraft:gold_block" : "minecraft:diamond_block"));bes.remove(0);
      if(pos.equals(new ChunkPos(0,0))) {
        blocks.set(17,chest());bes.put(17,chestNbt(7));
        if(sy==4) {
          blocks.set(3|(3<<4)|(1<<8),BlockState.AIR);bes.remove(3|(3<<4)|(1<<8));
          int lectern=4|(4<<4)|(1<<8);
          blocks.set(lectern,new BlockState("minecraft:lectern",new TreeMap<>(Map.of("facing","north","has_book","false","powered","false"))));
          bes.put(lectern,Nbt.write(new Nbt.Compound().with("id","minecraft:lectern")));
        }
      }
      var sections=new TreeMap<Integer,ApplyPlan.SectionOp>();
      sections.put(sy,new ApplyPlan.SectionOp(sy,SnapshotCodec.section(new Section(blocks,bes)),null));
      write(layout,pos,ChunkNbt.apply(current,new ApplyPlan.ChunkOp(pos,false,sections,new TreeMap<>(),false,null,false,null),layout.dataVersion(),IgnoreRules.none()));
    }
    var dim=layout.dimensions().get(DimensionId.OVERWORLD);
    // 靜態實體同 UUID 跨 chunk 移動；原始 A 沒有時就是新增。
    Nbt.Compound savedEntity=null;
    for(Path path:RegionFile.list(dim.entities())) try(var writer=new RegionWriter(path)) {
      for(int i=0;i<1024;i++) if(writer.has(i)) {
        var root=writer.read(i);var entities=new ArrayList<>(root.list("Entities").values());
        for(Object value:entities) if(ENTITY.equals(EntityNormalizer.uuid((Nbt.Compound)value))) savedEntity=Nbt.copy((Nbt.Compound)value);
        if(entities.removeIf(e->ENTITY.equals(EntityNormalizer.uuid((Nbt.Compound)e)))) {
          root.put("Entities",new Nbt.ListTag(10,entities));writer.write(i,root,2);
        }
      }
    }
    var dest=new ChunkPos(1,0);
    try(var writer=new RegionWriter(dim.entities().resolve(dest.regionName()+".mca"))) {
      var root=writer.read(dest.regionIndex());
      if(root==null) root=new Nbt.Compound().with("DataVersion",layout.dataVersion()).with("Position",new int[]{1,0});
      var moved=savedEntity==null ? armorStand(17.5,sy*16+2.0) : savedEntity;
      moved.put("Pos",new Nbt.ListTag(6,List.of(17.5,sy*16+2.0,0.5)));
      var entities=new ArrayList<>(root.list("Entities").values());entities.add(moved);
      root.put("Entities",new Nbt.ListTag(10,entities));writer.write(dest.regionIndex(),root,2);
    }
  }
  static Nbt.Compound armorStand(double x,double y) {
    return new Nbt.Compound().with("id","minecraft:armor_stand").with("UUID",new int[]{0,0x4000,0x80000000,0x25})
        .with("Pos",new Nbt.ListTag(6,List.of(x,y,0.5))).with("Rotation",new Nbt.ListTag(5,List.of(0f,0f)))
        .with("NoGravity",(byte)1).with("Invulnerable",(byte)1).with("Marker",(byte)1);
  }
  static void gzip(Path path,Nbt.Compound nbt) throws Exception {
    try(var out=new GZIPOutputStream(Files.newOutputStream(path))) { out.write(Nbt.write(nbt)); }
  }
  static void synthetic(WorldLayout layout,int count) throws Exception {
    for(var dim:layout.dimensions().values()) for(String name:List.of("region","entities","poi")) {
      Path dir=dim.directory().resolve(name);
      if(Files.exists(dir)) try(var stream=Files.walk(dir)) { for(Path p:stream.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
      Files.createDirectories(dir);
    }
    var levelPath=layout.world().resolve("level.dat");var root=WorldLayout.readGzip(levelPath);var data=root.compound("Data");
    data.put("spawn",new Nbt.Compound().with("pos",new int[]{0,65,0}).with("dimension","minecraft:overworld").with("yaw",0f).with("pitch",0f));
    for(String key:List.of("SpawnX","SpawnZ")) if(data.containsKey(key)) data.put(key,0);
    if(data.containsKey("SpawnY")) data.put("SpawnY",65);
    quiet(data.compound("game_rules"));gzip(levelPath,root);
    for(var dim:layout.dimensions().values()) {
      Path rules=dim.directory().resolve("data/minecraft/game_rules.dat");
      if(Files.exists(rules)) { var r=WorldLayout.readGzip(rules);quiet(r.compound("data"));gzip(rules,r); }
    }
    int side=(int)Math.ceil(Math.sqrt(count)),min=-side/2;
    var byRegion=new TreeMap<String,Map<Integer,Nbt.Compound>>();
    for(int j=0;j<count;j++) {
      var pos=new ChunkPos(min+j%side,min+j/side);var secs=new TreeMap<Integer,ApplyPlan.SectionOp>();var biomes=new TreeMap<Integer,ApplyPlan.BiomeOp>();
      for(int y=-4;y<20;y++) {
        biomes.put(y,new ApplyPlan.BiomeOp(y,Collections.nCopies(64,"minecraft:plains")));
        var blocks=new ArrayList<>(Collections.nCopies(4096,y<4 ? new BlockState("minecraft:stone") : BlockState.AIR));
        var bes=new HashMap<Integer,byte[]>();
        if(y==4 && pos.equals(new ChunkPos(0,0))) {
          blocks.set(1|(1<<4)|(1<<8),new BlockState("minecraft:sea_lantern"));
          int c=2|(2<<4)|(1<<8);blocks.set(c,chest());bes.put(c,chestNbt(3));
          int l=3|(3<<4)|(1<<8);blocks.set(l,new BlockState("minecraft:lectern",new TreeMap<>(Map.of("facing","north","has_book","false","powered","false"))));
          bes.put(l,Nbt.write(new Nbt.Compound().with("id","minecraft:lectern")));
        }
        secs.put(y,new ApplyPlan.SectionOp(y,SnapshotCodec.section(new Section(blocks,bes)),null));
      }
      var op=new ApplyPlan.ChunkOp(pos,false,secs,biomes,true,null,true,null);
      var chunk=ChunkNbt.apply(null,op,layout.dataVersion(),IgnoreRules.none());
      byRegion.computeIfAbsent(pos.regionName(),k->new TreeMap<>()).put(pos.regionIndex(),chunk);
    }
    var dim=layout.dimensions().get(DimensionId.OVERWORLD);
    for(var entry:byRegion.entrySet()) RegionFile.update(dim.region().resolve(entry.getKey()+".mca"),entry.getValue(),1);
    RegionFile.update(dim.entities().resolve("r.0.0.mca"),Map.of(0,new Nbt.Compound().with("DataVersion",layout.dataVersion()).with("Position",new int[]{0,0}).with("Entities",new Nbt.ListTag(10,List.of(armorStand(0.5,66.0))))),1);
    System.out.println("synthetic chunks="+count);
  }
  static void quiet(Nbt.Compound rules) {
    rules.put("minecraft:random_tick_speed",0);
    for(String key:List.of("spawn_mobs","advance_time","advance_weather")) rules.put("minecraft:"+key,(byte)0);
  }
  static void benchmarkPrepare(WorldLayout layout) throws Exception {
    var worlds=new WorldRepositories(layout);check(worlds.init(null,"creative",WorldGitConfig.Track.ALL,AUTHOR).success(),"bench init");
    try(var ops=new WorldOperations(layout)) { ops.createBranch("A",null); }
    var dim=layout.dimensions().get(DimensionId.OVERWORLD);int count=0;
    for(Path path:RegionFile.list(dim.region())) try(var writer=new RegionWriter(path)) {
      for(int i=0;i<1024;i++) if(writer.has(i)) {
        var pos=writer.pos(i);var sections=new TreeMap<Integer,ApplyPlan.SectionOp>();
        sections.put(5,new ApplyPlan.SectionOp(5,SnapshotCodec.section(new Section(Collections.nCopies(4096,new BlockState("minecraft:gold_block")),Map.of())),null));
        writer.write(i,ChunkNbt.apply(writer.read(i),new ApplyPlan.ChunkOp(pos,false,sections,new TreeMap<>(),false,null,false,null),layout.dataVersion(),IgnoreRules.none()),2);count++;
      }
    }
    check(count==1000,"benchmark must change 1000 chunks");check(worlds.commit(null,"B: 1000 chunks",AUTHOR,2).success(),"bench B");
    System.out.println("benchmark prepared chunks="+count);
  }
  static void inspect(WorldLayout layout) throws Exception {
    var dim=layout.dimensions().get(DimensionId.OVERWORLD);var chunk=raw(layout,new ChunkPos(0,0));
    int block=0,sky=0;
    for(Object o:chunk.list("sections").values()) { var s=(Nbt.Compound)o;if(s.containsKey("BlockLight"))block++;if(s.containsKey("SkyLight"))sky++; }
    var ids=new HashMap<UUID,Integer>();
    for(Path path:RegionFile.list(dim.entities())) try(var r=new RegionFile(path)) { for(int i=0;i<1024;i++) if(r.has(i)) for(Object o:r.read(i).list("Entities").values()) ids.merge(EntityNormalizer.uuid((Nbt.Compound)o),1,Integer::sum); }
    var poi=new ArrayList<String>();
    Path path=dim.poi().resolve("r.0.0.mca");
    if(Files.exists(path)) try(var r=new RegionFile(path)) { if(r.has(0)) {
      for(Object section:r.read(0).compound("Sections").values()) for(Object record:((Nbt.Compound)section).list("Records").values()) {
        var p=(Nbt.Compound)record;poi.add(p.string("type")+Arrays.toString((int[])p.get("pos")));
      }
    } }
    System.out.println("isLightOn="+chunk.integer("isLightOn",-1)+" blockLightSections="+block+" skyLightSections="+sky+" starlight="+chunk.integer("starlight.light_version",-1));
    System.out.println("duplicateUUIDs="+ids.values().stream().filter(n->n>1).count()+" controlledEntityCount="+ids.getOrDefault(ENTITY,0));
    System.out.println("poi="+poi);
  }
}
