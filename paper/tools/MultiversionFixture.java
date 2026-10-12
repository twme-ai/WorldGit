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
    var data = root.compound("Data");
    var expected = switch (data.integer("DataVersion", 0)) {
      case 3465 -> Set.of("bundle");
      case 3700, 3839 -> Set.of("bundle", "trade_rebalance", "update_1_21");
      case 3955 -> Set.of("bundle", "trade_rebalance");
      case 4189 -> Set.of("trade_rebalance", "redstone_experiments", "minecart_improvements");
      case 4440 -> Set.of("trade_rebalance", "redstone_experiments", "minecart_improvements");
      default -> throw new IllegalStateException("Unsupported fixture DataVersion: " + data.integer("DataVersion", 0));
    };
    var packs = data.compound("DataPacks");
    var enabled = packs.list("Enabled").values();
    if (!enabled.equals(List.of("vanilla"))) throw new IllegalStateException("Unexpected enabled packs: " + enabled);
    var disabled = new TreeSet<String>();
    for (Object value : packs.list("Disabled").values()) {
      String name = String.valueOf(value);
      if (!expected.contains(name))
        throw new IllegalStateException("Unexpected disabled pack: " + name);
      disabled.add(name);
    }
    if (!disabled.equals(expected) || packs.list("Disabled").values().size() != expected.size())
      throw new IllegalStateException("Unexpected vanilla disabled packs: " + disabled + "; expected " + expected);
    if (data.integer("DataVersion", 0) <= 3839) disabled.addAll(List.of("bundle", "update_1_21"));
    packs.put("Disabled", new Nbt.ListTag(8, new ArrayList<Object>(disabled)));
    try (var stream = new GZIPOutputStream(Files.newOutputStream(level))) { stream.write(Nbt.write(root)); }
  }
}
