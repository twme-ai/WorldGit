package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.ApplyPlanner;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.merge.TreeFilter;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.normalize.MetadataNormalizer;
import org.worldgit.core.store.*;

/** 26.x 的時鐘、天氣、流浪商人等 runtime saved-data 必須和 1.21.11 的 level.dat 白名單同語意。 */
class RuntimeSavedDataTest {
  @TempDir Path temp;

  @Test void legacyAndNamespacedStopwatchesTrackNamesAndPreserveCurrentTimestampsOnApply() throws Exception {
    for (String path : List.of("data/stopwatches.dat","data/minecraft/stopwatches.dat")) {
      assertNull(SavedData.normalize(key(path),nbt(new Nbt.Compound().with("stopwatches",new Nbt.Compound()))));
      var raw=nbt(new Nbt.Compound().with("stopwatches",new Nbt.Compound().with("custom:watch",123L)).with("custom",42));
      var canonical=Nbt.read(SavedData.normalize(key(path),raw));
      assertEquals(0L,canonical.compound("data").compound("stopwatches").get("custom:watch"));
      assertEquals(42,canonical.compound("data").get("custom"));
      var current=new Nbt.Compound().with("data",new Nbt.Compound().with("stopwatches",new Nbt.Compound().with("custom:watch",456L)));
      var applied=SavedData.materialize(temp.resolve(path),canonical,current);
      assertEquals(456L,applied.compound("data").compound("stopwatches").get("custom:watch"));
      assertEquals(42,applied.compound("data").get("custom"));
    }
  }

  @Test void paperNoneDfuCodecHeadersAreRuntimeButEveryPayloadFieldRemainsTracked() throws Exception {
    String name = "minecraft.overworld.saved." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("data/paper/metadata.dat".getBytes(StandardCharsets.UTF_8)) + ".nbt";
    var identity = new Nbt.Compound().with("uuid",new int[]{1,2,3,4});
    byte[] first = SavedData.normalize(name,Nbt.write(new Nbt.Compound().with("DataVersion",4790).with("data",identity)));
    assertArrayEquals(first,SavedData.normalize(name,Nbt.write(new Nbt.Compound().with("DataVersion",5023).with("data",identity))));
    identity.put("uuid",new int[]{5,6,7,8});
    assertFalse(Arrays.equals(first,SavedData.normalize(name,Nbt.write(new Nbt.Compound().with("DataVersion",5023).with("data",identity)))));
    for (int version : List.of(4671,5122)) {
      byte[] raw=Nbt.write(new Nbt.Compound().with("DataVersion",version).with("data",identity));
      assertArrayEquals(raw,SavedData.normalize(name,raw));
    }
    var unknown=new Nbt.Compound().with("DataVersion",5023).with("data",identity).with("custom",42);
    assertArrayEquals(Nbt.write(unknown),SavedData.normalize(name,Nbt.write(unknown)));
    identity.put("custom",42);
    unknown=new Nbt.Compound().with("DataVersion",5023).with("data",identity);
    assertArrayEquals(Nbt.write(unknown),SavedData.normalize(name,Nbt.write(unknown)));
  }

  @Test void forcedTicketInfiniteRepresentationsAndOrderingAreEquivalentAcrossNativePaths() throws Exception {
    for (String path : List.of("data/chunks.dat", "data/minecraft/chunk_tickets.dat")) {
      var first = new Nbt.Compound().with("chunk_pos", new int[] {2, -3}).with("level", 31)
          .with("type", "minecraft:forced").with("ticks_left", 0L);
      var second = new Nbt.Compound().with("chunk_pos", new int[] {0, 0}).with("level", 31)
          .with("type", "minecraft:forced").with("ticks_left", Long.MIN_VALUE);
      var a = nbt(new Nbt.Compound().with("tickets", new Nbt.ListTag(10, List.of(first, second))));
      var b = nbt(new Nbt.Compound().with("tickets", new Nbt.ListTag(10,
          List.of(Nbt.copy(second).with("ticks_left", 0L), Nbt.copy(first).with("ticks_left", Long.MIN_VALUE)))));
      assertArrayEquals(SavedData.normalize(key(path), a), SavedData.normalize(key(path), b));
      var output = Nbt.read(SavedData.normalize(key(path), a)).compound("data").list("tickets");
      assertEquals(2, output.values().size());
      assertTrue(output.values().stream().noneMatch(x -> ((Nbt.Compound) x).containsKey("ticks_left")));
      assertTrue(first.containsKey("ticks_left"));
      var changed = Nbt.copy(first).with("level", 30);
      assertFalse(Arrays.equals(SavedData.normalize(key(path), a), SavedData.normalize(key(path),
          nbt(new Nbt.Compound().with("tickets", new Nbt.ListTag(10, List.of(changed, second)))))));
    }
  }

  @Test void finiteAndCustomTicketDataRemainMeaningfulAndNamespaceScoped() throws Exception {
    for (String type : List.of("minecraft:portal", "mod:forced")) {
      var ticket = new Nbt.Compound().with("chunk_pos", new int[] {2, -3}).with("level", 31)
          .with("type", type).with("ticks_left", 100L).with("custom", "preserve");
      var a = nbt(new Nbt.Compound().with("tickets", new Nbt.ListTag(10, List.of(ticket))));
      var b = nbt(new Nbt.Compound().with("tickets", new Nbt.ListTag(10, List.of(Nbt.copy(ticket).with("ticks_left", 99L)))));
      var output = Nbt.read(SavedData.normalize(key("data/chunks.dat"), a)).compound("data");
      assertEquals(100L, ((Nbt.Compound) output.list("tickets").values().getFirst()).get("ticks_left"));
      assertEquals("preserve", ((Nbt.Compound) output.list("tickets").values().getFirst()).string("custom"));
      assertFalse(Arrays.equals(SavedData.normalize(key("data/chunks.dat"), a), SavedData.normalize(key("data/chunks.dat"), b)));
      assertArrayEquals(a, SavedData.normalize(key("data/mod/chunks.dat"), a));
    }
  }

  @Test void optionalSavedDataListsFollowTheNativeCodecAndRetainNonemptyOrUnknownContent() throws Exception {
    for (String path : List.of("data/raids.dat", "data/raids_nether.dat", "data/minecraft/raids.dat")) {
      var empty = nbt(new Nbt.Compound().with("next_id", 7));
      assertArrayEquals(SavedData.normalize(key(path), empty), SavedData.normalize(key(path),
          nbt(new Nbt.Compound().with("next_id", 7).with("raids", new Nbt.ListTag(10, List.of())))));
      var raid = new Nbt.Compound().with("id", 3).with("center", new int[] {1, 64, 2});
      var output = Nbt.read(SavedData.normalize(key(path), nbt(new Nbt.Compound().with("next_id", 7)
          .with("raids", new Nbt.ListTag(10, List.of(raid)))))).compound("data");
      assertEquals(7, output.integer("next_id", 0));
      assertArrayEquals(Nbt.write(raid), Nbt.write((Nbt.Compound) output.list("raids").values().getFirst()));
      assertTrue(Nbt.read(SavedData.normalize(key(path), nbt(new Nbt.Compound()
          .with("NextAvailableID", 7).with("Raids", new Nbt.ListTag(10, List.of())))))
          .compound("data").containsKey("Raids"));
    }
    for (String path : List.of("data/chunks.dat", "data/minecraft/chunk_tickets.dat")) {
      assertNull(SavedData.normalize(key(path), nbt(new Nbt.Compound().with("tickets", new Nbt.ListTag(10, List.of())))));
      var unknown = nbt(new Nbt.Compound().with("tickets", new Nbt.ListTag(10, List.of())).with("opaque", 7));
      assertEquals(7, Nbt.read(SavedData.normalize(key(path), unknown)).compound("data").integer("opaque", 0));
      var forced = Nbt.read(SavedData.normalize(key(path), nbt(new Nbt.Compound().with("Forced", new long[] {5, 1}))));
      assertArrayEquals(new long[] {1, 5}, (long[]) forced.compound("data").get("Forced"));
    }
  }
  private static final List<String> TRANSIENT =
      List.of("data/minecraft/world_clocks.dat", "data/minecraft/weather.dat", "data/minecraft/wandering_trader.dat");

  private static String key(String path) {
    return "saved." + Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8)) + ".nbt";
  }

  private static byte[] nbt(Nbt.Compound data) throws Exception {
    return Nbt.write(new Nbt.Compound().with("DataVersion", 4903).with("data", data));
  }

  private static void gz(Path file, Nbt.Compound data) throws Exception {
    Files.createDirectories(file.getParent());
    try (var out = new GZIPOutputStream(Files.newOutputStream(file))) { out.write(nbt(data)); }
  }

  @Test
  void captureSkipsRuntimeFilesButKeepsMeaningfulSavedData() throws Exception {
    Path world = temp.resolve("world");
    for (String path : TRANSIENT) gz(world.resolve(path), new Nbt.Compound().with("total_ticks", 5L));
    gz(world.resolve("data/minecraft/raids.dat"), new Nbt.Compound().with("tick", 9).with("next_id", 4));
    gz(world.resolve("data/minecraft/ender_dragon_fight.dat"), new Nbt.Compound().with("needs_state_scanning", (byte) 1));
    var captured = SavedData.capture(world, "");
    for (String path : TRANSIENT) assertFalse(captured.containsKey(key(path)), path);
    assertTrue(captured.containsKey(key("data/minecraft/raids.dat")));
    assertTrue(captured.containsKey(key("data/minecraft/ender_dragon_fight.dat")));
    assertEquals(4, Nbt.read(captured.get(key("data/minecraft/raids.dat"))).compound("data").integer("next_id", 0));
  }

  @Test
  void runtimeCursorsDoNotCountAsChanges() throws Exception {
    String sequences = key("data/minecraft/random_sequences.dat");
    byte[] a = nbt(new Nbt.Compound().with("sequences", new Nbt.Compound().with("minecraft:x", new Nbt.Compound().with("seed", 1L))));
    byte[] b = nbt(new Nbt.Compound().with("sequences", new Nbt.Compound().with("minecraft:x", new Nbt.Compound().with("seed", 99L))));
    assertArrayEquals(
        MetadataNormalizer.normalize(Map.of(sequences, a), IgnoreRules.none()).get(sequences),
        MetadataNormalizer.normalize(Map.of(sequences, b), IgnoreRules.none()).get(sequences));
    // 沒有遊戲意義內容時，整個檔案等同不存在。
    assertNull(SavedData.normalize(sequences, a));
    // /random reset 的 salt 設定仍追蹤。
    byte[] salted = nbt(new Nbt.Compound().with("sequences", new Nbt.Compound()).with("salt", 7));
    assertNotNull(SavedData.normalize(sequences, salted));
  }

  @Test
  void normalizerAndMetadataDropTransientEntriesFromOldHistory() throws Exception {
    var metadata = new TreeMap<String, byte[]>();
    for (String path : TRANSIENT) metadata.put(key(path), nbt(new Nbt.Compound().with("total_ticks", 5L)));
    metadata.put(key("data/minecraft/raids.dat"), nbt(new Nbt.Compound().with("tick", 9).with("next_id", 4)));
    var result = MetadataNormalizer.normalize(metadata, IgnoreRules.none());
    assertEquals(Set.of(key("data/minecraft/raids.dat")), result.keySet());
    assertFalse(Nbt.read(result.values().iterator().next()).compound("data").containsKey("tick"));
    // 同名但不是 data/*.dat 的 key（如模組資料）不受影響。
    assertFalse(SavedData.transientEntry("saved.bm90LWRhdGE.nbt"));
    assertNull(SavedData.relativePath("level.nbt"));
    assertEquals("data/minecraft/weather.dat", SavedData.displayName(key("data/minecraft/weather.dat")));
    assertEquals(
        "minecraft.the_nether: data/minecraft/foo.dat",
        SavedData.displayName("minecraft.the_nether." + key("data/minecraft/foo.dat")));
  }

  @Test
  void historyTreesWithTransientEntriesDiffFilterAndPlanCleanly() throws Exception {
    try (var store = new JGitStore(temp.resolve("repo"), true)) {
      String raids = key("data/minecraft/raids.dat");
      var old = new TreeEditor(store, null);
      var next = new TreeEditor(store, null);
      for (var editor : List.of(old, next)) editor.putBlob("world-meta/" + raids, nbt(new Nbt.Compound().with("next_id", 4)));
      int n = 0;
      for (String path : TRANSIENT) {
        old.putBlob("world-meta/" + key(path), nbt(new Nbt.Compound().with("total_ticks", 100L + n)));
        next.putBlob("world-meta/" + key(path), nbt(new Nbt.Compound().with("total_ticks", 900L + n++)));
      }
      String a = old.write(), b = next.write();
      store.flush();
      var diff = new DiffEngine(store).compare(DimensionId.OVERWORLD, a, b, 2);
      assertTrue(diff.metadata().isEmpty(), "transient 差異不得出現在 diff：" + diff.metadata());
      // 一側歷史有、另一側新 commit 沒有：同樣沒有差異（修正後 status 不得顯示假刪除）。
      var clean = new TreeEditor(store, null);
      clean.putBlob("world-meta/" + raids, nbt(new Nbt.Compound().with("next_id", 4)));
      String c = clean.write();
      store.flush();
      assertTrue(new DiffEngine(store).compare(DimensionId.OVERWORLD, a, c, 2).metadata().isEmpty());
      assertTrue(new DiffEngine(store).compare(DimensionId.OVERWORLD, c, a, 2).metadata().isEmpty());
      // 真正的 saved-data 變化仍看得到。
      var changed = new TreeEditor(store, c);
      changed.putBlob("world-meta/" + raids, nbt(new Nbt.Compound().with("next_id", 5)));
      String d = changed.write();
      store.flush();
      assertFalse(new DiffEngine(store).compare(DimensionId.OVERWORLD, a, d, 2).metadata().isEmpty());
      // TreeFilter：.wgignore 相同時仍會剝除舊歷史的暫態條目。
      String filtered = TreeFilter.filter(store, a, "", null);
      assertEquals(c, filtered);
      for (String path : TRANSIENT) assertNull(TreeEditor.find(store, filtered, "world-meta/" + key(path)));
      // ApplyPlanner 不認領暫態條目。
      for (String path : TRANSIENT) assertFalse(ApplyPlanner.ownsMetadata(DimensionId.OVERWORLD, key(path)));
      assertTrue(ApplyPlanner.ownsMetadata(DimensionId.OVERWORLD, raids));
    }
  }

  @Test
  void materializeKeepsCurrentRuntimeValues() throws Exception {
    Path file = temp.resolve("world/data/minecraft/raids.dat");
    var current = new Nbt.Compound().with("DataVersion", 4903).with("data", new Nbt.Compound().with("tick", 777).with("next_id", 1));
    var target = new Nbt.Compound().with("DataVersion", 4903).with("data", new Nbt.Compound().with("next_id", 3));
    var restored = SavedData.materialize(file, target, current);
    assertEquals(777, restored.compound("data").integer("tick", 0));
    assertEquals(3, restored.compound("data").integer("next_id", 0));
    Path clocks = temp.resolve("world/data/minecraft/world_clocks.dat");
    var live = new Nbt.Compound().with("data", new Nbt.Compound().with("total_ticks", 42L));
    assertEquals(42L, SavedData.materialize(clocks, new Nbt.Compound().with("data", new Nbt.Compound()), live).compound("data").get("total_ticks"));
  }
}
