package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.platform.remote.*;

class RemoteConfigTest {
  @TempDir Path temp;
  @Test void defaultsAndTypes() throws Exception {
    var d=ServerConfig.defaults().remote();assertFalse(d.webhook().enabled());assertEquals(0,d.fetchIntervalSeconds());
    var r=ServerConfig.parse("remote:\n  timeout-seconds: 3\n  fetch-interval-seconds: 60\n  webhook:\n    enabled: true\n    port: 25762\n", "t").remote();
    assertEquals(3,r.timeoutSeconds());assertEquals(25762,r.webhook().port());
    for(String s:List.of("remote: scalar", "remote:\n  webhook: scalar", "remote:\n  timeout-seconds: '3'", "remote:\n  fetch-interval-seconds: 1", "remote:\n  webhook:\n    enabled: yes-please", "remote:\n  token: secret"))assertThrows(IOException.class,()->ServerConfig.parse(s,"t"));
  }
  @Test void errorsDoNotRevealSecretsOrCause() {
    for(String s:List.of("remote:\n  hub-url: https://secret-token@host/owner/world", "remote:\n  secret-token: secret-token", "remote: [secret-token", "remote:\n  credentials-file: ../../secret-token")) {
      var e=assertThrows(IOException.class,()->ServerConfig.parse(s,"t"));assertFalse(e.toString().contains("secret-token"));assertNull(e.getCause());
    }
  }
  @Test void credentialsStayAtUserLevelAndEnvironmentWins() throws Exception {
    var p=temp.resolve("credentials.yml");Files.writeString(p,"credentials:\n  http://localhost:8091:\n    mode: bearer\n    token: file-pat\n");
    Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    var c=new PlatformCredentials(ServerConfig.defaults().remote(),Map.of(),temp).credentials().resolve("origin","http://localhost:8091/owner/world");
    assertEquals("Bearer file-pat",c.authorization());assertFalse(c.toString().contains("file-pat"));
    var env=new PlatformCredentials(ServerConfig.defaults().remote(),Map.of("WGIT_TOKEN","env-pat"),temp).credentials().resolve("origin","http://localhost:8091/owner/world");assertEquals("Bearer env-pat",env.authorization());
    Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(IOException.class,()->new PlatformCredentials(ServerConfig.defaults().remote(),Map.of(),temp).credentials().resolve("origin","http://localhost:8091/owner/world"));
  }
  @Test void readWriteClassification() {
    for(var c:List.of("fetch","comments"))assertFalse(RemoteAccess.writes(c,new String[0]));
    assertFalse(RemoteAccess.writes("remote",new String[]{"list"}));assertFalse(RemoteAccess.writes("pr",new String[]{"view","1"}));
    for(var c:List.of("push","pull","comment"))assertTrue(RemoteAccess.writes(c,new String[0]));
    assertTrue(RemoteAccess.writes("remote",new String[]{"set-url"}));assertTrue(RemoteAccess.writes("pr",new String[]{"create"}));
  }
  @Test void commentClientSettings() throws Exception {
    var c=ClientConfig.parse("comments-enabled: false\ncomments-distance: 32\ncomments-max-count: 5", "t");
    assertFalse(c.withPalette("default").commentsEnabled());assertEquals(32,c.withSeeThrough(true).commentsDistance());
    ClientConfig.save(temp,c);assertEquals(c,ClientConfig.load(temp));
    assertThrows(IOException.class,()->ClientConfig.parse("comments-max-count: 65","t"));
  }
}
