package org.worldgit.hub.collaboration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.worldgit.hub.account.Models.WorldRow;
import org.worldgit.hub.config.CollaborationProperties;
import org.worldgit.hub.web.ApiError;

@Service
public class Webhooks {
  public static final Set<String> EVENTS=Set.of("push","pr.opened","pr.merged","release");
  public record Hook(String id,String url,List<String> events,boolean enabled,long createdAt) {}
  public record Delivery(String id,String eventId,int attempt,String status,Integer responseCode,String error,long nextAt) {}
  private final JdbcClient db;private final CollaborationProperties.Webhooks props;
  public Webhooks(JdbcClient db,CollaborationProperties props){this.db=db;this.props=props.webhooks();}
  public Page<Hook> list(WorldRow w,int offset,int limit){Page.offset(offset);Page.limit(limit);return Page.of(db.sql("SELECT id,url,events,enabled,created_at FROM webhooks WHERE world_id=? ORDER BY created_at,id LIMIT ? OFFSET ?").params(w.id(),limit+1,offset).query((rs,n)->new Hook(rs.getString(1),rs.getString(2),List.of(rs.getString(3).split(",")),rs.getInt(4)!=0,rs.getLong(5))).list(),offset,limit);}
  public Hook find(WorldRow w,String id){return db.sql("SELECT id,url,events,enabled,created_at FROM webhooks WHERE world_id=? AND id=?").params(w.id(),id).query((rs,n)->new Hook(rs.getString(1),rs.getString(2),List.of(rs.getString(3).split(",")),rs.getInt(4)!=0,rs.getLong(5))).optional().orElseThrow(()->new ApiError.NotFound("找不到 webhook"));}
  @Transactional public Hook create(WorldRow w,String url,String secret,List<String> eventNames,boolean enabled)throws IOException {
    WebhookTarget.resolve(url,props.allowedHosts());validate(secret,eventNames);
    if(db.sql("SELECT COUNT(*) FROM webhooks WHERE world_id=?").param(w.id()).query(Long.class).single()>=10)throw new ApiError.Conflict("每世界最多 10 個 webhook");
    String id=UUID.randomUUID().toString();db.sql("INSERT INTO webhooks(id,world_id,url,secret,events,enabled,created_at) VALUES (?,?,?,?,?,?,?)").params(id,w.id(),url,secret,String.join(",",new TreeSet<>(eventNames)),enabled?1:0,System.currentTimeMillis()).update();return find(w,id);
  }
  private static void validate(String secret,List<String> events){if(secret==null || secret.length()<32 || secret.length()>256 || events==null || events.isEmpty() || events.size()>4 || !EVENTS.containsAll(events))throw new IllegalArgumentException("secret 需 32–256 字元；事件必須 push／pr.opened／pr.merged／release");}
  @Transactional public Hook update(WorldRow w,String id,String url,String secret,List<String> events,boolean enabled)throws IOException {
    find(w,id);WebhookTarget.resolve(url,props.allowedHosts());String key=secret==null?db.sql("SELECT secret FROM webhooks WHERE id=?").param(id).query(String.class).single():secret;validate(key,events);
    db.sql("UPDATE webhooks SET url=?,secret=?,events=?,enabled=? WHERE id=? AND world_id=?").params(url,key,String.join(",",new TreeSet<>(events)),enabled?1:0,id,w.id()).update();return find(w,id);
  }
  public void delete(WorldRow w,String id){find(w,id);db.sql("DELETE FROM webhooks WHERE id=? AND world_id=?").params(id,w.id()).update();}
  public Page<Delivery> deliveries(WorldRow w,String id,int offset,int limit){find(w,id);Page.offset(offset);Page.limit(limit);return Page.of(db.sql("SELECT id,event_id,attempt,status,response_code,error,next_at FROM webhook_deliveries WHERE webhook_id=? ORDER BY next_at DESC,id LIMIT ? OFFSET ?").params(id,limit+1,offset).query((rs,n)->new Delivery(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4),(Integer)rs.getObject(5),rs.getString(6),rs.getLong(7))).list(),offset,limit);}
  public static String signature(String secret,String payload){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(m.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException ex){throw new IllegalStateException(ex);}}
  /** 一個 instance 的有界 outbox worker；PROCESSING 帶 60 秒 lease，當機後可重投。 */
  @Scheduled(fixedDelayString="${worldgit.hub.collaboration.webhooks.poll-millis:1000}") public synchronized void deliverDue(){
    long now=System.currentTimeMillis();db.sql("UPDATE webhook_deliveries SET status='PENDING' WHERE status='PROCESSING' AND next_at<=?").param(now).update();
    var due=db.sql("SELECT d.id,d.attempt,h.url,h.secret,e.payload,h.id FROM webhook_deliveries d JOIN webhooks h ON h.id=d.webhook_id JOIN hub_events e ON e.id=d.event_id WHERE d.status='PENDING' AND d.next_at<=? AND h.enabled=1 ORDER BY d.next_at LIMIT 10").param(now).query((rs,n)->List.of(rs.getString(1),Integer.toString(rs.getInt(2)),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6))).list();
    for(var row:due){
      String id=row.get(0);int attempt=Integer.parseInt(row.get(1))+1;
      if(db.sql("UPDATE webhook_deliveries SET status='PROCESSING',next_at=? WHERE id=? AND status='PENDING'").params(System.currentTimeMillis()+60000,id).update()!=1)continue;
      Integer code=null;String error=null;
      try {
        var target=WebhookTarget.resolve(row.get(2),props.allowedHosts());
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(target.resolver()).setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(10)).build()).build();
        try(var client=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(10)).build()).build()) {
          var post=new HttpPost(target.uri());post.setHeader("X-WorldGit-Signature-256",signature(row.get(3),row.get(4)));post.setHeader("X-WorldGit-Delivery",id);post.setHeader("X-WorldGit-Attempt",Integer.toString(attempt));post.setEntity(new StringEntity(row.get(4),ContentType.APPLICATION_JSON));
          var response=client.executeOpen(null,post,null);
          try {code=response.getCode();}
          finally {
            // GRACEFUL close 可能排空無界 response body；只取 status 後立即丟棄連線。
            if(response instanceof org.apache.hc.core5.io.ModalCloseable closeable)closeable.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
            else {post.cancel();response.close();}
          }
        }
        if(code<200 || code>=300)error="HTTP "+code;
      }catch(Exception ex){error="目標禁止、解析或連線失敗";} // 不洩漏 URL 中 query／secret
      String status=error==null?"DELIVERED":attempt>=props.attempts()?"FAILED":"PENDING";
      long next=System.currentTimeMillis()+Math.min(3600,props.retrySeconds()*(1L<<Math.min(attempt-1,9)))*1000;
      db.sql("UPDATE webhook_deliveries SET attempt=?,status=?,response_code=?,error=?,next_at=? WHERE id=?").params(attempt,status,code,error,next,id).update();
    }
  }
}
