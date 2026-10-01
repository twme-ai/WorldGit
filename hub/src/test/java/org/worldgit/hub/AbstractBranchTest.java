package org.worldgit.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.worldgit.core.model.DimensionId;

/**
 * 分支與比較的測試夾具：用 core 對 fixture 世界 init，造出三個分支再推送到 Hub——
 * main（初始 + 一次編輯）、feature（從初始分出、另一次編輯，三個維度都有）、side（只推主世界）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractBranchTest {
  static final String TOKEN = "branch-test-token";
  static final ObjectMapper JSON = new ObjectMapper();
  static final String WORLD = "branches";
  static Path data;
  static Path work;

  @LocalServerPort int port;
  @org.springframework.beans.factory.annotation.Autowired org.worldgit.hub.account.AccountService accounts;
  @org.springframework.beans.factory.annotation.Autowired org.worldgit.hub.storage.RepoStorage storage;
  final HttpClient http = HttpClient.newHttpClient();
  java.util.Map<DimensionId, Path> paths;
  int[] mainCell;
  String initialSnapshot, featureSnapshot, mainSnapshot;

  static void prepareDirs() throws Exception {
    data = Files.createTempDirectory("hub-branch-data");
    work = Files.createTempDirectory("hub-branch-world");
  }

  @BeforeAll
  void buildWorld() throws Exception {
    paths = org.worldgit.hub.tools.BranchFixture.create(work);
    initialSnapshot = org.worldgit.hub.tools.BranchFixture.INITIAL.toString();
    featureSnapshot = org.worldgit.hub.tools.BranchFixture.FEATURE.toString();
    mainSnapshot = org.worldgit.hub.tools.BranchFixture.MAIN.toString();
    mainCell = org.worldgit.hub.tools.BranchFixture.MAIN_CELL;
    for (DimensionId d : paths.keySet()) {
      pushRef(d, "main");
      pushRef(d, "feature");
    }
    pushRef(DimensionId.OVERWORLD, "side");
  }

  @AfterAll
  void cleanup() throws Exception {
    for (Path p : new Path[] {data, work})
      try (var s = Files.walk(p)) {
        s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
      }
  }

  void pushRef(DimensionId dim, String branch) throws Exception {
    try (var git = Git.open(paths.get(dim).toFile())) {
      git.push().setRemote(url("/git/admin/" + WORLD + "/" + dim.directoryName() + ".git"))
          .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
          .setCredentialsProvider(new UsernamePasswordCredentialsProvider("admin", TOKEN)).call();
    }
  }

  String url(String path) { return "http://127.0.0.1:" + port + path; }

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

  static String shortId(JsonNode id) { return id.asText().substring(0, 10); }

  static List<String> names(JsonNode array, String field) {
    var out = new java.util.ArrayList<String>();
    array.forEach(n -> out.add(n.get(field).asText()));
    return out;
  }
}
