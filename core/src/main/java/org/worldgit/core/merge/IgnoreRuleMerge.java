package org.worldgit.core.merge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.merge.*;
import org.worldgit.core.config.IgnoreRules;

/** 保留有序規則的文字三方合併；重疊 edit（含同處新增）拒絕，不任意重排 ! 優先順序。 */
public final class IgnoreRuleMerge {
  private IgnoreRuleMerge() {}

  public static String merge(String base, String ours, String theirs) throws IOException {
    String result;
    if (ours.equals(theirs) || theirs.equals(base)) result = ours;
    else if (ours.equals(base)) result = theirs;
    else {
      var merged =
          new MergeAlgorithm()
              .merge(RawTextComparator.DEFAULT, text(base), text(ours), text(theirs));
      if (merged.containsConflicts()) throw new IOException(".wgignore 規則衝突；請先在兩個分支整合規則後重試。");
      var out = new ByteArrayOutputStream();
      new MergeFormatter()
          .formatMerge(out, merged, List.of("base", "ours", "theirs"), StandardCharsets.UTF_8);
      result = out.toString(StandardCharsets.UTF_8);
    }
    IgnoreRules.parse(result);
    return result;
  }

  private static RawText text(String value) {
    return new RawText(value.getBytes(StandardCharsets.UTF_8));
  }

  public record RuleLine(org.worldgit.core.diff.ChangeKind kind, String text) {}

  public static List<RuleLine> difference(String before, String after) {
    var a = text(before);
    var b = text(after);
    var lines = new ArrayList<RuleLine>();
    var edits =
        DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, a, b);
    for (var edit : edits) {
      for (int i = edit.getBeginA(); i < edit.getEndA(); i++)
        lines.add(new RuleLine(org.worldgit.core.diff.ChangeKind.REMOVED, a.getString(i)));
      for (int i = edit.getBeginB(); i < edit.getEndB(); i++)
        lines.add(new RuleLine(org.worldgit.core.diff.ChangeKind.ADDED, b.getString(i)));
    }
    return List.copyOf(lines);
  }
}
