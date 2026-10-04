package org.worldgit.paper;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.*;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.worldgit.core.merge.MergeState;
import org.worldgit.platform.remote.CommentText;

/** 七種查詢鍵、每種最多 256 項；single-flight，750ms 後回空，不讓 tick 等候 IO。 */
final class CommandSuggestions implements AutoCloseable {
  enum Kind { BRANCHES, REVISIONS, STASHES, REGIONS, REMOTES, PRS, DIMENSIONS }
  record Entry(String value, String message, Map<String, Object> args) {
    Entry { args = Map.copyOf(args); }
    static Entry of(String value, String message, Object... pairs) {
      var args = new LinkedHashMap<String, Object>();
      for (int i = 0; i < pairs.length; i += 2) args.put((String) pairs[i], pairs[i + 1]);
      return new Entry(value, message, args);
    }
    Component tooltip(String locale) {
      var pairs = new ArrayList<Object>(); args.forEach((k, v) -> { pairs.add(k); pairs.add(v); });
      return Messages.inLocale(locale, () -> Messages.text(message, pairs.toArray()));
    }
  }
  private static final class Cached {
    final CompletableFuture<List<Entry>> result = new CompletableFuture<>();
    volatile long expires = Long.MAX_VALUE;
  }
  private final Map<Kind, Cached> cache = new EnumMap<>(Kind.class);
  private final Function<Kind, CompletableFuture<List<Entry>>> loader;
  private final Supplier<MergeState> merging;
  private final long timeoutMillis, localTtlMillis, hubTtlMillis;
  private volatile boolean stopped;

  CommandSuggestions(Function<Kind, CompletableFuture<List<Entry>>> loader, Supplier<MergeState> merging) {
    this(loader, merging, Duration.ofMillis(750), Duration.ofSeconds(2), Duration.ofSeconds(10));
  }
  CommandSuggestions(Function<Kind, CompletableFuture<List<Entry>>> loader, Supplier<MergeState> merging,
      Duration timeout, Duration localTtl, Duration hubTtl) {
    this.loader = loader; this.merging = merging;
    timeoutMillis = timeout.toMillis(); localTtlMillis = localTtl.toMillis(); hubTtlMillis = hubTtl.toMillis();
  }
  static CommandSuggestions forPlugin(WorldGitPlugin plugin) {
    return new CommandSuggestions(kind -> plugin.repo().submit(() -> kind == Kind.PRS
        ? plugin.remote().suggestionPulls() : RepositorySuggestions.read(WorldMapper.map().layout(), kind,
            plugin.remote().suggestionDefaults())), () -> plugin.merges().state());
  }

  CompletableFuture<List<Entry>> entries(Kind kind) {
    if (stopped) return CompletableFuture.completedFuture(List.of());
    if (kind == Kind.REGIONS) return CompletableFuture.completedFuture(regions(merging.get()));
    Cached slot;
    synchronized (cache) {
      slot = cache.get(kind);
      if (slot == null || System.nanoTime() >= slot.expires) {
        slot = new Cached(); cache.put(kind, slot);
        Cached current = slot;
        try {
          loader.apply(kind).whenComplete((rows, error) -> {
            List<Entry> result = error == null && rows != null ? rows.stream().limit(256).toList() : List.of();
            current.expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(kind == Kind.PRS ? hubTtlMillis : localTtlMillis);
            current.result.complete(stopped ? List.of() : result);
          });
        } catch (RuntimeException e) {
          current.expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(localTtlMillis);
          current.result.complete(List.of());
        }
      }
    }
    // copy：逾時只能結束這次補全，不得解除 in-flight，避免玩家連按 Tab 堆積 IO 任務。
    return slot.result.copy().completeOnTimeout(List.of(), timeoutMillis, TimeUnit.MILLISECONDS);
  }
  static List<Entry> regions(MergeState state) {
    if (state == null) return List.of();
    return state.regions().stream().limit(256).map(region -> Entry.of(Integer.toString(region.id()), "paper.command.tip.region",
        "dimension", region.dimension(), "bounds", bounds(region.bounds()), "count", region.blockCount(),
        "choice", region.choice().name().toLowerCase(Locale.ROOT), "status", region.resolved() ? "resolved" : "unresolved")).toList();
  }
  private static String bounds(org.worldgit.core.apply.BlockBox box) {
    if (box == null) return "—"; // metadata／檔案衝突沒有空間座標。
    return "(" + box.minX() + "," + box.minY() + "," + box.minZ() + ")–(" + box.maxX() + "," + box.maxY() + "," + box.maxZ() + ")";
  }
  SuggestionProvider<CommandSourceStack> provider(Kind kind) { return provider(kind, false); }
  private SuggestionProvider<CommandSourceStack> provider(Kind kind, boolean prefixed) {
    return (context, builder) -> {
      if (!authorized(context)) return builder.buildFuture();
      String locale = Messages.language(context.getSource().getSender());
      if (prefixed && !builder.getRemaining().startsWith("#")) return builder.buildFuture();
      return entries(kind).thenApply(rows -> render(rows, builder, locale, prefixed, kind == Kind.BRANCHES || kind == Kind.REVISIONS));
    };
  }
  static Suggestions render(List<Entry> rows, SuggestionsBuilder builder, String locale, boolean prefixed, boolean quoted) {
    String remaining = builder.getRemainingLowerCase();
    for (var row : rows) {
      String value = prefixed ? "#" + row.value() : quoted ? CommandArguments.quote(row.value()) : row.value();
      String match = remaining.startsWith("\"") ? value : (prefixed ? "#" : "") + row.value();
      if (match.toLowerCase(Locale.ROOT).startsWith(remaining)) builder.suggest(value, MessageComponentSerializer.message().serialize(row.tooltip(locale)));
    }
    return builder.build();
  }

  /** greedy 相容尾端的 flags／branch 補全，offset 精確對準最後一個 token。 */
  SuggestionProvider<CommandSourceStack> textProvider(boolean pr) {
    return (context, builder) -> {
      if (!authorized(context)) return builder.buildFuture();
      String locale = Messages.language(context.getSource().getSender());
      String input = builder.getRemaining();
      int last = input.lastIndexOf(' ') + 1;
      String previous = input.substring(0, Math.max(0, last)).stripTrailing();
      var reader = new StringReader(previous);
      String word = ""; boolean quoted = false;
      var seen = new HashSet<String>();
      try {
        while (reader.canRead()) {
          reader.skipWhitespace(); if (!reader.canRead()) break;
          quoted = StringReader.isQuotedStringStart(reader.peek()); word = CommandArguments.token(reader);
          if (!quoted && word.startsWith("--")) seen.add(word);
        }
      } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { return builder.buildFuture(); }
      var offset = builder.createOffset(builder.getStart() + last);
      if (pr && !quoted && Set.of("--source", "--target").contains(word))
        return entries(Kind.BRANCHES).thenApply(rows -> render(rows, offset, locale, false, true));
      for (String flag : pr ? List.of("--source", "--target") : List.of("--here")) {
        if (!seen.contains(flag) && flag.startsWith(offset.getRemaining()) && (pr || context.getSource().getSender() instanceof org.bukkit.entity.Player))
          offset.suggest(flag, MessageComponentSerializer.message().serialize(Messages.inLocale(locale, () -> Messages.text("paper.command.tip." + flag.substring(2)))));
      }
      return offset.buildFuture();
    };
  }
  private static boolean authorized(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
    if (context.getNodes().size() < 2) return false;
    return CommandTree.allowed(context.getSource(), context.getNodes().get(1).getNode().getName());
  }
  static String plain(String value, int limit) { return CommentText.plain(value, limit); }
  void invalidateLocal() { synchronized (cache) { cache.keySet().removeIf(k -> k != Kind.PRS); } }
  void invalidateHub() { synchronized (cache) { cache.remove(Kind.PRS); cache.remove(Kind.REMOTES); } }
  public void close() { stopped = true; synchronized (cache) { cache.values().forEach(v -> v.result.complete(List.of())); cache.clear(); } }
}
