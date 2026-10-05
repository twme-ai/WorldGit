package org.worldgit.platform.remote;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.worldgit.core.remote.Credentials;

class HubClientTest {
  HttpServer server; ExecutorService worker;
  String id=UUID.randomUUID().toString(); String token="private-pat-never-show";
  @AfterEach void stop() {if(server!=null)server.stop(0);if(worker!=null)worker.shutdownNow();}
  HubClient client(HttpHandler handler,Duration timeout) throws Exception {
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);worker=Executors.newCachedThreadPool();server.setExecutor(worker);server.createContext("/",handler);server.start();
    return new HubClient("http://127.0.0.1:"+server.getAddress().getPort()+"/proxy/alice/world",new Credentials.Secret(Credentials.Mode.BEARER,"token",token),timeout);
  }
  void json(HttpExchange e,int status,String body) throws java.io.IOException {byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}}
  String pull(int n) {return "{\"id\":\""+id+"\",\"number\":"+n+",\"title\":\"<red>test</red>\",\"source\":\"topic\",\"target\":\"main\",\"status\":\"open\"}";}
  @Test void prefixAuthorizationPaginationAndNumericPrLookup() throws Exception {
    var calls=new java.util.concurrent.atomic.AtomicInteger();
    try(var h=client(e->{
      assertEquals("Bearer "+token,e.getRequestHeaders().getFirst("Authorization"));assertEquals("/proxy/api/v1/worlds/alice/world/pulls",e.getRequestURI().getPath());
      int offset=e.getRequestURI().getQuery().contains("offset=0&")?0:1;calls.incrementAndGet();
      json(e,200,"{\"items\":["+pull(offset+1)+"],\"offset\":"+offset+",\"hasMore\":"+(offset==0)+"}");
    },Duration.ofSeconds(2))) {assertEquals(2,h.find(2).number());assertEquals(2,calls.get());}
    assertThrows(java.io.IOException.class,()->HubClient.endpoint("https://name:pat@host/alice/world"));
    assertThrows(java.io.IOException.class,()->HubClient.endpoint("https://host/alice/world?token=secret"));
  }
  @Test void statusCodesDoNotExposePrivateBodyOrAuthAndNeverRedirect() throws Exception {
    for(int status:new int[]{401,403,404,409,429,302}) {
      try(var h=client(e->{e.getResponseHeaders().set("Location","http://evil.invalid/");json(e,status,"private world "+token);},Duration.ofSeconds(2))) {
        var ex=assertThrows(HubClient.Error.class,h::pulls);assertFalse(ex.toString().contains(token));assertFalse(ex.toString().contains("private world"));
        assertEquals(switch(status){case 401->HubClient.Failure.UNAUTHORIZED;case 403->HubClient.Failure.FORBIDDEN;case 404->HubClient.Failure.NOT_FOUND;case 409->HubClient.Failure.CONFLICT;case 429->HubClient.Failure.RATE_LIMIT;default->HubClient.Failure.SERVER;},ex.failure());
      }
      stop();server=null;worker=null;
    }
  }
  @Test void dimensionRemoteUsesWorldRestEndpointAndWebLinks() throws Exception {
    for(String prefix:List.of("", "/proxy")) for(String dimension:List.of("minecraft.overworld", "minecraft.the_nether", "example%2Ens.custom%2Fdimension")) {
      var endpoint=HubClient.endpoint("https://host"+prefix+"/git/alice/world/"+dimension+".git");
      assertEquals("alice",endpoint.owner());assertEquals("world",endpoint.world());
      assertEquals(URI.create("https://host"+prefix+"/api/v1/worlds/alice/world"),endpoint.api());
      assertEquals(URI.create("https://host"+prefix+"/alice/world"),endpoint.web());
    }
    try(var original=client(e->{
      assertEquals("/proxy/api/v1/worlds/alice/world/pulls",e.getRequestURI().getPath());
      json(e,200,"{\"items\":["+pull(1)+"],\"offset\":0,\"hasMore\":false}");
    },Duration.ofSeconds(2));var dimension=new HubClient("http://127.0.0.1:"+server.getAddress().getPort()+"/proxy/git/alice/world/minecraft.overworld.git",new Credentials.Secret(Credentials.Mode.BEARER,"token",token),Duration.ofSeconds(2))) {
      assertEquals(1,dimension.find(1).number());
      assertEquals("http://127.0.0.1:"+server.getAddress().getPort()+"/proxy/alice/world/pulls/"+id,dimension.link(dimension.find(1)));
    }
    assertThrows(java.io.IOException.class,()->HubClient.endpoint("https://name:pat@host/git/alice/world/minecraft.overworld.git"));
    assertThrows(java.io.IOException.class,()->HubClient.endpoint("https://host/git/alice/world/minecraft.overworld.git?token=secret"));
  }
  @Test void entireBodyDeadlineAndResponseLimit() throws Exception {
    try(var h=client(e->{e.sendResponseHeaders(200,0);e.getResponseBody().write('{');e.getResponseBody().flush();try{Thread.sleep(3000);}catch(InterruptedException ignored){}e.close();},Duration.ofMillis(150))) {
      long start=System.nanoTime();assertEquals(HubClient.Failure.TIMEOUT,assertThrows(HubClient.Error.class,h::pulls).failure());assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2));
    }
    stop();server=null;worker=null;
    try(var h=client(e->{try{json(e,200,"x".repeat(4*1024*1024+1));}catch(java.io.IOException ignored){}},Duration.ofSeconds(3))) {
      assertEquals(HubClient.Failure.TOO_LARGE,assertThrows(HubClient.Error.class,h::pulls).failure());
    }
  }
  @Test void invalidPaginationAndUntrustedPinFailClosed() throws Exception {
    try(var h=client(e->json(e,200,"{\"items\":[],\"offset\":0,\"hasMore\":true}"),Duration.ofSeconds(2))) {assertEquals(HubClient.Failure.INVALID,assertThrows(HubClient.Error.class,h::pulls).failure());}
    assertThrows(IllegalArgumentException.class,()->new HubClient.Pin("minecraft:overworld",0,0,0,1,null,1));
    assertThrows(IllegalArgumentException.class,()->new HubClient.Pin("minecraft:overworld",30000001,0,0,null,null,null));
  }
}
