package org.worldgit.hub.collaboration;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.worldgit.hub.account.Models.*;

/** 事件、站內通知與 webhook outbox 同一 DB transaction，投遞不阻塞合併。 */
@Service
public class EventService {
  private final JdbcClient db;private final ObjectMapper json;
  public EventService(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}
  public String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalArgumentException("事件資料無效",e);}}
  @Transactional public String emit(WorldRow w,String type,Map<String,?> data,Set<String> recipients){
    String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
    var body=new LinkedHashMap<String,Object>();body.put("id",id);body.put("event",type);body.put("at",now);body.put("world",Map.of("owner",w.ownerSlug(),"name",w.slug()));body.put("data",data);
    db.sql("INSERT INTO hub_events(id,world_id,type,payload,at) VALUES (?,?,?,?,?)").params(id,w.id(),type,encode(body),now).update();
    for(String user:recipients)db.sql("INSERT INTO notifications(id,event_id,user_id,at) VALUES (?,?,?,?)").params(UUID.randomUUID().toString(),id,user,now).update();
    for(var hook:db.sql("SELECT id,events FROM webhooks WHERE world_id=? AND enabled=1").param(w.id()).query((rs,n)->List.of(rs.getString(1),rs.getString(2))).list()) {
      if(Arrays.asList(hook.get(1).split(",")).contains(type))db.sql("INSERT INTO webhook_deliveries(id,webhook_id,event_id,status,next_at) VALUES (?,?,?,?,?)").params(UUID.randomUUID().toString(),hook.get(0),id,"PENDING",now).update();
    }
    return id;
  }
  public Page<Map<String,Object>> notifications(User user,int offset,int limit){Page.offset(offset);Page.limit(limit);
    var rows=db.sql("SELECT n.id,n.seen,e.payload,n.at FROM notifications n JOIN hub_events e ON e.id=n.event_id JOIN worlds w ON w.id=e.world_id WHERE n.user_id=? AND (?=1 OR w.visibility='PUBLIC' OR EXISTS (SELECT 1 FROM memberships m WHERE m.owner_id=w.owner_id AND m.user_id=n.user_id) OR EXISTS (SELECT 1 FROM world_grants g WHERE g.world_id=w.id AND g.user_id=n.user_id) OR EXISTS (SELECT 1 FROM team_grants g JOIN teams t ON t.id=g.team_id JOIN team_members tm ON tm.team_id=t.id JOIN memberships om ON om.owner_id=t.owner_id AND om.user_id=tm.user_id WHERE g.world_id=w.id AND tm.user_id=n.user_id)) ORDER BY n.at DESC,n.id LIMIT ? OFFSET ?").params(user.id(),user.admin()?1:0,limit+1,offset)
      .query((rs,n)->Map.<String,Object>of("id",rs.getString(1),"seen",rs.getInt(2)!=0,"event",decode(rs.getString(3)),"at",rs.getLong(4))).list();return Page.of(rows,offset,limit);
  }
  public Object decode(String value){try{return json.readValue(value,Object.class);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
  public void seen(User u,String id){db.sql("UPDATE notifications SET seen=1 WHERE id=? AND user_id=?").params(id,u.id()).update();}
}
