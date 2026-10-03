package org.worldgit.core.remote;

import java.io.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class SafeYaml {
  static Map<String, Object> parse(String text) throws IOException {
    if (text.length() > 65_536) throw new IOException("YAML 超過 64 KiB");
    var o = new LoaderOptions();
    o.setAllowDuplicateKeys(false);
    o.setMaxAliasesForCollections(0);
    o.setNestingDepthLimit(8);
    o.setCodePointLimit(65_536);
    try {
      var v = new Yaml(new SafeConstructor(o)).load(text);
      if (!(v instanceof Map<?, ?> m)) throw new IOException("YAML 必須是 mapping");
      var out = new LinkedHashMap<String, Object>();
      for (var e : m.entrySet()) {
        if (!(e.getKey() instanceof String s)) throw new IOException("YAML 鍵必須是字串");
        out.put(s, e.getValue());
      }
      return out;
    } catch (RuntimeException e) {
      throw new IOException("YAML 格式無效（內容已遮罩）");
    }
  }
}
