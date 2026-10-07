package org.worldgit.fabric.logic;

import java.util.*;
import org.worldgit.core.model.DimensionId;

/**
 * 指令的維度目標：預設是玩家目前所在維度（console 為主世界）；{@code --dimension <id>} 明確指定；
 * {@code --all} 逐維度執行（非原子、各自回報）。{@link #rest()} 是移除目標選項後的文字。
 */
public record Targets(Kind kind, DimensionId dimension, String rest) {
  public enum Kind { CURRENT, EXPLICIT, ALL }

  /**
   * @param leadingOnly 為真時只解析開頭連續的目標選項（之後是貪婪文字，例如 commit -m 訊息、ignore add 規則），
   *     其後的原文（含空白）原樣保留，文字內容裡的 {@code --all} 不會被當成選項。
   */
  public static Targets parse(String text, boolean leadingOnly) {
    return parse(text, leadingOnly ? 0 : -1);
  }

  /** 在 stash 訊息、PR 標題与留言正文開始後，保留目標旗標的字面語意。 */
  public static Targets parseCommand(String command, String text, boolean leadingOnly) {
    String withoutLeadingTargets = parse(text, true).rest();
    if (command.equals("stash") && withoutLeadingTargets.matches("push(?:\\s.*)?")
        || command.equals("pr") && withoutLeadingTargets.matches("create(?:\\s.*)?")
        || command.equals("comment")) return parse(text, 1);
    return parse(text, leadingOnly);
  }

  private static Targets parse(String text, int greedyAfter) {
    String source = text == null ? "" : text;
    var tokens = new ArrayList<String>();
    var starts = new ArrayList<Integer>();
    var matcher = java.util.regex.Pattern.compile("\\S+").matcher(source);
    while (matcher.find()) { tokens.add(matcher.group()); starts.add(matcher.start()); }
    DimensionId dimension = null;
    boolean all = false;
    var rest = new ArrayList<String>();
    String tail = null;
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      if (token.equals("--all") || token.equals("--dimension") || token.startsWith("--dimension=")) {
        if (token.equals("--all")) {
          if (all) throw new CommandArgs.Invalid(MessageKeys.ERROR_OPTION_NO_VALUE, "--all");
          all = true;
        } else {
          String value;
          if (token.startsWith("--dimension=")) value = token.substring("--dimension=".length());
          else if (i + 1 < tokens.size()) value = tokens.get(++i);
          else throw new CommandArgs.Invalid(MessageKeys.ERROR_OPTION_NEEDS_VALUE, "--dimension");
          if (value.isEmpty()) throw new CommandArgs.Invalid(MessageKeys.ERROR_OPTION_NEEDS_VALUE, "--dimension");
          if (dimension != null) throw new CommandArgs.Invalid(MessageKeys.ERROR_TARGET_EXCLUSIVE, "--dimension");
          try {
            dimension = new DimensionId(value);
          } catch (IllegalArgumentException e) {
            throw new CommandArgs.Invalid(Messages.error(MessageKeys.ERROR_INVALID_DIMENSION, "message", String.valueOf(e.getMessage())));
          }
        }
        continue;
      }
      if (greedyAfter >= 0 && rest.size() >= greedyAfter && !token.equals("--source") && !token.equals("--target") && !token.equals("--here")) {
        tail = (rest.isEmpty() ? "" : String.join(" ", rest) + " ") + source.substring(starts.get(i));
        break;
      }
      rest.add(token);
      if (greedyAfter >= 0 && (token.equals("--source") || token.equals("--target")) && i + 1 < tokens.size()) rest.add(tokens.get(++i));
    }
    if (all && dimension != null) throw new CommandArgs.Invalid(MessageKeys.ERROR_TARGET_EXCLUSIVE, "--all");
    return new Targets(all ? Kind.ALL : dimension != null ? Kind.EXPLICIT : Kind.CURRENT, dimension, tail != null ? tail : String.join(" ", rest));
  }
}
