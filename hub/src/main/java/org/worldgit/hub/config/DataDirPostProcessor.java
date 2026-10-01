package org.worldgit.hub.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/** SQLite 不會替我們建立父目錄；在資料來源啟動前先建好資料目錄。 */
public class DataDirPostProcessor implements EnvironmentPostProcessor {
  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
    var secrets = new HashMap<String, Object>();
    for (String key : new String[] {"admin-password", "admin-token"}) {
      String prefix = "worldgit.hub.bootstrap." + key;
      String file = env.getProperty(prefix + "-file");
      if (file == null || file.isBlank()) continue;
      try {
        Path path = Path.of(file);
        if (Files.size(path) > 4096) throw new IOException("secret 超過 4096 bytes");
        String secret = Files.readString(path);
        // secrets 檔案通常以換行結尾；密碼中的其他空白不移除。
        secrets.put(prefix, secret.replaceFirst("[\\r\\n]+$", ""));
      } catch (IOException e) { throw new UncheckedIOException("無法讀取 bootstrap secret 檔案", e); }
    }
    env.getPropertySources().addFirst(new MapPropertySource("bootstrapSecretFiles", secrets));
    String dir = env.getProperty("worldgit.hub.data-dir", "data");
    try {
      Files.createDirectories(Path.of(dir).toAbsolutePath());
    } catch (IOException e) {
      throw new UncheckedIOException("無法建立資料目錄 " + dir, e);
    }
  }
}
