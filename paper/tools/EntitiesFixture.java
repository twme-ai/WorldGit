import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;

/** 僅修改測試 baseline 的副本，隔離跨維度 UUID 場景中的自然熔岩／火焰排程。 */
class EntitiesFixture {
  public static void main(String[] args) throws Exception {
    var world = Path.of(args[0]);
    var layout = WorldLayout.discover(world);
    final int dataVersion = layout.dataVersion();
    for (String name : List.of("the_nether", "the_end")) {
      var id = new DimensionId("minecraft:" + name);
      var dimension = layout.dimensions().get(id);
      // 純原版 26.x 來源可能尚未產生非主世界的儲存目錄。
      if (dimension == null && dataVersion >= org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE) {
        Files.createDirectories(world.resolve("dimensions/minecraft/" + name + "/region"));
        layout = WorldLayout.discover(world);
        dimension = layout.dimensions().get(id);
      }
      if (dimension == null) throw new java.io.IOException("Entity fixture has no native storage: " + id);
      var sections = new ArrayList<Object>();
      for (int y = 0; y < 16; y++) sections.add(new Nbt.Compound().with("Y", (byte) y)
          .with("block_states", new Nbt.Compound().with("palette", new Nbt.ListTag(10,
              List.<Object>of(org.worldgit.core.version.BlockStateNbt.write(new BlockState(y < 4 ? (name.equals("the_end") ? "minecraft:end_stone" : "minecraft:stone") : "minecraft:air"), dataVersion)))))
          .with("biomes", new Nbt.Compound().with("palette", new Nbt.ListTag(8, List.<Object>of(name.equals("the_end") ? "minecraft:the_end" : "minecraft:nether_wastes")))));
      var regions = new TreeMap<String, Map<Integer, Nbt.Compound>>();
      for (int x = -22; x <= 22; x++) for (int z = -22; z <= 22; z++) {
        var pos = new ChunkPos(x, z);
        var chunk = new Nbt.Compound().with("DataVersion", dataVersion).with("xPos", x).with("zPos", z).with("yPos", 0)
            .with("Status", "minecraft:full").with("sections", new Nbt.ListTag(10, sections))
            .with("block_entities", new Nbt.ListTag(10, List.of())).with("block_ticks", new Nbt.ListTag(10, List.of()))
            .with("fluid_ticks", new Nbt.ListTag(10, List.of()))
            .with("structures", new Nbt.Compound().with("starts", new Nbt.Compound()).with("References", new Nbt.Compound()))
            .with("isLightOn", (byte) 0).with("LastUpdate", 0L).with("InhabitedTime", 0L);
        regions.computeIfAbsent(pos.regionName(), k -> new TreeMap<>()).put(pos.regionIndex(), chunk);
      }
      Files.createDirectories(dimension.region());
      for (var entry : regions.entrySet()) RegionFile.update(dimension.region().resolve(entry.getKey() + ".mca"), entry.getValue(), (int) (System.currentTimeMillis() / 1000));
      if (name.equals("the_end") && args.length > 1 && args[1].equals("--disk-uuid")) {
        var pos = new ChunkPos(96,96);
        var terrain = Nbt.copy(regions.values().iterator().next().values().iterator().next());
        terrain.put("xPos",96);terrain.put("zPos",96);
        RegionFile.update(dimension.region().resolve(pos.regionName()+".mca"),Map.of(pos.regionIndex(),terrain),0);
        var uid=UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        var entity=new Nbt.Compound().with("id","minecraft:cow")
            .with("UUID",new int[]{(int)(uid.getMostSignificantBits()>>32),(int)uid.getMostSignificantBits(),(int)(uid.getLeastSignificantBits()>>32),(int)uid.getLeastSignificantBits()})
            .with("Pos",new Nbt.ListTag(6,List.of(1544d,70d,1544d)))
            .with("NoAI",(byte)1).with("NoGravity",(byte)1).with("Invulnerable",(byte)1).with("PersistenceRequired",(byte)1);
        var stored=new Nbt.Compound().with("DataVersion",dataVersion).with("Position",new int[]{96,96})
            .with("Entities",new Nbt.ListTag(10,List.of(entity)));
        Files.createDirectories(dimension.entities());
        RegionFile.update(dimension.entities().resolve(pos.regionName()+".mca"),Map.of(pos.regionIndex(),stored),0);
        System.out.println("Entity fixture: disk-only UUID in the_end chunk=96,96");
      }
      System.out.println("Entity fixture: " + name + " full chunks=2025, block_ticks=0, fluid_ticks=0");
    }
  }
}
