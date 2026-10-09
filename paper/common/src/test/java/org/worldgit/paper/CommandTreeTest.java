package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.*;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.worldgit.i18n.MessageCatalog;

class CommandTreeTest {
  static CommandSourceStack source(boolean player, Set<String> permissions) {
    var sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
        new Class<?>[]{player ? Player.class : ConsoleCommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
          case "hasPermission" -> permissions.contains(args[0]);
          case "getLocale" -> "en_us";
          case "getName" -> "Test";
          case "hashCode" -> 1;
          case "equals" -> proxy == args[0];
          case "toString" -> "TestSender";
          default -> method.getReturnType().isPrimitive() ? method.getReturnType() == boolean.class ? false : 0 : null;
        });
    return new CommandSourceStack() {
      public CommandSender getSender() { return sender; }
      public Entity getExecutor() { return player ? (Player) sender : null; }
      public Location getLocation() { return new Location(null, 10, 64, -5); }
      public CommandSourceStack withLocation(Location location) { throw new UnsupportedOperationException(); }
      public CommandSourceStack withExecutor(Entity entity) { throw new UnsupportedOperationException(); }
    };
  }
  /** 測試 public API 的 injection 邊界；實際 native parser／wire type 另由真伺服器及客戶端 fixture 驗證。 */
  record NativeValue(String kind, List<String> words) { }
  record NativeArgument(String kind) implements ArgumentType<NativeValue> {
    public NativeValue parse(StringReader reader) throws CommandSyntaxException {
      var words = new ArrayList<String>();
      for (int i = 0; i < (kind.equals("position") ? 3 : 1); i++) {
        if (i > 0) reader.skipWhitespace(); words.add(CommandArguments.token(reader));
      }
      return new NativeValue(kind, words);
    }
  }
  CommandDispatcher<CommandSourceStack> dispatcher;
  List<CommandRequest> requests;
  CommandSourceStack admin = source(true, Set.of("worldgit.admin", "worldgit.debug"));
  @BeforeEach void setup() {
    Messages.configure(MessageCatalog.bundled(), "en_us"); requests = new ArrayList<>(); dispatcher = new CommandDispatcher<>();
    var suggestions = new CommandSuggestions(k -> CompletableFuture.completedFuture(List.of()), () -> null);
    dispatcher.register(new CommandTree((source, request) -> requests.add(request), suggestions, NativeArgument::new).build());
  }
  CommandRequest run(String command) throws Exception { dispatcher.execute("wg " + command, admin); return requests.getLast(); }
  CommandNode<CommandSourceStack> path(String... names) {
    CommandNode<CommandSourceStack> node = dispatcher.getRoot();
    for (String name : names) { node = node.getChild(name); assertNotNull(node, name); }
    return node;
  }
  @Test void completeTreeAndNativeTypes() {
    assertEquals(new HashSet<>(CommandTree.SUBS), new HashSet<>(path("wg").getChildren().stream().map(CommandNode::getName).toList()));
    assertEquals(integer(1, 100), ((ArgumentCommandNode<?, ?>) path("wg", "log", "limit")).getType());
    assertEquals(integer(0, 256), ((ArgumentCommandNode<?, ?>) path("wg", "restore", "revision", "--chunks", "chunks")).getType());
    assertEquals(new NativeArgument("position"), ((ArgumentCommandNode<?, ?>) path("wg", "restore", "revision", "--box", "from")).getType());
    assertEquals(new NativeArgument("position"), ((ArgumentCommandNode<?, ?>) path("wg", "restore", "revision", "--box", "from", "to")).getType());
    assertEquals(new NativeArgument("dimension"), ((ArgumentCommandNode<?, ?>) path("wg", "comments", "--dimension", "dimension")).getType());
    assertEquals(new NativeArgument("player"), ((ArgumentCommandNode<?, ?>) path("wg", "debug", "protection", "player")).getType());
    assertInstanceOf(CommandArguments.Token.class, ((ArgumentCommandNode<?, ?>) path("wg", "switch", "revision")).getType());
    assertEquals(StringArgumentType.StringType.GREEDY_PHRASE, ((StringArgumentType) ((ArgumentCommandNode<?, ?>) path("wg", "commit", "-m", "text")).getType()).getType());
    assertEquals(Set.of("ours", "theirs", "base", "manual"), new HashSet<>(path("wg", "resolve", "id").getChildren().stream().map(CommandNode::getName).toList()));
    assertEquals(Set.of("ours", "theirs", "base"), new HashSet<>(path("wg", "conflicts", "preview", "id").getChildren().stream().map(CommandNode::getName).toList()));
    // Vanilla 每次 ask_server 都取消上一個請求；同一位置只能有一個外部補全分支。
    for (var parent : List.of(path("wg", "resolve"), path("wg", "pr", "view"), path("wg", "comments"))) {
      assertEquals(1, parent.getChildren().stream().filter(n -> n instanceof ArgumentCommandNode<?, ?> a && a.getCustomSuggestions() != null).count());
      assertNull(((ArgumentCommandNode<?, ?>) parent.getChild("legacy_id")).getCustomSuggestions());
    }
  }
  private static IntegerArgumentType integer(int min, int max) { return IntegerArgumentType.integer(min, max); }
  private static StringArgumentType greedyString() { return StringArgumentType.greedyString(); }
  @Test void everyExistingActionParsesWithoutLegacyArrayParser() throws Exception {
    for (String command : List.of("init", "init --template creative", "init --template=survival", "status", "commit -m 中文 <red> message",
        "log", "diff", "clear", "reload", "restore HEAD", "switch main", "branch", "branch topic", "branch -d topic",
        "stash push", "stash push two words", "stash pop", "stash pop 3", "stash list", "stash drop", "reset --hard", "cancel",
        "merge main", "merge --continue", "merge --abort", "resolve 1 ours", "resolve #12 manual", "resolve all theirs",
        "conflict-select 1 base", "conflict-select all manual", "conflicts", "conflicts 2", "conflicts preview #1 ours",
        "conflict-preview 1 theirs", "tool", "revert HEAD~1", "cherry-pick abcdef12", "remote list",
        "remote add origin http://example.test/team/world", "remote set-url origin file:///tmp/world", "remote remove origin",
        "fetch", "fetch origin", "push", "push origin main", "pull", "pull origin main", "pull confirm 1234abcd", "pr list",
        "pr view 1", "pr view #2", "pr create title --source topic --target main", "comments", "comments pr 2", "comments show 1",
        "comments hide", "comments hide pr 1", "comment 1 two words --here", "help", "help restore", "debug apply", "debug fill 2 gold_block 3 8",
        "debug sample 0 1 -2", "debug entities overworld 0 0 inspect", "debug guard on", "debug protection Test",
        "debug fixture-container 0 64 0 stone", "debug fixture-clean-items", "debug merge-tool Test cycle", "debug merge-gui Test 3",
        "debug comment-camera Test", "debug comment-teleport Test minecraft:overworld", "debug comment-displays 0 0", "debug release",
        "debug probe start", "debug probe stop", "debug measurements")) {
      assertDoesNotThrow(() -> run(command), command);
    }
    assertEquals("two words", run("stash push two words").text("text"));
    assertEquals("中文 <red> message", run("commit -m 中文 <red> message").text("text"));
    assertEquals(12, run("resolve #12 manual").number("id", 0));
  }
  @Test void flagsAllOrdersAndTextCompatibility() throws Exception {
    assertEquals(run("status --full --show"), run("status --show --full"));
    assertEquals(run("diff --radius 6 --show"), run("diff --show --radius 6"));
    assertEquals(run("restore HEAD --dry-run --chunks 2"), run("restore HEAD --chunks 2 --dry-run"));
    var box = run("restore HEAD --box ~ ~ ~ ~3 ~4 ~5 --dry-run");
    assertEquals(List.of("~", "~", "~"), box.value("from", NativeValue.class).words());
    assertEquals(box, run("restore HEAD --dry-run --box ~ ~ ~ ~3 ~4 ~5"));
    for (String flags : List.of("--stash --force --dry-run", "--force --dry-run --stash", "--dry-run --stash --force"))
      assertEquals(Set.of("--stash", "--force", "--dry-run"), run("switch main " + flags).flags());
    assertEquals(run("push origin main --tags"), run("push --tags origin main"));
    assertEquals(run("push origin main --tags"), run("push origin --tags main"));
    assertEquals(run("pr create --source topic --target main A title"), run("pr create A title --target main --source topic"));
    assertEquals(run("pr create --target main A --source topic title"), run("pr create A title --target main --source topic"));
    assertEquals("A --source title", run("pr create \"A --source title\" --source topic").text("text"));
    for (String command : List.of("pr create title --source topic --source main", "pr create title --target main --target topic",
        "pr create --target main title --target topic"))
      assertThrows(CommandSyntaxException.class, () -> run(command), command);
    assertEquals(run("comment 2 --here Some text"), run("comment 2 Some text --here"));
    assertEquals("--here literal", run("comment 2 \"--here literal\"").text("text"));
    assertEquals(run("comments pr 2 --here"), run("comments --here 2"));
    assertEquals(run("comments --dimension minecraft:overworld pr 2"), run("comments 2 --dimension minecraft:overworld"));
  }
  @Test void permissionOnEveryDescendantAndPlayerBoundaries() throws Exception {
    var viewer = source(true, Set.of("worldgit.command.log", "worldgit.command.clear"));
    for (String sub : CommandTree.SUBS) {
      var node = path("wg", sub);
      if (Set.of("log", "clear", "help").contains(sub)) assertTrue(node.canUse(viewer), sub);
      else visit(node, descendant -> assertFalse(descendant.canUse(viewer), sub + " " + descendant.getName()));
    }
    assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("wg commit -m nope", viewer));
    assertTrue(path("wg", "help", "log").canUse(viewer));
    assertFalse(path("wg", "help", "switch").canUse(viewer));
    var console = source(false, Set.of("worldgit.admin"));
    for (String sub : List.of("tool", "diff", "clear", "conflict-preview")) assertFalse(path("wg", sub).canUse(console));
    assertFalse(path("wg", "restore", "revision", "--selection").canUse(console));
    assertFalse(path("wg", "comments", "show").canUse(console));
    assertFalse(path("wg", "status", "--show").canUse(console));
    assertTrue(path("wg", "debug").canUse(console));
    assertFalse(path("wg", "debug").canUse(source(true, Set.of("worldgit.admin"))));
    assertTrue(path("wg", "conflict-select").canUse(source(true, Set.of("worldgit.command.resolve"))));
    assertTrue(path("wg", "comments").canUse(source(true, Set.of("worldgit.command.comment"))));
  }
  static void visit(CommandNode<CommandSourceStack> node, java.util.function.Consumer<CommandNode<CommandSourceStack>> action) {
    action.accept(node); node.getChildren().forEach(child -> visit(child, action));
  }
  @Test void nativeAndCustomErrorsHaveCursorAndLocaleWithoutLeaks() throws Exception {
    for (String command : List.of("log 0", "log 101", "log abc", "diff --radius 33", "restore HEAD --chunks -1", "resolve 0 ours",
        "resolve 1 invalid", "stash pop -1", "pr view 0", "restore HEAD --chunks 1 --box 0 0 0 1 1 1", "push --force",
        "pr create title --source", "init --template invalid", "status --wat")) {
      var error = assertThrows(CommandSyntaxException.class, () -> run(command), command);
      assertTrue(error.getCursor() >= 3, command);
    }
    var error = assertThrows(CommandSyntaxException.class, () -> run("remote add origin https://user:PAT_SECRET@example.test/team/world"));
    assertFalse(error.getRawMessage().getString().contains("PAT_SECRET"));
    assertTrue(error.getRawMessage().getString().contains("credentials"));
    assertEquals("HEAD~3", run("switch HEAD~3").text("revision"));
    assertEquals("topic/nested", run("switch \"topic/nested\"").text("revision"));
  }
}
