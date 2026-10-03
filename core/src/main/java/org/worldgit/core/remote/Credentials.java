package org.worldgit.core.remote;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** 來源優先序：環境 → 使用者 600 YAML → 平台 provider → 明確 anonymous。 */
public final class Credentials {
  public enum Mode {
    BASIC,
    BEARER
  }

  public static final class Secret {
    private final Mode mode;
    private final String username, token;

    public Secret(Mode mode, String username, String token) {
      this.mode = Objects.requireNonNull(mode);
      this.username = Objects.requireNonNull(username);
      this.token = Objects.requireNonNull(token);
      if (token.contains("\n")
          || token.contains("\r")
          || username.contains(":")
          || username.contains("\n")
          || username.contains("\r")) throw new IllegalArgumentException("憑證格式無效");
    }

    public Mode mode() {
      return mode;
    }

    public String username() {
      return username;
    }

    public String authorization() {
      return mode == Mode.BEARER
          ? "Bearer " + token
          : "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (username + ":" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String redact(String message) {
      if (message == null) return "遠端操作失敗";
      String out = message.replace(authorization(), "[REDACTED]");
      if (mode == Mode.BASIC && !token.isEmpty())
        out = out.replace(authorization().substring(6), "[REDACTED]");
      if (!token.isEmpty())
        out =
            out.replace(token, "[REDACTED]")
                .replace(
                    java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8),
                    "[REDACTED]");
      return out.replaceAll("(?i)(bearer|basic)\\s+[^\\s]+", "$1 [REDACTED]")
          .replaceAll("(https?://)[^/@\\s]+@", "$1[REDACTED]@");
    }

    @Override
    public String toString() {
      return "Credentials[" + mode + ", REDACTED]";
    }
  }

  @FunctionalInterface
  public interface Provider {
    Secret resolve(String remote, String url) throws IOException;
  }

  private final Map<String, String> env;
  private final Path file;
  private final Provider platform;

  public Credentials(Map<String, String> env, Path file, Provider platform) {
    this.env = Map.copyOf(env);
    this.file = file;
    this.platform = platform;
  }

  public static Credentials system() {
    String override = System.getenv("WGIT_CREDENTIALS_FILE");
    Path file =
        override == null
            ? Path.of(System.getProperty("user.home"), ".config/worldgit/credentials.yml")
            : Path.of(override);
    return new Credentials(System.getenv(), file, null);
  }

  public Secret resolve(String remote, String url) throws IOException {
    String token = env.get("WGIT_TOKEN");
    if (token != null && !token.isEmpty())
      return secret(
          env.getOrDefault("WGIT_AUTH", "basic"),
          env.getOrDefault("WGIT_USERNAME", "token"),
          token);
    if (file != null && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("credentials 必須是普通檔案");
      try {
        if (!Files.getPosixFilePermissions(file)
            .equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
          throw new IOException("credentials 檔案權限必須是 600");
      } catch (UnsupportedOperationException ex) {
        throw new IOException("無法確認 credentials 權限；請使用環境變數或平台 provider");
      }
      if (Files.size(file) > 65_536) throw new IOException("credentials 超過 64 KiB");
      var m = SafeYaml.parse(Files.readString(file));
      if (!m.keySet().equals(Set.of("credentials"))
          || !(m.get("credentials") instanceof Map<?, ?> entries))
        throw new IOException("credentials YAML 格式無效");
      URI uri = URI.create(url);
      String origin = uri.getScheme() + "://" + uri.getRawAuthority();
      Object value = entries.get(origin);
      if (value != null) {
        if (!(value instanceof Map<?, ?> row)
            || !Set.of("mode", "username", "token").containsAll(row.keySet())
            || !(row.get("token") instanceof String t)) throw new IOException("credentials 項目格式無效");
        Object mode = row.get("mode"), user = row.get("username");
        if ((mode != null && !(mode instanceof String))
            || (user != null && !(user instanceof String)))
          throw new IOException("credentials 型別無效");
        return secret(
            mode == null ? "basic" : (String) mode, user == null ? "token" : (String) user, t);
      }
    }
    if (platform != null) {
      var s = platform.resolve(remote, url);
      if (s != null) return s;
    }
    return new Secret(Mode.BASIC, "anonymous", "");
  }

  private static Secret secret(String mode, String username, String token) throws IOException {
    try {
      return new Secret(Mode.valueOf(mode.toUpperCase(Locale.ROOT)), username, token);
    } catch (IllegalArgumentException e) {
      throw new IOException("認證 mode 必須是 basic|bearer，且憑證不得含換行");
    }
  }
}
