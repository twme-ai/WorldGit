package org.worldgit.fabric.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.model.DimensionId;
import org.worldgit.fabric.WorldGitMod;
import org.worldgit.fabric.client.ClientRuntime;
import org.worldgit.protocol.Protocol;

/**
 * 真正的 Minecraft 客戶端（Xvfb＋軟體渲染）上的單人世界驗收：
 * 建立世界 → /wg init → 放方塊 → /wg status 恰好 1 個 section → /wg diff --show（描邊／鬼影）→ /wg commit，
 * 再用合成的 diff/status 封包畫出四種狀態、色盲色票、LOD 與穿透；最後以 zh_tw 重跑一次確認訊息語言。
 * 通過標準以斷言及 {@code WGTEST ...} 日誌行記錄。
 */
public final class WorldGitClientGameTest implements FabricClientGameTest {
  static final Logger LOG = LoggerFactory.getLogger("WorldGitGameTest");
  static final DimensionId OVERWORLD = DimensionId.OVERWORLD;

  @Override
  public void runTest(ClientGameTestContext ctx) {
    ctx.runOnClient(client -> {
      client.options.renderDistance().set(4);
      client.options.simulationDistance().set(5);
      client.options.enableVsync().set(false);
      client.options.framerateLimit().set(120);
      client.options.languageCode = "en_us";
    });
    if(Boolean.getBoolean("wgtest.phase4")) { Phase4ClientGameTest.run(ctx);return; }
    int paperPort = Integer.getInteger("wgtest.paperPort", 0);
    if (paperPort != 0) {
      if (Boolean.getBoolean("wgtest.paperPhase3")) PaperPhase3ClientGameTest.run(ctx,paperPort);
      else PaperClientGameTest.run(ctx, paperPort);
      return;
    }
    if(Boolean.getBoolean("wgtest.phase2")) { Phase2ClientGameTest.run(ctx); return; }
    if(Boolean.getBoolean("wgtest.phase3")) { Phase3ClientGameTest.run(ctx); return; }
    english(ctx);
    chinese(ctx);
    LOG.info("WGTEST DONE");
  }

  // ---- 英文單人世界：完整流程 ---------------------------------------------------------

  private void english(ClientGameTestContext ctx) {
    try (var world = ctx.worldBuilder().create()) {
      var server = world.getServer();
      setup(ctx, server);
      server.runCommand("setblock 5 -60 5 minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
      server.runCommand("setblock 7 -60 7 minecraft:cobblestone");
      ctx.waitFor(c -> ClientRuntime.get().handshaken(), 400);
      LOG.info("WGTEST handshake=true");

      server.runCommand("wg init");
      awaitInitialized(ctx, server);
      ctx.waitTicks(10);
      ctx.takeScreenshot("01-init-chat");

      // 草地 y=-61；站在 (5,-58,-4) 面向南（+z）看 3..7 的方塊。
      server.runCommand("setblock 3 -60 3 minecraft:stone");   // 新增
      server.runCommand("setblock 5 -60 5 minecraft:air");     // 移除（樓梯）
      server.runCommand("setblock 7 -60 7 minecraft:dirt");    // 修改（鵝卵石 → 泥土）
      var status = await(ctx, server.computeOnServer(s -> WorldGitMod.runtime(s).status(false)), 1200);
      var diff = status.dimensions().get(OVERWORLD).value().diff();
      var counts = diff.counts();
      LOG.info("WGTEST status sections={} added={} removed={} modified={} conflict={}", diff.sections().size(), counts.added(), counts.removed(), counts.modified(), counts.conflict());
      check(status.success(), "status 維度操作失敗");
      check(diff.sections().size() == 1 && counts.added() == 1 && counts.removed() == 1 && counts.modified() == 1,
          "預期恰好 1 section、+1 -1 ~1，實際為 " + diff);
      // Force vanilla to clear unsaved, then ensure our generation still exposes the same changes.
      server.runOnServer(s -> s.saveEverything(true, true, true));
      var savedStatus = await(ctx, server.computeOnServer(s -> WorldGitMod.runtime(s).status(false)), 1200);
      check(savedStatus.success() && savedStatus.dimensions().get(OVERWORLD).value().diff().sections().size() == 1,
          "存檔後遺失 dirty generation");
      LOG.info("WGTEST saved-status sections=1");

      server.runCommand("wg status");
      ctx.waitTicks(20);
      ctx.takeScreenshot("02-status-chat");

      client(ctx, "wg diff --show"); // 需要真實玩家作為指令來源
      ctx.waitFor(c -> ClientRuntime.get().summary() != null, 1200);
      ctx.waitTicks(20);
      var summary = summary(ctx);
      check(summary.cells() == 3 && summary.ghost(), "預览應有 3 格");
      LOG.info("WGTEST client-preview {}", summary);
      hideGui(ctx);
      ctx.takeScreenshot("03-diff-default");
      client(ctx, "wgc palette colorblind");
      ctx.waitTicks(10);
      check(summary(ctx).detailSections() > 0, "色盲切換後明細沒有重建");
      LOG.info("WGTEST colorblind detail={}", summary(ctx).detailSections());
      ctx.takeScreenshot("04-diff-colorblind");
      client(ctx, "wgc palette default");

      // 穿透：在相機與目標之間放一道牆（預覽已是快照，不受影響）
      server.runCommand("fill 2 -60 0 8 -58 0 minecraft:stone");
      ctx.waitTicks(20);
      ctx.takeScreenshot("05-occluded");
      client(ctx, "wgc seethrough true");
      ctx.waitTicks(10);
      ctx.takeScreenshot("06-seethrough");
      client(ctx, "wgc seethrough false");
      server.runCommand("fill 2 -60 0 8 -58 0 minecraft:air");
      ctx.waitTicks(10);

      hideGui(ctx);
      client(ctx, "wg commit -m client game test");
      awaitLog(ctx, server, 2);
      var clean = await(ctx, server.computeOnServer(s -> WorldGitMod.runtime(s).status(false)), 1200);
      check(clean.success() && clean.dimensions().get(OVERWORLD).value().diff().sections().isEmpty(), "commit 後應為 clean");
      java.nio.file.Path worldPath = server.computeOnServer(s -> WorldGitMod.runtime(s).worldRoot());
      LOG.info("WGTEST world={}", worldPath);
      server.runCommand("wg log");
      ctx.waitTicks(20);
      ctx.takeScreenshot("07-commit-log-chat");

      synthetic(ctx, server);
    }
  }

  /** 合成封包：四種狀態、LOD（大量格數）、status 外框；證明渲染在沒有真實伺服器計算時也成立。 */
  private void synthetic(ClientGameTestContext ctx, TestServerContext server) {
    hideGui(ctx);
    server.runCommand("tp @p 11 -57 2 0 28");
    ctx.waitTicks(10);

    var cells = new ArrayList<Protocol.Cell>();
    String stairs = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";
    for (int i = 0; i < 4; i++) {
      cells.add(new Protocol.Cell(8 + i * 2, -60, 8, ChangeKind.ADDED, "minecraft:air", "minecraft:stone"));
      cells.add(new Protocol.Cell(8 + i * 2, -60, 10, ChangeKind.REMOVED, stairs, "minecraft:air"));
      cells.add(new Protocol.Cell(8 + i * 2, -60, 12, ChangeKind.MODIFIED, stairs, "minecraft:cobblestone"));
      cells.add(new Protocol.Cell(8 + i * 2, -60, 14, ChangeKind.CONFLICT, "minecraft:stone", "minecraft:dirt"));
    }
    publishDiff(ctx, 1000, cells);
    ctx.waitTicks(15);
    ctx.takeScreenshot("08-four-kinds-a");
    ctx.waitTicks(25);
    ctx.takeScreenshot("09-four-kinds-b-pulse");

    // LOD：3,072 格、6 個 section。遠處只畫包圍盒，靠近才逐格。
    var many = new ArrayList<Protocol.Cell>();
    for (int x = 40; x < 72; x++)
      for (int z = 0; z < 32; z++)
        for (int y = -60; y < -57; y++) {
          ChangeKind kind = switch (Math.floorMod(x * 7 + z * 13 + y, 4)) {
            case 0 -> ChangeKind.ADDED;
            case 1 -> ChangeKind.REMOVED;
            case 2 -> ChangeKind.MODIFIED;
            default -> ChangeKind.CONFLICT;
          };
          many.add(new Protocol.Cell(x, y, z, kind, "minecraft:stone", "minecraft:dirt"));
        }
    publishDiff(ctx, 1001, many);
    server.runCommand("tp @p 20 -43 -55 -26 13");
    ctx.waitFor(c -> ClientRuntime.get().summary() != null && ClientRuntime.get().summary().previewId() == 1001, 600);
    ctx.waitTicks(30);
    LOG.info("WGTEST lod-far {}", summary(ctx));
    check(summary(ctx).detailSections() == 0, "遠處應只畫包圍盒");
    ctx.takeScreenshot("10-lod-far-bbox");
    server.runCommand("tp @p 54 -53 -14 0 19");
    ctx.waitTicks(60);
    LOG.info("WGTEST lod-near {}", summary(ctx));
    check(summary(ctx).detailSections() > 0, "靠近後應建立明細");
    ctx.takeScreenshot("11-lod-near-detail");

    // status 外框（只有包圍盒的預覽）
    var outlines = List.of(
        new Protocol.Outline(40, -61, 0, 55, -59, 15, ChangeKind.ADDED, 100, 0, 0, 0),
        new Protocol.Outline(56, -61, 0, 71, -59, 15, ChangeKind.REMOVED, 0, 100, 0, 0),
        new Protocol.Outline(40, -61, 16, 55, -59, 31, ChangeKind.MODIFIED, 0, 0, 100, 0),
        new Protocol.Outline(56, -61, 16, 71, -59, 31, ChangeKind.CONFLICT, 0, 0, 0, 100));
    publishStatus(ctx, 1002, outlines);
    server.runCommand("tp @p 55 -52 -20 0 30");
    ctx.waitTicks(40);
    ctx.takeScreenshot("12-status-outlines");
    client(ctx, "wgc status");
    ctx.waitTicks(20);
    showGui(ctx);
    ctx.takeScreenshot("13-wgc-status-en");
    client(ctx, "wgc clear");
    ctx.waitTicks(10);
    LOG.info("WGTEST cleared summary={}", summary(ctx));
    check(summary(ctx) == null, "clear 後仍有預覽");
  }

  // ---- 繁體中文世界：伺服端與客戶端訊息語言 ---------------------------------------------

  private void chinese(ClientGameTestContext ctx) {
    ctx.runOnClient(client -> client.options.languageCode = "zh_tw");
    try (var world = ctx.worldBuilder().create()) {
      var server = world.getServer();
      setup(ctx, server);
      ctx.waitFor(c -> ClientRuntime.get().handshaken(), 400);
      client(ctx, "wg init");
      awaitInitialized(ctx, server);
      server.runCommand("setblock 3 -60 3 minecraft:stone");
      var status = await(ctx, server.computeOnServer(s -> WorldGitMod.runtime(s).status(false)), 1200);
      LOG.info("WGTEST zh-status sections={}", status.dimensions().get(OVERWORLD).value().diff().sections().size());
      check(status.success() && status.dimensions().get(OVERWORLD).value().diff().sections().size() == 1, "中文世界應有 1 section");
      client(ctx, "wg status");
      client(ctx, "wg commit -m 中文提交");
      awaitLog(ctx, server, 2);
      client(ctx, "wgc status");
      ctx.waitTicks(20);
      ctx.takeScreenshot("14-zh-tw-chat");
    }
  }

  // ---- 工具 ---------------------------------------------------------------------------

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void setup(ClientGameTestContext ctx, TestServerContext server) {
    server.runCommand("gamerule random_tick_speed 0");
    server.runCommand("gamerule spawn_mobs false");
    server.runCommand("gamemode creative @p");
    server.runCommand("time set noon");
    server.runCommand("weather clear");
    server.runCommand("tp @p 5 -58 -4 0 24");
    ctx.waitTicks(200); // 讓玩家周圍的 chunk 全部生成完，init 才不會漏掉稍後才出現的新 chunk
  }

  /** init 完成 = repo 已存在且第一個 commit（Initialize world）已寫入。 */
  private static void awaitInitialized(ClientGameTestContext ctx, TestServerContext server) {
    awaitLog(ctx, server, 1);
    LOG.info("WGTEST initialized");
  }

  private static void awaitLog(ClientGameTestContext ctx, TestServerContext server, int expected) {
    for (int attempt = 0; attempt < 120; attempt++) {
      int commits = 0;
      try {
        commits = await(ctx, server.computeOnServer(s -> WorldGitMod.runtime(s).log(10)), 600).stream().filter(row -> row.commits().containsKey(DimensionId.OVERWORLD)).toList().size();
      } catch (java.util.concurrent.CompletionException notYet) {
        // repo 尚未建立完成
      }
      if (commits >= expected) {
        LOG.info("WGTEST log commits={}", commits);
        return;
      }
      ctx.waitTicks(10);
    }
    throw new AssertionError("等待 commit 逾時（預期 " + expected + "）");
  }

  private static <T> T await(ClientGameTestContext ctx, CompletableFuture<T> future, int maxTicks) {
    for (int i = 0; i < maxTicks && !future.isDone(); i++) ctx.waitTick();
    if (!future.isDone()) throw new AssertionError("操作逾時");
    return future.join();
  }

  private static ClientRuntime.Summary summary(ClientGameTestContext ctx) {
    return ctx.<ClientRuntime.Summary, RuntimeException>computeOnClient(c -> ClientRuntime.get().summary());
  }

  private static void client(ClientGameTestContext ctx, String command) {
    ctx.runOnClient(c -> c.getConnection().sendCommand(command));
  }

  private static boolean guiHidden;

  private static void hideGui(ClientGameTestContext ctx) {
    if (guiHidden) return;
    ctx.getInput().pressKey(GLFW.GLFW_KEY_F1);
    guiHidden = true;
    ctx.waitTicks(2);
  }

  private static void showGui(ClientGameTestContext ctx) {
    if (!guiHidden) return;
    ctx.getInput().pressKey(GLFW.GLFW_KEY_F1);
    guiHidden = false;
    ctx.waitTicks(2);
  }

  private static void publishDiff(ClientGameTestContext ctx, long id, List<Protocol.Cell> cells) {
    int per = 250, parts = (cells.size() + per - 1) / per;
    var packets = new ArrayList<byte[]>();
    try {
      for (int p = 0; p < parts; p++) {
        var header = new Protocol.Header(id, p, parts, cells.size(), OVERWORLD);
        packets.add(Protocol.encode(new Protocol.DiffPart(header, cells.subList(p * per, Math.min(cells.size(), (p + 1) * per)))));
      }
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
    ctx.runOnClient(c -> packets.forEach(ClientRuntime.get()::onPreview));
  }

  private static void publishStatus(ClientGameTestContext ctx, long id, List<Protocol.Outline> outlines) {
    byte[] packet;
    try {
      packet = Protocol.encode(new Protocol.StatusPart(new Protocol.Header(id, 0, 1, outlines.size(), OVERWORLD), outlines));
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
    ctx.runOnClient(c -> ClientRuntime.get().onPreview(packet));
  }
}
