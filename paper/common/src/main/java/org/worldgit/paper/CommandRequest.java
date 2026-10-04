package org.worldgit.paper;

import java.util.*;

/** Brigadier 已完成語法驗證的值；執行層不再拆解指令字串。 */
record CommandRequest(String command, Map<String, Object> values, Set<String> flags) {
  CommandRequest {
    values = Map.copyOf(values);
    flags = Set.copyOf(flags);
  }
  String text(String name) { return (String) values.get(name); }
  String text(String name, String fallback) { return (String) values.getOrDefault(name, fallback); }
  int number(String name, int fallback) { return (Integer) values.getOrDefault(name, fallback); }
  boolean flag(String name) { return flags.contains(name); }
  <T> T value(String name, Class<T> type) { return type.cast(values.get(name)); }
}
