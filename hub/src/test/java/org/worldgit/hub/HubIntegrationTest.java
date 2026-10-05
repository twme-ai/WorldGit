package org.worldgit.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.WorldRepositories;

/**
 * 端到端：用 core 對 fixture 世界 init，以 JGit 經 smart HTTP + token 推送到 Hub，再用 HTTP API 讀回
 * （snapshot 列表、commit 詳情、chunk 二進位、部分推送、未授權與非 WorldGit commit 被拒）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubIntegrationTest {
  static final String TOKEN = "test-admin-token";
  static final ObjectMapper JSON = new ObjectMapper();
  static Path data;
  static Path work;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) throws Exception {
    data = Files.createTempDirectory("hub-test-data");
    work = Files.createTempDirectory("hub-test-world");
    // 選用：WORLDGIT_TEST_POSTGRES_URL（例 jdbc:postgresql://127.0.0.1:18432/hub）設定後整個端到端套件改跑 PostgreSQL；
    // 帳密用 WORLDGIT_TEST_POSTGRES_USER／_PASSWORD。預設（未設）仍是 SQLite。
    String pg = System.getenv("WORLDGIT_TEST_POSTGRES_URL");
    if (pg != null && !pg.isBlank()) {
      r.add("spring.datasource.url", () -> pg);
      r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
      r.add("spring.datasource.username", () -> System.getenv().getOrDefault("WORLDGIT_TEST_POSTGRES_USER", "postgres"));
      r.add("spring.datasource.password", () -> System.getenv().getOrDefault("WORLDGIT_TEST_POSTGRES_PASSWORD", ""));
    }
    r.add("server.address", () -> "127.0.0.1");
    r.add("server.port", () -> "18094");
    r.add("worldgit.hub.data-dir", () -> data.toString());
    r.add("worldgit.hub.bootstrap.admin-token", () -> TOKEN);
    r.add("worldgit.hub.bootstrap.admin-password", () -> "test-password-1");
    HubTestDatabase.configure(r);
  }

  @LocalServerPort int port;
  @org.springframework.beans.factory.annotation.Autowired org.worldgit.hub.account.AccountService accounts;
  @org.springframework.beans.factory.annotation.Autowired org.worldgit.hub.storage.RepoStorage storage;
  final HttpClient http = HttpClient.newHttpClient();
  WorldRepositories repos;

  @BeforeAll
  void makeWorld() throws Exception {
    Path root = Path.of(System.getProperty("worldgit.projectRoot"));
    copy(root.resolve("core/src/test/resources/fixtures/26.2"), work.resolve("world"));
    repos = new WorldRepositories(WorldLayout.discover(work.resolve("world")));
    var author = new CommitMetadata.Identity("tester", "tester@example.test");
    assertTrue(repos.initAll("creative", WorldGitConfig.Track.ALL, author, WorldGitConfig.Entities.ALL).success());
  }

  @AfterAll
  void cleanup() throws Exception {
    for (Path p : new Path[] {data, work})
      try (var s = Files.walk(p)) {
        s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
      }
  }

  static void copy(Path from, Path to) throws Exception {
    try (var s = Files.walk(from)) {
      for (Path p : s.toList()) {
        Path d = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) Files.createDirectories(d);
        else Files.copy(p, d);
      }
    }
  }

  String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  HttpResponse<byte[]> get(String path, String token) throws Exception {
    var b = HttpRequest.newBuilder(URI.create(url(path)));
    if (token != null) b.header("Authorization", "Bearer " + token);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  JsonNode json(String path, String token) throws Exception {
    var r = get(path, token);
    assertEquals(200, r.statusCode(), path + " → " + new String(r.body()));
    return JSON.readTree(r.body());
  }

  void push(DimensionId dim, String world, String token) throws Exception {
    try (var git = Git.open(repos.tracked().get(dim).toFile())) {
      git.push()
          .setRemote(url("/git/admin/" + world + "/" + dim.directoryName() + ".git"))
          .setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
          .setCredentialsProvider(new UsernamePasswordCredentialsProvider("admin", token))
          .call();
    }
  }

  @Test
  void pushThenReadBackThroughApi() throws Exception {
    // 未授權（錯誤 token）的 push 被拒，且不會建立世界
    assertThrows(TransportException.class, () -> push(DimensionId.OVERWORLD, "e2e", "wrong-token"));

    // 單維度推送已完整；世界容器仍列出尚未推送的維度。
    push(DimensionId.OVERWORLD, "e2e", TOKEN);
    var snaps = json("/api/v1/worlds/admin/e2e/snapshots", TOKEN).get("snapshots");
    assertEquals(1, snaps.size());
    assertFalse(snaps.get(0).get("partial").asBoolean(), "單維度歷史不依其他維度配對");
    assertEquals(2, java.util.stream.StreamSupport.stream(json("/api/v1/worlds/admin/e2e",TOKEN).get("dimensions").spliterator(),false).filter(d->d.get("head").isNull()).count());

    // 補推其他維度 → 完整
    for (var d : repos.tracked().keySet()) if (!d.equals(DimensionId.OVERWORLD)) push(d, "e2e", TOKEN);
    snaps = json("/api/v1/worlds/admin/e2e/snapshots", TOKEN).get("snapshots");
    assertEquals(3, snaps.size());
    for (var row : snaps) { assertFalse(row.get("partial").asBoolean()); assertEquals(1,row.get("commits").size(),"每維度獨立歷史保留現有列顯示"); }

    // 私人世界：匿名不可讀
    int anon = get("/api/v1/worlds/admin/e2e/snapshots", null).statusCode();
    assertEquals(404, anon, "匿名讀私人世界");

    // commit 詳情與 chunk 二進位
    var detail = json("/api/v1/worlds/admin/e2e/dims/minecraft.overworld/commits/HEAD", TOKEN);
    assertTrue(detail.toString().length() > 10);
    var chunks = get("/api/v1/worlds/admin/e2e/dims/minecraft.overworld/commits/HEAD/chunks?x0=-8&z0=-8&x1=8&z1=8", TOKEN);
    assertEquals(200, chunks.statusCode());
    var buf = ByteBuffer.wrap(chunks.body());
    byte[] magic = new byte[4];
    buf.get(magic);
    assertEquals("WGCK", new String(magic));
    assertEquals(1, buf.get());
  }

  @Test
  void rejectsNonWorldGitCommit() throws Exception {
    Path plain = Files.createTempDirectory("plain-repo");
    try (var git = Git.init().setDirectory(plain.toFile()).setInitialBranch("main").call()) {
      Files.writeString(plain.resolve("a.txt"), "hi");
      git.add().addFilepattern("a.txt").call();
      git.commit().setSign(false).setMessage("not a worldgit commit").setAuthor(new PersonIdent("x", "x@example.test")).call();
      var results =
          git.push()
              .setRemote(url("/git/admin/plain/minecraft.overworld.git"))
              .setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
              .setCredentialsProvider(new UsernamePasswordCredentialsProvider("admin", TOKEN))
              .call();
      for (var r : results)
        for (var u : r.getRemoteUpdates())
          assertEquals(org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON, u.getStatus(), u.getMessage());
    }
  }

  @Test
  void visibilityMatrixAndUniformGitChallenge() throws Exception {
    var alice = accounts.createUser("matrix-alice", "test-password-1", false);
    var bob = accounts.createUser("matrix-bob", "test-password-1", false);
    String[] tokens = {null, accounts.createToken(bob, "test", false), accounts.createToken(alice, "test", false)};
    for (boolean pub : new boolean[] {true, false}) {
      String world = pub ? "public" : "secret";
      accounts.createWorld(alice, "matrix-alice", world, world, "", pub);
      storage.create("matrix-alice", world, DimensionId.OVERWORLD);
      for (boolean exists : new boolean[] {true, false}) {
        String slug = exists ? world : world + "-missing";
        String rest = "/api/v1/worlds/matrix-alice/" + slug;
        String git = "/git/matrix-alice/" + slug + "/minecraft.overworld.git/info/refs?service=git-upload-pack";
        for (int actor = 0; actor < tokens.length; actor++) {
          int expected = exists && (pub || actor == 2) ? 200 : 404;
          assertEquals(expected, get(rest, tokens[actor]).statusCode(), rest + " actor=" + actor);
          var response = get(git, tokens[actor]);
          assertEquals(actor == 0 ? 401 : expected, response.statusCode(), git + " actor=" + actor);
          if (actor == 0) assertEquals("Basic realm=\"WorldGit Hub\", charset=\"UTF-8\"", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
        var request = HttpRequest.newBuilder(URI.create(url(git))).header("Authorization", "Basic YW5vbnltb3VzOg==").build();
        assertEquals(exists && pub ? 200 : 404, http.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        var pushAnon = HttpRequest.newBuilder(URI.create(url(git.replace("git-upload-pack", "git-receive-pack"))))
            .header("Authorization", "Basic YW5vbnltb3VzOg==").build();
        assertEquals(exists && pub ? 403 : 404, http.send(pushAnon, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
      }
    }
  }

  @Test
  void patExpiresAndRecordsLastUse() throws Exception {
    var user = accounts.findUser("admin").orElseThrow();
    String token = accounts.createToken(user, "expiring-test", false, java.time.Instant.now().plusSeconds(300));
    assertTrue(accounts.authenticateToken(token).isPresent());
    var row = accounts.listTokens(user).stream().filter(t -> "expiring-test".equals(t.get("name"))).findFirst().orElseThrow();
    assertNotNull(row.get("expiresAt")); assertNotNull(row.get("lastUsedAt"));
    assertThrows(IllegalArgumentException.class, () -> accounts.createToken(user, "past", false, java.time.Instant.now().minusSeconds(1)));
  }

  @Test
  void quotaRejectsPushAcrossWorldsWithoutUpdatingRef() throws Exception {
    Path padding = data.resolve("repos/admin/quota-padding");
    Files.createDirectories(padding.getParent());
    try {
      try (var file = new java.io.RandomAccessFile(padding.toFile(), "rw")) { file.setLength(10L << 30); }
      assertThrows(TransportException.class, () -> push(DimensionId.OVERWORLD, "quota-denied", TOKEN));
      assertFalse(Files.exists(data.resolve("repos/admin/quota-denied/minecraft.overworld.git/refs/heads/main")));
    } finally { Files.deleteIfExists(padding); }
  }

  @Test
  void healthAndPalettes() throws Exception {
    for (String path : new String[] {"/", "/actuator/health", "/api/v1/worlds", "/api/v1/worlds/admin/notfound", "/git/admin/notfound/minecraft.overworld.git/info/refs"}) {
      var response = get(path, null);
      assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("worker-src 'self'"));
      assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
      assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
      assertTrue(response.headers().firstValue("Strict-Transport-Security").isEmpty());
    }
    assertEquals(200, get("/actuator/health", null).statusCode());
    var p = json("/api/v1/diff-palettes", null);
    assertTrue(p.has("default") && p.has("colorblind"), p.toString());
  }
}
