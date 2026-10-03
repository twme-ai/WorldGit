package org.worldgit.hub;
import java.sql.*;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
/** PostgreSQL 測試 context 各用獨立 schema，完整 suite 不共享 bootstrap／fixture。 */
final class HubTestDatabase {
  static void configure(DynamicPropertyRegistry r)throws Exception {
    String pg=System.getenv("WORLDGIT_TEST_POSTGRES_URL");if(pg==null || pg.isBlank())return;
    String user=System.getenv().getOrDefault("WORLDGIT_TEST_POSTGRES_USER","postgres"),password=System.getenv().getOrDefault("WORLDGIT_TEST_POSTGRES_PASSWORD","");
    String schema="wg_"+UUID.randomUUID().toString().replace("-","");
    try(var c=DriverManager.getConnection(pg,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
    r.add("spring.datasource.url",()->pg+(pg.contains("?")?"&":"?")+"currentSchema="+schema);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->user);r.add("spring.datasource.password",()->password);
  }
}
