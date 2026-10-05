package org.worldgit.core.remote;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.OperationState;

/** 世界 URL、{dimension} URL 樣板或明確維度清單。所有 URL 禁止攜帶秘密。 */
public record RemoteSpec(String url, SortedMap<DimensionId, String> dimensions) {
  public RemoteSpec {
    dimensions = Collections.unmodifiableSortedMap(new TreeMap<>(dimensions));
    try {
      if (dimensions.isEmpty()) validateUrl(url.replace("{dimension}", "minecraft.overworld"));
      else {
        if (!"manifest".equals(url)) throw new IOException("明確維度清單的識別值必須為 manifest");
        for (String value : dimensions.values()) validateUrl(value);
      }
    } catch (IOException | NullPointerException ex) {
      throw new IllegalArgumentException("remote 設定無效（內容已遮罩）");
    }
  }

  public static RemoteSpec parse(String value) throws IOException {
    if (value.startsWith("manifest+")) {
      String location = value.substring(9);
      validateUrl(location);
      byte[] bytes;
      var uri = URI.create(location);
      if (uri.getScheme().equals("file")) bytes = Files.readAllBytes(Path.of(uri));
      else {
        var client =
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .build();
        try {
          var request =
              java.net.http.HttpRequest.newBuilder(uri)
                  .timeout(java.time.Duration.ofSeconds(30))
                  .GET()
                  .build();
          var response =
              client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
          try (var in = response.body()) {
            if (response.statusCode() != 200)
              throw new IOException("世界清單 HTTP " + response.statusCode());
            bytes = in.readNBytes(65_537);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("讀取清單取消");
        }
      }
      if (bytes.length > 65_536) throw new IOException("世界清單超過 64 KiB");
      return fromManifest(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }
    validateUrl(value.replace("{dimension}", "minecraft.overworld"));
    return new RemoteSpec(value, new TreeMap<>());
  }

  public static RemoteSpec fromManifest(String yaml) throws IOException {
    Map<String, Object> m = SafeYaml.parse(yaml);
    if (!m.keySet().equals(Set.of("dimensions")) || !(m.get("dimensions") instanceof Map<?, ?> map))
      throw new IOException("世界清單只接受 dimensions mapping");
    var result = new TreeMap<DimensionId, String>();
    for (var e : map.entrySet()) {
      if (!(e.getKey() instanceof String key) || !(e.getValue() instanceof String value))
        throw new IOException("維度 URL 必須是字串");
      validateUrl(value);
      try {
        result.put(new DimensionId(key), value);
      } catch (IllegalArgumentException ex) {
        throw new IOException("清單維度無效");
      }
    }
    if (result.isEmpty() || result.size() > 32) throw new IOException("世界清單需要 1–32 維度");
    return new RemoteSpec("manifest", result);
  }

  public String expand(DimensionId id) throws IOException {
    if (!dimensions.isEmpty()) {
      String value = dimensions.get(id);
      if (value == null) throw new IOException("remote 清單缺少維度：" + id);
      validateUrl(value);
      return value;
    }
    if (!url.contains("{dimension}") && url.endsWith(".git")) return url;
    // directoryName 本身含 %，URL 再編碼一次；伺服器解碼一次後仍是安全 repo 目錄名稱。
    String directory = id.directoryName().replace("%", "%25");
    validateUrl(url.replace("{dimension}", directory));
    if (url.contains("{dimension}")) return url.replace("{dimension}", directory);
    URI uri = URI.create(url);
    String path = uri.getRawPath().replaceAll("/+$", "");
    if (uri.getScheme().equals("file")) return url.replaceAll("/+$", "") + "/" + directory + ".git";
    int slash = path.lastIndexOf('/');
    if (slash < 1) throw new IOException("Hub 世界 URL 必須包含 owner/world");
    String prefix = path.substring(0, slash);
    int ownerSlash = prefix.lastIndexOf('/');
    String base = prefix.substring(0, ownerSlash);
    String owner = prefix.substring(ownerSlash + 1), world = path.substring(slash + 1);
    return uri.getScheme()
        + "://"
        + uri.getRawAuthority()
        + base
        + "/git/"
        + owner
        + "/"
        + world
        + "/"
        + directory
        + ".git";
  }

  public static void validateUrl(String value) throws IOException {
    try {
      URI uri = URI.create(value);
      if (!Set.of("https", "http", "file").contains(uri.getScheme())
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getFragment() != null
          || (!uri.getScheme().equals("file")
              && (uri.getHost() == null || uri.getHost().isBlank())))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new IOException("remote URL 無效；只接受 HTTP(S)/file，禁止帳密、query、fragment");
    }
  }

  public static SortedMap<String, RemoteSpec> read(Path root) throws IOException {
    var m = OperationState.read(root.resolve("remotes.yml"));
    var result = new TreeMap<String, RemoteSpec>();
    if (m.isEmpty()) return result;
    if (!m.keySet().equals(Set.of("remotes")) || !(m.get("remotes") instanceof Map<?, ?> remotes))
      throw new IOException("remotes.yml 格式無效");
    for (var e : remotes.entrySet()) {
      if (!(e.getKey() instanceof String name) || !(e.getValue() instanceof Map<?, ?> row))
        throw new IOException("remote 設定無效");
      validateName(name);
      if (row.keySet().equals(Set.of("url")) && row.get("url") instanceof String url)
        result.put(name, parse(url));
      else if (row.keySet().equals(Set.of("dimensions")))
        result.put(name, fromManifest(new org.yaml.snakeyaml.Yaml().dump(row)));
      else throw new IOException("remote 設定只接受 url 或 dimensions");
    }
    return result;
  }

  public static void write(Path root, SortedMap<String, RemoteSpec> remotes) throws IOException {
    var rows = new TreeMap<String, Object>();
    for (var e : remotes.entrySet()) {
      validateName(e.getKey());
      var spec = e.getValue();
      if (spec.dimensions.isEmpty()) {
        validateUrl(spec.url.replace("{dimension}", "minecraft.overworld"));
        rows.put(e.getKey(), Map.of("url", spec.url));
      } else {
        var urls = new TreeMap<String, String>();
        for (var item : spec.dimensions.entrySet()) {
          validateUrl(item.getValue());
          urls.put(item.getKey().value(), item.getValue());
        }
        rows.put(e.getKey(), Map.of("dimensions", urls));
      }
    }
    OperationState.write(root.resolve("remotes.yml"), Map.of("remotes", rows));
  }

  public static void validateName(String name) throws IOException {
    if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
      throw new IOException("remote 名稱無效");
  }
}
