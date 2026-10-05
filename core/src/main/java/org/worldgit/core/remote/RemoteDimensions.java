package org.worldgit.core.remote;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.operation.OperationProgress;

/** 發現目前可用的 repo，不以主世界的舊 commit 宣告限制其他維度。 */
final class RemoteDimensions {
  static Set<DimensionId> discover(RemoteSpec remote, Credentials credentials) throws IOException {
    var result = new TreeSet<>(remote.dimensions().keySet());
    if (!result.isEmpty() || remote.url().contains("{dimension}") || remote.url().endsWith(".git")) return result;
    var uri = URI.create(remote.url());
    OperationProgress.check();
    if (uri.getScheme().equals("file")) {
      Path root = Path.of(uri);
      if (!Files.isDirectory(root)) return result;
      try (var files = Files.list(root)) {
        for (Path file : files.toList()) {
          String name = file.getFileName().toString();
          if (!name.endsWith(".git") || !Files.isDirectory(file) || Files.isSymbolicLink(file)) continue;
          String encoded = name.substring(0,name.length()-4); int dot = encoded.indexOf('.');
          if (dot < 1) continue;
          try { result.add(new DimensionId(URLDecoder.decode(encoded.substring(0,dot),java.nio.charset.StandardCharsets.UTF_8)+":"+URLDecoder.decode(encoded.substring(dot+1),java.nio.charset.StandardCharsets.UTF_8))); }
          catch (IllegalArgumentException ignored) {}
          if (result.size() > 32) throw new IOException("遠端世界超過 32 維度");
        }
      }
      return result;
    }
    String path = uri.getRawPath().replaceAll("/+$", ""); int worldSlash = path.lastIndexOf('/');
    if (worldSlash < 1) return result;
    int ownerSlash = path.substring(0,worldSlash).lastIndexOf('/');
    URI endpoint = URI.create(uri.getScheme()+"://"+uri.getRawAuthority()+path.substring(0,ownerSlash)+"/api/v1/worlds"+path.substring(ownerSlash));
    var secret = credentials.resolve("origin", remote.expand(DimensionId.OVERWORLD));
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build()) {
      var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).header("Authorization",secret.authorization()).GET().build();
      var response = client.send(request,HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        if (response.statusCode() == 404) return result; // 舊版／一般 git 主機沿用快照宣告。
        if (response.statusCode() != 200) throw new IOException("維度發現 HTTP "+response.statusCode());
        byte[] bytes = input.readNBytes(65_537);if(bytes.length>65_536) throw new IOException("維度發現超過 64 KiB");
        var document = SafeYaml.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        if (!(document.get("dimensions") instanceof List<?> rows) || rows.size()>32) throw new IOException("遠端維度清單格式無效");
        for (Object value : rows) {
          if (!(value instanceof Map<?,?> row) || !(row.get("id") instanceof String id)) throw new IOException("遠端維度清單格式無效");
          result.add(new DimensionId(id));
        }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();throw new InterruptedIOException("維度發現已取消");
    } catch (IllegalArgumentException ex) { throw new IOException("遠端維度清單格式無效"); }
    OperationProgress.check();return result;
  }
}
