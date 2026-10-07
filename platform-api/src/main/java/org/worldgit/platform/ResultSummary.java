package org.worldgit.platform;

import java.util.*;
import java.util.function.Function;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.remote.WorldRemotes;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.i18n.MessageCatalog;

/**
 * 把 core 的結果物件整理成「人類可讀」的完成摘要文字（純文字、依語言）。Paper 與 Fabric 共用，
 * 終止訊息不可以直接輸出 Java record 的 toString（例如 {@code Counts[added=…]}）。
 * 樣板在 i18n 的 {@code common.result.*}，只用 {@code <name>} 替換，不含 MiniMessage 樣式。
 */
public final class ResultSummary {
  private final MessageCatalog catalog;
  private final String locale;

  public ResultSummary(MessageCatalog catalog, String locale) {
    this.catalog = Objects.requireNonNull(catalog);
    this.locale = MessageCatalog.normalizeLocale(locale);
  }

  /** 千分位數字（與語言無關）。 */
  public static String number(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }

  public static String shortId(String id) {
    return id == null ? "-" : id.substring(0, Math.min(8, id.length()));
  }

  private String text(String key, Object... nameValue) {
    String template = catalog.raw(locale, "common.result." + key);
    for (int i = 0; i + 1 < nameValue.length; i += 2)
      template = template.replace("<" + nameValue[i] + ">", String.valueOf(nameValue[i + 1]));
    return template;
  }

  private String join(List<String> parts, String separatorKey) {
    return String.join(text(separatorKey), parts);
  }

  /** 方塊／section／實體等變動的一句話：新增 N 方塊、M sections。沒有變動回「沒有變動」。 */
  public String changes(WorldDiff diff) {
    var parts = new ArrayList<String>();
    var c = diff.counts();
    if (c.added() > 0) parts.add(text("added", "n", number(c.added())));
    if (c.removed() > 0) parts.add(text("removed", "n", number(c.removed())));
    if (c.modified() > 0) parts.add(text("modified", "n", number(c.modified())));
    if (c.conflict() > 0) parts.add(text("conflict", "n", number(c.conflict())));
    if (!diff.sections().isEmpty()) parts.add(text("sections", "n", number(diff.sections().size())));
    if (!diff.entities().isEmpty()) parts.add(text("entities", "n", number(diff.entities().size())));
    if (!diff.biomes().isEmpty()) parts.add(text("biomes", "n", number(diff.biomes().size())));
    if (!diff.metadata().isEmpty()) parts.add(text("metadata", "n", number(diff.metadata().size())));
    return parts.isEmpty() ? text("nothing") : join(parts, "separator");
  }

  public String commit(WorldRepositories.Batch<DimensionRepository.CommitResult> batch) {
    var rows = new ArrayList<String>();
    batch.dimensions().forEach((id, outcome) -> {
      if (!outcome.success()) rows.add(text("dimension-failed", "dimension", id.value()));
      else if (outcome.value() == null) rows.add(text("dimension-skipped", "dimension", id.value()));
      else if (outcome.value().changed())
        rows.add(text("commit-row", "dimension", id.value(), "commit", shortId(outcome.value().commit()),
            "changes", changes(outcome.value().status().diff())));
      else rows.add(text("commit-unchanged", "dimension", id.value()));
    });
    return rows.isEmpty() ? text("nothing") : join(rows, "dimension-separator");
  }

  public String status(WorldRepositories.Batch<DimensionRepository.Status> batch) {
    var rows = new ArrayList<String>();
    batch.dimensions().forEach((id, outcome) -> {
      if (!outcome.success()) rows.add(text("dimension-failed", "dimension", id.value()));
      else if (outcome.value().diff().empty()) rows.add(text("status-clean", "dimension", id.value()));
      else rows.add(text("status-row", "dimension", id.value(), "changes", changes(outcome.value().diff())));
    });
    return rows.isEmpty() ? text("nothing") : join(rows, "dimension-separator");
  }

  public String applied(WorldOperations.Result result) {
    var rows = new ArrayList<String>();
    result.dimensions().forEach((id, stats) -> {
      var parts = new ArrayList<String>();
      if (stats.sections() > 0) parts.add(text("sections", "n", number(stats.sections())));
      if (stats.chunks() > 0) parts.add(text("chunks", "n", number(stats.chunks())));
      int entities = stats.entityPuts() + stats.entityRemoves();
      if (entities > 0) parts.add(text("entity-ops", "n", number(entities)));
      if (stats.biomeSections() > 0) parts.add(text("biome-sections", "n", number(stats.biomeSections())));
      if (stats.metaFiles() > 0) parts.add(text("meta-files", "n", number(stats.metaFiles())));
      rows.add(text("apply-row", "dimension", id.value(), "changes", parts.isEmpty() ? text("nothing") : join(parts, "separator")));
    });
    String body = rows.isEmpty() ? text("nothing") : join(rows, "dimension-separator");
    return result.state() == WorldOperations.State.DRY_RUN ? text("dry-run", "changes", body) : body;
  }

  public String merge(WorldOperations.MergeResult result) {
    var parts = new ArrayList<String>();
    if (!result.commits().isEmpty()) {
      var commits = new ArrayList<String>();
      result.commits().forEach((id, commit) -> commits.add(id.value() + " " + shortId(commit)));
      parts.add(text("merge-commits", "commits", join(commits, "separator")));
    }
    int remaining = result.merging() == null ? 0 : result.merging().remaining();
    parts.add(remaining == 0 ? text("merge-no-conflicts") : text("merge-remaining", "n", number(remaining)));
    return join(parts, "separator");
  }

  public String transfer(WorldRemotes.TransferResult result) {
    var parts = new ArrayList<String>();
    var commits = new ArrayList<String>();
    result.commits().forEach((id, commit) -> commits.add(id.value() + " " + shortId(commit)));
    if (!commits.isEmpty()) parts.add(text("transfer-commits", "commits", join(commits, "separator")));
    long bytes = result.packs().values().stream().flatMap(Collection::stream)
        .mapToLong(pack -> pack.wireBytes() == null ? pack.preparedBytes() : pack.wireBytes()).sum();
    if (bytes > 0) parts.add(text("bytes", "n", number(bytes)));
    return parts.isEmpty() ? text("nothing") : join(parts, "separator");
  }

  public String head(String branch, String commit) {
    return text("head", "branch", branch == null ? "HEAD" : branch, "commit", shortId(commit));
  }

  public String count(long n) {
    return text("count", "n", number(n));
  }

  /** 以語言的分隔符號串接多段摘要；沒有內容時回空字串。 */
  public String joinParts(List<String> parts) {
    return String.join(text("dimension-separator"), parts);
  }

  public String joinRows(List<String> rows) {
    return rows.isEmpty() ? text("nothing") : join(rows, "dimension-separator");
  }

  /** 依型別整理；未知型別回傳 null，由呼叫端決定（絕不輸出 record toString）。 */
  public String describe(Object value) {
    if (value instanceof WorldRepositories.Batch<?> batch) {
      boolean statuses = batch.dimensions().values().stream().anyMatch(o -> o.success() && o.value() instanceof DimensionRepository.Status);
      @SuppressWarnings("unchecked") var typed = (WorldRepositories.Batch<Object>) batch;
      return statuses ? status(cast(typed)) : commit(cast(typed));
    }
    if (value instanceof WorldOperations.Result r) return applied(r);
    if (value instanceof WorldOperations.MergeResult m) return merge(m);
    if (value instanceof WorldRemotes.TransferResult t) return transfer(t);
    return null;
  }

  @SuppressWarnings("unchecked")
  private static <T> WorldRepositories.Batch<T> cast(WorldRepositories.Batch<?> batch) {
    return (WorldRepositories.Batch<T>) batch;
  }

  /** 方便測試與呼叫端的別名。 */
  public Function<Object, String> describer() {
    return this::describe;
  }

  public static DimensionId dimension(String id) {
    return new DimensionId(id);
  }
}
