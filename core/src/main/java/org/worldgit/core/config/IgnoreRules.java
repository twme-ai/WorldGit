package org.worldgit.core.config;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.worldgit.core.anvil.Nbt;

/** .wgignore 的有序規則。含端點座標、! 加回、欄位覆寫（含內建忽略欄位）。 */
public final class IgnoreRules {
  private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

  public record Area(int x1, int y1, int z1, int x2, int y2, int z2) {
    public boolean contains(double x, double y, double z) {
      return x >= Math.min(x1, x2)
          && x <= Math.max(x1, x2)
          && y >= Math.min(y1, y2)
          && y <= Math.max(y1, y2)
          && z >= Math.min(z1, z2)
          && z <= Math.max(z1, z2);
    }
  }

  private record Rule(
      String kind,
      String type,
      boolean include,
      boolean nonPersistent,
      Area area,
      List<String> fields, int line, String text) {}

  private final List<Rule> rules;
  private final String source;
  private final boolean hasAreas;

  private IgnoreRules(String source, List<Rule> rules) {
    this.source = source;
    this.rules = List.copyOf(rules);
    this.hasAreas = rules.stream().anyMatch(r -> r.kind.equals("area"));
  }

  public String source() {
    return source;
  }

  public static IgnoreRules none() {
    return new IgnoreRules("", List.of());
  }

  public static IgnoreRules parse(String source) throws IOException {
    if (source.length() > 256 * 1024) throw new IOException(".wgignore 超過 256 KiB");
    var rules = new ArrayList<Rule>();
    int lineNo = 0;
    for (String line : source.split("\\R")) {
      lineNo++;
      var words = new ArrayList<String>();
      for (String word : line.trim().split("\\s+")) {
        if (word.isEmpty()) continue;
        if (word.startsWith("#")
            && !(words.size() == 1
                && words.getFirst().replace("!", "").equals("entity")
                && ID.matcher(word.substring(1)).matches())) break;
        words.add(word);
      }
      if (words.isEmpty()) continue;
      try {
        String head = words.getFirst();
        boolean include = head.startsWith("!");
        String kind = include ? head.substring(1) : head;
        if (kind.equals("area")) {
          if (words.size() != 7) throw new IllegalArgumentException("area 需要六個座標");
          rules.add(new Rule(kind, "*", include, false, area(words, 1), List.of(), lineNo, line));
          continue;
        }
        if (!Set.of("entity", "field").contains(kind) || words.size() < 2)
          throw new IllegalArgumentException("需要 area、entity 或 field 規則（不支援 dimension）");
        String type = words.get(1);
        if (!(type.equals("*")
            || ID.matcher(type).matches()
            || kind.equals("entity")
                && type.startsWith("#")
                && ID.matcher(type.substring(1)).matches()))
          throw new IllegalArgumentException("型別或 tag 無效：" + type);
        if (kind.equals("field")) {
          if (words.size() < 3) throw new IllegalArgumentException("field 需要至少一個欄位");
          var fields = List.copyOf(words.subList(2, words.size()));
          for (String f : fields)
            if (!f.matches("[A-Za-z0-9_:.*/-]+"))
              throw new IllegalArgumentException("NBT 欄位無效：" + f);
          rules.add(new Rule(kind, type, include, false, null, fields, lineNo, line));
          continue;
        }
        int i = 2;
        boolean nonPersistent = i < words.size() && words.get(i).equals("!persistent");
        if (nonPersistent) i++;
        Area area = null;
        if (i < words.size()) {
          if (words.size() != i + 8
              || !words.get(i).equals("in")
              || !words.get(i + 1).equals("area"))
            throw new IllegalArgumentException("entity 尾端需要 in area 與六個座標");
          area = area(words, i + 2);
        }
        rules.add(new Rule(kind, type, include, nonPersistent, area, List.of(), lineNo, line));
      } catch (IllegalArgumentException e) {
        throw new IOException(".wgignore 第 " + lineNo + " 行：" + e.getMessage(), e);
      }
    }
    return new IgnoreRules(source, rules);
  }

  private static Area area(List<String> words, int start) {
    int[] a = new int[6];
    for (int i = 0; i < 6; i++) a[i] = Integer.parseInt(words.get(start + i));
    return new Area(a[0], a[1], a[2], a[3], a[4], a[5]);
  }

  public record Decision(boolean excluded, Integer line, String rule) {}

  public Decision testBlock(int x, int y, int z) {
    var result = new Decision(false, null, null);
    for (var r : rules) if (r.kind.equals("area") && r.area.contains(x, y, z))
      result = new Decision(!r.include, r.line, r.text);
    return result;
  }
  public boolean ignoredBlock(int x, int y, int z) { return testBlock(x, y, z).excluded(); }

  public Decision testEntity(Nbt.Compound e, EntitySemantics semantics) {
    if (e.string("id").equals("minecraft:player")) return new Decision(true, null, "內建：玩家永遠不追蹤");
    var pos = e.list("Pos").values();
    if (pos.size() != 3) throw new IllegalArgumentException("entity 缺少 Pos");
    double x = ((Number) pos.get(0)).doubleValue(), y = ((Number) pos.get(1)).doubleValue(), z = ((Number) pos.get(2)).doubleValue();
    var result = new Decision(false, null, null);
    for (var r : rules) {
      boolean match = r.kind.equals("area") && r.area.contains(x, y, z);
      if (r.kind.equals("entity")) match =
          (r.type.equals("*") || r.type.equals(e.string("id"))
              || r.type.startsWith("#") && semantics.inTag(r.type.substring(1), e.string("id")))
          && (!r.nonPersistent || !semantics.persistent(e)) && (r.area == null || r.area.contains(x, y, z));
      if (match) result = new Decision(!r.include, r.line, r.text);
    }
    return result;
  }
  public boolean ignoredEntity(Nbt.Compound e, EntitySemantics semantics) { return testEntity(e, semantics).excluded(); }

  public Decision testField(String type, String field, boolean builtin) {
    var result = new Decision(builtin, null, builtin ? "內建忽略欄位" : null);
    for (var r : rules) if (r.kind.equals("field") && (r.type.equals("*") || r.type.equals(type)))
      for (String pattern : r.fields) if (glob(pattern, field)) result = new Decision(!r.include, r.line, r.text);
    return result;
  }
  public boolean ignoredField(String type, String field, boolean builtin) { return testField(type, field, builtin).excluded(); }

  private static boolean glob(String pattern, String value) {
    String regex =
        Arrays.stream(pattern.split("\\*", -1))
            .map(Pattern::quote)
            .collect(java.util.stream.Collectors.joining(".*"));
    return value.matches(regex);
  }

  public boolean hasAreas() {
    return hasAreas;
  }
}
