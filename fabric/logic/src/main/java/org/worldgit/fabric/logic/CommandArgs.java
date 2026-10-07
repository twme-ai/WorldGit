package org.worldgit.fabric.logic;

import java.util.*;

/** /wg 指令的參數：旗標（--show 等）可出現在任何位置；其餘是位置參數。 */
public final class CommandArgs {
  /** 參數錯誤；訊息是可翻譯的 Msg。 */
  public static final class Invalid extends IllegalArgumentException {
    private final transient Msg msg;

    Invalid(String key, String option) {
      super(key + ":" + option);
      this.msg = Messages.error(key, "option", option);
    }

    Invalid(Msg msg) {
      super(msg.key());
      this.msg = msg;
    }

    public Msg msg() {
      return msg;
    }
  }

  private final Set<String> flags = new LinkedHashSet<>();
  private final Map<String, String> values = new LinkedHashMap<>();
  private final List<String> positional = new ArrayList<>();

  private CommandArgs() {}

  /**
   * @param flagNames 無值旗標，例如 --show
   * @param valueNames 帶一個值的選項，例如 --template（也接受 --template=survival）
   */
  public static CommandArgs parse(String text, Set<String> flagNames, Set<String> valueNames) {
    var result = new CommandArgs();
    var tokens = text == null || text.isBlank() ? new String[0] : text.trim().split("\\s+");
    for (int i = 0; i < tokens.length; i++) {
      String t = tokens[i];
      int eq = t.indexOf('=');
      String name = t.startsWith("--") && eq > 0 ? t.substring(0, eq) : t;
      if (flagNames.contains(name)) {
        if (eq > 0) throw new Invalid(MessageKeys.ERROR_OPTION_NO_VALUE, name);
        result.flags.add(name);
      } else if (valueNames.contains(name)) {
        String value;
        if (eq > 0) value = t.substring(eq + 1);
        else if (i + 1 < tokens.length) value = tokens[++i];
        else throw new Invalid(MessageKeys.ERROR_OPTION_NEEDS_VALUE, name);
        if (value.isEmpty()) throw new Invalid(MessageKeys.ERROR_OPTION_NEEDS_VALUE, name);
        result.values.put(name, value);
      } else if (t.startsWith("--")) throw new Invalid(MessageKeys.ERROR_UNKNOWN_OPTION, t);
      else result.positional.add(t);
    }
    return result;
  }

  public boolean flag(String name) {
    return flags.contains(name);
  }

  public Optional<String> value(String name) {
    return Optional.ofNullable(values.get(name));
  }

  public List<String> positional() {
    return Collections.unmodifiableList(positional);
  }
}
