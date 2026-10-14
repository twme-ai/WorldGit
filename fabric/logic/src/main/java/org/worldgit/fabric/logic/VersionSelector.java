package org.worldgit.fabric.logic;

import java.io.IOException;
import java.util.Set;
import org.worldgit.core.version.MinecraftVersion;

/** Catalog 精確選擇；已規劃的模組與本次交付的 binary 分開。 */
public final class VersionSelector {
  private VersionSelector() {}
  public static final Set<String> AVAILABLE = Set.of("1.20.1", "1.20.4", "1.21.11", "26.2");
  public static String module(String id) throws IOException {
    return "mc" + MinecraftVersion.byId(id).id().replace('.', '_');
  }
  public static void require(String actual, int dataVersion, String artifact) throws IOException {
    var version = MinecraftVersion.byId(actual);
    if (!AVAILABLE.contains(actual) || !actual.equals(artifact) || version.dataVersion() != dataVersion)
      throw new IOException("fabric.version.unsupported");
  }
}
