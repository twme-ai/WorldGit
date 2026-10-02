package org.worldgit.hub.web;

import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.data.DataService.Window;
import org.worldgit.hub.history.MergePreviewService;

@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}/merge-preview")
public class MergePreviewController {
  private final Access access;
  private final MergePreviewService service;
  private final ObjectMapper json;
  public MergePreviewController(Access access, MergePreviewService service, ObjectMapper json) {
    this.access=access; this.service=service; this.json=json;
  }
  @GetMapping
  ResponseEntity<byte[]> report(HttpServletRequest req, @PathVariable String owner, @PathVariable String world,
      @RequestParam String ours, @RequestParam String theirs) throws IOException {
    return json(service.report(access.world(req,owner,world,Role.READER),ours,theirs));
  }
  @GetMapping("/view/{kind}")
  ResponseEntity<byte[]> view(HttpServletRequest req, @PathVariable String owner, @PathVariable String world,
      @PathVariable String kind, @RequestParam String ours, @RequestParam String theirs, @RequestParam String fingerprint,
      @RequestParam String dim, @RequestParam(defaultValue="auto") String view,
      @RequestParam(defaultValue="") String choices, @RequestParam int x0, @RequestParam int z0,
      @RequestParam int x1, @RequestParam int z1) throws IOException {
    var w=access.world(req,owner,world,Role.READER);
    Object value=service.view(w,ours,theirs,fingerprint,new org.worldgit.core.model.DimensionId(dim),view,choices,new Window(x0,z0,x1,z1),kind);
    if (value instanceof byte[] binary) return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore()).body(binary);
    return json(value);
  }
  // wildcard 保留含斜線的分支；Tomcat 不需開放 encoded slash。
  @GetMapping("/{*spec}")
  ResponseEntity<byte[]> pair(HttpServletRequest req, @PathVariable String owner, @PathVariable String world,
      @PathVariable String spec) throws IOException {
    var w=access.world(req,owner,world,Role.READER);
    String pair=spec.startsWith("/")?spec.substring(1):spec;
    int separator=pair.indexOf("...");
    if(separator<1 || pair.indexOf("...",separator+3)>=0) throw new IllegalArgumentException("網址需為 ours...theirs");
    return json(service.report(w,pair.substring(0,separator),pair.substring(separator+3)));
  }
  private ResponseEntity<byte[]> json(Object value) throws IOException {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(BoundedJson.encode(json,value));
  }
}
