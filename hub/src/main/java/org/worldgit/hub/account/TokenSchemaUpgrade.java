package org.worldgit.hub.account;

import java.sql.*;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** schema.sql 建立新表；此一次性相容升級保留既有 PAT 與 session。 */
@Component
@Order(-100)
public class TokenSchemaUpgrade implements ApplicationRunner {
  private final DataSource dataSource;
  public TokenSchemaUpgrade(DataSource dataSource) { this.dataSource = dataSource; }
  @Override public void run(ApplicationArguments args) throws SQLException {
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      boolean found = false, scope = false;
      try (var rows = statement.executeQuery("SELECT * FROM tokens WHERE 1 = 0")) {
        var meta = rows.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++)
          {
            if (meta.getColumnName(i).equalsIgnoreCase("last_used_at")) found = true;
            if (meta.getColumnName(i).equalsIgnoreCase("scope")) scope = true;
          }
      }
      if (!found) statement.executeUpdate("ALTER TABLE tokens ADD COLUMN last_used_at BIGINT");
      if (!scope) statement.executeUpdate("ALTER TABLE tokens ADD COLUMN scope TEXT NOT NULL DEFAULT 'admin'");
    }
  }
}
