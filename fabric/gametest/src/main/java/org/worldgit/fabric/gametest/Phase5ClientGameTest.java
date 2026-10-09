package org.worldgit.fabric.gametest;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import org.worldgit.fabric.WorldGitMod;
import org.worldgit.fabric.client.ClientRuntime;
import org.worldgit.fabric.client.ScreenDriver;

/**
 * Phase 5 真客戶端（Xvfb）：單人整合伺服器或連到 Fabric dedicated 伺服器，由 Python 以 request-N.json／response-N.json 控制。
 * 客戶端只做玩家做得到的輸入（指令、點選按鈕、輸入文字、右鍵實體）；聊天訊息連同 click／hover JSON 一併回報，供驗收斷言。
 */
final class Phase5ClientGameTest {
  private static final Gson JSON = new Gson();
  private static Path directory;
  private static final List<JsonObject> CHAT = new CopyOnWriteArrayList<>();

  static void run(ClientGameTestContext ctx) {
    directory = Path.of(System.getProperty("wgtest.phase5Dir"));
    boolean single = Boolean.getBoolean("wgtest.phase5Single");
    ctx.runOnClient(c -> {
      c.options.renderDistance().set(4);
      c.options.simulationDistance().set(4);
      c.options.enableVsync().set(false);
      c.options.framerateLimit().set(Boolean.getBoolean("wgtest.performance") ? 20 : 60);
      // Automated commands do not count as native mouse/keyboard input. Vanilla's
      // ten-minute AFK limit otherwise throttles the synchronized test server to 10 TPS.
      c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
      c.options.languageCode = System.getProperty("wgtest.phase5Language", "en_us");
      c.options.guiScale().set(2);
    });
    ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
      if (overlay) return;
      last = message;
      var row = new JsonObject();
      row.addProperty("text", message.getString());
      row.addProperty("receivedNanos", System.nanoTime());
      try {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var access = minecraft.level == null ? net.minecraft.core.RegistryAccess.EMPTY : minecraft.level.registryAccess();
        row.add("json", JsonParser.parseString(ComponentSerialization.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, access), message).getOrThrow().toString()));
      } catch (RuntimeException e) {
        row.addProperty("jsonError", e.toString());
      }
      CHAT.add(row);
    });
    var session = new Session();
    try {
      if (single) {
        var world = ctx.worldBuilder().adjustSettings(ui -> ui.setAllowCommands(false)).create();
        session.world = world;
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("gamerule random_tick_speed 0");
        world.getServer().runCommand("gamerule spawn_mobs false");
        world.getServer().runCommand("tp @a 8 -58 8");
        loop(ctx, true);
      } else {
        String address = "127.0.0.1:" + Integer.getInteger("wgtest.paperPort");
        ctx.runOnClient(c -> ConnectScreen.startConnecting(new TitleScreen(), c, ServerAddress.parseString(address), new ServerData("Fabric Phase 5", address, ServerData.Type.OTHER), false, null));
        loop(ctx, false);
      }
    } finally {
      session.close(ctx);
    }
    ctx.runOnClient(c -> {
      if (c.level != null) c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
      GameTestScreens.title(c);
    });
    ctx.waitFor(c -> c.level == null, 600);
    WorldGitClientGameTest.LOG.info("FABRIC5 DONE");
  }

  private static final class Session {
    net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world;

    void close(ClientGameTestContext ctx) {
      var server = ctx.<net.minecraft.server.MinecraftServer, RuntimeException>computeOnClient(c -> c.getSingleplayerServer());
      if (world != null) {
        world.close();
        world = null;
      } else ctx.runOnClient(c -> {
        if (c.level != null) c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
      });
      ctx.waitFor(c -> c.level == null && (server == null || !server.getRunningThread().isAlive()), 1200);
    }
  }

  private static volatile net.minecraft.network.chat.Component last;

  /** 最近一則（非 overlay）遊戲訊息。 */
  static net.minecraft.network.chat.Component lastMessage() { return last; }

  private static void loop(ClientGameTestContext ctx, boolean single) {
    ctx.waitFor(c -> c.player != null && c.level != null && ClientRuntime.get().handshaken(), 3600);
    var ready = ctx.<JsonObject, RuntimeException>computeOnClient(c -> {
      var v = new JsonObject();
      v.addProperty("player", c.player.getName().getString());
      v.addProperty("uiCapable", ClientRuntime.get().uiCapable());
      if (single) {
        var rt = WorldGitMod.runtime(c.getSingleplayerServer());
        v.addProperty("world", rt.worldRoot().toString());
        v.addProperty("repository", rt.repositoryRoot().toString());
      }
      return v;
    });
    write("ready.json", ready);
    WorldGitClientGameTest.LOG.info("FABRIC5 READY {}", ready);
    for (int sequence = 1;; sequence++) {
      String name = "request-" + sequence + ".json";
      ctx.waitFor(c -> Files.exists(directory.resolve(name)), 72000);
      var request = read(name);
      String action = request.get("action").getAsString();
      var out = new JsonObject();
      switch (action) {
        case "axiom-editor" -> {
          // Optional interoperability probe: press the real keybinding; Axiom retains its permission/licensing checks.
          ctx.runOnClient(c -> {
            try {
              var events = Class.forName("com.moulberry.axiom.ClientEvents");
              var key = (net.minecraft.client.KeyMapping) events.getField("toggleEditorUiKeyBind").get(null);
              net.minecraft.client.KeyMapping.click(key.getDefaultKey());
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
          });
          ctx.waitTicks(20);
          out.addProperty("enabled", ctx.<Boolean, RuntimeException>computeOnClient(c -> {
            try { return (Boolean) Class.forName("com.moulberry.axiom.editor.EditorUI").getMethod("isEnabled").invoke(null); }
            catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
          }));
        }
        case "command" -> {
          String command = request.get("command").getAsString();
          ctx.runOnClient(c -> {
            out.addProperty("sentNanos", System.nanoTime());
            c.getConnection().sendCommand(command);
          });
          ctx.waitTicks(4);
          if (single && !request.has("nowait")) {
            ctx.waitFor(c -> {
              var rt = WorldGitMod.runtime(c.getSingleplayerServer());
              return !rt.operationActive() && !rt.ui().busy();
            }, 36000);
            ctx.waitTicks(10);
          }
        }
        case "server-command" -> {
          if (!single) throw new AssertionError("server-command requires singleplayer");
          String command = request.get("command").getAsString();
          ctx.runOnClient(c -> c.getSingleplayerServer().execute(() -> c.getSingleplayerServer().getCommands().performPrefixedCommand(c.getSingleplayerServer().createCommandSourceStack(), command)));
          ctx.waitTicks(10);
        }
        case "chat" -> {
          int from = request.has("from") ? request.get("from").getAsInt() : 0;
          var rows = new JsonArray();
          for (int i = from; i < CHAT.size(); i++) rows.add(CHAT.get(i));
          out.add("messages", rows);
          out.addProperty("size", CHAT.size());
        }
        case "screenshot" -> out.addProperty("file", ctx.takeScreenshot(request.get("name").getAsString()).getFileName().toString());
        case "hud" -> {
          out.addProperty("count", ctx.<Integer, RuntimeException>computeOnClient(c -> ClientRuntime.get().progressCount()));
          out.addProperty("summary", ctx.<String, RuntimeException>computeOnClient(c -> ClientRuntime.get().progressSummary()));
          var bars = new JsonArray();
          ctx.<List<String>, RuntimeException>computeOnClient(c -> bossNames(c)).forEach(bars::add);
          out.add("bars", bars);
        }
        case "wait-hud" -> {
          String prefix = request.get("prefix").getAsString();
          int timeout = request.has("timeout") ? request.get("timeout").getAsInt() : 3600;
          try {
            ctx.waitFor(c -> ClientRuntime.get().progressSummary().startsWith(prefix), timeout);
            out.addProperty("ok", true);
          } catch (AssertionError e) {
            out.addProperty("ok", false);
          }
          out.addProperty("summary", ctx.<String, RuntimeException>computeOnClient(c -> ClientRuntime.get().progressSummary()));
        }
        case "wait-hud-gone" -> {
          int timeout = request.has("timeout") ? request.get("timeout").getAsInt() : 1200;
          try {
            ctx.waitFor(c -> ClientRuntime.get().progressCount() == 0, timeout);
            out.addProperty("ok", true);
          } catch (AssertionError e) {
            out.addProperty("ok", false);
          }
        }
        case "screen" -> {
          out.addProperty("name", ctx.<String, RuntimeException>computeOnClient(c -> ScreenDriver.screenName()));
          var buttons = new JsonArray();
          ctx.<List<String>, RuntimeException>computeOnClient(c -> ScreenDriver.buttons()).forEach(buttons::add);
          out.add("buttons", buttons);
          var ignoreRows = new JsonArray();
          ctx.<List<String>, RuntimeException>computeOnClient(c -> ScreenDriver.ignoreRows()).forEach(ignoreRows::add);
          out.add("ignoreRows", ignoreRows);
          out.addProperty("nodes", ctx.<Integer, RuntimeException>computeOnClient(c -> ScreenDriver.graphNodes()));
          out.addProperty("input", ctx.<String, RuntimeException>computeOnClient(c -> ScreenDriver.inputValue()));
        }
        case "wait-screen" -> {
          String screen = request.get("name").getAsString();
          int timeout = request.has("timeout") ? request.get("timeout").getAsInt() : 1200;
          try {
            ctx.waitFor(c -> ScreenDriver.screenName().equals(screen), timeout);
            out.addProperty("ok", true);
          } catch (AssertionError e) {
            out.addProperty("ok", false);
          }
          out.addProperty("name", ctx.<String, RuntimeException>computeOnClient(c -> ScreenDriver.screenName()));
          ctx.waitTicks(10);
        }
        case "click" -> out.addProperty("ok", ctx.<Boolean, RuntimeException>computeOnClient(c -> ScreenDriver.click(request.get("label").getAsString())));
        case "refresh-graph" -> {
          long before = ctx.<Long, RuntimeException>computeOnClient(c -> ClientRuntime.get().graphPublishedId());
          if (!ctx.<Boolean, RuntimeException>computeOnClient(c -> ScreenDriver.click("Refresh"))) throw new AssertionError("Refresh missing");
          ctx.waitFor(c -> ClientRuntime.get().graphPublishedId() > before, 600);
          out.addProperty("ok", true);
        }
        case "type" -> out.addProperty("ok", ctx.<Boolean, RuntimeException>computeOnClient(c -> ScreenDriver.type(request.get("text").getAsString())));
        case "select" -> out.addProperty("value", ctx.<Integer, RuntimeException>computeOnClient(c -> ScreenDriver.select(request.get("index").getAsInt())));
        case "close" -> ctx.runOnClient(c -> ScreenDriver.close());
        case "clipboard" -> out.addProperty("text", ctx.<String, RuntimeException>computeOnClient(c -> c.keyboardHandler.getClipboard()));
        case "prepare-dimension" -> {
          if (!single) throw new AssertionError("prepare-dimension requires singleplayer");
          String dimension = request.get("dimension").getAsString();
          sessionCommand(ctx, "execute in " + dimension + " run tp @a 8 " + (dimension.equals("minecraft:overworld") ? "-58" : "65") + " 8");
          ctx.waitFor(c -> c.level != null && c.level.dimension().identifier().toString().equals(dimension), 2400);
          ctx.waitTicks(200);
          var server = ctx.<net.minecraft.server.MinecraftServer, RuntimeException>computeOnClient(c -> c.getSingleplayerServer());
          var saved = server.submit(() -> server.saveEverything(true, true, true));
          ctx.waitFor(c -> saved.isDone(), 2400);
          saved.join();
        }
        case "natural-cow" -> {
          if (!single) throw new AssertionError("natural-cow requires singleplayer");
          var future = new java.util.concurrent.CompletableFuture<String>();
          ctx.runOnClient(c -> c.getSingleplayerServer().execute(() -> {
            var player = c.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
            var level = (net.minecraft.server.level.ServerLevel) player.level();
            var cow = (net.minecraft.world.entity.Mob) net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getValue(net.minecraft.resources.Identifier.parse("minecraft:cow")).create(level, net.minecraft.world.entity.EntitySpawnReason.NATURAL);
            cow.setPos(player.getX() + 1, player.getY(), player.getZ());
            cow.setNoAi(true); cow.setNoGravity(true); level.addFreshEntity(cow);
            future.complete(cow.getUUID().toString());
          }));
          ctx.waitFor(c -> future.isDone(), 600);
          out.addProperty("uuid", future.join());
          ctx.waitTicks(10);
        }
        case "place-armor" -> {
          out.addProperty("ok", ctx.<Boolean, RuntimeException>computeOnClient(c -> {
            var pos = c.player.blockPosition().below().west();
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos).add(0, .5, 0), net.minecraft.core.Direction.UP, pos, false);
            return c.gameMode.useItemOn(c.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit).consumesAction();
          }));
          ctx.waitTicks(20);
        }
        case "wait-ticks" -> ctx.waitTicks(request.get("ticks").getAsInt());
        case "language" -> ctx.runOnClient(c -> {
          c.options.languageCode = request.get("code").getAsString();
          c.getLanguageManager().setSelected(request.get("code").getAsString());
          c.reloadResourcePacks();
        });
        case "interact" -> {
          String uuid = request.get("uuid").getAsString();
          out.addProperty("ok", ctx.<Boolean, RuntimeException>computeOnClient(c -> {
            for (var entity : c.level.entitiesForRendering()) {
              if (entity.getUUID().toString().equals(uuid)) return GameTestScreens.interact(c, entity);
            }
            return false;
          }));
          ctx.waitTicks(10);
        }
        case "entities" -> {
          String type = request.get("type").getAsString();
          var ids = new JsonArray();
          ctx.<List<String>, RuntimeException>computeOnClient(c -> {
            var found = new ArrayList<String>();
            for (var entity : c.level.entitiesForRendering())
              if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(type)) found.add(entity.getUUID().toString());
            return found;
          }).forEach(ids::add);
          out.add("uuids", ids);
        }
        case "chat-open" -> {
          ctx.runOnClient(c -> ScreenDriver.openChat(""));
          ctx.waitTicks(3);
        }
        case "hover-chat" -> {
          // 把滑鼠移到最後一則訊息中帶 copy_to_clipboard 的片段上（與玩家移動滑鼠相同），讓原版畫出 hover 提示。
          var point = ctx.<double[], RuntimeException>computeOnClient(c -> ChatProbe.locate(c, "copy_to_clipboard"));
          out.addProperty("found", point != null);
          if (point != null) {
            ctx.getInput().setCursorPos(point[0], point[1]);
            ctx.waitTicks(6);
            out.addProperty("x", point[0]);
            out.addProperty("y", point[1]);
          }
        }
        case "state" -> out.addProperty("dimension", ctx.<String, RuntimeException>computeOnClient(c -> c.level.dimension().identifier().toString()));
        case "stop" -> {
          write("response-" + sequence + ".json", out);
          return;
        }
        default -> throw new AssertionError(action);
      }
      write("response-" + sequence + ".json", out);
    }
  }

  private static JsonObject read(String name) {
    try {
      return JSON.fromJson(Files.readString(directory.resolve(name)), JsonObject.class);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void sessionCommand(ClientGameTestContext ctx, String command) {
    ctx.runOnClient(c -> c.getSingleplayerServer().execute(() -> c.getSingleplayerServer().getCommands().performPrefixedCommand(c.getSingleplayerServer().createCommandSourceStack(), command)));
    ctx.waitTicks(10);
  }

  /** 私有欄位名稱會 remap；按欄位型別讀取真原版 BossBar，測試不合成 bar。 */
  private static List<String> bossNames(net.minecraft.client.Minecraft client) {
    var overlay = GameTestScreens.bossOverlay(client);
    try {
      for (var field : overlay.getClass().getDeclaredFields()) if (Map.class.isAssignableFrom(field.getType())) {
        field.setAccessible(true);
        var names = new ArrayList<String>();
        for (var value : ((Map<?, ?>) field.get(overlay)).values()) names.add(((net.minecraft.world.BossEvent) value).getName().getString());
        return names;
      }
      throw new AssertionError("BossBar events field missing");
    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
  }

  private static void write(String name, JsonObject value) {
    try {
      var tmp = directory.resolve(name + ".tmp");
      Files.writeString(tmp, JSON.toJson(value));
      Files.move(tmp, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
