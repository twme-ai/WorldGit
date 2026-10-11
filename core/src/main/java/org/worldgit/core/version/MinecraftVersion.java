package org.worldgit.core.version;

import java.io.*;
import java.util.*;

/** 正式驗收目標；不以相近 DataVersion 假設格式相容。 */
public record MinecraftVersion(String id, int dataVersion, int javaVersion) {
  public static final List<MinecraftVersion> SUPPORTED;
  public static final int NAMESPACED_STORAGE = 4790;

  static {
    try (var in =
        MinecraftVersion.class.getResourceAsStream("/org/worldgit/versions/catalog.yml")) {
      var root =
          VersionYaml.parse(
              new String(
                  Objects.requireNonNull(in).readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
      var versions = new ArrayList<MinecraftVersion>();
      for (Object item : (List<?>) root.get("versions")) {
        var m = (Map<?, ?>) item;
        versions.add(
            new MinecraftVersion(
                (String) m.get("id"),
                ((Number) m.get("data-version")).intValue(),
                ((Number) m.get("java")).intValue()));
      }
      SUPPORTED = List.copyOf(versions);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static MinecraftVersion byId(String id) throws IOException {
    return SUPPORTED.stream()
        .filter(v -> v.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IOException("未正式支援 Minecraft " + id));
  }

  public static MinecraftVersion byDataVersion(int version) throws IOException {
    return SUPPORTED.stream()
        .filter(v -> v.dataVersion == version)
        .findFirst()
        .orElseThrow(() -> new IOException("未正式支援 DataVersion " + version));
  }

  public static String name(int version) {
    return SUPPORTED.stream()
        .filter(v -> v.dataVersion == version)
        .map(MinecraftVersion::id)
        .findFirst()
        .orElse("DataVersion=" + version);
  }
}
