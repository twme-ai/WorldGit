package org.worldgit.core;

import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.service.*;

/** 本機驗證工具，沒有任何 MC 執行期依賴。 */
public final class AcceptanceTool {
  public static void main(String[] args) throws Exception {
    var layout = WorldLayout.discover(Path.of(args[1]));
    switch (args[0]) {
      case "chunks" -> {
        for (var d : layout.dimensions().values())
          for (Path file : RegionFile.list(d.region()))
            try (var r = new RegionFile(file)) {
              for (int i = 0; i < 1024; i++)
                if (r.has(i) && ChunkNormalizer.full(r.read(i)))
                  System.out.println(
                      d.id() + " " + r.pos(i).x() + " " + r.pos(i).z() + " " + r.timestamp(i));
            }
      }
      case "repack" -> {
        var world = new WorldRepositories(layout);
        for (var e : world.tracked().entrySet())
          try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false)) {
            System.out.println(e.getKey() + " " + repo.repack());
          }
      }
      case "one-block" ->
          TestWorlds.oneBlock(layout, DimensionId.OVERWORLD, new ChunkPos(0, 0), 9, 0);
      default -> throw new IllegalArgumentException("chunks|repack|one-block");
    }
  }
}
