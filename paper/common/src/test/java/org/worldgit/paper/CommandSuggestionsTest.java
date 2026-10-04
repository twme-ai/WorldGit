package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.apply.BlockBox;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.service.OperationState;
import org.worldgit.core.store.*;
import org.worldgit.i18n.MessageCatalog;
import static org.worldgit.paper.CommandSuggestions.*;

class CommandSuggestionsTest {
  @BeforeEach void setup() { Messages.configure(MessageCatalog.bundled(), "en_us"); }
  @Test void repoRefsTagsHistoryStashesDimensionsAreReadOnly(@TempDir Path root) throws Exception {
    Path world = root.resolve("world"); Files.createDirectories(world.resolve("region")); Files.write(world.resolve("level.dat"), new byte[]{0});
    var layout = WorldLayout.discover(world); Path repo = layout.repositoryRoot().resolve("minecraft.overworld");
    String parent, child;
    try (var store = new JGitStore(repo, true)) {
      String tree = store.writeTree(List.of());
      var identity = new CommitMetadata.Identity("tester", "tester@example.invalid");
      parent = store.commit(tree, null, metadata(identity, "first <red>message"));
      child = store.commit(tree, parent, metadata(identity, "second <click:run_command:/op bad>"));
      store.updateRef("refs/heads/topic/nested", null, parent);
      store.createTag("v1", parent, "tag", identity);
    }
    OperationState.write(layout.repositoryRoot().resolve("stash.yml"), Map.of("entries", List.of(Map.of("message", "roof <red>literal"))));
    var before = snapshot(root);
    var branches = RepositorySuggestions.read(layout, Kind.BRANCHES, Map.of());
    assertEquals(Set.of("main", "topic/nested"), new HashSet<>(branches.stream().map(Entry::value).toList()));
    assertTrue(branches.stream().filter(e -> e.value().equals("main")).findFirst().orElseThrow().tooltip("en_us").toString().contains("second"));
    var revisions = RepositorySuggestions.read(layout, Kind.REVISIONS, Map.of());
    assertTrue(revisions.stream().map(Entry::value).toList().containsAll(List.of("HEAD", "HEAD~1", "v1", Messages.shortId(parent), Messages.shortId(child))));
    var prior = revisions.stream().filter(e -> e.value().equals("HEAD~1")).findFirst().orElseThrow();
    assertEquals(Messages.shortId(parent), prior.args().get("hash"));
    assertEquals("roof <red>literal", RepositorySuggestions.read(layout, Kind.STASHES, Map.of()).getFirst().args().get("message"));
    assertEquals("minecraft:overworld", RepositorySuggestions.read(layout, Kind.DIMENSIONS, Map.of()).getFirst().value());
    assertEquals(before, snapshot(root), "補全不得新增 lock／改 config、refs 或任何 sidecar");
  }
  static CommitMetadata metadata(CommitMetadata.Identity identity, String message) {
    return new CommitMetadata(identity, identity, message, Instant.now(), 0, DimensionId.OVERWORLD, CommitMetadata.Source.PLUGIN, false, UUID.randomUUID(), List.of());
  }
  static Map<String, String> snapshot(Path root) throws Exception {
    var result = new TreeMap<String, String>();
    try (var paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) result.put(root.relativize(path).toString(),
          Files.getLastModifiedTime(path) + ":" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }
    return result;
  }
  @Test void remoteTooltipsNeverContainCredentialsOrFetchManifest(@TempDir Path root) throws Exception {
    OperationState.write(root.resolve("remotes.yml"), Map.of("remotes", Map.of(
        "safe", Map.of("url", "https://example.test/team/world"),
        "bad", Map.of("url", "https://user:PAT_SECRET@example.test/team/world?token=PAT_SECRET"),
        "manifest", Map.of("url", "manifest+http://127.0.0.1:1/missing.yml"))));
    var before = snapshot(root);
    var rows = RepositorySuggestions.remotes(root, Map.of("default", "https://example.test/team/default"));
    assertEquals(4, rows.size());
    assertTrue(rows.stream().filter(e -> e.value().equals("safe")).findFirst().orElseThrow().args().get("url").toString().contains("https://example.test/team/world"));
    assertEquals("[URL]", rows.stream().filter(e -> e.value().equals("bad")).findFirst().orElseThrow().args().get("url"));
    for (var row : rows) assertFalse(row.tooltip("en_us").toString().contains("PAT_SECRET"));
    assertEquals(before, snapshot(root));
  }
  @Test void tooltipLocaleMarkupAndSuggestionOffsets() throws Exception {
    String injection = "<red><click:run_command:/op bad>literal</click>";
    var rows = List.of(Entry.of("topic/nested", "paper.command.tip.revision", "hash", "abcdef12", "message", injection));
    var suggestions = new CommandSuggestions(k -> CompletableFuture.completedFuture(k == Kind.PRS
        ? List.of(Entry.of("7", "paper.command.tip.pr", "title", injection, "state", "open")) : rows), () -> null);
    var dispatcher = dispatcher(suggestions);
    var source = CommandTreeTest.source(true, Set.of("worldgit.admin"));
    var result = dispatcher.getCompletionSuggestions(dispatcher.parse("wg switch topic", source)).get(2, TimeUnit.SECONDS);
    assertEquals("\"topic/nested\"", result.getList().getFirst().getText());
    assertTrue(result.getList().getFirst().getTooltip().getString().contains(injection));
    for (String locale : List.of("en_us", "zh_tw")) {
      var component = rows.getFirst().tooltip(locale);
      for (var part : component.iterable(ComponentIteratorType.DEPTH_FIRST)) assertNull(part.clickEvent());
    }
    var pr = dispatcher.getCompletionSuggestions(dispatcher.parse("wg pr view ", source)).get(2, TimeUnit.SECONDS).getList().getFirst();
    assertEquals("7", pr.getText()); assertTrue(pr.getTooltip().getString().contains(injection));
    assertTrue(pr.getTooltip().getString().contains("open"));
    String text = "wg pr create A title --source topic";
    var branch = dispatcher.getCompletionSuggestions(dispatcher.parse(text, source)).get(2, TimeUnit.SECONDS).getList().getFirst();
    assertEquals(text.lastIndexOf("topic"), branch.getRange().getStart());
    assertEquals("\"topic/nested\"", branch.getText());
    var forbidden = dispatcher.getCompletionSuggestions(dispatcher.parse("wg pr view ", CommandTreeTest.source(true, Set.of()))).get(2, TimeUnit.SECONDS);
    assertTrue(forbidden.isEmpty());
  }
  static CommandDispatcher<CommandSourceStack> dispatcher(CommandSuggestions suggestions) {
    var dispatcher = new CommandDispatcher<CommandSourceStack>();
    dispatcher.register(new CommandTree((source, request) -> {}, suggestions, CommandTreeTest.NativeArgument::new).build()); return dispatcher;
  }
  @Test void conflictSuggestionsOnlyExistDuringMergingAndUpdateImmediately() throws Exception {
    var region = new MergeReport.Region(3, DimensionId.OVERWORLD, new BlockBox(-1, 64, 5, 1, 66, 7), 9,
        List.of(), List.of(), false, MergeReport.Choice.THEIRS, true, List.of());
    var nonSpatial = new MergeReport.Region(4, DimensionId.OVERWORLD, null, 0,
        List.of(), List.of(), false, MergeReport.Choice.OURS, false, List.of());
    var report = new MergeReport(0, List.of(region, nonSpatial), List.of(), List.of(), List.of());
    var dimension = new MergeState.Dimension(null, null, null, null, null, null, null, null, null, report);
    var state = new MergeState(UUID.randomUUID(), "merge", "topic", "message", new TreeMap<>(Map.of(DimensionId.OVERWORLD, dimension)));
    var current = new java.util.concurrent.atomic.AtomicReference<MergeState>();
    var suggestions = new CommandSuggestions(k -> { fail("region suggestions must not do IO"); return null; }, current::get);
    assertTrue(suggestions.entries(Kind.REGIONS).join().isEmpty());
    current.set(state); var row = suggestions.entries(Kind.REGIONS).join().getFirst();
    assertEquals("3", row.value());
    String tooltip = row.tooltip("en_us").toString();
    for (String expected : List.of("minecraft:overworld", "64", "9", "theirs", "resolved")) assertTrue(tooltip.contains(expected), expected);
    var noPosition = suggestions.entries(Kind.REGIONS).join().getLast();
    assertEquals("4", noPosition.value());
    assertDoesNotThrow(() -> noPosition.tooltip("en_us"));
    assertTrue(noPosition.tooltip("en_us").toString().contains("unresolved"));
    current.set(null); assertTrue(suggestions.entries(Kind.REGIONS).join().isEmpty());
  }
  @Test void timeoutFailureBoundsAndSingleFlightAreSilent() throws Exception {
    var calls = new AtomicInteger(); var pending = new CompletableFuture<List<Entry>>();
    try (var cache = new CommandSuggestions(k -> { calls.incrementAndGet(); return pending; }, () -> null,
        Duration.ofMillis(30), Duration.ofMillis(20), Duration.ofMillis(20))) {
      var requests = new ArrayList<CompletableFuture<List<Entry>>>();
      for (int i = 0; i < 100; i++) requests.add(cache.entries(Kind.PRS));
      for (var request : requests) assertTrue(request.get(1, TimeUnit.SECONDS).isEmpty());
      assertEquals(1, calls.get());
      pending.complete(java.util.stream.IntStream.range(0, 1000).mapToObj(n -> Entry.of(Integer.toString(n), "paper.command.tip.pr", "title", "t", "state", "open")).toList());
      assertEquals(256, cache.entries(Kind.PRS).join().size());
      cache.invalidateHub(); cache.entries(Kind.PRS).join(); assertEquals(2, calls.get());
    }
    try (var failed = new CommandSuggestions(k -> CompletableFuture.failedFuture(new java.io.IOException("PAT_SECRET")), () -> null)) {
      for (Kind kind : Kind.values()) assertTrue(failed.entries(kind).get(1, TimeUnit.SECONDS).isEmpty());
      failed.close(); assertTrue(failed.entries(Kind.PRS).join().isEmpty());
    }
    var failedCalls = new AtomicInteger();
    try (var expires = new CommandSuggestions(k -> { failedCalls.incrementAndGet(); return CompletableFuture.failedFuture(new Exception()); }, () -> null,
        Duration.ofMillis(30), Duration.ofMillis(5), Duration.ofMillis(5))) {
      expires.entries(Kind.BRANCHES).join(); TimeUnit.MILLISECONDS.sleep(10); expires.entries(Kind.BRANCHES).join(); assertEquals(2, failedCalls.get());
    }
  }
}
