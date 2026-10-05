package org.worldgit.hub.config;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.worldgit.hub.account.*;

@Configuration
public class LoginSecurity {
  @Bean ClientRegistrationRepository registrations(CollaborationProperties props) {
    var entries=new ArrayList<ClientRegistration>();
    props.oauth().forEach((id,p)-> {
      if(!p.enabled()) return;
      String auth,token,info,attribute; List<String> scopes;
      switch(id) {
        case "github" -> {auth="https://github.com/login/oauth/authorize";token="https://github.com/login/oauth/access_token";info="https://api.github.com/user";attribute="id";scopes=List.of("read:user");}
        case "discord" -> {auth="https://discord.com/oauth2/authorize";token="https://discord.com/api/oauth2/token";info="https://discord.com/api/users/@me";attribute="id";scopes=List.of("identify");}
        case "microsoft" -> {auth="https://login.microsoftonline.com/common/oauth2/v2.0/authorize";token="https://login.microsoftonline.com/common/oauth2/v2.0/token";info="https://graph.microsoft.com/v1.0/me";attribute="id";scopes=List.of("User.Read");}
        default -> throw new IllegalArgumentException("OAuth provider 無效");
      }
      entries.add(ClientRegistration.withRegistrationId(id).clientId(p.clientId()).clientSecret(p.clientSecret())
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .authorizationUri(p.authorizationUri()==null?auth:p.authorizationUri()).tokenUri(p.tokenUri()==null?token:p.tokenUri())
        .userInfoUri(p.userInfoUri()==null?info:p.userInfoUri()).userNameAttributeName(p.userNameAttribute()==null?attribute:p.userNameAttribute())
        .scope(p.scopes()==null?scopes:p.scopes()).clientName(id).build());
    });
    if(entries.isEmpty()) return id -> null;
    return new InMemoryClientRegistrationRepository(entries);
  }

  @Bean SecurityFilterChain security(HttpSecurity http,ClientRegistrationRepository clients,CollaborationProperties props,
      OAuthAccounts oauth,AccountService accounts) throws Exception {
    var cookies=CookieCsrfTokenRepository.withHttpOnlyFalse();
    cookies.setCookieCustomizer(b -> b.sameSite("Lax").path("/"));
    // SPA 從 cookie 送純 token；不接受 query/form token。
    var handler=new CsrfTokenRequestAttributeHandler(); handler.setCsrfRequestAttributeName(null);
    http.authorizeHttpRequests(a->a.anyRequest().permitAll()).requestCache(c->c.disable())
      .csrf(c->c.csrfTokenRepository(cookies).csrfTokenRequestHandler(handler).requireCsrfProtectionMatcher(req-> {
        if(Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) return false;
        if(req.getRequestURI().startsWith("/git/")) return false;
        String header=req.getHeader("Authorization");
        if(header!=null && !header.isBlank()) return false;
        if(Set.of("/api/v1/auth/login","/api/v1/auth/register","/api/v1/auth/verify").contains(req.getRequestURI())) return false;
        var s=req.getSession(false); return s!=null && s.getAttribute(RequestUser.SESSION_USER)!=null;
      }))
      .exceptionHandling(e->e.accessDeniedHandler((req,res,ex)->error(res,403,"csrf","CSRF token 無效",req)))
      .headers(h->h.disable()).formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable());
    if(props.oauth().values().stream().anyMatch(CollaborationProperties.OAuth::enabled)) {
      var resolver=new DefaultOAuth2AuthorizationRequestResolver(clients,"/oauth2/authorization");
      resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
      http.oauth2Login(o->o.authorizationEndpoint(a->a.authorizationRequestResolver(resolver))
        .successHandler((req,res,authentication)-> {
          var auth=(org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken)authentication;
          var s=req.getSession();
          Object link=s.getAttribute("worldgit.oauth.link"); Object provider=s.getAttribute("worldgit.oauth.provider"); Object started=s.getAttribute("worldgit.oauth.started");
          s.removeAttribute("worldgit.oauth.link");s.removeAttribute("worldgit.oauth.provider");s.removeAttribute("worldgit.oauth.started");
          try {
            Models.User actor=null;
            if(link instanceof String id) {
              if(!id.equals(s.getAttribute(RequestUser.SESSION_USER)) || !auth.getAuthorizedClientRegistrationId().equals(provider) || !(started instanceof Long at) || System.currentTimeMillis()-at>600_000)
                throw new SecurityException("OAuth 連結已過期");
              actor=accounts.userById(id).orElseThrow();
            }
            OAuth2User principal=auth.getPrincipal();
            var u=oauth.login(auth.getAuthorizedClientRegistrationId(),principal.getName(),actor);
            req.changeSessionId();s.setAttribute(RequestUser.SESSION_USER,u.id());
            s.setAttribute("worldgit.notification",new org.worldgit.core.operation.OperationResult(java.util.UUID.randomUUID(),"oauth",org.worldgit.core.operation.OperationResult.Status.SUCCESS,null,Map.of("message","OAuth 登入／連結完成"),0,List.of(),null));
            res.sendRedirect("/settings");
          } catch(RuntimeException ex) {
            s.invalidate(); oauthError(req,res,403,"OAuth 登入／連結失敗");
          }
        }).failureHandler((req,res,e)-> {
          var s=req.getSession(false); if(s!=null) {s.removeAttribute("worldgit.oauth.link");s.removeAttribute("worldgit.oauth.provider");s.removeAttribute("worldgit.oauth.started");}
          oauthError(req,res,401,"OAuth state 或授權無效");
        }));
    }
    return http.build();
  }
  private static void oauthError(HttpServletRequest req,HttpServletResponse res,int status,String message)throws IOException {
    if(Objects.toString(req.getHeader("Accept"),"").contains("text/html")){
      var c=org.worldgit.hub.operation.Reports.OperationProgressContext.of(req);
      var report=org.worldgit.core.operation.OperationResult.ErrorReport.create("oauth",c.id(),"oauth",null,org.worldgit.hub.operation.Reports.VERSION,"Hub "+org.worldgit.hub.operation.Reports.VERSION,message);
      req.getSession().setAttribute("worldgit.notification",new org.worldgit.core.operation.OperationResult(c.id(),"oauth",org.worldgit.core.operation.OperationResult.Status.FAILED,null,Map.of("message",message),c.elapsed(),List.of(),report));res.sendRedirect("/login");
    }else error(res,status,"oauth",message,req);
  }
  public static void error(HttpServletResponse res,int status,String code,String message) throws IOException {error(res,status,code,message,org.worldgit.hub.operation.Outcomes.request());}
  public static void error(HttpServletResponse res,int status,String code,String message,HttpServletRequest req)throws IOException {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();java.util.Map<String,Object> body;
    if(req!=null && req.getAttribute("worldgit.reports") instanceof org.worldgit.hub.operation.Reports reports)body=reports.error(req,code,message);
    else {
      var c=org.worldgit.hub.operation.Reports.OperationProgressContext.of(req);
      var report=org.worldgit.core.operation.OperationResult.ErrorReport.create(code,c.id(),c.operation(),null,org.worldgit.hub.operation.Reports.VERSION,"Hub "+org.worldgit.hub.operation.Reports.VERSION,message);
      var result=new org.worldgit.core.operation.OperationResult(c.id(),c.operation(),org.worldgit.core.operation.OperationResult.Status.FAILED,null,Map.of("message",report.message()),c.elapsed(),List.of(),report);
      body=Map.of("code",code,"error",report.message(),"errorReport",report,"result",result);
    }
    res.setStatus(status);res.setContentType("application/json;charset=UTF-8");org.worldgit.hub.operation.Outcomes.headers(res,mapper,(org.worldgit.core.operation.OperationResult)body.get("result"));res.getWriter().write(mapper.writeValueAsString(body));
  }
}
