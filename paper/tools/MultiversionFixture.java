import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.worldgit.core.anvil.*;

/** Synchronize only disabled vanilla experimental pack names in a test copy.
 * The source DataVersion and enabled packs remain those of the real server. */
class MultiversionFixture {
  public static void main(String[] args) throws Exception {
    Path level = Path.of(args[0]);
    var root = WorldLayout.readGzip(level);
    var packs = root.compound("Data").compound("DataPacks");
    var enabled = packs.list("Enabled").values();
    if (!enabled.equals(List.of("vanilla"))) throw new IllegalStateException("Unexpected enabled packs: " + enabled);
    var disabled = new TreeSet<String>();
    for (Object value : packs.list("Disabled").values()) {
      String name = String.valueOf(value);
      if (!Set.of("bundle", "update_1_20", "update_1_21").contains(name))
        throw new IllegalStateException("Unexpected disabled pack: " + name);
      disabled.add(name);
    }
    disabled.addAll(List.of("bundle", "update_1_21"));
    packs.put("Disabled", new Nbt.ListTag(8, new ArrayList<Object>(disabled)));
    try (var stream = new GZIPOutputStream(Files.newOutputStream(level))) { stream.write(Nbt.write(root)); }
  }
}
