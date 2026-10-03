package org.worldgit.core.capture;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.service.OperationState;
import org.worldgit.core.store.*;

/** 每維度本機的曾編輯集合。遊戲事件 adapter 必須先持久化，再 acknowledge dirty batch。 */
public final class ModifiedChunks {
  private ModifiedChunks() {}

  public static Optional<Set<ChunkPos>> read(Path repo) throws IOException {
    Path file = repo.resolve("modified-chunks.yml");
    if (!Files.exists(file)) return Optional.empty();
    var m = OperationState.read(file);
    if (!m.keySet().equals(Set.of("chunks")) || !(m.get("chunks") instanceof List<?> list))
      throw new IOException("modified-chunks.yml 格式無效");
    var positions = new TreeSet<ChunkPos>();
    for (Object value : list) {
      if (!(value instanceof String s)) throw new IOException("modified chunk 座標無效");
      String[] xy = s.split(",", -1);
      try {
        if (xy.length != 2) throw new IllegalArgumentException();
        positions.add(new ChunkPos(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
      } catch (IllegalArgumentException ex) {
        throw new IOException("modified chunk 座標無效");
      }
    }
    return Optional.of(Collections.unmodifiableSet(positions));
  }

  public static void write(Path repo, Set<ChunkPos> positions) throws IOException {
    var yaml =
        Map.of("chunks", new TreeSet<>(positions).stream().map(p -> p.x() + "," + p.z()).toList());
    if (new org.yaml.snakeyaml.Yaml()
            .dump(yaml)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            .length
        > 1_048_576) throw new IOException("modified chunk 集合超過 1 MiB");
    OperationState.write(repo.resolve("modified-chunks.yml"), yaml);
  }

  public static Set<ChunkPos> tree(ObjectStore objects, String tree) throws IOException {
    var chunks = new TreeSet<ChunkPos>();
    for (var region : objects.readTree(tree).values())
      if (region.name().startsWith("r.") && region.kind() == ObjectStore.Kind.TREE)
        for (var chunk : objects.readTree(region.id()).values())
          if (chunk.name().matches("c\\.-?\\d+\\.-?\\d+")
              && chunk.kind() == ObjectStore.Kind.TREE) {
            String[] xy = chunk.name().split("\\.");
            try {
              chunks.add(new ChunkPos(Integer.parseInt(xy[1]), Integer.parseInt(xy[2])));
            } catch (NumberFormatException ex) {
              throw new IOException("chunk tree 座標超出範圍");
            }
          }
    return chunks;
  }
}
