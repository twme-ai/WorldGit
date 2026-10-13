import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.ChunkPos;

/** 只改受測副本，讓真 server loader 建立未展開的 SavedTick 容器。 */
class PendingFixture {
  public static void main(String[] args) throws Exception {
    var pos=new ChunkPos(8,8);var file=WorldLayout.discover(Path.of(args[0]).resolve("world")).dimensions().get(org.worldgit.core.model.DimensionId.OVERWORLD).region().resolve(pos.regionName()+".mca");
    Nbt.Compound raw;
    try(var region=new RegionFile(file)){raw=region.read(pos.regionIndex());}
    if(raw==null)throw new IllegalStateException("pending fixture chunk missing");
    raw.put("block_ticks",new Nbt.ListTag(10,List.<Object>of(tick("minecraft:stone",132,-1))));
    raw.put("fluid_ticks",new Nbt.ListTag(10,List.<Object>of(tick("minecraft:water",133,1))));
    RegionFile.update(file,Map.of(pos.regionIndex(),raw),(int)(System.currentTimeMillis()/1000));
  }
  private static Nbt.Compound tick(String id,int x,int priority){return new Nbt.Compound().with("i",id).with("x",x).with("y",64).with("z",132).with("t",600).with("p",priority);}
}
