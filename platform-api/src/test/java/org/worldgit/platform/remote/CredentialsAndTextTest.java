package org.worldgit.platform.remote;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialsAndTextTest {
  @TempDir Path dir;
  @Test void environmentFilePermissionsAndRedaction() throws Exception {
    String token="very-secret-pat-abc";var s=RemoteSettings.defaults();
    var secret=new PlatformCredentials(s,Map.of("WGIT_TOKEN",token),dir).credentials().resolve("origin","https://host/world.git");
    assertEquals("Bearer "+token,secret.authorization());assertFalse(secret.toString().contains(token));assertFalse(secret.redact(token+" "+secret.authorization()).contains(token));
    Path file=dir.resolve("credentials.yml");Files.writeString(file,"credentials:\n  https://host:\n    mode: bearer\n    token: "+token+"\n");
    Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    assertEquals(secret.authorization(),new PlatformCredentials(s,Map.of(),dir).credentials().resolve("origin","https://host/world.git").authorization());
    Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(java.io.IOException.class,()->new PlatformCredentials(s,Map.of(),dir).credentials().resolve("origin","https://host/world.git"));
    Files.delete(file);Files.createSymbolicLink(file,dir.resolve("missing"));
    assertThrows(java.io.IOException.class,()->new PlatformCredentials(s,Map.of(),dir).credentials().resolve("origin","https://host/world.git"));
  }
  @Test void textKeepsMarkupAsLiteralAndBoundsUnicode() {
    assertEquals("<red><script>alert(1)</script>",CommentText.plain("§c<red><script>alert(1)</script>\u0000\u202e",100));
    assertEquals("😀😀…",CommentText.plain("😀😀😀",2));assertEquals("a b",CommentText.plain("a\nb",10));
    String controls=CommentText.plain("visible"+"\n\t".repeat(10000),240);
    assertEquals(241,controls.codePointCount(0,controls.length()));assertTrue(controls.endsWith("…"));
    assertThrows(IllegalArgumentException.class,()->new RemoteSettings("","origin","WGIT_TOKEN","../credentials.yml",30,0,RemoteSettings.defaults().webhook()));
  }
  @Test void secretNeverComesFromWorldOrConfigAndRequires600() throws Exception {
    var c=new PlatformCredentials(RemoteSettings.defaults(),Map.of(),dir);Path secret=dir.resolve("webhook.secret");Files.writeString(secret,"s".repeat(32));
    Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));assertEquals(32,c.webhookSecret().length);
    Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));assertThrows(java.io.IOException.class,c::webhookSecret);
  }
  @Test void clipboardMaskRemembersArbitraryTokensAndBasicAuthorizationWithoutDoingIo() throws Exception {
    String token="arbitrary value/%+secret";
    var credentials=new PlatformCredentials(RemoteSettings.defaults(),Map.of("WGIT_TOKEN",token),dir);
    assertFalse(credentials.mask("bad revision "+token).contains(token));
    Path file=dir.resolve("credentials.yml");Files.writeString(file,"credentials:\n  origin:\n    mode: basic\n    username: player\n    token: another-secret\n");
    Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));credentials.credentials();
    Files.delete(file);
    String basic=Base64.getEncoder().encodeToString("player:another-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String report=credentials.mask("another-secret "+basic+" "+java.net.URLEncoder.encode(token,java.nio.charset.StandardCharsets.UTF_8));
    assertFalse(report.contains("another-secret"));assertFalse(report.contains(basic));assertFalse(report.contains("arbitrary"));assertTrue(report.contains("[REDACTED]"));
  }
}
