package org.worldgit.platform.remote;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.remote.Credentials;

/** 有界、禁止 redirect 的 Hub REST client；caller 必須放在背景 executor。沒有 merge/approve API。 */
public final class HubClient implements AutoCloseable {
  static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(
      StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(32768).maxNumberLength(32).build())
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  public enum Failure { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMIT, TIMEOUT, UNREACHABLE, INVALID, TOO_LARGE, SERVER }
  public static final class Error extends IOException {
    private final Failure failure;
    public Error(Failure failure) { super("Hub: "+failure); this.failure=failure; }
    public Failure failure() { return failure; }
  }
  public record Endpoint(URI api, URI web, String owner, String world) {}
  public record Pull(String id,int number,String title,String source,String target,String status) {}
  public record Detail(Pull pr,String mergeability,int approvals,int requiredReviews) {}
  public record Pin(String dimension,int x,int y,int z,Integer maxX,Integer maxY,Integer maxZ) {
    public Pin {
      new DimensionId(dimension);
      if(Math.abs((long)x)>30_000_000 || Math.abs((long)z)>30_000_000 || y < -2048 || y > 2048
          || (maxX==null)!=(maxY==null) || (maxX==null)!=(maxZ==null)
          || maxX!=null && (maxX<x || maxY<y || maxZ<z || maxX>30_000_000 || maxZ>30_000_000 || maxY>2048))
        throw new IllegalArgumentException("Hub 釘選座標無效");
    }
    public boolean range() { return maxX!=null; }
  }
  public record Comment(String id,String username,String body,Pin pin) {}
  private final Endpoint endpoint;
  private final Credentials.Secret secret;
  private final Duration timeout;
  private final HttpClient http;
  public HubClient(String url,Credentials.Secret secret,Duration timeout) throws IOException {
    this.endpoint=endpoint(url); this.secret=secret; this.timeout=timeout;
    http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
  }
  public static Endpoint endpoint(String url) throws IOException {
    try {
      URI uri=URI.create(url.replaceAll("/+$",""));
      if(!Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
          || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
      String path=uri.getRawPath(); String[] bits=path.split("/");
      // Phase 5 remote 設定儲存各維度的 smart HTTP URL；REST／網頁仍屬世界。
      if(bits.length>=5 && bits[bits.length-4].equals("git") && bits[bits.length-1].endsWith(".git")) {
        String dimension=bits[bits.length-1];
        new DimensionId(URLDecoder.decode(dimension.substring(0,dimension.length()-4).replaceFirst("\\.",":"),StandardCharsets.UTF_8));
        String prefix=String.join("/",Arrays.copyOf(bits,bits.length-4));
        path=prefix+"/"+bits[bits.length-3]+"/"+bits[bits.length-2];
        uri=URI.create(uri.getScheme()+"://"+uri.getRawAuthority()+path);
        bits=path.split("/");
      }
      if(bits.length<3) throw new IllegalArgumentException();
      String owner=bits[bits.length-2],world=bits[bits.length-1];
      if(!owner.matches("[A-Za-z0-9_-]{1,64}") || !world.matches("[A-Za-z0-9_.-]{1,128}") || world.equals(".") || world.equals("..")) throw new IllegalArgumentException();
      String base=uri.getScheme()+"://"+uri.getRawAuthority()+path.substring(0,path.lastIndexOf('/'+owner+'/'));
      return new Endpoint(URI.create(base+"/api/v1/worlds/"+owner+"/"+world),uri,owner,world);
    } catch(RuntimeException ex) { throw new IOException("Hub URL 無效（內容已遮罩）"); }
  }
  public String link(Pull pr) { return endpoint.web()+"/pulls/"+pr.id(); }
  public Endpoint endpoint() { return endpoint; }
  public List<Pull> pulls(DimensionId dimension) throws IOException { var out=new ArrayList<Pull>();for(var n:pages("/pulls?dimension="+enc(dimension.value()),1000))out.add(pull(n));return List.copyOf(out); }
  public Pull find(int number,DimensionId dimension) throws IOException {return pulls(dimension).stream().filter(p->p.number()==number).findFirst().orElseThrow(()->new Error(Failure.NOT_FOUND));}
  public List<Pull> pulls() throws IOException { var out=new ArrayList<Pull>(); for(var n:pages("/pulls",1000)) out.add(pull(n)); return List.copyOf(out); }
  public Pull find(int number) throws IOException {
    if(number<1) throw new Error(Failure.INVALID);
    return pulls().stream().filter(p->p.number()==number).findFirst().orElseThrow(()->new Error(Failure.NOT_FOUND));
  }
  public Detail view(Pull pr) throws IOException {
    var n=request("GET","/pulls/"+id(pr.id()),null);
    return new Detail(pull(n.path("pr")),required(n,"mergeability"),integer(n,"approvals"),integer(n,"requiredReviews"));
  }
  public Pull create(String title,String source,String target,DimensionId dimension) throws IOException {
    if(title.isBlank() || title.length()>200)throw new Error(Failure.INVALID);
    return pull(request("POST","/pulls",Map.of("title",title,"source",source,"target",target,"description","","dimension",dimension.value())));
  }
  public Pull create(String title,String source,String target) throws IOException {
    if(title.isBlank() || title.length()>200) throw new Error(Failure.INVALID);
    return pull(request("POST","/pulls",Map.of("title",title,"source",source,"target",target,"description","")));
  }
  public List<Comment> comments(Pull pr,String dimension) throws IOException {
    if(dimension!=null) new DimensionId(dimension);
    String query="/comments?pinned=true"+(pr==null?"":"&pr="+id(pr.id()))+(dimension==null?"":"&dimension="+enc(dimension));
    var out=new ArrayList<Comment>();
    try {
      for(var n:pages(query,512)) {
        if(n.path("deleted").asBoolean(false)) continue;
        var p=n.path("pin"); if(p.isNull() || p.isMissingNode()) continue;
        var pin=new Pin(required(p,"dimension"),integer(p,"x"),integer(p,"y"),integer(p,"z"),optionalInt(p,"maxX"),optionalInt(p,"maxY"),optionalInt(p,"maxZ"));
        out.add(new Comment(id(required(n,"id")),required(n,"username"),required(n,"body"),pin));
      }
    } catch(IllegalArgumentException ex) { throw new Error(Failure.INVALID); }
    return List.copyOf(out);
  }
  public void comment(Pull pr,String text,Pin pin) throws IOException {
    if(text.isBlank() || text.length()>8000) throw new Error(Failure.INVALID);
    var b=new LinkedHashMap<String,Object>(); b.put("body",text); if(pin!=null) b.put("pin",pin);
    request("POST","/pulls/"+id(pr.id())+"/comments",b);
  }
  private List<JsonNode> pages(String path,int max) throws IOException {
    var out=new ArrayList<JsonNode>(); int offset=0;
    do {
      var n=request("GET",path+(path.contains("?")?"&":"?")+"offset="+offset+"&limit=100",null);
      var items=n.path("items");
      if(!items.isArray() || items.size()>100 || integer(n,"offset")!=offset || !n.path("hasMore").isBoolean()) throw new Error(Failure.INVALID);
      for(var v:items) { if(out.size()>=max) throw new Error(Failure.TOO_LARGE); out.add(v); }
      if(!n.path("hasMore").booleanValue()) return out;
      if(items.isEmpty() || offset+items.size()>10000) throw new Error(Failure.INVALID);
      offset+=items.size();
    } while(true);
  }
  private static Pull pull(JsonNode n) throws IOException {
    return new Pull(id(required(n,"id")),integer(n,"number"),required(n,"title"),required(n,"source"),required(n,"target"),required(n,"status"));
  }
  static String required(JsonNode n,String key) throws IOException { var v=n.path(key); if(!v.isTextual()) throw new Error(Failure.INVALID); return v.textValue(); }
  static int integer(JsonNode n,String key) throws IOException { var v=n.path(key); if(!v.isIntegralNumber() || !v.canConvertToInt()) throw new Error(Failure.INVALID); return v.intValue(); }
  private static Integer optionalInt(JsonNode n,String key) throws IOException { return n.path(key).isNull() || n.path(key).isMissingNode()?null:integer(n,key); }
  private static String id(String id) throws IOException { try { return UUID.fromString(id).toString(); } catch(IllegalArgumentException ex) { throw new Error(Failure.INVALID); } }
  private static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
  private JsonNode request(String method,String path,Object body) throws IOException {
    CompletableFuture<HttpResponse<byte[]>> future=null;
    try {
      var b=HttpRequest.newBuilder(URI.create(endpoint.api()+path)).timeout(timeout).header("Accept","application/json").header("Authorization",secret.authorization());
      if(body==null) b.GET(); else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
      future=http.sendAsync(b.build(),info->new BoundedBody(4*1024*1024));
      var response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
      if(response.statusCode()<200 || response.statusCode()>=300) throw new Error(switch(response.statusCode()) {
        case 401->Failure.UNAUTHORIZED; case 403->Failure.FORBIDDEN; case 404->Failure.NOT_FOUND; case 409->Failure.CONFLICT; case 429->Failure.RATE_LIMIT; case 413->Failure.TOO_LARGE; default->Failure.SERVER;
      });
      JsonNode value=JSON.readTree(response.body()); if(value==null || !value.isObject()) throw new Error(Failure.INVALID); return value;
    } catch(TimeoutException e) { if(future!=null)future.cancel(true); throw new Error(Failure.TIMEOUT); }
      catch(InterruptedException e) { if(future!=null)future.cancel(true); Thread.currentThread().interrupt(); throw new Error(Failure.TIMEOUT); }
      catch(ExecutionException e) { Throwable cause=e.getCause(); if(cause instanceof Error err)throw err; throw new Error(cause instanceof HttpTimeoutException?Failure.TIMEOUT:Failure.UNREACHABLE); }
      catch(JsonProcessingException | IllegalArgumentException e) { throw new Error(Failure.INVALID); }
  }
  /** 不使用無界 byte[] 或只等 headers 的 InputStream；完整 body 含 deadline。 */
  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> result=new CompletableFuture<>(); final ByteArrayOutputStream out=new ByteArrayOutputStream(); final int limit; Flow.Subscription subscription;
    BoundedBody(int limit) { this.limit=limit; }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription s) { subscription=s; s.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
      for(var b:buffers) { if(b.remaining()>limit-out.size()) { subscription.cancel(); result.completeExceptionally(new Error(Failure.TOO_LARGE)); return; }
        byte[] bytes=new byte[b.remaining()]; b.get(bytes); out.writeBytes(bytes); }
      subscription.request(1);
    }
    public void onError(Throwable t) { result.completeExceptionally(t); }
    public void onComplete() { result.complete(out.toByteArray()); }
  }
  public void close() { http.shutdownNow(); }
}
