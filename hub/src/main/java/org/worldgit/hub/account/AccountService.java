package org.worldgit.hub.account;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.config.HubProperties;
import org.worldgit.hub.storage.NameRules;

/** 本機帳號、PAT 與租戶授權。角色和 token scope 分別檢查。 */
@Service
public class AccountService implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(AccountService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private final JdbcClient db;
  private final HubProperties props;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

  public AccountService(JdbcClient db, HubProperties props) {
    this.db = db;
    this.props = props;
  }

  /** 啟動時：沒有任何使用者就建立管理員；有設定固定 token 時確保它存在。 */
  @Override
  public void run(ApplicationArguments args) {
    var b = props.bootstrap();
    long users = db.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
    String adminName = b.adminUser() == null || b.adminUser().isBlank() ? "admin" : b.adminUser();
    if (users == 0) {
      String password = b.adminPassword();
      boolean generated = password == null || password.isBlank();
      if (generated) password = randomToken(12);
      createUser(adminName, password, true);
      if (generated) log.warn("已建立管理員 '{}'，一次性密碼：{}（請登入後建立 token 並自行保管）", adminName, password);
      else log.info("已建立管理員 '{}'", adminName);
    }
    if (b.adminToken() != null && !b.adminToken().isBlank()) {
      var admin = findUser(adminName);
      if (admin.isPresent() && authenticateToken(b.adminToken()).isEmpty())
        insertToken(admin.get().id(), "bootstrap", "PAT", b.adminToken(), null);
    }
  }

  // ---- 使用者 ----

  @org.springframework.transaction.annotation.Transactional
  public User createUser(String username, String password, boolean admin) {
    if (!NameRules.validSlug(username)) throw new IllegalArgumentException("使用者名稱無效（小寫英數、-、_，2–40 字）");
    if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("密碼至少 8 個字元");
    String ownerId = UUID.randomUUID().toString(), userId = UUID.randomUUID().toString();
    long now = Instant.now().toEpochMilli();
    try {
      db.sql("INSERT INTO owners(id, slug, kind, display_name, created_at) VALUES (?,?,?,?,?)")
          .params(ownerId, username, "USER", username, now)
          .update();
      db.sql("INSERT INTO users(id, owner_id, username, password_hash, is_admin, created_at) VALUES (?,?,?,?,?,?)")
          .params(userId, ownerId, username, encoder.encode(password), admin ? 1 : 0, now)
          .update();
      db.sql("INSERT INTO memberships(owner_id, user_id, role) VALUES (?,?,?)").params(ownerId, userId, "OWNER").update();
    } catch (DuplicateKeyException e) {
      throw new IllegalArgumentException("名稱已被使用：" + username);
    }
    return new User(userId, ownerId, username, admin);
  }

  public Optional<User> findUser(String username) {
    return db.sql("SELECT id, owner_id, username, is_admin FROM users WHERE username = ?")
        .param(username)
        .query((rs, n) -> new User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) != 0))
        .optional();
  }

  public Optional<User> authenticatePassword(String username, String password) {
    var hash =
        db.sql("SELECT password_hash FROM users WHERE username = ?").param(username).query(String.class).optional();
    if (hash.isEmpty()) return Optional.empty();
    return encoder.matches(password == null ? "" : password, hash.get()) ? findUser(username) : Optional.empty();
  }

  // ---- token ----

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String randomToken(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private void insertToken(String userId, String name, String kind, String raw, Long expires) {
    insertToken(userId, name, kind, raw, expires, "admin");
  }
  private void insertToken(String userId, String name, String kind, String raw, Long expires, String scope) {
    db.sql("INSERT INTO tokens(id, user_id, name, kind, token_hash, created_at, expires_at, scope) VALUES (?,?,?,?,?,?,?,?)")
        .params(UUID.randomUUID().toString(), userId, name, kind, sha256(raw), Instant.now().toEpochMilli(), expires, scope)
        .update();
  }

  /** 回傳明文 token（只此一次）；資料庫只存 SHA-256。 */
  public String createToken(User user, String name, boolean session) {
    return createToken(user, name, session, null);
  }

  public String createToken(User user, String name, boolean session, Instant requestedExpiry) {
    return createToken(user, name, session, requestedExpiry, "admin");
  }

  public String createToken(User user, String name, boolean session, Instant requestedExpiry, String scope) {
    if (scope == null) scope = "admin";
    if (!Set.of("read", "write", "admin").contains(scope)) throw new IllegalArgumentException("scope 無效");
    if (name != null && name.length() > 100) throw new IllegalArgumentException("名稱最多 100 字");
    String raw = (session ? "wgs_" : "wgt_") + randomToken(32);
    Instant now = Instant.now();
    Instant expiry = session ? now.plusSeconds(14L * 24 * 3600)
        : requestedExpiry == null ? now.plusSeconds(props.tokens().patDays() * 86400L) : requestedExpiry;
    if (!expiry.isAfter(now) || expiry.isAfter(now.plusSeconds(3650L * 86400)))
      throw new IllegalArgumentException("token 到期日必須在未來且不超過十年");
    Long expires = expiry.toEpochMilli();
    insertToken(user.id(), name == null || name.isBlank() ? "token" : name, session ? "SESSION" : "PAT", raw, expires, scope);
    return raw;
  }

  public record Credential(User user, String scope, String kind) {}
  public Optional<Credential> credential(String raw) {
    if (raw == null || raw.isBlank() || raw.length() > 4096) return Optional.empty();
    long now = Instant.now().toEpochMilli();
    var value = db.sql("SELECT u.id, u.owner_id, u.username, u.is_admin, t.scope, t.kind FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ? AND (t.expires_at IS NULL OR t.expires_at > ?)")
        .params(sha256(raw), now)
        .query((rs, n) -> new Credential(new User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) != 0), rs.getString(5), rs.getString(6)))
        .optional();
    if (value.isPresent()) db.sql("UPDATE tokens SET last_used_at = ? WHERE token_hash = ?").params(now, sha256(raw)).update();
    return value;
  }
  public Optional<User> authenticateToken(String raw) { return credential(raw).map(Credential::user); }

  public Optional<User> userById(String id) {
    return db.sql("SELECT id, owner_id, username, is_admin FROM users WHERE id = ?").param(id)
        .query((rs,n) -> new User(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4)!=0)).optional();
  }

  public List<Map<String, Object>> listTokens(User user) {
    return db.sql("SELECT id, name, kind, created_at, expires_at, last_used_at, scope FROM tokens WHERE user_id = ? ORDER BY created_at")
        .param(user.id())
        .query(
            (rs, n) -> {
              var m = new LinkedHashMap<String, Object>();
              m.put("id", rs.getString(1));
              m.put("name", rs.getString(2));
              m.put("kind", rs.getString(3));
              m.put("createdAt", rs.getLong(4));
              m.put("expiresAt", rs.getObject(5));
              m.put("lastUsedAt", rs.getObject(6));
              m.put("scope", rs.getString(7));
              return (Map<String, Object>) m;
            })
        .list();
  }

  public boolean deleteToken(User user, String id) {
    return db.sql("DELETE FROM tokens WHERE id = ? AND user_id = ?").params(id, user.id()).update() > 0;
  }

  // ---- 組織 ----

  @org.springframework.transaction.annotation.Transactional
  public void createOrganization(User creator, String slug, String display) {
    if (!NameRules.validSlug(slug)) throw new IllegalArgumentException("組織名稱無效");
    String id = UUID.randomUUID().toString();
    try {
      db.sql("INSERT INTO owners(id, slug, kind, display_name, created_at) VALUES (?,?,?,?,?)")
          .params(id, slug, "ORG", display == null || display.isBlank() ? slug : display, Instant.now().toEpochMilli())
          .update();
    } catch (DuplicateKeyException e) {
      throw new IllegalArgumentException("名稱已被使用：" + slug);
    }
    db.sql("INSERT INTO memberships(owner_id, user_id, role) VALUES (?,?,?)").params(id, creator.id(), "OWNER").update();
  }

  @org.springframework.transaction.annotation.Transactional
  public void setMember(User actor, String ownerSlug, String username, Role role) {
    // 第一個 SQL 就取得組織寫入鎖，持有到 transaction commit；SQLite／PostgreSQL
    // 都序列化成員異動，避免兩位 owner 同時移除自己而跳過最後 owner 檢查。
    db.sql("UPDATE owners SET display_name = display_name WHERE slug = ?").param(ownerSlug).update();
    String ownerId = db.sql("SELECT id FROM owners WHERE slug = ?").param(ownerSlug).query(String.class).optional()
        .orElseThrow(() -> new NoSuchElementException("找不到 " + ownerSlug));
    if (!roleOnOwner(actor, ownerId).atLeast(Role.OWNER)) throw new SecurityException("需要 owner 權限");
    if (db.sql("SELECT kind FROM owners WHERE id = ?").param(ownerId).query(String.class).single().equals("USER"))
      throw new IllegalArgumentException("個人命名空間不能設定組織成員");
    User target = findUser(username).orElseThrow(() -> new NoSuchElementException("找不到使用者 " + username));
    if (roleOnOwner(target, ownerId) == Role.OWNER && role != Role.OWNER &&
        db.sql("SELECT COUNT(*) FROM memberships WHERE owner_id = ? AND role = 'OWNER'").param(ownerId).query(Long.class).single() <= 1)
      throw new IllegalArgumentException("不能移除最後一位 owner");
    db.sql("DELETE FROM memberships WHERE owner_id = ? AND user_id = ?").params(ownerId, target.id()).update();
    if (role != Role.NONE)
      db.sql("INSERT INTO memberships(owner_id, user_id, role) VALUES (?,?,?)").params(ownerId, target.id(), role.name()).update();
  }

  public Role roleOnOwner(User user, String ownerId) {
    if (user == null) return Role.NONE;
    if (user.admin()) return Role.OWNER;
    return db.sql("SELECT role FROM memberships WHERE owner_id = ? AND user_id = ?")
        .params(ownerId, user.id())
        .query(String.class)
        .optional()
        .map(Role::valueOf)
        .orElse(Role.NONE);
  }

  // ---- 世界 ----

  private static final org.springframework.jdbc.core.RowMapper<WorldRow> WORLD =
      (rs, n) ->
          new WorldRow(
              rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
              rs.getString(6), "PUBLIC".equals(rs.getString(7)), rs.getLong(8));
  private static final String WORLD_SELECT =
      "SELECT w.id, w.owner_id, o.slug, w.slug, w.display_name, w.description, w.visibility, w.created_at FROM worlds w JOIN owners o ON o.id = w.owner_id ";

  public Optional<WorldRow> findWorld(String owner, String slug) {
    return db.sql(WORLD_SELECT + "WHERE o.slug = ? AND w.slug = ?").params(owner, slug).query(WORLD).optional();
  }

  public boolean canCreateIn(User user, String ownerId) {
    return roleOnOwner(user, ownerId).atLeast(Role.WRITER);
  }

  public Optional<String> findOwnerId(String slug) {
    return db.sql("SELECT id FROM owners WHERE slug = ?").param(slug).query(String.class).optional();
  }

  public Role roleOn(User user, WorldRow world) {
    Role r = roleOnOwner(user, world.ownerId());
    if (user != null) {
      var grants = db.sql("SELECT role FROM world_grants WHERE world_id = ? AND user_id = ? UNION ALL SELECT g.role FROM team_grants g JOIN team_members m ON m.team_id = g.team_id JOIN teams t ON t.id = g.team_id JOIN memberships om ON om.owner_id = t.owner_id AND om.user_id = m.user_id WHERE g.world_id = ? AND m.user_id = ?")
          .params(world.id(), user.id(), world.id(), user.id()).query(String.class).list();
      for (String grant : grants) { Role candidate = Role.parse(grant); if (candidate.atLeast(r)) r = candidate; }
    }
    if (r == Role.NONE && world.isPublic()) return Role.READER;
    return r;
  }

  /** 需要 WRITER 以上；owner 命名空間不存在時回傳空。 */
  public Optional<WorldRow> createWorld(User user, String owner, String slug, String display, String description, boolean isPublic) {
    NameRules.requireSlug(slug);
    if (!NameRules.validSlug(slug)) throw new IllegalArgumentException("世界名稱無效或為保留字");
    Optional<String> ownerId = findOwnerId(owner);
    if (ownerId.isEmpty()) return Optional.empty();
    if (!roleOnOwner(user, ownerId.get()).atLeast(Role.WRITER)) throw new SecurityException("沒有權限在 " + owner + " 建立世界");
    try {
      db.sql("INSERT INTO worlds(id, owner_id, slug, display_name, description, visibility, created_at) VALUES (?,?,?,?,?,?,?)")
          .params(
              UUID.randomUUID().toString(), ownerId.get(), slug, display == null || display.isBlank() ? slug : display,
              description == null ? "" : description, isPublic ? "PUBLIC" : "PRIVATE", Instant.now().toEpochMilli())
          .update();
    } catch (DuplicateKeyException e) {
      throw new IllegalArgumentException("世界已存在：" + owner + "/" + slug);
    }
    return findWorld(owner, slug);
  }

  public List<WorldRow> listWorlds(User viewer) {
    var all = db.sql(WORLD_SELECT + "ORDER BY w.created_at DESC").query(WORLD).list();
    return all.stream().filter(w -> roleOn(viewer, w).atLeast(Role.READER)).toList();
  }

  public void deleteWorld(WorldRow w) {
    db.sql("DELETE FROM push_events WHERE world_id = ?").param(w.id()).update();
    db.sql("DELETE FROM worlds WHERE id = ?").param(w.id()).update();
  }

  @org.springframework.transaction.annotation.Transactional
  public void grant(WorldRow w, User actor, String username, Role role) {
    if (!roleOn(actor, w).atLeast(Role.ADMIN)) throw new SecurityException("需要 admin 權限");
    if (role == Role.OWNER || !roleOn(actor,w).atLeast(role)) throw new IllegalArgumentException("不能授予 owner 或高於自身的角色");
    User target = findUser(username).orElseThrow(() -> new NoSuchElementException("找不到使用者"));
    db.sql("DELETE FROM world_grants WHERE world_id = ? AND user_id = ?").params(w.id(),target.id()).update();
    if (role != Role.NONE) db.sql("INSERT INTO world_grants(world_id,user_id,role) VALUES (?,?,?)").params(w.id(),target.id(),role.name()).update();
  }

  @org.springframework.transaction.annotation.Transactional
  public void visibility(WorldRow w, boolean isPublic) {
    db.sql("UPDATE worlds SET visibility = ? WHERE id = ?").params(isPublic ? "PUBLIC" : "PRIVATE",w.id()).update();
  }

  public List<Map<String,Object>> grants(WorldRow w) {
    return db.sql("SELECT u.username,g.role FROM world_grants g JOIN users u ON u.id = g.user_id WHERE g.world_id = ? ORDER BY u.username")
      .param(w.id()).query((rs,n) -> Map.<String,Object>of("username",rs.getString(1),"role",Role.parse(rs.getString(2)).api())).list();
  }

  // ---- push 事件 ----

  public record PushEvent(String dimension, String ref, String oldHead, String newHead, String snapshot, String status, String message, long at) {}

  public void recordPush(String worldId, String dimension, String ref, String oldHead, String newHead, String snapshot, String userId, String status, String message) {
    db.sql("INSERT INTO push_events(id, world_id, dimension, ref_name, old_head, new_head, snapshot, user_id, status, message, at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")
        .params(UUID.randomUUID().toString(), worldId, dimension, ref, oldHead, newHead, snapshot, userId, status, message, Instant.now().toEpochMilli())
        .update();
  }

  public List<PushEvent> pushEvents(String worldId, int limit) {
    return db.sql("SELECT dimension, ref_name, old_head, new_head, snapshot, status, message, at FROM push_events WHERE world_id = ? ORDER BY at DESC LIMIT ?")
        .params(worldId, limit)
        .query((rs, n) -> new PushEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)))
        .list();
  }
}
