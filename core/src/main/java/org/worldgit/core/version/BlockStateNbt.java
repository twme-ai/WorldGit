package org.worldgit.core.version;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.BlockState;

/** 26.3 的 id/properties 與預設 state 簡寫；repo 內仍使用完整 canonical state。 */
public final class BlockStateNbt {
  private static Map<String, Object> defaults;

  private BlockStateNbt() {}

  @SuppressWarnings("unchecked")
  private static synchronized Map<String, Object> defaults() throws IOException {
    if (defaults == null)
      defaults = (Map<String, Object>) VersionYaml.resource("5023.yml").get("blocks");
    return defaults;
  }

  @SuppressWarnings("unchecked")
  public static BlockState read(Object value, int version) throws IOException {
    if (value instanceof Nbt.Compound wrapped && wrapped.size() == 1 && wrapped.containsKey(""))
      value = wrapped.get("");
    String id;
    var properties = new TreeMap<String, String>();
    if (value instanceof String s) id = s;
    else if (value instanceof Nbt.Compound n) {
      id = n.string(n.containsKey("id") ? "id" : "Name");
      n.compound(n.containsKey("properties") ? "properties" : "Properties")
          .forEach((k, v) -> properties.put(k, String.valueOf(v)));
    } else throw new IOException("block state 型別無效");
    if (id.isEmpty()) throw new IOException("block state 缺少 id");
    if (version >= 5023) {
      Object spec = defaults().get(id);
      if (spec instanceof Map<?, ?> m) {
        var full = new TreeMap<>((Map<String, String>) m.get("default"));
        full.putAll(properties);
        properties.clear();
        properties.putAll(full);
      } else if (value instanceof String)
        throw new IOException("自訂 compact block state 缺少來源 registry defaults：" + id);
    }
    return new BlockState(id, properties);
  }

  public static Nbt.Compound write(BlockState state, int version) {
    var n = new Nbt.Compound().with(version >= 5023 ? "id" : "Name", state.name());
    if (!state.properties().isEmpty()) {
      var p = new Nbt.Compound();
      state.properties().forEach(p::put);
      n.put(version >= 5023 ? "properties" : "Properties", p);
    }
    return n;
  }
}
