package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.version.NbtUpgrader;

class PaperMetadataProjectionTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder;
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

  private static String saved(String path) {
    return "minecraft.overworld.saved." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(path.getBytes(StandardCharsets.UTF_8)) + ".nbt";
  }

  @Test void currentNativeMarkerUsesTargetBytesButCustomSchemasAndAssetsStayUntouched() throws Exception {
    try(var gzip=new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(folder.resolve("level.dat")))) {
      gzip.write(Nbt.write(new Nbt.Compound().with("Data",new Nbt.Compound())));
    }
    var markerFile=folder.resolve("datapacks/bukkit/pack.mcmeta");java.nio.file.Files.createDirectories(markerFile.getParent());
    String nativeText="{\n  \"pack\": {\n    \"description\": \"Persistent resources provided by plugins\",\n    \"min_format\": [107, 1],\n    \"max_format\": 107\n  }\n}\n";
    java.nio.file.Files.writeString(markerFile,nativeText);
    var base=PaperMetadataProjection.wrap((type,raw,from,to)->raw,4903,new Nbt.Compound(),107,1,List.of("vanilla"));
    var backend=PaperMetadataProjection.forWorld(base,"minecraft.overworld",UUID.randomUUID(),folder);
    var input=new TreeMap<String,byte[]>();input.put(MARKER,JSON.getBytes(StandardCharsets.UTF_8));
    assertArrayEquals(nativeText.getBytes(StandardCharsets.UTF_8),backend.projectMetadata(input,4790,4903).get(MARKER));
    input.put(MARKER,nativeText.getBytes(StandardCharsets.UTF_8));
    var old=PaperMetadataProjection.wrap((type,raw,from,to)->raw,4790,new Nbt.Compound(),101,1,List.of("vanilla"));
    String projected=new String(old.projectMetadata(input,4903,4790).get(MARKER),StandardCharsets.UTF_8);
    assertTrue(projected.contains("Data pack for resources provided by Bukkit plugins"));assertTrue(projected.contains("[101, 1]"));
    for(String custom:List.of(nativeText.replace("\"max_format\": 107","\"max_format\": 108"),
        nativeText.replace("\"max_format\": 107","\"max_format\": 107, \"custom\": true"))) {
      input.put(MARKER,custom.getBytes(StandardCharsets.UTF_8));
      assertArrayEquals(input.get(MARKER),backend.projectMetadata(input,4790,4903).get(MARKER));
    }
    input.put(MARKER,JSON.getBytes(StandardCharsets.UTF_8));input.put(asset("datapacks/bukkit/data/custom/function/test.mcfunction"),"say keep".getBytes(StandardCharsets.UTF_8));
    assertArrayEquals(input.get(MARKER),backend.projectMetadata(input,4790,4903).get(MARKER));
  }

  @Test void paperStorageBoundaryUsesSourceSettingsAndKeepsUnknownPayloadsStrict() throws Exception {
    try(var gzip=new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(folder.resolve("level.dat")))) {gzip.write(Nbt.write(new Nbt.Compound().with("Data",new Nbt.Compound())));}
    var level = new Nbt.Compound().with("GameType",1).with("difficulty_settings",
        new Nbt.Compound().with("difficulty","easy").with("hardcore",(byte)0).with("locked",(byte)0))
        .with("spawn",new Nbt.Compound().with("dimension","minecraft:overworld")
            .with("pos",new int[]{8,64,8}).with("yaw",0f).with("pitch",0f));
    var base = PaperMetadataProjection.wrap((type,raw,from,to)->raw,4790,new Nbt.Compound(),101,1,List.of("vanilla"));
    var uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    var backend = PaperMetadataProjection.forWorld(base,"minecraft.overworld",uuid,folder);
    var input = new TreeMap<String,byte[]>();input.put("level.nbt",Nbt.write(level));
    String events = "saved." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("data/minecraft/scheduled_events.dat".getBytes(StandardCharsets.UTF_8)) + ".nbt";
    input.put(events,Nbt.write(new Nbt.Compound().with("DataVersion",4790).with("data",
        new Nbt.Compound().with("events",new Nbt.ListTag(10,List.of())))));
    var out = backend.projectMetadata(input,4671,4790);
    assertFalse(out.containsKey(events));assertArrayEquals(input.get(events),out.get("minecraft.overworld."+events));
    assertArrayEquals(new int[]{0x00112233,0x44556677,0x8899aabb,0xccddeeff},
        (int[])Nbt.read(out.get(saved("data/paper/metadata.dat"))).compound("data").get("uuid"));
    var overrides = Nbt.read(out.get(saved("data/paper/level_overrides.dat"))).compound("data");
    assertEquals(1,overrides.get("game_type"));assertEquals("easy",overrides.compound("difficulty_settings").string("difficulty"));
    assertArrayEquals(new int[]{8,64,8},(int[])overrides.compound("spawn").get("pos"));
    assertFalse(overrides.compound("spawn").containsKey("dimension"));
    assertFalse(out.containsKey(saved("data/paper/persistent_data_container.dat")));
    assertNotEquals(backend.projectionFingerprint(),PaperMetadataProjection.forWorld(base,"minecraft.overworld",UUID.randomUUID(),folder).projectionFingerprint());
    var old = PaperMetadataProjection.wrap((type,raw,from,to)->raw,4671,new Nbt.Compound(),94,1,List.of("vanilla"));
    var back = old.projectMetadata(out,4790,4671);
    assertFalse(back.containsKey(saved("data/paper/metadata.dat")));assertFalse(back.containsKey(saved("data/paper/level_overrides.dat")));
    overrides.put("custom:setting",42);
    out.put(saved("data/paper/level_overrides.dat"),Nbt.write(new Nbt.Compound().with("data",overrides)));
    out.put(saved("data/paper/persistent_data_container.dat"),Nbt.write(new Nbt.Compound().with("data",new Nbt.Compound().with("custom:opaque",42))));
    back = old.projectMetadata(out,4790,4671);
    assertArrayEquals(out.get(saved("data/paper/level_overrides.dat")),back.get(saved("data/paper/level_overrides.dat")));
    assertArrayEquals(out.get(saved("data/paper/persistent_data_container.dat")),back.get(saved("data/paper/persistent_data_container.dat")));
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

  @Test void intermediateVanillaExperimentsUseTheExactTargetListInBothDirections() throws Exception {
    for (boolean upgrade : List.of(true, false)) {
      int from = upgrade ? 3955 : 4189, to = upgrade ? 4189 : 3955;
      var available = upgrade ? List.of("vanilla", "trade_rebalance", "redstone_experiments", "minecart_improvements")
          : List.of("vanilla", "bundle", "trade_rebalance");
      var source = upgrade ? List.of("bundle", "trade_rebalance")
          : List.of("trade_rebalance", "redstone_experiments", "minecart_improvements");
      var projection = PaperMetadataProjection.wrap((type, raw, f, t) -> raw, to,
          new Nbt.Compound(), 61, available);
      var input = new TreeMap<String, byte[]>();
      var enabled = List.of("vanilla", "file/bukkit");
      input.put("level.nbt", Nbt.write(new Nbt.Compound().with("DataPacks", new Nbt.Compound()
          .with("Enabled", new Nbt.ListTag(8, new ArrayList<Object>(enabled))).with("Disabled", new Nbt.ListTag(8, new ArrayList<Object>(source))))));
      var output = Nbt.read(projection.projectMetadata(input, from, to).get("level.nbt")).compound("DataPacks");
      assertEquals(enabled, output.list("Enabled").values());
      assertEquals(available.stream().filter(x -> !x.equals("vanilla")).sorted().toList(), output.list("Disabled").values());
      assertEquals(source, Nbt.read(input.get("level.nbt")).compound("DataPacks").list("Disabled").values());
      assertTrue(projection.projectionFingerprint().startsWith("paper-metadata-v6:"));
    }
  }

  @Test void intermediateProjectionPreservesActiveExperimentsAndCustomDisabledPolicies() throws Exception {
    var projection = PaperMetadataProjection.wrap((type, raw, f, t) -> raw, 4189,
        new Nbt.Compound(), 61, List.of("vanilla", "trade_rebalance", "redstone_experiments", "minecart_improvements"));
    var input = new TreeMap<String, byte[]>();
    var enabled = List.of("file/custom", "redstone_experiments", "vanilla", "file/bukkit");
    input.put("level.nbt", Nbt.write(new Nbt.Compound().with("DataPacks", new Nbt.Compound()
        .with("Enabled", new Nbt.ListTag(8, new ArrayList<Object>(enabled)))
        .with("Disabled", new Nbt.ListTag(8, List.of("trade_rebalance"))))));
    var output = Nbt.read(projection.projectMetadata(input, 3955, 4189).get("level.nbt")).compound("DataPacks");
    assertEquals(enabled, output.list("Enabled").values());
    assertEquals(List.of("minecart_improvements", "trade_rebalance"), output.list("Disabled").values());
    var policy = List.of("file/custom-disabled", "redstone_experiments", "trade_rebalance");
    input.put("level.nbt", Nbt.write(new Nbt.Compound().with("DataPacks", new Nbt.Compound()
        .with("Enabled", new Nbt.ListTag(8, new ArrayList<Object>(enabled))).with("Disabled", new Nbt.ListTag(8, new ArrayList<Object>(policy))))));
    assertArrayEquals(input.get("level.nbt"), projection.projectMetadata(input, 3955, 4189).get("level.nbt"));
  }

  @Test void nativeMinorPackSchemaAndModernRuleDefaultsProjectInBothDirections() throws Exception {
    var input = new TreeMap<String, byte[]>();input.put(MARKER, JSON.getBytes(StandardCharsets.UTF_8));
    input.put("level.nbt", Nbt.write(new Nbt.Compound().with("game_rules",
        new Nbt.Compound().with("minecraft:spawn_mobs", (byte) 0).with("mod:rule", 17))));
    var modern = PaperMetadataProjection.wrap((type, raw, from, to) -> raw, 4671,
        new Nbt.Compound().with("minecraft:spawn_mobs", (byte) 1).with("minecraft:pvp", (byte) 1),
        94, 1, List.of("vanilla", "trade_rebalance"));
    var upgraded = modern.projectMetadata(input, 4440, 4671);
    var pack = new com.fasterxml.jackson.databind.ObjectMapper().readTree(upgraded.get(MARKER)).path("pack");
    assertFalse(pack.has("pack_format"));
    assertEquals(List.of(94, 1), new com.fasterxml.jackson.databind.ObjectMapper().convertValue(pack.get("min_format"), List.class));
    assertEquals(pack.get("min_format"), pack.get("max_format"));
    var rules = Nbt.read(upgraded.get("level.nbt")).compound("game_rules");
    assertEquals((byte) 0, rules.get("minecraft:spawn_mobs"));
    assertEquals((byte) 1, rules.get("minecraft:pvp"));assertEquals(17, rules.get("mod:rule"));
    var older = PaperMetadataProjection.wrap((type, raw, from, to) -> raw, 4440,
        new Nbt.Compound(), 81, List.of("vanilla", "trade_rebalance"));
    var downgraded = older.projectMetadata(upgraded, 4671, 4440);
    var legacy = new com.fasterxml.jackson.databind.ObjectMapper().readTree(downgraded.get(MARKER)).path("pack");
    assertEquals(81, legacy.path("pack_format").intValue());assertFalse(legacy.has("min_format"));
    assertEquals(JSON, new String(input.get(MARKER), StandardCharsets.UTF_8));
    assertNotEquals(modern.projectionFingerprint(), PaperMetadataProjection.wrap((type, raw, from, to) -> raw,
        4671, new Nbt.Compound(), 94, 2, List.of("vanilla", "trade_rebalance")).projectionFingerprint());
  }

  @Test void customMinorPackRangesAndExtraSchemaStayByteIdentical() throws Exception {
    var projection = PaperMetadataProjection.wrap((type, raw, from, to) -> raw, 4671,
        new Nbt.Compound(), 94, 1, List.of("vanilla"));
    for (String fields : List.of("\"min_format\":[81,0],\"max_format\":[94,1]",
        "\"min_format\":[94,1],\"max_format\":[94,1],\"supported_formats\":[81,94]",
        "\"min_format\":[94,-1],\"max_format\":[94,-1]")) {
      var input = new TreeMap<String, byte[]>();
      input.put(MARKER, JSON.replace("\"pack_format\":15", fields).getBytes(StandardCharsets.UTF_8));
      assertArrayEquals(input.get(MARKER), projection.projectMetadata(input, 4440, 4671).get(MARKER));
    }
    var input = new TreeMap<String, byte[]>();
    input.put(MARKER, JSON.getBytes(StandardCharsets.UTF_8));
    input.put(asset("datapacks/bukkit/data/custom/function/test.mcfunction"), "say retain".getBytes(StandardCharsets.UTF_8));
    assertArrayEquals(input.get(MARKER), projection.projectMetadata(input, 4440, 4671).get(MARKER));
  }

  @Test void namespacedRulesUseNativeDefaultsAndPreserveExistingAndCustomData() throws Exception {
    for (int target : List.of(4790, 4903, 5023)) {
      var defaults = new Nbt.Compound().with("minecraft:spawn_mobs", (byte) 1)
          .with("minecraft:random_tick_speed", 3);
      var projection = PaperMetadataProjection.wrap((type, raw, from, to) -> raw,
          target, defaults, 100, 1, List.of("vanilla"));
      var input = new TreeMap<String, byte[]>();
      var saved = new Nbt.Compound().with("DataVersion", target).with("data",
          new Nbt.Compound().with("minecraft:spawn_mobs", (byte) 0).with("custom:rule", 42));
      input.put("minecraft.overworld.game_rules.dat.nbt", Nbt.write(saved));
      input.put("custom.game_rules_backup.dat.nbt", Nbt.write(saved));
      var output = projection.projectMetadata(input, 4671, target);
      var rules = Nbt.read(output.get("minecraft.overworld.game_rules.dat.nbt")).compound("data");
      assertEquals((byte) 0, rules.get("minecraft:spawn_mobs"));
      assertEquals(3, rules.get("minecraft:random_tick_speed"));assertEquals(42, rules.get("custom:rule"));
      assertArrayEquals(input.get("custom.game_rules_backup.dat.nbt"), output.get("custom.game_rules_backup.dat.nbt"));
      assertFalse(Nbt.read(input.get("minecraft.overworld.game_rules.dat.nbt")).compound("data").containsKey("minecraft:random_tick_speed"));
    }
  }

  @Test void liveLevelUpgradesStaleDiskSchemaBeforeOverlayWithoutDfuForSameVersion() throws Exception {
    var stored = new Nbt.Compound().with("DataVersion", 4440).with("GameRules",
        new Nbt.Compound().with("doMobSpawning", "false"));
    var active = new Nbt.Compound().with("game_rules", new Nbt.Compound().with("minecraft:spawn_mobs", (byte) 0));
    int[] calls = {0};
    NbtUpgrader dfu = (type, raw, from, to) -> {
      calls[0]++;assertEquals("LEVEL", type);assertEquals(4440, from);assertEquals(4671, to);
      raw.remove("GameRules");raw.put("game_rules", new Nbt.Compound().with("minecraft:spawn_mobs", (byte) 1));
      return raw;
    };
    var projected = PaperMetadataProjection.liveLevel(stored, active, 4671, dfu);
    assertFalse(projected.containsKey("GameRules"));assertEquals((byte) 0, projected.compound("game_rules").get("minecraft:spawn_mobs"));
    assertTrue(stored.containsKey("GameRules"));assertEquals(4440, stored.integer("DataVersion", 0));
    var same = PaperMetadataProjection.liveLevel(projected, active, 4671, dfu);
    assertArrayEquals(Nbt.write(projected), Nbt.write(same));assertEquals(1, calls[0]);
    assertThrows(java.io.IOException.class, () -> PaperMetadataProjection.liveLevel(projected, active, 4440, dfu));
    assertEquals(1, calls[0]);
  }
}
