package org.worldgit.hub;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.worldgit.hub.account.DimensionSchemaUpgrade;
class DimensionMigrationTest {
  @TempDir Path dir;
  @Test void legacyRowsAndAclSurviveAndOpenReviewsRequireConfirmation()throws Exception {
    var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("phase4.db"));
    try(var c=ds.getConnection();var s=c.createStatement()) {
      for(String sql:new String[]{"CREATE TABLE worlds(id TEXT PRIMARY KEY,visibility TEXT)","CREATE TABLE world_grants(world_id TEXT,user_id TEXT,role TEXT)","CREATE TABLE pull_requests(id TEXT,status TEXT,fingerprint TEXT,invalidated INTEGER)","CREATE TABLE comments(pr_id TEXT)","CREATE TABLE pr_choices(pr_id TEXT)","CREATE TABLE pr_reviews(pr_id TEXT)","CREATE TABLE branch_rules(world_id TEXT,branch TEXT,pr_only INTEGER,reviews INTEGER)","CREATE TABLE releases(id TEXT,commits TEXT)"})s.executeUpdate(sql);
      s.executeUpdate("INSERT INTO worlds VALUES ('w','PRIVATE')");s.executeUpdate("INSERT INTO world_grants VALUES ('w','reader','READER')");
      s.executeUpdate("INSERT INTO pull_requests VALUES ('p','open','old',0),('merged','merged','fixed',0)");s.executeUpdate("INSERT INTO comments VALUES ('p')");
      s.executeUpdate("INSERT INTO pr_choices VALUES ('p')");s.executeUpdate("INSERT INTO pr_reviews VALUES ('p'),('merged')");s.executeUpdate("INSERT INTO branch_rules VALUES ('w','main',1,2)");s.executeUpdate("INSERT INTO releases VALUES ('r','{fixed:old}')");
    }
    DimensionSchemaUpgrade.migrate(ds);DimensionSchemaUpgrade.migrate(ds);
    try(var c=ds.getConnection();var s=c.createStatement()) {
      try(var r=s.executeQuery("SELECT dimension,legacy,fingerprint,invalidated FROM pull_requests WHERE id='p'")){assertTrue(r.next());assertEquals("minecraft:overworld",r.getString(1));assertEquals(1,r.getInt(2));assertNull(r.getString(3));assertEquals(1,r.getInt(4));}
      try(var r=s.executeQuery("SELECT dimension,pr_only,reviews FROM dimension_branch_rules")){assertTrue(r.next());assertEquals("*",r.getString(1));assertEquals(1,r.getInt(2));assertEquals(2,r.getInt(3));assertFalse(r.next());}
      for(var query:java.util.Map.of("SELECT visibility FROM worlds","PRIVATE","SELECT role FROM world_grants","READER","SELECT commits FROM releases","{fixed:old}","SELECT project_dimension FROM comments","minecraft:overworld").entrySet())try(var r=s.executeQuery(query.getKey())){assertTrue(r.next());assertEquals(query.getValue(),r.getString(1));}
      try(var r=s.executeQuery("SELECT COUNT(*) FROM pr_reviews")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
    }
  }
}
