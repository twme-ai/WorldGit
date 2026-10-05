package org.worldgit.hub.operation;

import java.util.*;
import java.io.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.web.context.request.*;
import org.worldgit.core.operation.*;

/** 所有動作有同一個 execution boundary；成功通知放 HTTP header，保留舊陣列／物件 DTO。 */
@Configuration
public class Outcomes {
  public static HttpServletRequest request(){var a=RequestContextHolder.getRequestAttributes();return a instanceof ServletRequestAttributes s?s.getRequest():null;}
  public static OperationResult result(HttpServletRequest req){
    if(req.getAttribute("worldgit.result") instanceof OperationResult r)return r;
    var c=Reports.OperationProgressContext.of(req);
    var r=new OperationResult(c.id(),c.operation(),Boolean.TRUE.equals(req.getAttribute("worldgit.noop"))?OperationResult.Status.NO_OP:OperationResult.Status.SUCCESS,Reports.dimension(req),Map.of("message",Boolean.TRUE.equals(req.getAttribute("worldgit.noop"))?"設定已是所選狀態":message(req)),c.elapsed(),List.of(),null);
    req.setAttribute("worldgit.result",r);return r;
  }
  private static String message(HttpServletRequest req){
    String path=req.getRequestURI(),method=req.getMethod();
    if(path.endsWith("/operations"))return "工作已排程";
    if(path.endsWith("/test"))return "測試投遞已排入佇列";
    if(path.endsWith("/cancel"))return "取消請求已送出";
    if(path.endsWith("/reviews"))return "審核已儲存";
    if(path.endsWith("/choices"))return "衝突選擇已儲存";
    if(path.endsWith("/merge"))return "PR 合併完成";
    if(path.endsWith("/seen"))return "通知已標為已讀";
    if(path.endsWith("/default-branch"))return "此維度預設分支已儲存";
    if(path.endsWith("/auth/login"))return "登入完成";
    if(path.endsWith("/auth/logout"))return "登出完成";
    if(path.endsWith("/auth/password"))return "密碼已更新";
    if(path.endsWith("/auth/register"))return "註冊申請已處理，請查看信箱";
    if(path.endsWith("/auth/verify"))return "信箱驗證完成";
    if(method.equals("DELETE"))return "移除完成";
    if(method.equals("PUT") || method.equals("PATCH"))return "設定已儲存";
    if(method.equals("POST"))return "建立完成";
    return "操作已完成";
  }
  public static void headers(HttpServletResponse response,ObjectMapper json,OperationResult result)throws IOException {
    response.setHeader("X-WorldGit-Operation",result.operationId().toString());
    // 避免完整報告使 headers 過大；完整錯誤只在 JSON body。
    var summary=Map.<String,Object>of("message",Objects.toString(result.summary().get("message"),"").substring(0,Math.min(256,Objects.toString(result.summary().get("message"),"").length())));
    String operation=result.operation().substring(0,Math.min(200,result.operation().length()));
    var header=new OperationResult(result.operationId(),operation,result.status(),result.dimension(),summary,result.elapsedMillis(),result.nextSteps(),null);
    response.setHeader("X-WorldGit-Result",Base64.getEncoder().encodeToString(json.writeValueAsBytes(header)));
  }
  @Bean FilterRegistrationBean<Filter> outcomeFilter(ObjectMapper json,Operations operations){
    Filter f=(request,response,chain)-> {
      var req=(HttpServletRequest)request;var res=(HttpServletResponse)response;
      if(!req.getRequestURI().startsWith("/api/")){chain.doFilter(req,res);return;}
      var c=Reports.OperationProgressContext.of(req);req.setAttribute("worldgit.reports",operations.reports());
      // JSON 上限先於配置；重播讀取是讓秘密來源與維度在格式錯誤時也能遮罩。
      HttpServletRequest wrapped=req;
      if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod()) && req.getContentType()!=null && req.getContentType().startsWith("application/json") && req.getContentLengthLong()<=1_048_576){
        byte[] bytes=req.getInputStream().readNBytes(1_048_577);
        if(bytes.length>1_048_576){org.worldgit.hub.config.LoginSecurity.error(res,413,"budget","JSON 請求最多 1 MiB",req);return;}
        try{var tree=json.readTree(bytes);var values=new ArrayList<String>();collect(tree,values);req.setAttribute("worldgit.secrets",values);if(tree!=null && tree.path("dimension").isTextual())req.setAttribute("worldgit.dimension",tree.path("dimension").asText());}catch(IOException ignored){}
        wrapped=new HttpServletRequestWrapper(req){@Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return input.read();}public int read(byte[] b,int o,int n){return input.read(b,o,n);}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){throw new UnsupportedOperationException();}};}};
      }
      boolean progress=!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod()) || req.getRequestURI().endsWith("/merge-preview") || req.getRequestURI().endsWith("/zip");
      if(!progress){chain.doFilter(wrapped,res);return;}
      var entry=operations.transientEntry(c.id(),c.operation());
      try(var context=new OperationProgress(c.id(),c.operation(),entry::progress)){
        entry.context=context;chain.doFilter(wrapped,res);
      }catch(IOException | ServletException | RuntimeException e){operations.reports().error(req,"internal",Objects.toString(e.getMessage(),"操作失敗"));throw e;}finally{
        if(res.getStatus()>=400 && req.getAttribute("worldgit.result")==null)operations.reports().error(req,"http-"+res.getStatus(),"HTTP "+res.getStatus());
        var terminal=result(req);entry.finish(terminal,null);operations.retain(req,entry);
      }
    };
    var bean=new FilterRegistrationBean<>(f);bean.setOrder(Integer.MIN_VALUE+1);bean.addUrlPatterns("/api/*");return bean;
  }
  private static void collect(com.fasterxml.jackson.databind.JsonNode tree,List<String> values){
    if(tree==null)return;
    if(tree.isObject())tree.fields().forEachRemaining(e->{if(e.getKey().matches("(?i).*(token|secret|password|authorization|pat).*")){if(e.getValue().isTextual())values.add(e.getValue().asText());}else collect(e.getValue(),values);});
    else if(tree.isArray())tree.forEach(n->collect(n,values));
  }
}
