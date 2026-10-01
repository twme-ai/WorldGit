package org.worldgit.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.worldgit.core.model.DimensionId;

/** 分支列表（跨維度合併、預設分支、ahead／behind）、比較 API、授權 404 與請求上限。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class BranchCompareTest extends AbstractBranchTest {
  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) throws Exception {
    prepareDirs();
    r.add("server.address", () -> "127.0.0.1");
    r.add("server.port", () -> "18095");
    r.add("worldgit.hub.limits.work", () -> "100000000"); // 6001 個 section 的截斷驗證用，產品仍預設 5000 萬
    r.add("worldgit.hub.data-dir", () -> data.toString());
    r.add("worldgit.hub.bootstrap.admin-token", () -> TOKEN);
    r.add("worldgit.hub.bootstrap.admin-password", () -> "test-password-1");
  }

  String base() { return "/api/v1/worlds/admin/" + WORLD; }

  JsonNode branch(JsonNode page, String name) {
    for (JsonNode b : page.get("branches")) if (b.get("name").asText().equals(name)) return b;
    return fail("沒有分支 " + name + "：" + page);
  }

  @Test
  void branchesMergeAcrossDimensionsAndCountBySnapshot() throws Exception {
    var page = json(base() + "/branches", TOKEN);
    assertEquals("main", page.get("defaultBranch").asText());
    assertEquals(List.of("main", "feature", "side").stream().sorted().toList(), names(page.get("branches"), "name").stream().sorted().toList());
    assertEquals("main", page.get("branches").get(0).get("name").asText(), "預設分支排最前");
    assertEquals(3, page.get("declaredDimensions").size());

    var main = branch(page, "main");
    assertTrue(main.get("isDefault").asBoolean());
    assertEquals(3, main.get("heads").size(), "main 在三個維度都有 head");
    assertTrue(main.get("consistent").asBoolean());
    assertTrue(main.get("ahead").isNull() && main.get("behind").isNull(), "預設分支沒有 ahead／behind");
    assertEquals(mainSnapshot, main.get("snapshot").asText());

    var feature = branch(page, "feature");
    assertFalse(feature.get("isDefault").asBoolean());
    assertEquals(3, feature.get("heads").size(), "同名分支跨維度合併成一列");
    assertTrue(feature.get("consistent").asBoolean());
    assertFalse(feature.get("aligned").asBoolean(), "主世界 head 是新存檔，地獄／終界停在初始存檔");
    assertEquals(1, feature.get("ahead").asInt(), "ahead 以存檔計：feature 多一個存檔");
    assertEquals(1, feature.get("behind").asInt(), "behind 以存檔計：main 多一個存檔");
    assertTrue(feature.get("message").asText().startsWith("feature："));
    assertEquals(featureSnapshot, feature.get("snapshot").asText());

    var side = branch(page, "side");
    assertEquals(1, side.get("heads").size(), "side 只推了主世界");
    assertFalse(side.get("consistent").asBoolean());
    assertEquals(2, side.get("missingDimensions").size());
    assertEquals(0, side.get("ahead").asInt());
    assertEquals(1, side.get("behind").asInt());
    assertTrue(side.get("aligned").asBoolean());
  }

  @Test
  void snapshotsCanBeFilteredByBranch() throws Exception {
    var onMain = names(json(base() + "/snapshots", TOKEN).get("snapshots"), "snapshot");
    assertEquals(List.of(mainSnapshot, initialSnapshot), onMain);
    var onFeature = names(json(base() + "/snapshots?branch=feature", TOKEN).get("snapshots"), "snapshot");
    assertEquals(List.of(featureSnapshot, initialSnapshot), onFeature);
    var side = json(base() + "/snapshots?branch=side", TOKEN).get("snapshots");
    assertEquals(1, side.size());
    assertTrue(side.get(0).get("partial").asBoolean(), "side 缺少其他維度");
    assertEquals(404, get(base() + "/snapshots?branch=nope", TOKEN).statusCode());
    assertEquals(400, get(base() + "/snapshots?branch=a..b", TOKEN).statusCode());
  }

  @Test
  void compareBranchesGivesPerDimensionStats() throws Exception {
    var r = json(base() + "/compare?a=main&b=feature", TOKEN);
    assertEquals("branch", r.get("a").get("kind").asText());
    assertFalse(r.get("identical").asBoolean());
    var dims = r.get("dimensions");
    assertEquals(3, dims.size());
    JsonNode overworld = null;
    for (JsonNode d : dims) {
      if (d.get("dimension").asText().equals("minecraft:overworld")) overworld = d;
      else assertEquals("same", d.get("status").asText(), "地獄／終界沒有變動");
    }
    assertNotNull(overworld);
    assertEquals("changed", overworld.get("status").asText());
    // a→b：金塊 +64、拆石柱與綠寶石 -5、石柱改鑽石 ~4。
    long total = overworld.get("added").asLong() + overworld.get("removed").asLong() + overworld.get("modified").asLong();
    assertEquals(73, total);
    assertEquals(64, overworld.get("added").asLong());
    assertEquals(5, overworld.get("removed").asLong());
    assertEquals(4, overworld.get("modified").asLong());
    assertEquals(1, overworld.get("changedSections").size());
    assertEquals(4, overworld.get("changedSections").get(0).get(2).asInt());
    assertEquals(1, overworld.get("chunkCount").asInt());
    assertEquals(1, overworld.get("changedChunks").size());
    assertEquals(Math.floorDiv(mainCell[0], 16), overworld.get("changedChunks").get(0).get(0).asInt());
    assertFalse(overworld.get("chunksTruncated").asBoolean());
    assertEquals(r.get("added").asLong() + r.get("removed").asLong() + r.get("modified").asLong(), 73);
    assertFalse(overworld.get("mcVersion").asText().isBlank());

    // 反向：相同格數，新增／移除反轉
    var back = json(base() + "/compare?a=feature&b=main", TOKEN);
    assertEquals(73, back.get("added").asLong() + back.get("removed").asLong() + back.get("modified").asLong());

    // 自己比自己 → identical
    assertTrue(json(base() + "/compare?a=main&b=main", TOKEN).get("identical").asBoolean());
    // HEAD 是預設分支
    assertTrue(json(base() + "/compare?a=HEAD&b=main", TOKEN).get("identical").asBoolean());
  }

  @Test
  void compareAcceptsCommitIdsIncludingOffDefaultBranch() throws Exception {
    var page = json(base() + "/snapshots?branch=feature", TOKEN);
    String featureHead = null;
    for (JsonNode s : page.get("snapshots")) if (s.get("snapshot").asText().equals(featureSnapshot)) featureHead = s.get("commits").get("minecraft:overworld").get("id").asText();
    assertNotNull(featureHead);
    String mainHead = json(base() + "/snapshots", TOKEN).get("snapshots").get(0).get("commits").get("minecraft:overworld").get("id").asText();

    // feature 的 commit 不在 main 的歷史上，仍可用前綴比較
    var r = json(base() + "/compare?a=" + featureHead.substring(0, 10) + "&b=" + mainHead.substring(0, 10), TOKEN);
    assertEquals("commit", r.get("a").get("kind").asText());
    assertEquals(featureSnapshot, r.get("a").get("snapshot").asText());
    JsonNode overworld = null;
    for (JsonNode d : r.get("dimensions")) if (d.get("dimension").asText().equals("minecraft:overworld")) overworld = d;
    assertEquals("changed", overworld.get("status").asText());
    assertEquals(featureHead, overworld.get("a").get("id").asText());
    assertEquals(mainHead, overworld.get("b").get("id").asText());
    // 兩次編輯皆只在主世界產生 commit；沒有推測其他維度的當時狀態。
    for (JsonNode d : r.get("dimensions")) if (!d.get("dimension").asText().equals("minecraft:overworld")) assertEquals("same", d.get("status").asText());

    // 非預設分支的 commit 詳情也要能讀
    assertEquals(200, get(base() + "/dims/minecraft.overworld/commits/" + featureHead.substring(0, 8), TOKEN).statusCode());

    // 3D 比較用的 diff／entities 端點：base 可以是其他分支上的 commit
    int cx = Math.floorDiv(mainCell[0], 16), cz = Math.floorDiv(mainCell[2], 16);
    String q = "base=" + featureHead + "&x0=" + cx + "&z0=" + cz + "&x1=" + cx + "&z1=" + cz;
    var diff = get(base() + "/dims/minecraft.overworld/commits/" + mainHead + "/diff?" + q, TOKEN);
    assertEquals(200, diff.statusCode());
    var buf = ByteBuffer.wrap(diff.body());
    byte[] magic = new byte[4];
    buf.get(magic);
    assertEquals("WGDF", new String(magic));
    buf.get();
    int states = buf.getShort() & 0xffff;
    for (int i = 0; i < states; i++) buf.position(buf.position() + 2 + (buf.getShort(buf.position()) & 0xffff));
    assertEquals(1, buf.getInt(), "視窗內 1 個有變動的 section");
    assertEquals(200, get(base() + "/dims/minecraft.overworld/commits/" + mainHead + "/entities?" + q, TOKEN).statusCode());
  }

  @Test
  void baseBranchAndDefaultHeadChangesAreObserved() throws Exception {
    var p = json(base() + "/branches?base=feature", TOKEN);
    assertEquals("feature", p.get("comparedTo").asText());
    assertEquals(1, branch(p, "main").get("ahead").asInt());
    assertEquals(1, branch(p, "main").get("behind").asInt());
    assertTrue(branch(p, "feature").get("ahead").isNull());
    assertEquals(404, get(base() + "/branches?base=nope", TOKEN).statusCode());
    var path = storage.repoPath("admin", WORLD, DimensionId.OVERWORLD);
    try {
      org.worldgit.hub.tools.BranchFixture.head(path, "feature");
      assertEquals("feature", json(base() + "/branches", TOKEN).get("defaultBranch").asText());
      assertTrue(json(base() + "/compare?a=HEAD&b=feature", TOKEN).get("identical").asBoolean());
      assertEquals(featureSnapshot, json(base() + "/snapshots", TOKEN).get("snapshots").get(0).get("snapshot").asText());
      org.worldgit.hub.tools.BranchFixture.head(path, "unborn");
      assertEquals("main", json(base() + "/branches", TOKEN).get("defaultBranch").asText());
      assertTrue(json(base() + "/compare?a=HEAD&b=main", TOKEN).get("identical").asBoolean());
      assertEquals(mainSnapshot, json(base() + "/snapshots", TOKEN).get("snapshots").get(0).get("snapshot").asText());
    } finally { org.worldgit.hub.tools.BranchFixture.head(path, "main"); }
  }

  @Test
  void snapshotCountsSubtractWorldUnionsAndDeclaredMissingReposAreVisible() throws Exception {
    var admin = accounts.findUser("admin").orElseThrow();
    accounts.createWorld(admin, "admin", "unions", "unions", "", false).orElseThrow();
    // 同一存檔出现在不同分支的不同維度：世界層級仍是共有存檔，不能在兩邊各算一次。
    for (var d : org.worldgit.hub.tools.BranchFixture.DIMS) {
      var path = storage.create("admin", "unions", d);
      try (var store = new org.worldgit.core.store.JGitStore(path, false)) {
        String tree = org.worldgit.hub.tools.BranchFixture.tree(store, d, "initial");
        String init = store.commit(tree, null, org.worldgit.hub.tools.BranchFixture.metadata(d, org.worldgit.hub.tools.BranchFixture.INITIAL, "initial", 0));
        String shared = store.commit(tree, init, org.worldgit.hub.tools.BranchFixture.metadata(d, org.worldgit.hub.tools.BranchFixture.FEATURE, "shared snapshot", 1));
        org.worldgit.hub.tools.BranchFixture.ref(path, "feature", d.equals(DimensionId.OVERWORLD) ? init : shared);
        if (!d.equals(DimensionId.OVERWORLD)) org.worldgit.hub.tools.BranchFixture.ref(path, "main", init);
      }
    }
    var p = json("/api/v1/worlds/admin/unions/branches", TOKEN);
    assertEquals(0, branch(p, "feature").get("ahead").asInt());
    assertEquals(0, branch(p, "feature").get("behind").asInt());
    // 移除一個已宣告維度的 repo，仍須列為缺少分支。
    PathCleanup.delete(storage.repoPath("admin", "unions", new DimensionId("minecraft:the_end")));
    p = json("/api/v1/worlds/admin/unions/branches", TOKEN);
    assertFalse(branch(p, "main").get("consistent").asBoolean());
    assertEquals(List.of("minecraft:the_end"), namesAsText(branch(p, "main").get("missingDimensions")));
  }

  private static List<String> namesAsText(JsonNode a) {
    var out = new ArrayList<String>();
    a.forEach(x -> out.add(x.asText()));
    return out;
  }

  @Test
  void branchCapAndJsonByteCapFailExplicitly() throws Exception {
    var admin = accounts.findUser("admin").orElseThrow();
    accounts.createWorld(admin, "admin", "limits", "limits", "", false).orElseThrow();
    var path = storage.create("admin", "limits", DimensionId.OVERWORLD);
    String id;
    try (var store = new org.worldgit.core.store.JGitStore(path, false)) {
      id = store.commit(store.writeTree(List.of()), null, org.worldgit.hub.tools.BranchFixture.metadata(DimensionId.OVERWORLD,
          org.worldgit.hub.tools.BranchFixture.INITIAL, "limits", 0));
    }
    try (var git = org.eclipse.jgit.api.Git.open(path.toFile())) {
      for (int i = 0; i < 500; i++) {
        var u = git.getRepository().updateRef("refs/heads/limit-" + i);
        u.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(id));
        u.update();
      }
    }
    assertEquals(413, get("/api/v1/worlds/admin/limits/branches", TOKEN).statusCode());
    // 一個合法的近 1 MiB commit 訊息在幾個分支列重複出現，輸出仍受 4 MiB 上限。
    accounts.createWorld(admin, "admin", "jsonlimit", "jsonlimit", "", false).orElseThrow();
    path = storage.create("admin", "jsonlimit", DimensionId.OVERWORLD);
    try (var store = new org.worldgit.core.store.JGitStore(path, false)) {
      id = store.commit(store.writeTree(List.of()), null, org.worldgit.hub.tools.BranchFixture.metadata(DimensionId.OVERWORLD,
          org.worldgit.hub.tools.BranchFixture.INITIAL, "q".repeat(900_000), 0));
    }
    for (int i = 0; i < 3; i++) org.worldgit.hub.tools.BranchFixture.ref(path, "large-" + i, id);
    var r = get("/api/v1/worlds/admin/jsonlimit/branches", TOKEN);
    assertEquals(413, r.statusCode(), new String(r.body()));
    assertTrue(new String(r.body()).contains("4 MiB"));
  }

  @Test
  void compareChunkListCapPreservesExactTotalsAndBounds() throws Exception {
    var admin = accounts.findUser("admin").orElseThrow();
    accounts.createWorld(admin, "admin", "wide", "wide", "", false).orElseThrow();
    for (var dim : List.of(DimensionId.OVERWORLD, new DimensionId("minecraft:the_nether"))) {
    var path = storage.create("admin", "wide", dim);
    try (var store = new org.worldgit.core.store.JGitStore(path, false)) {
      String init = store.commit(store.writeTree(List.of()), null, org.worldgit.hub.tools.BranchFixture.metadata(dim,
          org.worldgit.hub.tools.BranchFixture.INITIAL, "empty", 0));
      org.worldgit.hub.tools.BranchFixture.ref(path, "empty", init);
      var chunk = store.writeTree(List.of(new org.worldgit.core.store.ObjectStore.Entry("structures", org.worldgit.core.store.ObjectStore.Kind.BLOB, store.writeBlob(new byte[] {1}))));
      var children = new java.util.TreeMap<Integer, List<org.worldgit.core.store.ObjectStore.Entry>>();
      for (int x = 0; x < 2005; x++) children.computeIfAbsent(Math.floorDiv(x, 32), k -> new ArrayList<>())
          .add(new org.worldgit.core.store.ObjectStore.Entry("c." + x + ".0", org.worldgit.core.store.ObjectStore.Kind.TREE, chunk));
      var regions = new ArrayList<org.worldgit.core.store.ObjectStore.Entry>();
      for (var e : children.entrySet()) regions.add(new org.worldgit.core.store.ObjectStore.Entry("r." + e.getKey() + ".0", org.worldgit.core.store.ObjectStore.Kind.TREE, store.writeTree(e.getValue())));
      store.commit(store.writeTree(regions), init, org.worldgit.hub.tools.BranchFixture.metadata(dim,
          org.worldgit.hub.tools.BranchFixture.MAIN, "wide", 1));
    }
    }
    var r = json("/api/v1/worlds/admin/wide/compare?a=empty&b=main", TOKEN);
    assertEquals(4010, r.get("chunkCount").asInt());
    int listed = 0;
    for (var dimension : r.get("dimensions")) listed += dimension.get("changedChunks").size();
    assertEquals(2000, listed, "清單上限為跨維度合計");
    var d = r.get("dimensions").get(0);
    assertEquals(2005, d.get("chunkCount").asInt());
    assertEquals(2000, d.get("changedChunks").size());
    assertTrue(d.get("chunksTruncated").asBoolean());
    assertEquals(2004, d.get("bounds").get(2).asInt());
    assertEquals(0, r.get("added").asLong());
  }

  @Test
  void compareSectionListCapPreservesBlockTotals() throws Exception {
    var admin = accounts.findUser("admin").orElseThrow();
    accounts.createWorld(admin, "admin", "tall", "tall", "", false).orElseThrow();
    var path = storage.create("admin", "tall", DimensionId.OVERWORLD);
    try (var store = new org.worldgit.core.store.JGitStore(path, false)) {
      String init = store.commit(store.writeTree(List.of()), null, org.worldgit.hub.tools.BranchFixture.metadata(DimensionId.OVERWORLD,
          org.worldgit.hub.tools.BranchFixture.INITIAL, "empty", 0));
      org.worldgit.hub.tools.BranchFixture.ref(path, "empty", init);
      String section = store.writeBlob(org.worldgit.core.normalize.SnapshotCodec.section(new org.worldgit.core.model.Section(
          java.util.Collections.nCopies(4096, new org.worldgit.core.model.BlockState("minecraft:stone")), java.util.Map.of())));
      var files = new ArrayList<org.worldgit.core.store.ObjectStore.Entry>();
      for (int y = 0; y < 24; y++) files.add(new org.worldgit.core.store.ObjectStore.Entry("s." + y + ".bin", org.worldgit.core.store.ObjectStore.Kind.BLOB, section));
      String full = store.writeTree(files);
      String single = store.writeTree(List.of(files.get(0)));
      var children = new java.util.TreeMap<Integer, List<org.worldgit.core.store.ObjectStore.Entry>>();
      for (int x = 0; x <= 250; x++) children.computeIfAbsent(Math.floorDiv(x, 32), k -> new ArrayList<>())
          .add(new org.worldgit.core.store.ObjectStore.Entry("c." + x + ".0", org.worldgit.core.store.ObjectStore.Kind.TREE, x == 250 ? single : full));
      var regions = new ArrayList<org.worldgit.core.store.ObjectStore.Entry>();
      for (var e : children.entrySet()) regions.add(new org.worldgit.core.store.ObjectStore.Entry("r." + e.getKey() + ".0", org.worldgit.core.store.ObjectStore.Kind.TREE, store.writeTree(e.getValue())));
      store.commit(store.writeTree(regions), init, org.worldgit.hub.tools.BranchFixture.metadata(DimensionId.OVERWORLD,
          org.worldgit.hub.tools.BranchFixture.MAIN, "6001 sections", 1));
    }
    var r = json("/api/v1/worlds/admin/tall/compare?a=empty&b=main", TOKEN);
    var d = r.get("dimensions").get(0);
    assertEquals(6001, d.get("sectionCount").asInt());
    assertEquals(6000, d.get("changedSections").size());
    assertTrue(d.get("sectionsTruncated").asBoolean());
    assertEquals(6001L * 4096, r.get("added").asLong());
  }

  private static class PathCleanup {
    static void delete(java.nio.file.Path p) throws Exception {
      try (var files = java.nio.file.Files.walk(p)) { for (var f : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(f); }
    }
  }

  @Test
  void invalidAndUnknownRevisions() throws Exception {
    assertEquals(404, get(base() + "/compare?a=main&b=nope", TOKEN).statusCode());
    assertEquals(404, get(base() + "/compare?a=deadbeef&b=main", TOKEN).statusCode());
    assertEquals(400, get(base() + "/compare?a=main&b=" + "x".repeat(101), TOKEN).statusCode());
    assertEquals(400, get(base() + "/compare?a=main", TOKEN).statusCode());
    // 前綴碰到兩個物件時要明確拒絕，不能略過該維度後選成另一個 commit。
    var admin = accounts.findUser("admin").orElseThrow();
    accounts.createWorld(admin, "admin", "ambiguous", "ambiguous", "", false).orElseThrow();
    var path = storage.create("admin", "ambiguous", DimensionId.OVERWORLD);
    String ambiguous = null;
    try (var formatter = new org.eclipse.jgit.lib.ObjectInserter.Formatter();
        var store = new org.worldgit.core.store.JGitStore(path, false)) {
      var seen = new java.util.HashMap<String, byte[]>();
      for (int i = 0; i < 10000; i++) {
        byte[] raw = ("collision-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String prefix = formatter.idFor(org.eclipse.jgit.lib.Constants.OBJ_BLOB, raw).name().substring(0, 4);
        byte[] previous = seen.putIfAbsent(prefix, raw);
        if (previous == null) continue;
        store.writeBlob(previous); store.writeBlob(raw); ambiguous = prefix; break;
      }
      assertNotNull(ambiguous);
      store.commit(store.writeTree(List.of()), null, org.worldgit.hub.tools.BranchFixture.metadata(DimensionId.OVERWORLD,
          org.worldgit.hub.tools.BranchFixture.INITIAL, "ambiguous", 0));
    }
    assertEquals(400, get("/api/v1/worlds/admin/ambiguous/compare?a=main&b=" + ambiguous, TOKEN).statusCode());
    // 視窗上限沿用：一次最多 1024 個 chunk
    assertEquals(400, get(base() + "/dims/minecraft.overworld/commits/HEAD/diff?base=feature&x0=0&z0=0&x1=63&z1=63", TOKEN).statusCode());
  }

  @Test
  void privateWorldsAreInvisibleWithoutPermission() throws Exception {
    var bob = accounts.createUser("branch-bob", "test-password-1", false);
    String bobToken = accounts.createToken(bob, "test", false);
    for (String path : new String[] {base() + "/branches", base() + "/compare?a=main&b=feature", base() + "/snapshots?branch=feature"}) {
      assertEquals(404, get(path, null).statusCode(), "匿名 " + path);
      assertEquals(404, get(path, bobToken).statusCode(), "無權限使用者 " + path);
      assertEquals(200, get(path, TOKEN).statusCode(), "擁有者 " + path);
    }
    // 不存在的世界與無權限的世界回應一致
    var missing = get("/api/v1/worlds/admin/no-such-world/branches", null);
    var hidden = get(base() + "/branches", null);
    assertEquals(missing.statusCode(), hidden.statusCode());

    // 公開世界匿名可讀分支列表；沒有 commit 的 repo 回傳空清單
    var alice = accounts.createUser("branch-alice", "test-password-1", false);
    accounts.createWorld(alice, "branch-alice", "open", "open", "", true);
    storage.create("branch-alice", "open", DimensionId.OVERWORLD);
    var open = json("/api/v1/worlds/branch-alice/open/branches", null);
    assertEquals(0, open.get("branches").size());
    assertEquals(404, get("/api/v1/worlds/branch-alice/open/compare?a=main&b=feature", null).statusCode());
  }
}
