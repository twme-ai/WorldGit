package org.worldgit.hub.operation;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.worldgit.core.operation.OperationResult;
import org.worldgit.core.model.DimensionId;
import org.worldgit.hub.config.*;
import org.worldgit.hub.account.Models.WorldRow;

/** 已知秘密先替換，再用 core 型態遮罩；不回傳 stack、SQL 或未授權子資源資訊。 */
@Component
public class Reports {
  public static final String VERSION="0.1.0-SNAPSHOT";
  private final JdbcClient db;private final List<String> configured=new ArrayList<>();
  public Reports(JdbcClient db,HubProperties props,CollaborationProperties collaboration,org.springframework.core.env.Environment env){
    this.db=db;configured.add(props.bootstrap().adminToken());configured.add(props.bootstrap().adminPassword());
    collaboration.oauth().values().forEach(p->configured.add(p.clientSecret()));configured.add(env.getProperty("spring.mail.password"));
  }
  public List<String> secrets(HttpServletRequest req){
    var out=new ArrayList<>(configured);
    if(req!=null) {
      String h=req.getHeader("Authorization");if(h!=null){out.add(h);if(h.startsWith("Bearer "))out.add(h.substring(7));if(h.startsWith("Basic "))try{String decoded=new String(Base64.getDecoder().decode(h.substring(6)),java.nio.charset.StandardCharsets.UTF_8);out.add(decoded);int i=decoded.indexOf(':');if(i>=0)out.add(decoded.substring(i+1));}catch(IllegalArgumentException ignored){}}
      if(req.getAttribute("worldgit.secrets") instanceof Collection<?> values)values.forEach(v->out.add(v.toString()));
      if(req.getAttribute("worldgit.world") instanceof WorldRow w)out.addAll(db.sql("SELECT secret FROM webhooks WHERE world_id=? LIMIT 10").param(w.id()).query(String.class).list());
    }
    return out;
  }
  public static DimensionId dimension(HttpServletRequest req){
    if(req==null)return null;String d=Objects.toString(req.getAttribute("worldgit.dimension"),req.getParameter("dimension"));
    if(d==null)d=req.getParameter("dim");if(d==null){var parts=Objects.toString(req.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI),req.getRequestURI()).split("/dims/",2);if(parts.length==2)try{d=org.worldgit.hub.web.Access.dimension(java.net.URLDecoder.decode(parts[1].split("/",2)[0],java.nio.charset.StandardCharsets.UTF_8)).value();}catch(IllegalArgumentException ignored){}}try{return d==null?null:new DimensionId(d);}catch(IllegalArgumentException ignored){return null;}
  }
  public OperationResult.ErrorReport report(String code,UUID id,String operation,DimensionId dim,String message,Collection<String> secrets){
    return OperationResult.ErrorReport.create(code,id,operation,dim,VERSION,"Hub "+VERSION,message,secrets);
  }
  public Map<String,Object> error(HttpServletRequest req,String code,String message){
    var context=OperationProgressContext.of(req);var report=report(code,context.id(),context.operation(),dimension(req),message,secrets(req));
    var result=new OperationResult(context.id(),report.operation(),OperationResult.Status.FAILED,dimension(req),Map.of("message",report.message()),context.elapsed(),List.of(),report);
    if(req!=null)req.setAttribute("worldgit.result",result);
    var body=new LinkedHashMap<String,Object>();body.put("code",code);body.put("error",report.message());body.put("errorReport",report);body.put("result",result);return body;
  }
  public record OperationProgressContext(UUID id,String operation,long started) {
    public long elapsed(){return Math.max(0,(System.nanoTime()-started)/1_000_000);}
    public static OperationProgressContext of(HttpServletRequest req){
      if(req!=null && req.getAttribute("worldgit.context") instanceof OperationProgressContext c)return c;
      var c=new OperationProgressContext(UUID.randomUUID(),req==null?"http":req.getMethod()+" "+req.getRequestURI(),System.nanoTime());if(req!=null)req.setAttribute("worldgit.context",c);return c;
    }
  }
}
