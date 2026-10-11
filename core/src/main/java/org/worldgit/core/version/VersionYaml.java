package org.worldgit.core.version;

import java.io.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class VersionYaml {
  private VersionYaml() {}

  static Map<String, Object> parse(String text) throws IOException {
    try {
      var options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(0);
      options.setNestingDepthLimit(64);
      options.setCodePointLimit(4 * 1024 * 1024);
      Object value = new Yaml(new SafeConstructor(options)).load(text);
      if (!(value instanceof Map<?, ?> m)) throw new IOException("版本 YAML 必須是 mapping");
      var out = new LinkedHashMap<String, Object>();
      for (var e : m.entrySet()) {
        if (!(e.getKey() instanceof String s)) throw new IOException("版本 YAML 鍵必須是字串");
        out.put(s, e.getValue());
      }
      return out;
    } catch (RuntimeException e) {
      throw new IOException("版本 YAML 無效", e);
    }
  }

  static Map<String, Object> resource(String name) throws IOException {
    try (var in = VersionYaml.class.getResourceAsStream("/org/worldgit/versions/" + name)) {
      if (in == null) throw new IOException("缺少版本資料 " + name);
      return parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
