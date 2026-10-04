package org.worldgit.platform.remote;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebhookReceiverTest {
  @TempDir Path dir;
  byte[] secret="test-webhook-secret-at-least-32-characters".getBytes();
  int freePort() throws Exception {try(var s=new java.net.ServerSocket(0)){return s.getLocalPort();}}
  RemoteSettings.Webhook config(int rate) throws Exception {return new RemoteSettings.Webhook(true,"127.0.0.1",freePort(),"/hook","SECRET","hook.secret",1024,rate);}
  byte[] body(String event,String id) {return ("{\"id\":\""+UUID.randomUUID()+"\",\"event\":\""+event+"\",\"at\":"+System.currentTimeMillis()+",\"world\":{\"owner\":\"alice\",\"name\":\"castle\"},\"data\":{\"target\":\"main\",\"ref\":\"refs/heads/main\"}}").getBytes();}
  String signature(byte[] bytes) throws Exception {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(mac.doFinal(bytes));}
  int send(WebhookReceiver receiver,byte[] bytes,String id,String sig,String method) throws Exception {
    try(var client=HttpClient.newHttpClient()) {
      return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+receiver.port()+"/hook"))
          .timeout(java.time.Duration.ofSeconds(8)).header("X-WorldGit-Delivery",id).header("X-WorldGit-Signature-256",sig)
          .method(method,HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
  }
  @Test void signatureReplaySurvivesRestartAndNeverApplies() throws Exception {
    var count=new AtomicInteger();var cfg=config(60);String id=UUID.randomUUID().toString();byte[] bytes=body("pr.merged",id);
    try(var r=new WebhookReceiver(cfg,secret,dir.resolve("replays.yml"),e->{assertEquals("main",e.branch());count.incrementAndGet();return true;})) {
      assertTrue(WebhookReceiver.verify(secret,bytes,signature(bytes)));assertFalse(WebhookReceiver.verify(secret,bytes,"sha256="+"0".repeat(64)));
      assertEquals(401,send(r,bytes,id,"sha256="+"0".repeat(64),"POST"));
      assertEquals(202,send(r,bytes,id,signature(bytes),"POST"));assertEquals(202,send(r,bytes,id,signature(bytes),"POST"));assertEquals(1,count.get());
      assertEquals(202,send(r,bytes,UUID.randomUUID().toString(),signature(bytes),"POST"));assertEquals(1,count.get());
    }
    try(var r=new WebhookReceiver(cfg,secret,dir.resolve("replays.yml"),e->{count.incrementAndGet();return true;})) {assertEquals(202,send(r,bytes,id,signature(bytes),"POST"));assertEquals(1,count.get());}
  }
  @Test void methodOversizeRateAndFailedEnqueueRetry() throws Exception {
    byte[] bytes=body("push","ignored");String id=UUID.randomUUID().toString();var calls=new AtomicInteger();
    try(var r=new WebhookReceiver(config(60),secret,null,e->calls.incrementAndGet()>1)) {
      assertEquals(405,send(r,bytes,id,signature(bytes),"GET"));
      byte[] big=new byte[1025];assertEquals(413,send(r,big,id,signature(big),"POST"));
      assertEquals(503,send(r,bytes,id,signature(bytes),"POST"));assertEquals(202,send(r,bytes,id,signature(bytes),"POST"));assertEquals(2,calls.get());
    }
    try(var r=new WebhookReceiver(config(1),secret,null,e->true)) {assertEquals(202,send(r,bytes,id,signature(bytes),"POST"));assertEquals(429,send(r,bytes,UUID.randomUUID().toString(),signature(bytes),"POST"));}
  }
  @Test void staleFutureAndUnknownEventsCannotTriggerFetch() throws Exception {
    var count=new AtomicInteger();String id=UUID.randomUUID().toString();
    try(var r=new WebhookReceiver(config(60),secret,null,e->{count.incrementAndGet();return true;})) {
      byte[] unknown=body("release",id);assertEquals(204,send(r,unknown,id,signature(unknown),"POST"));
      String b=new String(body("push",id)).replaceFirst("\\\"at\\\":\\d+","\"at\":1");byte[] old=b.getBytes();
      assertEquals(401,send(r,old,id,signature(old),"POST"));assertEquals(0,count.get());
    }
  }
  @Test void headerBudgetTransferEncodingAndSlowHeadersAreBounded() throws Exception {
    try(var r=new WebhookReceiver(config(60),secret,null,e->true)) {
      for(String raw:new String[]{
          "POST /hook HTTP/1.1\r\nX-Flood: "+"x".repeat(16384)+"\r\n\r\n",
          "POST /hook HTTP/1.1\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n",
          "POST /hook HTTP/1.1\r\nContent-Length: 0\r\nContent-Length: 9\r\n\r\n"}) {
        try(var socket=new java.net.Socket("127.0.0.1",r.port())) {
          socket.setSoTimeout(7000);socket.getOutputStream().write(raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          assertTrue(new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())).readLine().startsWith("HTTP/1.1 400"));
        }
      }
      try(var socket=new java.net.Socket("127.0.0.1",r.port())) {
        socket.setSoTimeout(7000);socket.getOutputStream().write("POST /hook HTTP/1.1\r\nX: ".getBytes());
        long start=System.nanoTime();
        // 5 秒整條連線 deadline，包含尚未完成的 headers。
        int first=socket.getInputStream().read();assertTrue(first==-1 || first=='H');
        assertTrue(System.nanoTime()-start<java.util.concurrent.TimeUnit.SECONDS.toNanos(7));
      }
    }
  }

}
