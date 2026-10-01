package org.worldgit.hub;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.*;
import org.sqlite.SQLiteDataSource;
import org.worldgit.hub.account.TokenSchemaUpgrade;
import org.worldgit.hub.config.DataDirPostProcessor;

class SecretsMigrationTest {
  @TempDir Path dir;
  @Test void secretFileOverridesPlainValueAndStripsOnlyTrailingNewlines() throws Exception {
    Path secret = dir.resolve("password"); Files.writeString(secret, " secret with spaces \n");
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
        "worldgit.hub.data-dir", dir.resolve("data").toString(),
        "worldgit.hub.bootstrap.admin-password-file", secret.toString(),
        "worldgit.hub.bootstrap.admin-password", "plain")));
    new DataDirPostProcessor().postProcessEnvironment(env, new SpringApplication());
    assertEquals(" secret with spaces ", env.getProperty("worldgit.hub.bootstrap.admin-password"));
    assertTrue(Files.isDirectory(dir.resolve("data")));
  }

  @Test void legacyTokenSchemaUpgradesIdempotentlyAndPreservesData() throws Exception {
    var ds = new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + dir.resolve("old.db"));
    try (var connection = ds.getConnection(); var sql = connection.createStatement()) {
      sql.executeUpdate("CREATE TABLE tokens (id TEXT PRIMARY KEY, expires_at BIGINT)");
      sql.executeUpdate("INSERT INTO tokens(id, expires_at) VALUES ('old-token', NULL)");
    }
    var migration = new TokenSchemaUpgrade(ds);
    migration.run(null); migration.run(null);
    try (var connection = ds.getConnection(); var sql = connection.createStatement();
        var rows = sql.executeQuery("SELECT id, expires_at, last_used_at FROM tokens")) {
      assertTrue(rows.next()); assertEquals("old-token", rows.getString(1));
      assertNull(rows.getObject(2)); assertNull(rows.getObject(3));
    }
  }
}
