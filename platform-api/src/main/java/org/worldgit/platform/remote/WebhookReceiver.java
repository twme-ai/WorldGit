package org.worldgit.platform.remote;

import java.nio.charset.StandardCharsets;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.worldgit.core.service.OperationState;

/** 有界最小 HTTP receiver；只 enqueue 通知，沒有世界／apply 的依賴。 */
public final class WebhookReceiver implements AutoCloseable {
  public record Event(String delivery,String event,String owner,String world,String branch) {}
  private final ServerSocket server;
  private final Thread acceptor;
  private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor workers;
  private final ScheduledExecutorService deadlines;
  private final byte[] secret;
  private final RemoteSettings.Webhook settings;
  private final Predicate<Event> enqueue;
  private final Path replayFile;
  private final Map<String,Long> deliveries=new LinkedHashMap<>();
  private long window=System.nanoTime(); private int requests;
  private volatile boolean closed;
  public WebhookReceiver(RemoteSettings.Webhook settings,byte[] secret,Path replayFile,Predicate<Event> enqueue) throws IOException {
    this.settings=settings; this.secret=secret.clone(); this.replayFile=replayFile; this.enqueue=enqueue;
    if(secret.length<32) throw new IOException("webhook secret 至少 32 bytes");
    load();
    server=new ServerSocket(); server.setReuseAddress(true);
    server.bind(new InetSocketAddress(InetAddress.getByName(settings.bind()),settings.port()),16);
    workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"WorldGit-Webhook");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    deadlines=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"WorldGit-WebhookDeadline");t.setDaemon(true);return t;});
    acceptor=new Thread(this::accept,"WorldGit-WebhookAccept"); acceptor.setDaemon(true); acceptor.start();
  }
  public int port() { return server.getLocalPort(); }
  public static boolean verify(byte[] secret,byte[] body,String header) {
    if(header==null || !header.matches("sha256=[0-9a-fA-F]{64}")) return false;
    try {
      var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
      return MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(header.substring(7)));
    } catch(GeneralSecurityException | IllegalArgumentException e) { return false; }
  }
  private synchronized boolean rate() {
    long now=System.nanoTime(); if(now-window>=TimeUnit.MINUTES.toNanos(1)) {window=now;requests=0;}
    return ++requests<=settings.requestsPerMinute();
  }
  private void accept() {
    while(!closed) {
      try {
        Socket socket=server.accept();socket.setSoTimeout(5000);sockets.add(socket);
        ScheduledFuture<?> deadline;
        try {deadline=deadlines.schedule(()->closeSocket(socket),5,TimeUnit.SECONDS);}
        catch(RejectedExecutionException e) {closeSocket(socket);sockets.remove(socket);return;}
        try {workers.execute(()->{try {handle(socket);}finally {deadline.cancel(false);closeSocket(socket);sockets.remove(socket);}});}
        catch(RejectedExecutionException e) {deadline.cancel(false);closeSocket(socket);sockets.remove(socket);}
      } catch(IOException e) {if(closed)return;}
    }
  }
  private static void closeSocket(Socket socket) {try {socket.close();}catch(IOException ignored){}}
  private record Request(String method,String path,Map<String,String> headers,InputStream body) {}
  private static Request read(Socket socket) throws IOException {
    // 整個 header 16 KiB，CRLF only，不支援折行/duplicate/Transfer-Encoding/keep-alive。
    InputStream in=socket.getInputStream();var bytes=new ByteArrayOutputStream();int matched=0;
    byte[] end={13,10,13,10};
    while(matched<4) {
      int b=in.read();if(b<0)throw new EOFException();if(bytes.size()>=16384)throw new IOException("headers too large");bytes.write(b);
      matched=b==end[matched]?matched+1:b==13?1:0;
    }
    String[] lines=bytes.toString(StandardCharsets.ISO_8859_1).split("\r\n");String[] first=lines[0].split(" ",-1);
    if(first.length!=3 || !first[2].equals("HTTP/1.1"))throw new IOException("invalid request");
    var headers=new HashMap<String,String>();
    for(int i=1;i<lines.length;i++) {
      int colon=lines[i].indexOf(':');if(colon<1)throw new IOException("invalid header");
      String name=lines[i].substring(0,colon).toLowerCase(Locale.ROOT),value=lines[i].substring(colon+1).strip();
      if(!name.matches("[a-z0-9-]+") || headers.putIfAbsent(name,value)!=null)throw new IOException("invalid header");
    }
    if(headers.containsKey("transfer-encoding"))throw new IOException("transfer encoding unsupported");
    return new Request(first[0],first[1],headers,in);
  }
  private void handle(Socket socket) {

    int code=400;
    try {
      if(closed) { code=503; return; }
      Request request=read(socket);
      if(!request.path().equals(settings.path())) {code=404;return;}
      if(!request.method().equals("POST")) {code=405;return;}
      if(!rate()) {code=429;return;}
      var headers=request.headers();
      String sig=single(headers,"X-WorldGit-Signature-256"),delivery=single(headers,"X-WorldGit-Delivery");
      if(sig==null || delivery==null || !delivery.matches("[0-9a-fA-F-]{36}")) {code=401;return;}
      try { if(!UUID.fromString(delivery).toString().equals(delivery)) {code=401;return;} } catch(IllegalArgumentException e) {code=401;return;}
      String length=single(headers,"Content-Length");
      if(length==null || !length.matches("[0-9]{1,10}"))return;
      long count=Long.parseLong(length);
      if(count>settings.maxBodyBytes()) {code=413;return;}
      byte[] body=request.body().readNBytes((int)count);
      if(body.length!=count)return;
      if(body.length>settings.maxBodyBytes()) {code=413;return;}
      if(!verify(secret,body,sig)) {code=401;return;}
      var json=HubClient.JSON.readTree(body);
      if(json==null || !json.isObject()) return;
      String eventId="event/"+UUID.fromString(HubClient.required(json,"id"));
      Instant at=json.path("at").isIntegralNumber() ? Instant.ofEpochMilli(json.path("at").longValue()) : Instant.parse(HubClient.required(json,"at"));
      long now=System.currentTimeMillis();
      if(at.toEpochMilli()<now-TimeUnit.HOURS.toMillis(24) || at.toEpochMilli()>now+TimeUnit.MINUTES.toMillis(5)) {code=401;return;}
      String event=HubClient.required(json,"event"),owner=HubClient.required(json.path("world"),"owner"),world=HubClient.required(json.path("world"),"name");
      String branch;
      if(event.equals("pr.merged")) branch=HubClient.required(json.path("data"),"target");
      else if(event.equals("push")) { String ref=HubClient.required(json.path("data"),"ref"); branch=ref.startsWith("refs/heads/")?ref.substring(11):""; }
      else {code=204;return;}
      synchronized(this) {
        prune(now);
        if(deliveries.containsKey(delivery) || deliveries.containsKey(eventId)) {code=202;return;}
        if(deliveries.size()>=4094) {code=503;return;}
        // 持久化先於接受。callback 只 enqueue；fail 時移除讓 Hub 重試。
        deliveries.put(delivery,now); deliveries.put(eventId,now); save();
        if(!enqueue.test(new Event(delivery,event,owner,world,branch))) {deliveries.remove(delivery);deliveries.remove(eventId);save();code=503;return;}
      }
      code=202;
    } catch(RuntimeException | IOException ignored) { code=400; }
    finally {
      try {socket.getOutputStream().write(("HTTP/1.1 "+code+" Response\r\nContent-Length: 0\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));}
      catch(IOException ignored) {}
    }
  }
  private static String single(Map<String,String> headers,String key) {return headers.get(key.toLowerCase(Locale.ROOT));}
  private void prune(long now) {deliveries.values().removeIf(t->t<now-TimeUnit.HOURS.toMillis(24));}
  private void load() throws IOException {
    if(replayFile==null) return;
    if(Files.isSymbolicLink(replayFile) || Files.exists(replayFile) && Files.size(replayFile)>262144) throw new IOException("webhook replay 檔無效");
    var m=OperationState.read(replayFile);
    if(m.size()>4096) throw new IOException("webhook replay 超過上限");
    for(var e:m.entrySet()) {
      if(!e.getKey().matches("(event/)?[0-9a-f-]{36}") || !(e.getValue() instanceof Number n)) throw new IOException("webhook replay 格式無效");
      deliveries.put(e.getKey(),n.longValue());
    }
    prune(System.currentTimeMillis());
  }
  private void save() throws IOException {if(replayFile!=null)OperationState.write(replayFile,new LinkedHashMap<String,Object>(deliveries));}
  public void close() {
    closed=true;try {server.close();}catch(IOException ignored){}sockets.forEach(WebhookReceiver::closeSocket);
    workers.shutdownNow();deadlines.shutdownNow();
    try {acceptor.join(1000);workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    Arrays.fill(secret,(byte)0);
  }
}
