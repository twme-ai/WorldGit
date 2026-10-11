import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.worldgit.core.anvil.*;

/** 測試副本手動同步未啟用的原版實驗包；不忽略 DataPacks 預檢。 */
class FixturePacks {
  public static void main(String[] args) throws Exception {
    Path path = Path.of(args[0]).resolve("level.dat");
    var root = WorldLayout.readGzip(path);
    var packs = root.compound("Data").compound("DataPacks");
    if (!packs.list("Enabled").values().equals(List.of("vanilla")))
      throw new IOException("Fixture must enable only vanilla");
    var experiments = Set.of("bundle", "trade_rebalance", "update_1_21",
        "minecart_improvements", "redstone_experiments");
    for (var pack : packs.list("Disabled").values())
      if (!experiments.contains(pack)) throw new IOException("Unknown disabled fixture pack: " + pack);
    packs.put("Disabled", new Nbt.ListTag(8, List.of()));
    var bytes = new ByteArrayOutputStream();
    try (var output = new GZIPOutputStream(bytes)) { output.write(Nbt.write(root)); }
    RegionFile.atomicWrite(path, bytes.toByteArray());
  }
}
