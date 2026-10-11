package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.version.NbtUpgrader;

class PaperMetadataProjectionTest {
  private static String asset(String path) {
    return "asset." + Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
  }
  private static final String MARKER = asset("datapacks/bukkit/pack.mcmeta");
  private static final String JSON = "{\"pack\":{\"description\":\"Data pack for resources provided by Bukkit plugins\",\"pack_format\":15}}";
  private NbtUpgrader projection(int format) throws Exception {
    return PaperMetadataProjection.wrap((type, raw, from, to) -> raw, 3700,
        new Nbt.Compound().with("doMobSpawning", "true").with("maxCommandForkCount", "65536"),
        format, List.of("vanilla", "bundle", "trade_rebalance", "update_1_21", "file/bukkit"));
  }

  @Test void fillsOnlyMissingNativeDefaultsAndProjectsEmptyPlatformMarker() throws Exception {
    var input = new TreeMap<String, byte[]>();
    input.put("level.nbt", Nbt.write(new Nbt.Compound().with("GameRules",
        new Nbt.Compound().with("doMobSpawning", "false").with("customRule", "17"))
        .with("DataPacks", new Nbt.Compound()
            .with("Enabled", new Nbt.ListTag(8, List.of("vanilla", "file/bukkit")))
            .with("Disabled", new Nbt.ListTag(8, List.of("bundle"))))));
    input.put(MARKER, JSON.getBytes(StandardCharsets.UTF_8));
    var output = projection(26).projectMetadata(input, 3465, 3700);
    var level = Nbt.read(output.get("level.nbt"));
    assertEquals("false", level.compound("GameRules").string("doMobSpawning"));
    assertEquals("17", level.compound("GameRules").string("customRule"));
    assertEquals("65536", level.compound("GameRules").string("maxCommandForkCount"));
    assertEquals(List.of("vanilla", "file/bukkit"), level.compound("DataPacks").list("Enabled").values());
    assertEquals(List.of("bundle", "trade_rebalance", "update_1_21"), level.compound("DataPacks").list("Disabled").values());
    assertEquals(JSON.replace(":15", ":26"), new String(output.get(MARKER), StandardCharsets.UTF_8));
    assertEquals(JSON, new String(input.get(MARKER), StandardCharsets.UTF_8));
    assertFalse(Nbt.read(input.get("level.nbt")).compound("GameRules").containsKey("maxCommandForkCount"));
    assertNotEquals(projection(26).projectionFingerprint(), projection(27).projectionFingerprint());
  }

  @Test void customPackContentAndDisabledPolicyRemainUnchanged() throws Exception {
    var input = new TreeMap<String, byte[]>();
    input.put(MARKER, JSON.getBytes(StandardCharsets.UTF_8));
    input.put(asset("datapacks/bukkit/data/demo/function/test.mcfunction"), "say original".getBytes(StandardCharsets.UTF_8));
    input.put("level.nbt", Nbt.write(new Nbt.Compound().with("DataPacks", new Nbt.Compound()
        .with("Enabled", new Nbt.ListTag(8, List.of("vanilla")))
        .with("Disabled", new Nbt.ListTag(8, List.of("file/custom", "bundle"))))));
    var output = projection(26).projectMetadata(input, 3465, 3700);
    for (String key : input.keySet()) assertArrayEquals(input.get(key), output.get(key), key);
    input.remove(input.keySet().stream().filter(key -> key.startsWith("asset.") && !key.equals(MARKER)).findFirst().orElseThrow());
    input.put(MARKER, JSON.replace("\"pack_format\":15", "\"pack_format\":15,\"supported_formats\":[15,26]").getBytes(StandardCharsets.UTF_8));
    assertArrayEquals(input.get(MARKER), projection(26).projectMetadata(input, 3465, 3700).get(MARKER));
  }
}
