package org.worldgit.hub.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.assets.AssetService;
import org.worldgit.hub.config.HubProperties;

/** 預處理過的資源包（公開，與世界權限無關；內容由 Hub 從 Mojang 取得並處理）。 */
@RestController
@RequestMapping("/api/v1")
public class AssetController {
  private final AssetService assets;
  private final HubProperties props;

  public AssetController(AssetService assets, HubProperties props) {
    this.assets = assets;
    this.props = props;
  }

  @GetMapping("/assets")
  List<HubProperties.McVersion> versions() {
    return props.minecraftVersions();
  }

  @GetMapping("/assets/{version}/{file}")
  ResponseEntity<FileSystemResource> file(@PathVariable String version, @PathVariable String file) throws IOException {
    if (!AssetService.FILES.contains(file)) throw new ApiError.NotFound("沒有這個資源 " + file);
    Path p = assets.pack(version).resolve(file);
    MediaType type = file.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.APPLICATION_JSON;
    return ResponseEntity.ok().contentType(type).contentLength(Files.size(p))
        .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
        .body(new FileSystemResource(p));
  }
}
