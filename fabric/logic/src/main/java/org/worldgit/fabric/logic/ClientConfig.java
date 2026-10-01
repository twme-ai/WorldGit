package org.worldgit.fabric.logic;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import org.worldgit.core.anvil.RegionFile;
import org.worldgit.protocol.DiffPalette;

/** Fabric 客戶端顯示設定，YAML（config/worldgit-client.yml）。 */
public record ClientConfig(
    String palette,
    boolean seeThrough,
    int detailDistance,
    int maxDistance,
    int maxDetailSections,
    int ghostBuildPerFrame) {
  public static final String FILE_NAME = "worldgit-client.yml";

  public static ClientConfig defaults() {
    try {
      return parse("", "<defaults>");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static ClientConfig parse(String text, String source) throws IOException {
    var root = YamlFile.parse(text, source);
    var config =
        new ClientConfig(
            root.string("palette", "auto", Set.of("auto", "default", "colorblind")),
            root.bool("see-through", false),
            root.integer("detail-distance", 48, 8, 512),
            root.integer("max-distance", 384, 16, 4096),
            root.integer("max-detail-sections", 192, 1, 4096),
            root.integer("ghost-build-per-frame", 4, 1, 256));
    root.rejectUnknown();
    return config;
  }

  public static ClientConfig load(Path dir) throws IOException {
    Path file = dir.resolve(FILE_NAME);
    if (!Files.exists(file)) {
      Files.createDirectories(dir);
      RegionFile.atomicWrite(file, DEFAULT_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return parse(Files.readString(file), file.toString());
  }

  public static void save(Path dir, ClientConfig c) throws IOException {
    Files.createDirectories(dir);
    RegionFile.atomicWrite(
        dir.resolve(FILE_NAME),
        (DEFAULT_TEXT
                .replaceFirst("(?m)^palette: .*$", "palette: " + c.palette)
                .replaceFirst("(?m)^see-through: .*$", "see-through: " + c.seeThrough)
                .replaceFirst("(?m)^detail-distance: .*$", "detail-distance: " + c.detailDistance)
                .replaceFirst("(?m)^max-distance: .*$", "max-distance: " + c.maxDistance)
                .replaceFirst(
                    "(?m)^max-detail-sections: .*$", "max-detail-sections: " + c.maxDetailSections)
                .replaceFirst(
                    "(?m)^ghost-build-per-frame: .*$",
                    "ghost-build-per-frame: " + c.ghostBuildPerFrame))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** auto 使用伺服器 hello 帶來的色票；否則用設定指定的。 */
  public DiffPalette resolve(DiffPalette serverPalette) {
    return switch (palette) {
      case "colorblind" -> DiffPalette.COLORBLIND;
      case "default" -> DiffPalette.DEFAULT;
      default -> serverPalette == null ? DiffPalette.DEFAULT : serverPalette;
    };
  }

  public ClientConfig withPalette(String value) {
    return new ClientConfig(
        value, seeThrough, detailDistance, maxDistance, maxDetailSections, ghostBuildPerFrame);
  }

  public ClientConfig withSeeThrough(boolean value) {
    return new ClientConfig(
        palette, value, detailDistance, maxDistance, maxDetailSections, ghostBuildPerFrame);
  }

  public static final String DEFAULT_TEXT =
      """
      # WorldGit 客戶端顯示設定（連到裝了 WorldGit 的伺服器，或單人世界 /wg diff --show）
      # 遊戲內也可用 /wgc palette <auto|default|colorblind>、/wgc seethrough <on|off> 暫時切換並寫回此檔。

      # 色票：auto = 使用伺服器 hello 傳來的；default 綠/紅/黃/紫；colorblind 藍/橘/黃/粉
      palette: auto
      # true = 外框與鬼影可穿過方塊顯示；預設遵守深度遮擋
      see-through: false

      # 距離（方塊）：近於 detail-distance 才畫逐格外框與鬼影，其餘畫該區塊的包圍盒
      detail-distance: 48
      # 超過 max-distance 的區塊不畫
      max-distance: 384
      # 同時保留在 GPU 的逐格明細區塊上限（最近優先）
      max-detail-sections: 192
      # 每個畫面最多建立幾個新的明細區塊（避免首幀停頓）
      ghost-build-per-frame: 4
      """;
}
