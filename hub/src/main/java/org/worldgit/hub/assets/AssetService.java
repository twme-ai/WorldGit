package org.worldgit.hub.assets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.worldgit.hub.config.HubProperties;
import org.worldgit.hub.config.HubProperties.McVersion;
import org.worldgit.hub.storage.RepoStorage;

/**
 * 每個 Minecraft 版本一份預處理資源包。Mojang 的材質與模型不能由我們散布，所以由 Hub 在需要時
 * 從官方版本清單下載該版本的 client jar（驗證 SHA-1），處理後快取在 cache/assets/&lt;版本&gt;/。
 */
@Service
public class AssetService {
  private static final Logger log = LoggerFactory.getLogger(AssetService.class);
  public static final List<String> FILES =
      List.of("atlas.png", "atlas.json", "blockstates.json", "models.json", "flags.json", "biomes.json", "mapcolors.json");
  private final HubProperties props;
  private final RepoStorage storage;
  private final ObjectMapper json = new ObjectMapper();
  private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Map<String, Integer>> mapColorCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Map<String, int[]>> biomeCache = new ConcurrentHashMap<>();

  public AssetService(HubProperties props, RepoStorage storage) {
    this.props = props;
    this.storage = storage;
  }

  public McVersion versionFor(int dataVersion) {
    return props.minecraftVersions().stream()
        .filter(v -> v.dataVersion() <= dataVersion)
        .max(Comparator.comparingInt(McVersion::dataVersion))
        .orElseThrow(() -> new IllegalArgumentException("不支援的 DataVersion " + dataVersion
            + "（已設定：" + props.minecraftVersions() + "）"));
  }

  public boolean known(String versionId) {
    return props.minecraftVersions().stream().anyMatch(v -> v.id().equals(versionId));
  }

  private Path packDir(String id) {
    return storage.cacheDir().resolve("assets").resolve(id);
  }

  /** 取得（必要時建立）資源包目錄。第一次可能需要下載數十 MB。 */
  public Path pack(String versionId) throws IOException {
    if (!known(versionId)) throw new NoSuchElementException("未設定的 Minecraft 版本 " + versionId);
    Path dir = packDir(versionId);
    if (complete(dir)) return dir;
    synchronized (locks.computeIfAbsent(versionId, k -> new Object())) {
      if (complete(dir)) return dir;
      Path tmp = dir.resolveSibling(versionId + ".tmp-" + System.nanoTime());
      try (ResourceSource src = openSource(versionId)) {
        var stats = new AssetPipeline(src).run(tmp);
        log.info("資源包 {} 完成：{}", versionId, stats);
        if (Files.exists(dir)) deleteTree(dir);
        Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
      } finally {
        if (Files.exists(tmp)) deleteTree(tmp);
      }
      return dir;
    }
  }

  private static boolean complete(Path dir) {
    for (String f : FILES) if (!Files.isRegularFile(dir.resolve(f))) return false;
    return true;
  }

  private ResourceSource openSource(String id) throws IOException {
    Path configured = props.assets().sourceDir();
    if (configured != null && !configured.toString().isBlank()) {
      Path dir = configured.resolve(id);
      if (Files.isDirectory(dir.resolve("assets"))) return ResourceSource.directory(dir);
      if (Files.isRegularFile(dir.resolve("client.jar"))) return ResourceSource.zip(dir.resolve("client.jar"));
    }
    return ResourceSource.zip(downloadClient(id));
  }

  private Path downloadClient(String id) throws IOException {
    Path jar = storage.cacheDir().resolve("mojang").resolve(id).resolve("client.jar");
    if (Files.isRegularFile(jar)) return jar;
    Files.createDirectories(jar.getParent());
    try {
      HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
      JsonNode manifest = json.readTree(get(http, props.assets().manifestUrl()));
      String versionUrl = null;
      for (JsonNode v : manifest.path("versions")) if (v.path("id").asText().equals(id)) versionUrl = v.path("url").asText();
      if (versionUrl == null) throw new IOException("Mojang 版本清單中沒有 " + id);
      JsonNode client = json.readTree(get(http, versionUrl)).path("downloads").path("client");
      String url = client.path("url").asText(), sha1 = client.path("sha1").asText();
      log.info("下載 Minecraft {} client jar（{} bytes）", id, client.path("size").asLong());
      Path tmp = jar.resolveSibling("client.jar.part");
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
      if (res.statusCode() != 200) throw new IOException("下載失敗 HTTP " + res.statusCode());
      try (InputStream in = res.body(); var out = Files.newOutputStream(tmp)) {
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) {
          md.update(buf, 0, n);
          out.write(buf, 0, n);
        }
      }
      if (!HexFormat.of().formatHex(md.digest()).equals(sha1)) throw new IOException("client jar SHA-1 不符");
      Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
      return jar;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("下載被中斷", e);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] get(HttpClient http, String url) throws IOException, InterruptedException {
    var res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " " + url);
    return res.body();
  }

  /** 方塊名稱（不含 minecraft:）→ RGB；供 tile 渲染。 */
  public Map<String, Integer> mapColors(String versionId) throws IOException {
    var cached = mapColorCache.get(versionId);
    if (cached != null) return cached;
    var map = new HashMap<String, Integer>();
    json.readTree(pack(versionId).resolve("mapcolors.json").toFile()).fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asInt()));
    mapColorCache.put(versionId, map);
    return map;
  }

  /** biome id → [grass, foliage, water]。 */
  public Map<String, int[]> biomeColors(String versionId) throws IOException {
    var cached = biomeCache.get(versionId);
    if (cached != null) return cached;
    var map = new HashMap<String, int[]>();
    json.readTree(pack(versionId).resolve("biomes.json").toFile()).fields().forEachRemaining(
        e -> map.put(e.getKey(), new int[] {e.getValue().path("grass").asInt(), e.getValue().path("foliage").asInt(), e.getValue().path("water").asInt()}));
    biomeCache.put(versionId, map);
    return map;
  }

  private static void deleteTree(Path p) throws IOException {
    try (var s = Files.walk(p)) {
      for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(x);
    }
  }
}
