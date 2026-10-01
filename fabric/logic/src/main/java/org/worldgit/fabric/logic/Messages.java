package org.worldgit.fabric.logic;

import java.util.*;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;

/**
 * 指令輸出（語言鍵＋參數，不含文字）。顏色用 MiniMessage 的 {@code <wg_added>} 等自訂標籤，由平台層依色票
 * （普通／色盲）渲染，所以這裡完全不碰顏色碼；顏色之外一律還有 + - ~ ! 記號（doc 06 §1.1）。
 */
public final class Messages {
  private final int maxLines;

  public Messages(int maxLines) {
    this.maxLines = maxLines;
  }

  public Messages() {
    this(12);
  }

  private static Object[] withCounts(WorldDiff.Counts c, Object... lead) {
    var args = new ArrayList<>(Arrays.asList(lead));
    args.addAll(List.of("added", c.added(), "removed", c.removed(), "modified", c.modified(), "conflict", c.conflict()));
    return args.toArray();
  }

  public static Msg error(String key, Object... args) {
    return Msg.prefixed(key, args);
  }

  public static Msg warning(String text) {
    return Msg.of(MessageKeys.WARNING, "text", text);
  }

  private static String blockKey(ChangeKind kind) {
    return switch (kind) {
      case ADDED -> MessageKeys.BLOCK_ADDED;
      case REMOVED -> MessageKeys.BLOCK_REMOVED;
      case MODIFIED -> MessageKeys.BLOCK_MODIFIED;
      case CONFLICT -> MessageKeys.BLOCK_CONFLICT;
    };
  }

  private static String entityKey(ChangeKind kind) {
    return switch (kind) {
      case ADDED -> MessageKeys.ENTITY_ADDED;
      case REMOVED -> MessageKeys.ENTITY_REMOVED;
      case MODIFIED -> MessageKeys.ENTITY_MODIFIED;
      case CONFLICT -> MessageKeys.ENTITY_CONFLICT;
    };
  }

  /** 單維度的 diff／status 摘要（含每個 section 的列表，最多 maxLines 行）。 */
  public List<Msg> diff(WorldDiff diff, boolean showBlocks) {
    var lines = new ArrayList<Msg>();
    var c = diff.counts();
    lines.add(
        Msg.of(
            MessageKeys.STATUS_DIMENSION,
            withCounts(c, "dimension", diff.dimension().value(), "chunks", diff.chunks().size(), "sections", diff.sections().size())));
    if (!diff.entities().isEmpty() || !diff.biomes().isEmpty() || !diff.metadata().isEmpty())
      lines.add(
          Msg.of(
              MessageKeys.STATUS_EXTRA,
              "entities",
              diff.entities().size(),
              "biomes",
              diff.biomes().size(),
              "metadata",
              diff.metadata().size()));
    int shown = 0;
    for (var s : diff.sections()) {
      if (shown++ >= maxLines) {
        lines.add(Msg.of(MessageKeys.STATUS_MORE, "count", diff.sections().size() - maxLines));
        break;
      }
      lines.add(
          Msg.of(
              MessageKeys.STATUS_SECTION,
              withCounts(s.counts(), "x", s.chunk().x(), "z", s.chunk().z(), "y", s.sectionY())));
      if (showBlocks) {
        int blockLines = 0;
        for (var blk : s.blocks()) {
          if (blockLines++ >= 6) {
            lines.add(Msg.of(MessageKeys.BLOCK_MORE));
            break;
          }
          lines.add(
              Msg.of(
                  blockKey(blk.kind()),
                  "x",
                  blk.pos().x(),
                  "y",
                  blk.pos().y(),
                  "z",
                  blk.pos().z(),
                  "before",
                  blk.before().canonical(),
                  "after",
                  blk.after().canonical()));
        }
      }
    }
    int entityLines = 0;
    for (var e : diff.entities()) {
      if (entityLines++ >= maxLines) break;
      var data = (e.after() != null ? e.after() : e.before()).data();
      lines.add(Msg.of(entityKey(e.kind()), "type", data.string("id"), "uuid", e.uuid()));
    }
    return lines;
  }

  public List<Msg> status(WorldRepositories.Batch<DimensionRepository.Status> batch, boolean showBlocks) {
    var lines = new ArrayList<Msg>();
    long total = 0;
    var warnings = new LinkedHashSet<String>();
    for (var e : batch.dimensions().entrySet()) {
      var outcome = e.getValue();
      if (!outcome.success()) {
        lines.add(Msg.of(MessageKeys.STATUS_DIMENSION_FAILED, "dimension", e.getKey(), "message", outcome.error()));
        continue;
      }
      var s = outcome.value();
      total += s.diff().sections().size() + s.diff().entities().size() + s.diff().metadata().size();
      warnings.addAll(s.warnings());
      lines.addAll(diff(s.diff(), showBlocks));
    }
    lines.add(0, Msg.prefixed(total == 0 ? MessageKeys.STATUS_CLEAN : MessageKeys.STATUS_DIRTY));
    for (String w : warnings) lines.add(warning(w));
    return lines;
  }

  public List<Msg> commit(WorldRepositories.Batch<DimensionRepository.CommitResult> batch, boolean init) {
    var lines = new ArrayList<Msg>();
    int committed = 0, failed = 0, ok = 0;
    for (var e : batch.dimensions().entrySet()) {
      var o = e.getValue();
      DimensionId d = e.getKey();
      if (!o.success()) {
        failed++;
        lines.add(Msg.of(MessageKeys.COMMIT_DIM_FAILED, "dimension", d, "message", o.error()));
      } else if (o.value() == null) {
        lines.add(Msg.of(MessageKeys.COMMIT_DIM_SKIPPED, "dimension", d));
      } else if (o.value().changed()) {
        committed++;
        ok++;
        lines.add(
            Msg.of(
                MessageKeys.COMMIT_DIM_CHANGED,
                withCounts(o.value().status().diff().counts(), "dimension", d, "commit", o.value().commit().substring(0, 10))));
      } else {
        ok++;
        lines.add(Msg.of(MessageKeys.COMMIT_DIM_UNCHANGED, "dimension", d));
      }
    }
    String snapshot = batch.snapshot().toString().substring(0, 8);
    Msg summary =
        failed > 0
            ? Msg.prefixed(MessageKeys.COMMIT_INCOMPLETE, "ok", ok, "failed", failed)
            : committed == 0 && !init
                ? Msg.prefixed(MessageKeys.COMMIT_NOTHING)
                : Msg.prefixed(init ? MessageKeys.COMMIT_DONE_INIT : MessageKeys.COMMIT_DONE, "count", committed, "snapshot", snapshot);
    lines.add(0, summary);
    var warnings = new LinkedHashSet<String>();
    batch.dimensions().values().stream()
        .filter(o -> o.success() && o.value() != null)
        .forEach(o -> warnings.addAll(o.value().status().warnings()));
    for (String w : warnings) lines.add(warning(w));
    return lines;
  }

  public List<Msg> log(List<WorldOps.LogRow> rows) {
    var lines = new ArrayList<Msg>();
    lines.add(Msg.prefixed(rows.isEmpty() ? MessageKeys.LOG_EMPTY : MessageKeys.LOG_HEADER));
    for (var r : rows) {
      lines.add(
          Msg.of(
              r.auto() ? MessageKeys.LOG_ROW_AUTO : MessageKeys.LOG_ROW,
              "snapshot",
              r.snapshot().toString().substring(0, 8),
              "message",
              r.message(),
              "author",
              r.author(),
              "time",
              r.time().toString().replace('T', ' ').substring(0, 19) + "Z"));
      var dims = new StringJoiner(", ");
      r.commits().forEach((d, id) -> dims.add(d.path() + "=" + id.substring(0, 7)));
      lines.add(Msg.of(MessageKeys.LOG_DIMENSIONS, "dimensions", dims));
    }
    return lines;
  }
}
