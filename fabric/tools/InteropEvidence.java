import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.store.*;
import org.worldgit.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 唯讀候選樹；不取得線上 session lock、不套用世界。 */
class InteropEvidence {
  public static void main(String[] args) throws Exception {
    var root=WorldLayout.discover(Path.of(args[0])).repositoryRoot();
    var state=MergeState.read(root.resolve("merge-state.bin"));
    var out=new TreeMap<String,Object>();
    var rows=new ArrayList<MergeProtocol.RegionInfo>();
    var previews=new TreeMap<String,Object>();
    if(state!=null) for(var r:state.regions()) {
      rows.add(new MergeProtocol.RegionInfo(r.id(),r.bounds(),r.blockCount(),r.choice(),r.resolved(),r.redstone(),r.oursAuthors(),r.theirsAuthors()));
      if(args.length>1) try(var store=new JGitStore(WorldLayout.discover(Path.of(args[0])).repository(r.dimension()),false)) {
        var d=state.dimensions().get(r.dimension());
        var candidates=Map.of("OURS",d.oursTree(),"THEIRS",d.theirsTree(),"BASE",d.baseTree());
        for(var e:candidates.entrySet()) {
          var cells=new ArrayList<Map<String,Object>>();
          for(var b:MergeEngine.preview(store,e.getValue(),r)) {
            var cell=new TreeMap<String,Object>();
            cell.put("position",b.position()); cell.put("state",b.state().canonical());
            cell.put("be",b.blockEntity()==null ? "" : Base64.getEncoder().encodeToString(b.blockEntity()));
            cells.add(cell);
          }
          previews.put(r.id()+":"+e.getKey(),cells);
        }
      }
    }
    rows.sort(Comparator.comparingInt(MergeProtocol.RegionInfo::id));
    out.put("regions",rows); out.put("previews",previews);
    var heads=new TreeMap<String,Object>();
    for(var dimension:WorldLayout.discover(Path.of(args[0])).dimensions().keySet()) {
      var directory=WorldLayout.discover(Path.of(args[0])).repository(dimension);
      if(!Files.exists(directory.resolve("HEAD"))) continue;
      try(var store=new JGitStore(directory,false)) {
        var commit=store.readCommit(store.head());
        heads.put(dimension.value(),Map.of("id",commit.id(),"parents",commit.parents()));
      }
    }
    out.put("heads",heads);
    System.out.println(new ObjectMapper().writeValueAsString(out));
  }
}
