package org.worldgit.hub.config;

import java.io.*;
import java.net.URI;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

/** JSON 寫入有大小預算；登入亦拒絕跨來源請求（無 CORS、只收 JSON）。 */
@Configuration
public class RequestBoundary {
  @Bean FilterRegistrationBean<Filter> requestBodyBoundaryFilter(CollaborationProperties props) {
    Filter filter=(request,response,chain)-> {
      var req=(HttpServletRequest)request;var res=(HttpServletResponse)response;
      if(!req.getRequestURI().startsWith("/api/")) {chain.doFilter(req,res);return;}
      if(!java.util.Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) {
        String origin=req.getHeader("Origin");
        String self=req.getScheme()+"://"+req.getServerName()+((req.getServerPort()==80 || req.getServerPort()==443)?"":":"+req.getServerPort());
        String publicOrigin;
        try {var u=URI.create(props.registration().publicUrl());publicOrigin=u.getScheme()+"://"+u.getAuthority();}catch(Exception e){publicOrigin="";}
        if("cross-site".equals(req.getHeader("Sec-Fetch-Site")) || origin!=null && !origin.equals(self) && !origin.equals(publicOrigin)) {
          LoginSecurity.error(res,403,"origin","不接受跨來源寫入",req);return;
        }
      }
      if(req.getContentLengthLong()>1_048_576) {LoginSecurity.error(res,413,"budget","JSON 請求最多 1 MiB",req);return;}
      var wrapped=new HttpServletRequestWrapper(req) {
        @Override public ServletInputStream getInputStream() throws IOException {
          var stream=super.getInputStream();
          return new ServletInputStream() {
            long count;
            @Override public int read() throws IOException {int b=stream.read();if(b>=0 && ++count>1_048_576) throw new IOException("JSON 請求超過預算");return b;}
            @Override public boolean isFinished(){return stream.isFinished();}
            @Override public boolean isReady(){return stream.isReady();}
            @Override public void setReadListener(ReadListener listener){stream.setReadListener(listener);}
          };
        }
      };
      chain.doFilter(wrapped,res);
    };
    var bean=new FilterRegistrationBean<>(filter);bean.setOrder(Integer.MIN_VALUE+2);bean.addUrlPatterns("/api/*");return bean;
  }
}
