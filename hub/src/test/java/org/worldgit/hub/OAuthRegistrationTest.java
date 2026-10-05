package org.worldgit.hub;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.*;
import org.worldgit.hub.account.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthRegistrationTest {
  static HttpServer mock;static Path data;static final ObjectMapper JSON=new ObjectMapper();static final Map<String,String> challenges=new ConcurrentHashMap<>();static final AtomicReference<SimpleMailMessage> mail=new AtomicReference<>();
  @TestConfiguration static class MailConfig {
    @Bean JavaMailSender testMail(){var sender=mock(JavaMailSender.class);doAnswer(call->{mail.set(call.getArgument(0));return null;}).when(sender).send(any(SimpleMailMessage.class));return sender;}
  }
  static Map<String,String> query(String value){var map=new HashMap<String,String>();if(value!=null)for(String pair:value.split("&")){var p=pair.split("=",2);map.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length<2?"":URLDecoder.decode(p[1],StandardCharsets.UTF_8));}return map;}
  static void respond(HttpExchange ex,int status,String body)throws IOException {byte[] bytes=body.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(status,bytes.length);ex.getResponseBody().write(bytes);ex.close();}
  @DynamicPropertySource static void props(DynamicPropertyRegistry r)throws Exception {
    data=Files.createTempDirectory("p4-oauth");mock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    mock.createContext("/authorize",ex->{var q=query(ex.getRequestURI().getRawQuery());String code=UUID.randomUUID().toString();challenges.put(code,q.get("code_challenge"));ex.getResponseHeaders().set("Location",q.get("redirect_uri")+"?code="+code+"&state="+URLEncoder.encode(q.get("state"),StandardCharsets.UTF_8));ex.sendResponseHeaders(302,-1);ex.close();});
    mock.createContext("/token",ex->{var q=query(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));try{String hashed=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(q.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));assertEquals(challenges.remove(q.get("code")),hashed);respond(ex,200,"{\"access_token\":\"mock-token\",\"token_type\":\"Bearer\",\"expires_in\":300}");}catch(Exception e){respond(ex,400,"{}");}});
    mock.createContext("/userinfo",ex->{assertEquals("Bearer mock-token",ex.getRequestHeaders().getFirst("Authorization"));respond(ex,200,"{\"id\":\"mock-stable-subject\",\"name\":\"Mock user\"}");});mock.start();String base="http://127.0.0.1:"+mock.getAddress().getPort();
    r.add("server.address",()->"127.0.0.1");r.add("server.port",()->"8095");r.add("worldgit.hub.data-dir",()->data.toString());r.add("worldgit.hub.bootstrap.admin-token",()->"oauth-bootstrap");r.add("worldgit.hub.bootstrap.admin-password",()->"test-password");r.add("worldgit.hub.collaboration.registration.enabled",()->"true");HubTestDatabase.configure(r);
    for(String provider:List.of("github","discord","microsoft")){String p="worldgit.hub.collaboration.oauth."+provider+".";r.add(p+"enabled",()->"true");r.add(p+"client-id",()->"mock-client");r.add(p+"client-secret",()->"mock-secret");r.add(p+"authorization-uri",()->base+"/authorize");r.add(p+"token-uri",()->base+"/token");r.add(p+"user-info-uri",()->base+"/userinfo");r.add(p+"user-name-attribute",()->"id");}
  }
  @Autowired AccountService accounts;@LocalServerPort int port;
  @AfterAll void cleanup()throws Exception {mock.stop(0);try(var paths=Files.walk(data)){paths.sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());}}
  URI uri(String path){return URI.create("http://127.0.0.1:"+port+path);}
  HttpClient client(){var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);return HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();}
  String csrf(HttpClient c)throws Exception {c.send(HttpRequest.newBuilder(uri("/api/v1/me")).build(),HttpResponse.BodyHandlers.ofString());var cookies=(CookieManager)c.cookieHandler().orElseThrow();return cookies.getCookieStore().getCookies().stream().filter(v->v.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue();}
  HttpResponse<String> send(HttpClient c,String path,String method,Object body)throws Exception {var b=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");if(!path.equals("/api/v1/auth/login")&&!path.equals("/api/v1/auth/register")&&!path.equals("/api/v1/auth/verify"))b.header("X-XSRF-TOKEN",csrf(c));var response=c.send(b.method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    var result=JSON.readTree(response.body()).path("result");assertFalse(result.path("operationId").asText().isEmpty(),method+" "+path+" "+response.body());
    assertEquals(response.statusCode()<300?"SUCCESS":"FAILED",result.path("status").asText());assertTrue(response.headers().firstValue("X-WorldGit-Result").isPresent());return response;}
  void oauth(HttpClient c,String provider)throws Exception {
    var begin=c.send(HttpRequest.newBuilder(uri("/oauth2/authorization/"+provider)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(302,begin.statusCode());URI authorization=URI.create(begin.headers().firstValue("Location").orElseThrow());var q=query(authorization.getRawQuery());assertEquals("S256",q.get("code_challenge_method"));assertNotNull(q.get("state"));assertNotNull(q.get("code_challenge"));
    var grant=c.send(HttpRequest.newBuilder(authorization).build(),HttpResponse.BodyHandlers.ofString());URI callback=URI.create(grant.headers().firstValue("Location").orElseThrow());var complete=c.send(HttpRequest.newBuilder(callback).build(),HttpResponse.BodyHandlers.ofString());assertEquals(302,complete.statusCode(),complete.body());assertEquals("/settings",URI.create(complete.headers().firstValue("Location").orElseThrow()).getPath());
    var outcome=c.send(HttpRequest.newBuilder(uri("/api/v1/auth/outcome")).build(),HttpResponse.BodyHandlers.ofString());assertEquals("SUCCESS",JSON.readTree(outcome.body()).path("result").path("status").asText());
    assertTrue(JSON.readTree(c.send(HttpRequest.newBuilder(uri("/api/v1/auth/outcome")).build(),HttpResponse.BodyHandlers.ofString()).body()).path("result").isNull());
  }
  @Test void mockLoginLinkUnlinkAllProvidersAndInvalidState()throws Exception {
    var u=accounts.createUser("oauth-user","test-password",false);var c=client();assertEquals(200,send(c,"/api/v1/auth/login","POST",Map.of("username",u.username(),"password","test-password")).statusCode());
    for(String provider:List.of("github","discord","microsoft")){
      assertEquals(200,send(c,"/api/v1/auth/oauth/"+provider+"/link","POST",Map.of()).statusCode());oauth(c,provider);assertEquals(200,send(c,"/api/v1/auth/logout","POST",Map.of()).statusCode());oauth(c,provider);
      var me=c.send(HttpRequest.newBuilder(uri("/api/v1/me")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(u.username(),JSON.readTree(me.body()).path("user").path("username").asText());
    }
    assertEquals(3,JSON.readTree(c.send(HttpRequest.newBuilder(uri("/api/v1/auth/identities")).build(),HttpResponse.BodyHandlers.ofString()).body()).size());assertEquals(200,send(c,"/api/v1/auth/identities/github","DELETE",Map.of()).statusCode());
    var bad=client().send(HttpRequest.newBuilder(uri("/login/oauth2/code/discord?code=invalid&state=invalid")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,bad.statusCode());
    var other=accounts.createUser("oauth-other","test-password",false);assertThrows(SecurityException.class,()->new OAuthAccountsProxy().link(other));
  }
  @Autowired OAuthAccounts oauthAccounts;
  class OAuthAccountsProxy { void link(org.worldgit.hub.account.Models.User u){oauthAccounts.login("discord","mock-stable-subject",u);} }
  @Test void registrationRequiresSingleUseMailVerificationAndLimitsRequests()throws Exception {
    var c=client();String username="verified-user",email="verified@example.test";var body=Map.of("username",username,"email",email,"password","verified-password-123");assertEquals(200,send(c,"/api/v1/auth/register","POST",body).statusCode());assertTrue(accounts.findUser(username).isEmpty());assertNotNull(mail.get());String token=mail.get().getText().split("#")[1];
    assertEquals(200,send(c,"/api/v1/auth/verify","POST",Map.of("token",token)).statusCode());assertTrue(accounts.authenticatePassword(username,"verified-password-123").isPresent());assertEquals(400,send(c,"/api/v1/auth/verify","POST",Map.of("token",token)).statusCode());
    assertEquals(200,send(c,"/api/v1/auth/register","POST",body).statusCode());assertEquals(200,send(c,"/api/v1/auth/register","POST",body).statusCode());assertEquals(429,send(c,"/api/v1/auth/register","POST",body).statusCode());
  }
}
