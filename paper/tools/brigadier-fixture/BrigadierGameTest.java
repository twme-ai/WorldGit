package org.worldgit.fabric.gametest;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.*;
import com.mojang.brigadier.tree.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.Rect2i;
import org.slf4j.LoggerFactory;

/** 只加入暫時 gametest classpath；操作真正聊天 UI、原生 client dispatcher 與收到的 server suggestions。 */
public final class BrigadierGameTest implements FabricClientGameTest {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("PaperBrigadierEvidence");
  @Override public void runTest(ClientGameTestContext ctx) {
    String address = "127.0.0.1:" + Integer.getInteger("wgtest.paperPort");
    Path ready = Path.of(System.getProperty("wgtest.paperReady"));
    ctx.runOnClient(c -> {
      c.options.renderDistance().set(3); c.options.enableVsync().set(false); c.options.languageCode = "en_us"; c.options.guiScale().set(2);
      ConnectScreen.startConnecting(new TitleScreen(), c, ServerAddress.parseString(address), new ServerData("Paper Brigadier", address, ServerData.Type.OTHER), false, null);
    });
    ctx.waitFor(c -> c.player != null && c.level != null, 1200);
    LOG.info("BRIGADIER connected={}", (Object) ctx.computeOnClient(c -> c.player.getName().getString()));
    ctx.waitFor(c -> Files.isRegularFile(ready), 12000);
    ctx.waitFor(c -> c.getConnection().getCommands().getRoot().getChild("wg").getChild("switch") != null, 1200);
    ctx.runOnClient(c -> {
      var root = c.getConnection().getCommands().getRoot().getChild("wg");
      Set<String> names = new HashSet<>(root.getChildren().stream().map(CommandNode::getName).toList());
      Set<String> expected = Set.of("init", "status", "commit", "log", "diff", "clear", "reload", "restore", "switch", "branch", "stash", "reset", "cancel", "merge", "resolve", "conflict-select", "conflicts", "conflict-preview", "tool", "revert", "cherry-pick", "remote", "fetch", "push", "pull", "pr", "comments", "comment", "help");
      if (!names.equals(expected)) throw new AssertionError("incorrect command tree " + names);
      if (c.getConnection().getCommands().getRoot().getChild("worldgit") == null) throw new AssertionError("missing alias");
      var from = (ArgumentCommandNode<?, ?>) root.getChild("restore").getChild("revision").getChild("--box").getChild("from");
      var dimension = (ArgumentCommandNode<?, ?>) root.getChild("comments").getChild("--dimension").getChild("dimension");
      if (!from.getType().getClass().getSimpleName().equals("BlockPosArgument")) throw new AssertionError(from.getType());
      // 26.2 將 ResourceLocationArgument 更名為 IdentifierArgument；兩者都是原生 namespaced key 型別。
      if (!Set.of("ResourceLocationArgument", "IdentifierArgument").contains(dimension.getType().getClass().getSimpleName())) throw new AssertionError(dimension.getType());
      LOG.info("BRIGADIER tree={} position={} dimension={}", names, from.getType().getClass().getSimpleName(), dimension.getType().getClass().getSimpleName());
    });
    screenshot(ctx, "/wg ", "root", null, false);
    screenshot(ctx, "/wg switch ", "switch-tooltip", "main", true);
    screenshot(ctx, "/wg resolve ", "resolve-tooltip", "1", true);
    screenshot(ctx, "/wg restore HEAD --box ~ ~ ~ ", "restore-relative", null, false);
    screenshot(ctx, "/wg restore HEAD --box ~ ~ ~ ~3 ~4 ~5 --dry-run", "restore-complete", null, false);
    screenshot(ctx, "/wg pr view ", "pr-tooltip", "1", true);
    screenshot(ctx, "/wg log nope", "integer-error", null, false);
    screenshot(ctx, "/wg restore HEAD --box ~ nope ~", "coordinate-error", null, false);
    ctx.runOnClient(c -> {
      var dispatcher = c.getConnection().getCommands(); var source = c.getConnection().getSuggestionsProvider();
      for (String input : List.of("wg log nope", "wg restore HEAD --box ~ nope ~")) {
        var result = dispatcher.parse(input, source);
        if (!result.getReader().canRead() || result.getExceptions().isEmpty()) throw new AssertionError("missing client type error " + input);
        LOG.info("BRIGADIER error input={} cursor={} message={}", input, result.getReader().getCursor(), result.getExceptions());
      }
      var valid = dispatcher.parse("wg restore HEAD --box ~ ~ ~ ~3 ~4 ~5 --dry-run", source);
      if (valid.getReader().canRead() || !valid.getExceptions().isEmpty()) throw new AssertionError("relative coordinates do not parse " + valid.getExceptions());
    });
    LOG.info("BRIGADIER deop-ready");
    ctx.waitFor(c -> Files.isRegularFile(Path.of(ready + ".deop")), 12000);
    ctx.waitFor(c -> c.getConnection().getCommands().getRoot().getChild("wg").getChild("switch") == null, 1200);
    ctx.runOnClient(c -> {
      var names = new HashSet<>(c.getConnection().getCommands().getRoot().getChild("wg").getChildren().stream().map(CommandNode::getName).toList());
      if (!names.equals(Set.of("log", "clear", "help"))) throw new AssertionError("permission tree leaked " + names);
      LOG.info("BRIGADIER viewer={}", names);
    });
    screenshot(ctx, "/wg ", "no-permission-root", null, false);
    ctx.runOnClient(c -> { GameTestScreens.play(c); c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE); GameTestScreens.title(c); });
    ctx.waitFor(c -> c.level == null, 600); LOG.info("BRIGADIER DONE");
  }

  private static void screenshot(ClientGameTestContext ctx, String input, String label, String selected, boolean tooltip) {
    ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_T);
    ctx.waitFor(c -> GameTestScreens.current(c) instanceof ChatScreen, 600);
    ctx.runOnClient(c -> {
      ((net.minecraft.client.gui.components.EditBox) field(GameTestScreens.current(c), "input")).setValue(input);
      var suggestions = (CommandSuggestions) field(GameTestScreens.current(c), "commandSuggestions");
      suggestions.setAllowSuggestions(true); suggestions.updateCommandInfo();
    });
    ctx.waitFor(c -> {
      var pending = (CompletableFuture<?>) field(field(GameTestScreens.current(c), "commandSuggestions"), "pendingSuggestions");
      return pending != null && pending.isDone();
    }, 1200);
    if (selected != null) {
      // 初次未命中快取若已於 750ms 回空，給背景的有界 IO 完成後重新請求。
      ctx.waitTicks(40);
      ctx.runOnClient(c -> ((CommandSuggestions) field(GameTestScreens.current(c), "commandSuggestions")).updateCommandInfo());
      ctx.waitFor(c -> {
        var pending = (CompletableFuture<Suggestions>) field(field(GameTestScreens.current(c), "commandSuggestions"), "pendingSuggestions");
        return pending != null && pending.isDone() && pending.join().getList().stream().anyMatch(s -> s.getText().equals(selected));
      }, 1200);
    }
    double[] mouse = ctx.computeOnClient(c -> {
      var suggestions = (CommandSuggestions) field(GameTestScreens.current(c), "commandSuggestions"); suggestions.showSuggestions(false);
      Object list = field(suggestions, "suggestions");
      if (list == null) return new double[]{0, 0};
      var rows = (List<Suggestion>) field(list, "suggestionList");
      LOG.info("BRIGADIER suggestions label={} input={} rows={}", label, input, rows.stream().map(s -> s.getText() + " :: " + (s.getTooltip() == null ? "" : s.getTooltip().getString())).toList());
      int index = 0;
      if (selected != null) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).getText().equals(selected)) index = i;
        if (tooltip && rows.get(index).getTooltip() == null) throw new AssertionError("missing tooltip " + label);
      }
      var rect = (Rect2i) field(list, "rect");
      int visible = Math.max(1, rect.getHeight() / 12);
      int offset = Math.min(Math.max(0, index - 4), Math.max(0, rows.size() - visible));
      setField(list, "offset", offset);
      try {
        var select = list.getClass().getDeclaredMethod("select", int.class); select.setAccessible(true); select.invoke(list, index);
      } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
      double scale = c.getWindow().getGuiScale();
      return new double[]{(rect.getX() + Math.max(1, Math.min(8, rect.getWidth() / 2))) * scale, (rect.getY() + (index - offset) * 12 + 6) * scale};
    });
    ctx.getInput().setCursorPos(mouse[0], mouse[1]); ctx.waitTicks(15); ctx.takeScreenshot("brigadier-" + label);
    if (tooltip) LOG.info("BRIGADIER tooltip-rendered={}", label);
    ctx.runOnClient(c -> GameTestScreens.play(c));
  }
  private static Object field(Object object, String name) {
    try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }
  private static void setField(Object object, String name, Object value) {
    try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }
}
