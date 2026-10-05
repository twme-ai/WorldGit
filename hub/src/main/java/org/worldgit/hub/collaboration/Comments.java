package org.worldgit.hub.collaboration;

import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.worldgit.core.model.DimensionId;
import org.worldgit.hub.account.*;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.web.ApiError;

@Service
public class Comments {
  public record Pin(String dimension,int x,int y,int z,Integer maxX,Integer maxY,Integer maxZ) {
    public Pin {
      new DimensionId(dimension);
      if(Math.abs((long)x)>30_000_000 || Math.abs((long)z)>30_000_000 || y< -2048 || y>2048) throw new IllegalArgumentException("座標超過世界範圍");
      if((maxX==null)!=(maxY==null) || (maxX==null)!=(maxZ==null)) throw new IllegalArgumentException("範圍需完整三個上限");
      if(maxX!=null && (maxX<x || maxY<y || maxZ<z || maxX>30_000_000 || maxZ>30_000_000 || maxY>2048)) throw new IllegalArgumentException("座標範圍無效");
    }
  }
  public record Comment(String id,String prId,String userId,String username,String parentId,String body,Pin pin,long createdAt,long updatedAt,boolean deleted,String dimension) {}
  private final JdbcClient db;private final PullRequests prs;private final AccountService accounts;
  public Comments(JdbcClient db,PullRequests prs,AccountService accounts){this.db=db;this.prs=prs;this.accounts=accounts;}
  private static final String SELECT="SELECT c.id,c.pr_id,c.user_id,u.username,c.parent_id,c.body,c.dimension,c.x,c.y,c.z,c.max_x,c.max_y,c.max_z,c.created_at,c.updated_at,c.deleted,c.project_dimension FROM comments c JOIN users u ON u.id=c.user_id ";
  private final RowMapper<Comment> row=(rs,n)->new Comment(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(16)!=0?"":rs.getString(6),rs.getString(7)==null?null:new Pin(rs.getString(7),rs.getInt(8),rs.getInt(9),rs.getInt(10),(Integer)rs.getObject(11),(Integer)rs.getObject(12),(Integer)rs.getObject(13)),rs.getLong(14),rs.getLong(15),rs.getInt(16)!=0,rs.getString(17));
  public Comment find(WorldRow w,String id){return db.sql(SELECT+"WHERE c.world_id=? AND c.id=?").params(w.id(),id).query(row).optional().orElseThrow(()->new ApiError.NotFound("找不到留言"));}
  public Page<Comment> list(WorldRow w,String pr,boolean pinned,String dimension,int offset,int limit){
    Page.offset(offset);Page.limit(limit);if(pr!=null)prs.find(w,pr);if(dimension!=null)new DimensionId(dimension);
    var params=new ArrayList<Object>();params.add(w.id());String where="WHERE c.world_id=? ";
    if(pr!=null){where+="AND c.pr_id=? ";params.add(pr);}if(pinned)where+="AND c.dimension IS NOT NULL AND c.deleted=0 ";
    if(dimension!=null){where+="AND c.dimension=? ";params.add(dimension);}params.add(limit+1);params.add(offset);
    return Page.of(db.sql(SELECT+where+"ORDER BY c.created_at,c.id LIMIT ? OFFSET ?").params(params.toArray()).query(row).list(),offset,limit);
  }
  @Transactional public Comment create(WorldRow w,User u,String pr,String parent,String body,Pin pin){
    var pull=prs.find(w,pr);if(pin!=null && !pin.dimension().equals(pull.dimension()))throw new IllegalArgumentException("釘選需在 PR 維度");String b=PullRequests.text(body,8000,"留言",true);
    if(parent!=null && !find(w,parent).prId().equals(pr))throw new IllegalArgumentException("回覆需在同一 PR");
    if(db.sql("SELECT COUNT(*) FROM comments WHERE pr_id=?").param(pr).query(Long.class).single()>=10000)throw new ApiError.Conflict("每 PR 最多 10000 則留言");
    String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
    db.sql("INSERT INTO comments(id,world_id,pr_id,user_id,parent_id,body,dimension,x,y,z,max_x,max_y,max_z,created_at,updated_at,project_dimension) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
      .params(id,w.id(),pr,u.id(),parent,b,pin==null?null:pin.dimension(),pin==null?null:pin.x(),pin==null?null:pin.y(),pin==null?null:pin.z(),pin==null?null:pin.maxX(),pin==null?null:pin.maxY(),pin==null?null:pin.maxZ(),now,now,pull.dimension()).update();return find(w,id);
  }
  private Comment editable(WorldRow w,User u,String id){var c=find(w,id);if(!u.id().equals(c.userId()) && !accounts.roleOn(u,w).atLeast(Role.ADMIN))throw new SecurityException("只有留言作者或 admin 可以編輯／刪除");if(c.deleted())throw new ApiError.Conflict("留言已刪除");return c;}
  public Comment edit(WorldRow w,User u,String id,String body){editable(w,u,id);db.sql("UPDATE comments SET body=?,updated_at=? WHERE id=?").params(PullRequests.text(body,8000,"留言",true),System.currentTimeMillis(),id).update();return find(w,id);}
  public void delete(WorldRow w,User u,String id){editable(w,u,id);db.sql("UPDATE comments SET body='',dimension=NULL,deleted=1,updated_at=? WHERE id=?").params(System.currentTimeMillis(),id).update();}
}
