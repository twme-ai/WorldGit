package org.worldgit.fabric.logic;

import java.io.IOException;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 設定檔共用的 safe YAML 讀取：拒絕未知鍵、重複鍵、型別錯誤，錯誤訊息帶來源與鍵路徑。 */
final class YamlFile {
  private final String source;
  private final Map<String, Object> map;
  private final String prefix;
  private final Set<String> used = new HashSet<>();

  private YamlFile(String source, String prefix, Map<String, Object> map) {
    this.source = source;
    this.prefix = prefix;
    this.map = map;
  }

  static YamlFile parse(String text, String source) throws IOException {
    if (text.length() > 64 * 1024) throw new IOException(source + "：設定檔超過 64 KiB");
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(8);
    options.setCodePointLimit(64 * 1024);
    Object root;
    try {
      root = new Yaml(new SafeConstructor(options)).load(text);
    } catch (RuntimeException e) {
      throw new IOException(source + "：YAML 語法錯誤：" + e.getMessage(), e);
    }
    if (root == null) return new YamlFile(source, "", Map.of());
    return new YamlFile(source, "", asMap(root, source, "<root>"));
  }

  private static Map<String, Object> asMap(Object value, String source, String path)
      throws IOException {
    if (!(value instanceof Map<?, ?> m)) throw new IOException(source + "：" + path + " 必須是 mapping");
    var result = new LinkedHashMap<String, Object>();
    for (var e : m.entrySet()) {
      if (!(e.getKey() instanceof String s)) throw new IOException(source + "：" + path + " 的鍵必須是字串");
      result.put(s, e.getValue());
    }
    return result;
  }

  YamlFile section(String key) throws IOException {
    used.add(key);
    Object v = map.get(key);
    if (v == null) return new YamlFile(source, prefix + key + ".", Map.of());
    return new YamlFile(source, prefix + key + ".", asMap(v, source, prefix + key));
  }

  String string(String key, String fallback, Set<String> allowed) throws IOException {
    used.add(key);
    Object v = map.get(key);
    if (v == null) return fallback;
    if (!(v instanceof String s)) throw error(key, "必須是字串");
    if (allowed != null && !allowed.contains(s)) throw error(key, "必須是 " + String.join("|", allowed));
    return s;
  }

  boolean bool(String key, boolean fallback) throws IOException {
    used.add(key);
    Object v = map.get(key);
    if (v == null) return fallback;
    if (!(v instanceof Boolean b)) throw error(key, "必須是 true|false");
    return b;
  }

  int integer(String key, int fallback, int min, int max) throws IOException {
    used.add(key);
    Object v = map.get(key);
    if (v == null) return fallback;
    if (!(v instanceof Integer n)) throw error(key, "必須是整數");
    if (n < min || n > max) throw error(key, "必須介於 " + min + "–" + max);
    return n;
  }

  double number(String key, double fallback, double min, double max) throws IOException {
    used.add(key);
    Object v = map.get(key);
    if (v == null) return fallback;
    if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) throw error(key, "必須是數字");
    double d = n.doubleValue();
    if (d < min || d > max) throw error(key, "必須介於 " + min + "–" + max);
    return d;
  }

  /** 所有鍵都被讀取後呼叫；任何剩餘的鍵就是未知設定。 */
  void rejectUnknown() throws IOException {
    for (String k : map.keySet())
      if (!used.contains(k)) throw new IOException(source + "：未知設定鍵：" + prefix + k);
  }

  private IOException error(String key, String detail) {
    return new IOException(source + "：" + prefix + key + " " + detail);
  }
}
