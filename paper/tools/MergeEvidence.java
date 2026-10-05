import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;
import org.worldgit.core.merge.*;
import org.worldgit.core.store.*;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 唯讀驗收：durable 清單、快照 section state，以及真 plugin payload。 */
class MergeEvidence {
  public static void main(String[] args) throws Exception {
    var json=new ObjectMapper();
    if(args[0].equals("wire")) {
      var assemblers=new HashMap<String,MergeProtocol.Assembler>(); var completed=new ArrayList<MergeProtocol.Completed>();
      for(var line:Files.readAllLines(Path.of(args[1]))) {
        var a=line.split(" ",2); if(!a[0].equals(MergeProtocol.REGIONS) && !a[0].equals(MergeProtocol.PREVIEW)) continue;
        var part=MergeProtocol.decode(HexFormat.of().parseHex(a[1]));
        assemblers.computeIfAbsent(a[0]+part.dimension(),k->new MergeProtocol.Assembler()).accept(part,System.currentTimeMillis()).ifPresent(completed::add);
      }
      System.out.println(json.writeValueAsString(completed)); return;
    }
    var root=WorldLayout.discover(Path.of(args[1])).repositoryRoot();
    if(args[0].equals("state")) { var state=MergeState.read(root.resolve("merge-state.bin")); System.out.println(json.writeValueAsString(state)); return; }
    int cx=Integer.parseInt(args[3]),cz=Integer.parseInt(args[4]),sy=Integer.parseInt(args[5]);
    try(var store=new JGitStore(WorldLayout.discover(Path.of(args[1])).repository(DimensionId.OVERWORLD),false)) {
      var commit=store.readCommit(store.resolve(args[2]));
      var blob=TreeEditor.find(store,commit.tree(),new ChunkPos(cx,cz).treePath()+"/s."+sy+".bin");
      var section=blob==null ? Section.air() : SnapshotCodec.section(store.readBlob(blob.id()));
      var hash=MessageDigest.getInstance("SHA-256");
      for(int i=0;i<4096;i++) hash.update((section.block(i).canonical()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      System.out.println(HexFormat.of().formatHex(hash.digest()));
    }
  }
}
