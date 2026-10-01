package org.worldgit.hub.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.data.DataService;
import org.worldgit.hub.data.DataService.Window;

@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}/dims/{dim}/commits/{rev}")
public class DataController {
  private static final MediaType BINARY = MediaType.APPLICATION_OCTET_STREAM;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final Access access;
  private final DataService data;

  public DataController(Access access, DataService data, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.json = json;
    this.access = access;
    this.data = data;
  }

  private static ResponseEntity<byte[]> immutable(byte[] body, MediaType type) {
    // commit id 是內容雜湊，回應不會變；私人世界用 private 快取。
    return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePrivate()).body(body);
  }

  @GetMapping("/chunks")
  ResponseEntity<byte[]> chunks(HttpServletRequest req, @PathVariable String owner, @PathVariable String world, @PathVariable String dim,
      @PathVariable String rev, @RequestParam int x0, @RequestParam int z0, @RequestParam int x1, @RequestParam int z1) throws IOException {
    var w = access.world(req, owner, world, Role.READER);
    return immutable(data.chunks(w, Access.dimension(dim), rev, new Window(x0, z0, x1, z1)), BINARY);
  }

  @GetMapping("/diff")
  ResponseEntity<byte[]> diff(HttpServletRequest req, @PathVariable String owner, @PathVariable String world, @PathVariable String dim,
      @PathVariable String rev, @RequestParam(required = false) String base, @RequestParam int x0, @RequestParam int z0,
      @RequestParam int x1, @RequestParam int z1) throws IOException {
    var w = access.world(req, owner, world, Role.READER);
    return immutable(data.diff(w, Access.dimension(dim), rev, base, new Window(x0, z0, x1, z1)), BINARY);
  }

  @GetMapping("/entities")
  ResponseEntity<byte[]> entities(HttpServletRequest req, @PathVariable String owner, @PathVariable String world, @PathVariable String dim,
      @PathVariable String rev, @RequestParam(required = false) String base, @RequestParam(defaultValue = "false") boolean plain,
      @RequestParam int x0, @RequestParam int z0, @RequestParam int x1, @RequestParam int z1) throws IOException {
    var w = access.world(req, owner, world, Role.READER);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
        .body(BoundedJson.encode(json, data.entities(w, Access.dimension(dim), rev, base, plain, new Window(x0, z0, x1, z1))));
  }

  @GetMapping("/tiles")
  List<DataService.TileRef> tiles(HttpServletRequest req, @PathVariable String owner, @PathVariable String world, @PathVariable String dim,
      @PathVariable String rev) throws IOException {
    var w = access.world(req, owner, world, Role.READER);
    return data.tiles(w, Access.dimension(dim), rev);
  }

  @GetMapping("/tiles/{rx}/{rz}.png")
  ResponseEntity<byte[]> tilePng(HttpServletRequest req, @PathVariable String owner, @PathVariable String world, @PathVariable String dim,
      @PathVariable String rev, @PathVariable int rx, @PathVariable int rz) throws IOException, InterruptedException {
    var w = access.world(req, owner, world, Role.READER);
    var t = data.tile(w, Access.dimension(dim), rev, rx, rz).orElseThrow(() -> new ApiError.NotFound("沒有這個 tile"));
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).eTag("\"" + t.etag() + "\"")
        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate()).body(t.png());
  }

  @GetMapping("/tiles/{rx}/{rz}.height")
  ResponseEntity<byte[]> tileHeights(HttpServletRequest req, @PathVariable String owner, @PathVariable String world, @PathVariable String dim,
      @PathVariable String rev, @PathVariable int rx, @PathVariable int rz) throws IOException, InterruptedException {
    var w = access.world(req, owner, world, Role.READER);
    var t = data.tile(w, Access.dimension(dim), rev, rx, rz).orElseThrow(() -> new ApiError.NotFound("沒有這個 tile"));
    return ResponseEntity.ok().contentType(BINARY).eTag("\"" + t.etag() + "h\"")
        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate()).body(t.heights());
  }
}
