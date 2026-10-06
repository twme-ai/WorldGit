package org.worldgit.paper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.model.DimensionId;

/** Bukkit World ↔ DimensionId 對應，以及主世界的磁碟版面（兩版目錄結構的差異由 core 的 WorldLayout 處理）。 */
public final class WorldMapper {
  /** 只支援磁碟上找得到維度目錄的世界；其餘（例如動態建立的世界）會列在 unsupported。 */
  public record Mapping(
      WorldLayout layout, SortedMap<DimensionId, World> worlds, List<String> unsupported) {}

  private WorldMapper() {}

  public static Mapping map() throws IOException {
    List<World> all = Bukkit.getWorlds();
    if (all.isEmpty()) throw new IOException("伺服器沒有任何世界");
    return map(all.getFirst());
  }

  public static Mapping map(World anchor) throws IOException {
    List<World> all = Bukkit.getWorlds();
    Path folder = anchor.getWorldFolder().toPath();
    if (anchor.getEnvironment() != World.Environment.NORMAL) {
      String suffix = anchor.getEnvironment() == World.Environment.NETHER ? "_nether" : "_the_end";
      String name = anchor.getName();
      World parent = name.endsWith(suffix) ? Bukkit.getWorld(name.substring(0, name.length()-suffix.length())) : null;
      if (parent != null) folder = parent.getWorldFolder().toPath();
    }
    WorldLayout layout = WorldLayout.discover(worldRoot(folder));
    var worlds = new TreeMap<DimensionId, World>();
    var unsupported = new ArrayList<String>();
    for (World world : all) {
      DimensionId id = dimensionOf(world, layout);
      if(id==null || !belongs(world,layout,id))continue;
      if(worlds.containsKey(id))unsupported.add(world.getName());else worlds.put(id,world);
    }
    return new Mapping(layout, worlds, unsupported);
  }

  /** 26.2 的 Bukkit world folder 是維度目錄；level.dat 在 dimensions/ 的上層世界根目錄。 */
  static Path worldRoot(Path folder) throws IOException {
    Path path = folder.toAbsolutePath().normalize();
    if (Files.isRegularFile(path.resolve("level.dat"))) return path;
    for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent())
      if (ancestor.getFileName() != null && ancestor.getFileName().toString().equals("dimensions")) {
        Path root = ancestor.getParent();
        if (root != null && Files.isRegularFile(root.resolve("level.dat"))) return root;
        break;
      }
    throw new IOException("找不到世界 level.dat：" + folder);
  }

  static DimensionId dimensionOf(World world, WorldLayout layout) {
    DimensionId fromKey = tryId(world.getKey().toString());
    if (fromKey != null && layout.dimensions().containsKey(fromKey)) return fromKey;
    DimensionId byEnvironment =
        switch (world.getEnvironment()) {
          case NORMAL -> DimensionId.OVERWORLD;
          case NETHER -> new DimensionId("minecraft:the_nether");
          case THE_END -> new DimensionId("minecraft:the_end");
          default -> null;
        };
    return byEnvironment != null && layout.dimensions().containsKey(byEnvironment) ? byEnvironment : null;
  }

  private static boolean belongs(World world, WorldLayout layout, DimensionId id) {
    Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
    Path terrain = layout.dimensions().get(id).directory().toAbsolutePath().normalize();
    return terrain.equals(folder) || terrain.getParent().equals(folder)
        || id.equals(DimensionId.OVERWORLD) && layout.world().toAbsolutePath().normalize().equals(folder);
  }

  private static DimensionId tryId(String value) {
    try {
      return new DimensionId(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
