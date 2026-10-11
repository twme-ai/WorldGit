import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;

/** 僅修改測試 baseline 的副本，避免跨維度 UUID 場景混入自然熔岩流動。 */
class EntitiesFixture {
  public static void main(String[] args) throws Exception {
    var world = Path.of(args[0]);
    var layout = WorldLayout.discover(world);
    var nether = layout.dimensions().get(new DimensionId("minecraft:the_nether"));
    var sections = new ArrayList<Object>();
    for (int y = 0; y < 16; y++) sections.add(new Nbt.Compound().with("Y", (byte) y)
        .with("block_states", new Nbt.Compound().with("palette", new Nbt.ListTag(10,
            List.<Object>of(new Nbt.Compound().with("Name", y < 4 ? "minecraft:stone" : "minecraft:air")))))
        .with("biomes", new Nbt.Compound().with("palette", new Nbt.ListTag(8, List.<Object>of("minecraft:nether_wastes")))));
    var regions = new TreeMap<String, Map<Integer, Nbt.Compound>>();
    for (int x = -22; x <= 22; x++) for (int z = -22; z <= 22; z++) {
      var pos = new ChunkPos(x, z);
      var chunk = new Nbt.Compound().with("DataVersion", layout.dataVersion()).with("xPos", x).with("zPos", z).with("yPos", 0)
          .with("Status", "minecraft:full").with("sections", new Nbt.ListTag(10, sections))
          .with("block_entities", new Nbt.ListTag(10, List.of())).with("block_ticks", new Nbt.ListTag(10, List.of()))
          .with("fluid_ticks", new Nbt.ListTag(10, List.of()))
          .with("structures", new Nbt.Compound().with("starts", new Nbt.Compound()).with("References", new Nbt.Compound()))
          .with("isLightOn", (byte) 0).with("LastUpdate", 0L).with("InhabitedTime", 0L);
      regions.computeIfAbsent(pos.regionName(), k -> new TreeMap<>()).put(pos.regionIndex(), chunk);
    }
    Files.createDirectories(nether.region());
    for (var entry : regions.entrySet()) RegionFile.update(nether.region().resolve(entry.getKey() + ".mca"), entry.getValue(), (int) (System.currentTimeMillis() / 1000));
    System.out.println("Entity fixture: nether full chunks=2025, block_ticks=0, fluid_ticks=0");
  }
}
