package org.worldgit.hub.account;

import java.sql.*;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Phase 5 冪等遷移；保留原 ACL、release 的固定 commits 與舊保護規則。 */
@Component
@Order(-90)
public class DimensionSchemaUpgrade implements ApplicationRunner {
  private final DataSource source;
  public DimensionSchemaUpgrade(DataSource source){this.source=source;}
  @Override public void run(ApplicationArguments args)throws SQLException {migrate(source);}
  public static void migrate(DataSource source)throws SQLException {
    try(var c=source.getConnection()) {
      c.setAutoCommit(false);
      try(var s=c.createStatement()) {
        s.executeUpdate("CREATE TABLE IF NOT EXISTS hub_schema_migrations(version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
        try(var r=s.executeQuery("SELECT version FROM hub_schema_migrations WHERE version=5")){if(r.next()){c.commit();return;}}
        add(c,"pull_requests","dimension","TEXT NOT NULL DEFAULT 'minecraft:overworld'");
        add(c,"pull_requests","legacy","INTEGER NOT NULL DEFAULT 1");
        add(c,"comments","project_dimension","TEXT NOT NULL DEFAULT 'minecraft:overworld'");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS dimension_branch_rules(world_id TEXT NOT NULL REFERENCES worlds(id) ON DELETE CASCADE, dimension TEXT NOT NULL, branch TEXT NOT NULL, pr_only INTEGER NOT NULL, reviews INTEGER NOT NULL, PRIMARY KEY(world_id,dimension,branch))");
        s.executeUpdate("INSERT INTO dimension_branch_rules(world_id,dimension,branch,pr_only,reviews) SELECT world_id,'*',branch,pr_only,reviews FROM branch_rules WHERE NOT EXISTS (SELECT 1 FROM dimension_branch_rules d WHERE d.world_id=branch_rules.world_id AND d.dimension='*' AND d.branch=branch_rules.branch)");
        s.executeUpdate("DELETE FROM pr_choices WHERE pr_id IN (SELECT id FROM pull_requests WHERE legacy=1 AND status='open')");
        s.executeUpdate("DELETE FROM pr_reviews WHERE pr_id IN (SELECT id FROM pull_requests WHERE legacy=1 AND status='open')");
        s.executeUpdate("UPDATE pull_requests SET invalidated=1,fingerprint=NULL WHERE legacy=1 AND status='open'");
        s.executeUpdate("UPDATE comments SET project_dimension=COALESCE((SELECT dimension FROM pull_requests WHERE pull_requests.id=comments.pr_id),'minecraft:overworld')");
        s.executeUpdate("INSERT INTO hub_schema_migrations(version,applied_at) VALUES (5,"+System.currentTimeMillis()+")");
        c.commit();
      }catch(SQLException e){c.rollback();throw e;}
    }
  }
  private static void add(Connection c,String table,String column,String type)throws SQLException {
    try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" WHERE 1=0")) {
      var m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++)if(m.getColumnName(i).equalsIgnoreCase(column))return;
    }
    try(var s=c.createStatement()){s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN "+column+" "+type);}
  }
}
