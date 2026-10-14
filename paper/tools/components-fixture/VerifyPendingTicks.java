import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;

/** 停服且原生 IO 完成後讀真磁碟；不透過正規化掩蓋 delay／priority。 */
class VerifyPendingTicks {
  public static void main(String[] args) throws Exception {
    var layout=WorldLayout.discover(Path.of(args[0]));
    var pos=new ChunkPos(8,8);
    var region=layout.dimensions().get(DimensionId.OVERWORLD).region().resolve(pos.regionName()+".mca");
    try(var file=new RegionFile(region)) {
      var raw=file.read(pos.regionIndex());
      for(String key:List.of("block_ticks","fluid_ticks")) {
        var values=raw.list(key).values();
        if(values.size()!=1)throw new AssertionError(key+" count="+values.size());
        var tick=(Nbt.Compound)values.getFirst();
        System.out.println(key+" "+tick);
        if(tick.integer("t",0)!=600 || tick.integer("p",0)!=(key.equals("block_ticks")?-1:1))
          throw new AssertionError(key+" "+tick);
      }
    }
    System.out.println("WGPENDINGDISK success delay=600 priorities=-1,1");
  }
}
