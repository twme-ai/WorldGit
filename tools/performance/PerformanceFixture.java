import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;

/** 可重現的離線負載；只在指定的空目錄建置世界，修改以 region 為單位批次寫入。 */
class PerformanceFixture {
  public static void main(String[] args) throws Exception {
    String action=args[0]; Path world=Path.of(args[1]); int count=Integer.parseInt(args[2]);
    if (action.equals("create")) {
      if(Files.exists(world)) throw new IllegalArgumentException("目的地已存在");
      Path baseline=Path.of(args[3]); Files.createDirectories(world);
      Files.copy(baseline.resolve("level.dat"),world.resolve("level.dat"));
      Files.createDirectories(world.resolve("region"));
      if(Files.isDirectory(baseline.resolve("datapacks"))) try(var paths=Files.walk(baseline.resolve("datapacks"))) {
        for(var p:paths.toList()) { var dest=world.resolve(baseline.relativize(p));
          if(Files.isDirectory(p)) Files.createDirectories(dest); else Files.copy(p,dest); }
      }
    }
    var layout=WorldLayout.discover(world); var dimension=layout.dimensions().get(DimensionId.OVERWORLD);
    var grouped=new TreeMap<Path,Map<Integer,Nbt.Compound>>();
    int width=(int)Math.ceil(Math.sqrt(count));
    List<ChunkPos> positions=new ArrayList<>();
    if(action.equals("create")) for(int i=0;i<count;i++) positions.add(new ChunkPos(i%width,i/width));
    else {
      for(var path:RegionFile.list(dimension.region())) try(var r=new RegionFile(path)) {
        for(int i=0;i<1024;i++) if(r.has(i)) positions.add(r.pos(i));
      }
      Collections.sort(positions); positions=positions.subList(0,count);
    }
    for(var pos:positions) {
      Path path=dimension.region().resolve(pos.regionName()+".mca"); Nbt.Compound raw;
      if(action.equals("create")) {
        var section=new Nbt.Compound().with("Y",(byte)4)
          .with("block_states",new Nbt.Compound().with("palette",new Nbt.ListTag(10,List.of(new Nbt.Compound().with("Name","minecraft:stone")))))
          .with("biomes",new Nbt.Compound().with("palette",new Nbt.ListTag(8,List.of("minecraft:plains"))));
        raw=new Nbt.Compound().with("DataVersion",layout.dataVersion()).with("xPos",pos.x()).with("zPos",pos.z()).with("yPos",-4)
          .with("Status","minecraft:full").with("sections",new Nbt.ListTag(10,List.of(section)))
          .with("block_entities",new Nbt.ListTag(10,List.of())).with("block_ticks",new Nbt.ListTag(10,List.of()))
          .with("fluid_ticks",new Nbt.ListTag(10,List.of())).with("isLightOn",(byte)0);
      } else {
        try(var r=new RegionFile(path)) { raw=r.read(pos.regionIndex()); }
        var section=(Nbt.Compound)raw.list("sections").values().stream()
          .filter(s->!((Nbt.Compound)s).compound("block_states").list("palette").values().isEmpty()).findFirst().orElseThrow();
        var states=section.compound("block_states");
        var palette=new ArrayList<>(states.list("palette").values());
        int[] indices=org.worldgit.core.normalize.ChunkNormalizer.unpack(states,palette.size(),4,4096);
        palette.add(new Nbt.Compound().with("Name",args[3]));indices[0]=palette.size()-1;
        states.put("palette",new Nbt.ListTag(10,palette));
        states.put("data",org.worldgit.core.normalize.ChunkNormalizer.pack(indices,
          Math.max(4,org.worldgit.core.normalize.ChunkNormalizer.ceilLog2(palette.size()))));
      }
      grouped.computeIfAbsent(path,k->new TreeMap<>()).put(pos.regionIndex(),raw);
    }
    for(var e:grouped.entrySet()) RegionFile.update(e.getKey(),e.getValue(),(int)(System.currentTimeMillis()/1000));
    System.out.println(action+" chunks="+count);
  }
}
