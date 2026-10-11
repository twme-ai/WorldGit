import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.version.*;

/** Exercise every supported target JVM against genuine legacy terrain, entities and level data. */
class DfuTargets {
  public static void main(String[] args) throws Exception {
    Path source = Path.of(args[0]), output = Path.of(args[1]);
    Nbt.Compound terrain, entities;
    try (var region = new RegionFile(source.resolve("region/r.0.0.mca"))) {
      terrain = region.read(0);
    }
    try (var region = new RegionFile(source.resolve("entities/r.0.0.mca"))) {
      entities = region.read(0);
    }
    var level = WorldLayout.readGzip(source.resolve("level.dat")).compound("Data");
    var rows = new ArrayList<Map<String, Object>>();
    var json = new ObjectMapper();
    for (var target : MinecraftVersion.SUPPORTED) {
      if (target.dataVersion() <= 3465) continue;
      var row = new LinkedHashMap<String, Object>();
      row.put("target", target.id());
      rows.add(row);
      try (var dfu = new DfuProcess(target)) {
        var chunk = dfu.upgrade("CHUNK", terrain, 3465, target.dataVersion());
        chunk.put("DataVersion", target.dataVersion());
        var entityChunk = dfu.upgrade("ENTITY_CHUNK", entities, 3465, target.dataVersion());
        var data = dfu.upgrade("LEVEL", level, 3465, target.dataVersion());
        var normalizer = new ChunkNormalizer(IgnoreRules.none(), EntitySemantics.OFFLINE);
        var snapshot = normalizer.normalize(new org.worldgit.core.model.ChunkPos(0, 0), chunk,
            entityChunk.list("Entities").values().stream().map(Nbt.Compound.class::cast).toList());
        if (snapshot.sections().isEmpty() || snapshot.entities().isEmpty() || data.isEmpty())
          throw new AssertionError("DFU lost representative content: " + target.id());
        row.put("passed", true);
        row.put("types", List.of("CHUNK", "ENTITY_CHUNK", "LEVEL"));
        System.out.println("PASS DFU target " + target.id());
      } catch (Exception | AssertionError failure) {
        row.put("passed", false);
        row.put("error", failure.toString());
        throw failure;
      } finally {
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), rows);
      }
    }
  }
}
