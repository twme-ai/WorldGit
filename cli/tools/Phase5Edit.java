import java.nio.file.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;
import org.worldgit.core.TestWorlds;
/** 離線驗收只改維度副本的一格；不碰 baseline。 */
class Phase5Edit {
  public static void main(String[] args) throws Exception {
    var layout=WorldLayout.discover(Path.of(args[0])); var dimension=new DimensionId(args[1]);
    for(Path file : RegionFile.list(layout.dimensions().get(dimension).region())) try(var region=new RegionFile(file)) {
      for(int i=0;i<1024;i++) if(region.has(i)) for(Object value : region.read(i).list("sections").values()) {
        var section=(Nbt.Compound)value; if(section.compound("block_states").isEmpty()) continue;
        TestWorlds.oneBlock(layout,dimension,region.pos(i),section.integer("Y",0),0); return;
      }
    }
    throw new IllegalStateException("維度缺少可修改的 section");
  }
}
