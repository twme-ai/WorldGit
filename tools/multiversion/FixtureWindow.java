import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;

/** Keep the four representative chunks of a copied real-server fixture for bounded Hub requests. */
class FixtureWindow {
  public static void main(String[] args) throws Exception {
    Path world = Path.of(args[0]);
    int terrain = 0;
    try (var files = Files.walk(world)) {
      for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".mca")).toList()) {
        var removed = new TreeMap<Integer, Nbt.Compound>();
        int kept = 0;
        try (var region = new RegionFile(path)) {
          for (int i = 0; i < 1024; i++) {
            if (!region.has(i)) continue;
            var pos = region.pos(i);
            if (pos.x() >= 0 && pos.x() <= 1 && pos.z() >= 0 && pos.z() <= 1) kept++;
            else removed.put(i, null);
          }
        }
        if (path.getParent().getFileName().toString().equals("region")) terrain += kept;
        if (kept == 0) Files.delete(path);
        else if (!removed.isEmpty()) RegionFile.update(path, removed, 0);
      }
    }
    if (terrain != 4) throw new IllegalStateException("Expected four real fixture terrain chunks, got " + terrain);
    System.out.println("Retained four real-server fixture chunks without changing their NBT");
  }
}
