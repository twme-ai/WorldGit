package org.worldgit.platform.remote;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import org.worldgit.core.remote.Credentials;

/** 不讀使用者 home、不存世界；環境 PAT 優先於平台資料夾的 600 YAML。 */
public final class PlatformCredentials {
  private final RemoteSettings settings;
  private final Map<String,String> environment;
  private final Path directory;
  private final Set<String> known = java.util.concurrent.ConcurrentHashMap.newKeySet();
  public PlatformCredentials(RemoteSettings settings, Map<String,String> environment, Path directory) {
    this.settings=settings; this.environment=Map.copyOf(environment); this.directory=directory.toAbsolutePath().normalize();
    remember(environment.get(settings.tokenEnvironment()));remember(environment.get(settings.webhook().secretEnvironment()));
  }
  public Credentials credentials() throws IOException {
    secureDirectory();
    Path file=directory.resolve(settings.credentialsFile());
    if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) && Files.size(file)<=65536
        && Files.getPosixFilePermissions(file).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))) {
      var data=org.worldgit.core.service.OperationState.read(file);
      if(data.get("credentials") instanceof Map<?,?> entries)for(var row:entries.values())if(row instanceof Map<?,?> credential && credential.get("token") instanceof String token) {
        remember(token);String username=String.valueOf(credential.containsKey("username")?credential.get("username"):"token");
        remember(Base64.getEncoder().encodeToString((username+":"+token).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      }
    }
    var env=new HashMap<String,String>();
    String token=environment.get(settings.tokenEnvironment());
    if(token!=null && !token.isEmpty()) { env.put("WGIT_TOKEN",token); env.put("WGIT_AUTH","bearer"); }
    return new Credentials(env,directory.resolve(settings.credentialsFile()),null);
  }
  private void secureDirectory() throws IOException {
    if(Files.isSymbolicLink(directory) || !Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory)) throw new IOException("插件憑證目錄必須是普通目錄");
  }
  public byte[] webhookSecret() throws IOException {
    secureDirectory();
    String secret=environment.get(settings.webhook().secretEnvironment());
    if(secret==null || secret.isEmpty()) {
      secureDirectory(); Path file=directory.resolve(settings.webhook().secretFile());
      try {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
            || !Files.getPosixFilePermissions(file).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
            || Files.size(file)>4096) throw new IOException("webhook secret 必須是權限 600 的普通檔案（最多 4 KiB）");
        secret=Files.readString(file).strip();
      } catch(UnsupportedOperationException e) { throw new IOException("無法驗證 secret 權限；請使用環境變數"); }
    }
    if(secret.length()<32 || secret.length()>4096 || secret.indexOf('\n')>=0 || secret.indexOf('\r')>=0)
      throw new IOException("webhook secret 需 32–4096 字元且不得含換行");
    remember(secret);return secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
  private void remember(String value) {
    if(value!=null && !value.isEmpty() && known.size()<512) {known.add(value);known.add(java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8));}
  }
  /** UI 只遮罩記憶體，憑證解析時更新集合，包含不符合 PAT 形狀的已知秘密。 */
  public String mask(String message) {
    if(message==null)return "";
    for(var secret:known.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList())message=message.replace(secret,"[REDACTED]");
    return org.worldgit.core.operation.OperationResult.redact(message);
  }
  /** 統一去除 exception chain，避免 logger 印出 transport/header/body 中秘密。 */
  public static String redact(Throwable error) {
    Throwable root=error;
    while(root instanceof java.util.concurrent.CompletionException && root.getCause()!=null) root=root.getCause();
    return String.valueOf(root.getMessage()).replaceAll("(?i)(Bearer|Basic)\\s+\\S+","$1 [REDACTED]")
        .replaceAll("(https?://)[^/@\\s]+@","$1[REDACTED]@");
  }
}
