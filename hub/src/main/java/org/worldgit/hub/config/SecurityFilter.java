package org.worldgit.hub.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.EnumSet;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.worldgit.core.normalize.DecodeBudget;

/** 所有 dispatch（包含 Git、靜態檔、actuator 與錯誤頁）皆帶防禦標頭。 */
@Configuration
public class SecurityFilter {
  public static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
      + "style-src-attr 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
      + "worker-src 'self'; font-src 'self'; base-uri 'none'; object-src 'none'; "
      + "frame-ancestors 'none'; form-action 'self'";

  @Bean
  FilterRegistrationBean<Filter> securityHeaders(HubProperties props) {
    Filter filter = (request, response, chain) -> {
      var req = (HttpServletRequest) request;
      var res = (HttpServletResponse) response;
      res.setHeader("Content-Security-Policy", CSP);
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.setHeader("X-Frame-Options", "DENY");
      res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
      res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
      if (props.security().hsts()) res.setHeader("Strict-Transport-Security", "max-age=31536000");
      String attr = SecurityFilter.class.getName() + ".budget";
      if (req.getAttribute(attr) != null) { chain.doFilter(request, response); return; }
      req.setAttribute(attr, Boolean.TRUE);
      var l = props.limits();
      try (var scope = new DecodeBudget(l.decodedBytes(), l.readBytes(), l.nodes(), l.objects(), l.work()).open()) {
        chain.doFilter(request, response);
      }
    };
    var bean = new FilterRegistrationBean<>(filter);
    bean.setOrder(Integer.MIN_VALUE);
    bean.addUrlPatterns("/*");
    bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
    return bean;
  }
}
