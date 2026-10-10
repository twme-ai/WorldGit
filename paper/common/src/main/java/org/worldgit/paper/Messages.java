package org.worldgit.paper;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.core.store.RefStore;
import org.worldgit.i18n.MessageCatalog;
import org.worldgit.protocol.DiffPalette;

/** Paper/Folia 的 MiniMessage 渲染器；語言來自 i18n，色票來自 protocol。 */
final class Messages {
  private static final MiniMessage MM = MiniMessage.miniMessage();
  private static final ThreadLocal<String> LOCALE = new ThreadLocal<>();
  private static volatile MessageCatalog catalog = MessageCatalog.bundled();
  private static volatile String defaultLocale = MessageCatalog.FALLBACK_LOCALE;
  static OperationUi ui;
  private static volatile DiffPalette currentPalette = DiffPalette.DEFAULT;
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
  private Messages() {}

  static void configure(MessageCatalog value, String language) { catalog = Objects.requireNonNull(value); defaultLocale = MessageCatalog.normalizeLocale(language); }
  static void reload() { catalog.reload(); }
  static String language(CommandSender sender) { return MessageCatalog.normalizeLocale(sender instanceof Player p ? p.getLocale() : defaultLocale); }
  static <T> T inLocale(CommandSender sender, Supplier<T> work) {
    String previous = LOCALE.get();
    LOCALE.set(MessageCatalog.normalizeLocale(sender instanceof Player p ? p.getLocale() : defaultLocale));
    try { return work.get(); } finally { if (previous == null) LOCALE.remove(); else LOCALE.set(previous); }
  }
  static void inLocale(CommandSender sender, Runnable work) { inLocale(sender, () -> { work.run(); return null; }); }
  static <T> T inLocale(String language, Supplier<T> work) {
    String previous = LOCALE.get();
    LOCALE.set(MessageCatalog.normalizeLocale(language));
    try { return work.get(); } finally { if (previous == null) LOCALE.remove(); else LOCALE.set(previous); }
  }
  private static String locale() { return Optional.ofNullable(LOCALE.get()).orElse(defaultLocale); }
  /** 目前 locale 的完成摘要渲染器（人類可讀，不輸出 record toString）。 */
  static org.worldgit.platform.ResultSummary summaryRenderer() { return new org.worldgit.platform.ResultSummary(catalog, locale()); }
  static boolean hasSummaryLabel(String key) { return catalog.find(locale(), "paper.phase5.summary." + key).isPresent(); }
  static String plain(Component component) { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component); }
  static String fullSuffix() { return catalog.raw(locale(), "paper.status.full-suffix"); }
  static DiffPalette palette(String name) { return "colorblind".equals(name) ? DiffPalette.COLORBLIND : DiffPalette.DEFAULT; }

  private static TagResolver kind(String name, DiffPalette palette, ChangeKind kind) { return TagResolver.resolver(name, Tag.styling(TextColor.color(palette.rgb(kind)))); }
  private static TagResolver resolver(DiffPalette palette, Map<String, ?> args) {
    var b = TagResolver.builder();
    b.resolver(kind("wg_added", palette, ChangeKind.ADDED));
    b.resolver(kind("wg_removed", palette, ChangeKind.REMOVED));
    b.resolver(kind("wg_modified", palette, ChangeKind.MODIFIED));
    b.resolver(kind("wg_conflict", palette, ChangeKind.CONFLICT));
    args.forEach((key, value) -> b.resolver(Placeholder.unparsed(key, String.valueOf(value))));
    return b.build();
  }
  private static Component raw(String key, DiffPalette palette, Map<String, ?> args) { return MM.deserialize(catalog.raw(locale(), key), resolver(palette, args)); }
  private static Map<String, Object> pairs(Object... args) {
    if ((args.length & 1) != 0) throw new IllegalArgumentException("訊息參數必須成對");
    var map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < args.length; i += 2) map.put(String.valueOf(args[i]), args[i + 1]);
    return map;
  }
  static Component line(String key, Object... args) {
    var result=raw("common.prefix", currentPalette, Map.of()).append(raw(key, currentPalette, pairs(args)));
    return ui!=null && (key.equals("common.error") || key.contains(".error.") || key.endsWith("-failed") || key.endsWith(".partial") || key.endsWith(".expired") || key.endsWith(".busy")) ? ui.error(result) : result;
  }
  static Component text(String key, Object... args) { return raw(key, currentPalette, pairs(args)); }
  static Component prefix() { return raw("common.prefix", currentPalette, Map.of()); }
  static Component info(String text) { return prefix().append(Component.text(text, NamedTextColor.GRAY)); }
  static Component ok(String text) { return prefix().append(Component.text(text, NamedTextColor.GREEN)); }
  static Component warn(String text) { return prefix().append(Component.text(text, NamedTextColor.GOLD)); }
  static Component error(String text) { return line("common.error", "message", text); }
  static Component errorKey(String key, Object... args) { return line(key, args); }
  /** 預檢與上鎖之間出現的未提交變動：列出 chunk 座標與種類（方塊、實體類型與數量），最多 LIMIT 項。 */
  static Component worldChanged(org.worldgit.core.apply.PreflightChangedException e) {
    var items = new ArrayList<String>();
    for (var c : e.chunks()) {
      var parts = new ArrayList<String>();
      if (c.blocks() > 0) parts.add(plain(text("paper.error.world-changed-blocks", "n", c.blocks())));
      if (c.biomes() > 0) parts.add(plain(text("paper.error.world-changed-biomes", "n", c.biomes())));
      if (!c.entities().isEmpty()) {
        var types = new ArrayList<String>();
        c.entities().forEach((type, n) -> types.add(type + "×" + n));
        parts.add(plain(text("paper.error.world-changed-entities", "types", String.join(", ", types))));
      }
      if (parts.isEmpty()) parts.add(plain(text("paper.error.world-changed-other")));
      items.add(plain(text("paper.error.world-changed-chunk", "x", c.chunk().x(), "z", c.chunk().z(), "what", String.join(", ", parts))));
    }
    return line("paper.error.world-changed",
        "retries", e.attempts() > 1 ? plain(text("paper.error.world-changed-retries", "n", e.attempts() - 1)) : "",
        "total", e.total(), "list", String.join("; ", items),
        "more", e.total() > e.chunks().size() ? plain(text("paper.error.world-changed-more", "n", e.total() - e.chunks().size())) : "");
  }
  static Component permission(String sub) { return line("paper.error.permission", "sub", sub); }
  static Component debugPermission() { return line("paper.error.debug-permission"); }

  static Component symbol(ChangeKind kind, long count, DiffPalette palette) { return raw("paper.diff.symbol." + kind.name().toLowerCase(Locale.ROOT), palette, Map.of("symbol", DiffPalette.symbol(kind), "count", count)); }
  static Component counts(WorldDiff.Counts c, DiffPalette palette) {
    currentPalette = palette;
    var parts = new ArrayList<Component>();
    if (c.added() > 0) parts.add(symbol(ChangeKind.ADDED, c.added(), palette));
    if (c.removed() > 0) parts.add(symbol(ChangeKind.REMOVED, c.removed(), palette));
    if (c.modified() > 0) parts.add(symbol(ChangeKind.MODIFIED, c.modified(), palette));
    if (c.conflict() > 0) parts.add(symbol(ChangeKind.CONFLICT, c.conflict(), palette));
    return parts.isEmpty() ? Component.text("0", NamedTextColor.DARK_GRAY) : Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.space()), parts);
  }
  static String shortId(String id) { return id == null ? "-" : id.substring(0, Math.min(8, id.length())); }
  static Component tpSuggestion(DimensionId dimension, int x, int y, int z, Component label, Component hover) {
    String command = "/execute in " + dimension.value() + " run tp @s " + x + " " + y + " " + z;
    return label.hoverEvent(HoverEvent.showText(hover.append(Component.newline()).append(raw("paper.diff.click-to-teleport", currentPalette, Map.of())))).clickEvent(ClickEvent.suggestCommand(command));
  }
  static Component section(DimensionId dimension, WorldDiff.SectionChange s, DiffPalette palette) {
    int x0 = s.chunk().x() * 16, z0 = s.chunk().z() * 16, y0 = s.sectionY() * 16;
    var label = raw("paper.status.section-label", palette, Map.of("region", s.chunk().regionName(), "chunk", s.chunk().chunkName(), "y", s.sectionY()));
    var hover = raw("paper.status.section-hover", palette, Map.of("x0", x0, "x1", x0 + 15, "y0", y0, "y1", y0 + 15, "z0", z0, "z1", z0 + 15)).append(Component.newline()).append(counts(s.counts(), palette));
    return tpSuggestion(dimension, x0 + 8, y0 + 8, z0 + 8, label, hover);
  }
  static Component block(DimensionId dimension, WorldDiff.BlockChange b, DiffPalette palette) {
    var p = b.pos();
    var label = raw("paper.diff.block-label." + b.kind().name().toLowerCase(Locale.ROOT), palette, Map.of("symbol", DiffPalette.symbol(b.kind()), "x", p.x(), "y", p.y(), "z", p.z()));
    var hover = raw("paper.diff.block-hover", palette, Map.of("before", b.before().canonical(), "after", b.after().canonical()));
    return tpSuggestion(dimension, p.x(), p.y(), p.z(), label, hover);
  }
  static List<Component> status(DimensionId dimension, DimensionRepository.Status status, DiffPalette palette, int maxSections) {
    currentPalette = palette;
    var lines = new ArrayList<Component>(); var diff = status.diff();
    var header = raw("paper.status.dimension", palette, Map.of("dimension", dimension.value())).append(Component.text("  ", NamedTextColor.GRAY)).append(counts(diff.counts(), palette)).append(raw("paper.status.section-count", palette, Map.of("count", diff.sections().size())));
    if (!diff.entities().isEmpty()) header = header.append(raw("paper.status.entity-count", palette, Map.of("count", diff.entities().size())));
    if (!diff.biomes().isEmpty()) header = header.append(raw("paper.status.biome-count", palette, Map.of("count", diff.biomes().size())));
    lines.add(header);
    if (diff.empty()) lines.add(raw("paper.status.clean", palette, Map.of("candidates", status.candidates())));
    else {
      int shown = 0; var row = Component.text("  ");
      for (var s : diff.sections()) { if (shown++ >= maxSections) break; row = row.append(section(dimension, s, palette)).append(Component.space()); }
      if (shown > 0) lines.add(row);
      if (diff.sections().size() > maxSections) lines.add(raw("paper.status.more", palette, Map.of("count", diff.sections().size() - maxSections)));
    }
    for (String w : status.warnings()) lines.add(raw("paper.status.warning", palette, Map.of("text", w)));
    return lines;
  }
  static List<Component> commit(WorldRepositories.Batch<DimensionRepository.CommitResult> batch, DiffPalette palette) {
    currentPalette = palette; var lines = new ArrayList<Component>();
    batch.dimensions().forEach((id, outcome) -> {
      if (!outcome.success()) { lines.add(line("paper.commit.dimension-failed", "dimension", id.value(), "message", outcome.error())); return; }
      var r = outcome.value();
      if (r.changed()) lines.add(line("paper.commit.dimension-changed", "dimension", id.value(), "commit", shortId(r.commit()), "full", r.commit()).append(Component.space()).append(counts(r.status().diff().counts(), palette)).append(raw("paper.status.section-count", palette, Map.of("count", r.status().diff().sections().size()))));
      else if (!r.status().diff().empty()) lines.add(line("paper.commit.below-threshold", "dimension", id.value(), "count", r.status().diff().sections().size()));
      else lines.add(line("paper.commit.unchanged", "dimension", id.value()));
    });
    return lines;
  }
  static Component commitSummary(WorldRepositories.Batch<DimensionRepository.CommitResult> batch, boolean auto) {
    var parts = new ArrayList<String>(); batch.dimensions().forEach((id, o) -> { if (o.success() && o.value().changed()) parts.add(id.path() + " " + shortId(o.value().commit())); });
    return parts.isEmpty() ? null : line(auto ? "paper.commit.summary-auto" : "paper.commit.summary", "items", String.join("、", parts));
  }
  static List<Component> log(Map<DimensionId, WorldRepositories.Outcome<List<RefStore.Commit>>> logs, int limit) {
    var lines=new ArrayList<Component>();
    logs.forEach((dim,outcome)->{
      if(!outcome.success()) {lines.add(line("paper.log.dimension-failed","dimension",dim,"message",outcome.error()));return;}
      for(var commit:outcome.value()) {
        var m=commit.metadata();
        lines.add(raw("paper.log.row",currentPalette,Map.of("time",TIME.format(m.time()),"message",m.message(),"author",m.author().name()))
            .append(Component.text(" "+dim+":"+shortId(commit.id()),NamedTextColor.YELLOW))
            .hoverEvent(Component.text(commit.id()+"\n"+m.author().git()+"\n"+m.time()+"\n"+m.message()+"\nparents="+commit.parents()+"\nsnapshot="+m.snapshot()))
            .clickEvent(ClickEvent.suggestCommand("/wg diff "+commit.id()+" --dimension "+dim.value())));
      }
    });
    if(lines.isEmpty())lines.add(line("paper.log.empty"));
    return lines;
  }
}
