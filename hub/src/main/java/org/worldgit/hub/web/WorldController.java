package org.worldgit.hub.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.AccountService;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.assets.AssetService;
import org.worldgit.hub.history.Dto.*;
import org.worldgit.hub.history.HistoryService;
import org.worldgit.hub.storage.RepoStorage;

@RestController
@RequestMapping("/api/v1")
public class WorldController {
  private final AccountService accounts;
  private final Access access;
  private final HistoryService history;
  private final RepoStorage storage;
  private final AssetService assets;
  private final org.worldgit.hub.storage.OwnerQuota quota;

  public WorldController(AccountService accounts, Access access, HistoryService history, RepoStorage storage, AssetService assets, org.worldgit.hub.storage.OwnerQuota quota) {
    this.accounts = accounts;
    this.access = access;
    this.history = history;
    this.storage = storage;
    this.assets = assets;
    this.quota = quota;
  }

  static Map<String, Object> worldJson(WorldRow w) {
    var m = new LinkedHashMap<String, Object>();
    m.put("owner", w.ownerSlug());
    m.put("name", w.slug());
    m.put("displayName", w.displayName());
    m.put("description", w.description());
    m.put("public", w.isPublic());
    m.put("createdAt", w.createdAt());
    return m;
  }

  @GetMapping("/worlds")
  List<Map<String, Object>> list(HttpServletRequest req) {
    return accounts.listWorlds(access.optionalUser(req)).stream().map(WorldController::worldJson).toList();
  }

  record NewWorld(String owner, String name, String displayName, String description, Boolean isPublic) {}

  @PostMapping("/worlds")
  Map<String, Object> create(HttpServletRequest req, @RequestBody NewWorld body) {
    access.scope(req,"write");
    User u = access.requireUser(req);
    String owner = body.owner() == null || body.owner().isBlank() ? u.username() : body.owner();
    return worldJson(accounts.createWorld(u, owner, body.name(), body.displayName(), body.description(), Boolean.TRUE.equals(body.isPublic()))
        .orElseThrow(() -> new ApiError.NotFound("找不到命名空間 " + owner)));
  }

  @GetMapping("/worlds/{owner}/{world}")
  Map<String, Object> get(HttpServletRequest req, @PathVariable String owner, @PathVariable String world) throws IOException {
    WorldRow w = access.world(req, owner, world, Role.READER);
    var m = worldJson(w);
    m.put("dimensions", history.dimensions(w));
    var page = history.snapshots(w, 1, null, true);
    m.put("latest", page.snapshots().isEmpty() ? null : page.snapshots().get(0));
    m.put("role", accounts.roleOn(access.optionalUser(req), w).api());
    return m;
  }

  @DeleteMapping("/worlds/{owner}/{world}")
  Map<String, Object> delete(HttpServletRequest req, @PathVariable String owner, @PathVariable String world) throws IOException {
    WorldRow w = access.world(req, owner, world, Role.OWNER);
    var lock = quota.lock(owner);
    lock.lock();
    try {
      storage.deleteWorld(owner, world);
      accounts.deleteWorld(w);
    } finally { lock.unlock(); }
    return Map.of("deleted", true);
  }

  @GetMapping("/worlds/{owner}/{world}/snapshots")
  SnapshotPage snapshots(HttpServletRequest req, @PathVariable String owner, @PathVariable String world,
      @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "true") boolean auto, @RequestParam(required = false) String branch,@RequestParam(required=false) String dimension) throws IOException {
    WorldRow w = access.world(req, owner, world, Role.READER);
    if (branch != null && !branch.isEmpty() && (branch.length() > 100 || !org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + branch)))
      throw new IllegalArgumentException("分支名稱無效");
    return history.snapshots(w, Math.max(1, Math.min(limit, 500)), before, auto, branch == null || branch.isEmpty() ? null : branch,dimension==null?null:new org.worldgit.core.model.DimensionId(dimension));
  }

  @GetMapping("/worlds/{owner}/{world}/pushes")
  List<AccountService.PushEvent> pushes(HttpServletRequest req, @PathVariable String owner, @PathVariable String world) {
    WorldRow w = access.world(req, owner, world, Role.READER);
    return accounts.pushEvents(w.id(), 50);
  }

  @GetMapping("/worlds/{owner}/{world}/dims/{dim}/commits/{rev}")
  CommitDetail commit(HttpServletRequest req, @PathVariable String owner, @PathVariable String world,
      @PathVariable String dim, @PathVariable String rev) throws IOException {
    WorldRow w = access.world(req, owner, world, Role.READER);
    var d = Access.dimension(dim);
    var info = history.find(w, d, rev).orElseThrow(() -> new ApiError.NotFound("找不到 commit " + rev));
    return history.detail(w, d, rev, assets.versionFor(info.dataVersion()).id());
  }
}
