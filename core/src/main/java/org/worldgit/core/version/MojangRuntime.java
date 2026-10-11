package org.worldgit.core.version;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** 只在本機下載／使用原版 jar；所有下載驗證 manifest 的 SHA-1，不進入發行產物。 */
public final class MojangRuntime {
  private MojangRuntime() {}

  public static Path cache() {
    return Path.of(System.getProperty("worldgit.versions", ".work/versions")).toAbsolutePath();
  }

  private static byte[] get(String url, int limit) throws IOException {
    try {
      var http =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(20))
              .followRedirects(HttpClient.Redirect.NORMAL)
              .build();
      var r =
          http.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(Duration.ofSeconds(120))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var in = r.body()) {
        if (r.statusCode() != 200) throw new IOException("Mojang 下載 HTTP " + r.statusCode());
        byte[] bytes = in.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("Mojang 資源超過大小限制");
        return bytes;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Mojang 下載取消");
    }
  }

  private static Map<String, Object> json(byte[] b) throws IOException {
    return VersionYaml.parse(new String(b, java.nio.charset.StandardCharsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  public static synchronized Path prepare(MinecraftVersion version) throws IOException {
    Path dir = cache().resolve(version.id());
    Files.createDirectories(dir);
    Path info = cache().resolve(version.id() + ".json");
    if (!Files.isRegularFile(info)) {
      var manifest =
          json(
              get(
                  "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                  2 * 1024 * 1024));
      String url = null;
      for (Object x : (List<?>) manifest.get("versions")) {
        var m = (Map<?, ?>) x;
        if (version.id().equals(m.get("id"))) url = (String) m.get("url");
      }
      if (url == null) throw new IOException("Mojang manifest 缺少 " + version.id());
      org.worldgit.core.anvil.RegionFile.atomicWrite(info, get(url, 4 * 1024 * 1024));
    }
    var downloads = (Map<String, Object>) json(Files.readAllBytes(info)).get("downloads");
    for (String key : List.of("server", "server_mappings")) {
      var data = (Map<String, Object>) downloads.get(key);
      if (data == null) continue;
      Path file = dir.resolve(key.equals("server") ? "server.jar" : "server.txt");
      String expected = (String) data.get("sha1");
      if (!Files.isRegularFile(file) || !sha1(Files.readAllBytes(file)).equals(expected)) {
        byte[] bytes = get((String) data.get("url"), 128 * 1024 * 1024);
        if (!sha1(bytes).equals(expected)) throw new IOException(key + " SHA-1 不符");
        org.worldgit.core.anvil.RegionFile.atomicWrite(file, bytes);
      }
    }
    // bundle 內只取版本與 libraries；不接受任意路徑或無界解壓縮。
    try (var zip = new ZipFile(dir.resolve("server.jar").toFile())) {
      long total = 0;
      for (var e :
          zip.stream()
              .filter(
                  e ->
                      !e.isDirectory()
                          && e.getName().endsWith(".jar")
                          && (e.getName().startsWith("META-INF/libraries/")
                              || e.getName().startsWith("META-INF/versions/")))
              .toList()) {
        Path target =
            dir.resolve("runtime").resolve(e.getName().substring("META-INF/".length())).normalize();
        if (!target.startsWith(dir.resolve("runtime"))
            || e.getSize() < 0
            || (total += e.getSize()) > 256L * 1024 * 1024)
          throw new IOException("Mojang bundle 路徑／大小無效");
        if (!Files.isRegularFile(target)) {
          Files.createDirectories(target.getParent());
          try (var in = zip.getInputStream(e)) {
            org.worldgit.core.anvil.RegionFile.atomicWrite(
                target, in.readNBytes((int) e.getSize() + 1));
          }
        }
      }
    }
    return dir;
  }

  public static String sha1(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  public static Path java(MinecraftVersion version) throws IOException {
    String configured = System.getProperty("worldgit.java" + version.javaVersion());
    if (configured != null) return Path.of(configured);
    if (Runtime.version().feature() >= version.javaVersion())
      return Path.of(System.getProperty("java.home"), "bin", "java");
    Path linux = Path.of("/usr/lib/jvm/java-" + version.javaVersion() + "-openjdk-amd64/bin/java");
    if (Files.isExecutable(linux)) return linux;
    throw new IOException(
        "Minecraft "
            + version.id()
            + " DFU 需要 Java "
            + version.javaVersion()
            + "；用 -Dworldgit.java"
            + version.javaVersion()
            + "=/path/bin/java 設定");
  }
}
