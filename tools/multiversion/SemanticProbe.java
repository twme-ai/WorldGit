import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.SnapshotCodec;

/** Hash every normalized component of the four fixture chunks, without touching repository refs. */
class SemanticProbe {
  public static void main(String[] args) throws Exception {
    var layout=WorldLayout.discover(Path.of(args[0]));var files=new TreeMap<String,Object>();
    boolean ticks = args.length > 1 && args[1].equals("--ticks");
    try(var source=new OfflineSnapshotSource(layout,layout.dimensions().get(DimensionId.OVERWORLD))) {
      for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
        var snapshot=source.snapshot(new ChunkPos(x,z),IgnoreRules.none()).toCompletableFuture().join().orElseThrow();
        if (ticks) files.put(x+","+z, snapshot.ticks() == null ? null : Nbt.read(snapshot.ticks()));
        else for(var file:SnapshotCodec.chunkFiles(snapshot).entrySet())files.put(x+","+z+"/"+file.getKey(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getValue())));
      }
    }
    new ObjectMapper().writeValue(System.out,files);
  }
}
