package org.worldgit.hub.operation;

import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.io.*;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.worldgit.core.operation.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.web.*;

/** 單 Hub 有界記憶體作業簿；TTL 不依賴客戶端輪詢，重啟清除準備檔。 */
@Service
public class Operations {
  public static final int MAX_RECORDS=128,MAX_EVENTS=64,MAX_PER_USER=8;
  public static final long TTL_MILLIS=15*60_000;
  private final LinkedHashMap<UUID,Entry> entries=new LinkedHashMap<>();
  private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->new Thread(r,"hub-operation"),new ThreadPoolExecutor.AbortPolicy());
  private final Reports reports;private final Path artifacts;private final org.worldgit.hub.config.HubProperties.Limits limits;private final com.fasterxml.jackson.databind.ObjectMapper json;
  public Operations(Reports reports,org.worldgit.hub.config.HubProperties props,com.fasterxml.jackson.databind.ObjectMapper json)throws IOException {
    this.reports=reports;this.limits=props.limits();this.json=json;artifacts=props.dataDir().resolve("operation-downloads");Files.createDirectories(artifacts);
    try(var paths=Files.list(artifacts)){for(var p:paths.toList())Files.deleteIfExists(p);}
  }
  public Reports reports(){return reports;}
  public static final class Entry {
    public final UUID id;public final String operation;public volatile WorldRow world;public volatile String actor;public volatile DimensionId dimension;
    public final long created=System.currentTimeMillis();public volatile OperationProgress context;public volatile OperationResult result;public volatile Object data;public volatile Path artifact;public volatile boolean cancellable;public volatile boolean cancelRequested;
    private final ArrayDeque<OperationProgress.Event> events=new ArrayDeque<>();private long sequence;private volatile long finished;
    Entry(UUID id,String operation){this.id=id;this.operation=operation;}
    public synchronized void progress(OperationProgress.Event event){if(result!=null)return;if(event.dimension()!=null)dimension=event.dimension();while(events.size()>=MAX_EVENTS)events.removeFirst();events.addLast(new OperationProgress.Event(id,operation,event.dimension(),event.phase(),event.completed(),event.total(),event.unit(),event.remainingMillis(),cancellable,event.ratePerSecond()));sequence++;}
    public synchronized Map<String,Object> snapshot(){var m=new LinkedHashMap<String,Object>();m.put("id",id);m.put("operation",operation);m.put("dimension",dimension);m.put("sequence",sequence);m.put("events",List.copyOf(events));m.put("result",result);m.put("data",data);m.put("cancellable",cancellable && result==null);m.put("download",artifact!=null);return m;}
    public synchronized void finish(OperationResult result,Object data){this.result=result;this.data=data;finished=System.currentTimeMillis();cancellable=false;sequence++;}
    boolean expired(long now){return result!=null && now-finished> (artifact==null?TTL_MILLIS:5*60_000);}
  }
  public Entry transientEntry(UUID id,String operation){return new Entry(id,operation);}
  public synchronized void retain(HttpServletRequest req,Entry entry){
    if(!(req.getAttribute("worldgit.world") instanceof WorldRow w) || !(req.getAttribute("worldgit.actor") instanceof User u))return;
    entry.world=w;entry.actor=u.id();entry.dimension=Reports.dimension(req);
    // 非同步啟動 request 自身不重複占作業簿。
    if(req.getRequestURI().endsWith("/operations"))return;
    try{insert(entry);}catch(ApiError.Unavailable ignored){} // 同步完成結果仍在 response。
  }
  private synchronized void insert(Entry entry){
    cleanup();
    long active=entries.values().stream().filter(e->Objects.equals(e.actor,entry.actor) && e.result==null).count();
    if(entry.result==null && active>=MAX_PER_USER)throw new ApiError.Unavailable("每位使用者最多 8 個進行中操作");
    while(entries.size()>=MAX_RECORDS){var old=entries.values().stream().filter(e->e.result!=null && e.artifact==null).findFirst().orElseThrow(()->new ApiError.Unavailable("操作紀錄已滿"));entries.remove(old.id);}
    entries.put(entry.id,entry);
  }
  @FunctionalInterface public interface Work {Object run(Entry entry)throws Exception;}
  public Entry submit(WorldRow world,User user,String operation,DimensionId dimension,boolean cancellable,boolean zip,List<String> secrets,Work work){
    var entry=new Entry(UUID.randomUUID(),operation);entry.world=world;entry.actor=user.id();entry.dimension=dimension;entry.cancellable=cancellable;
    synchronized(this){if(zip && entries.values().stream().filter(e->e.artifact!=null || e.operation.endsWith("zip") && e.result==null).count()>=2)throw new ApiError.Unavailable("最多 2 個 ZIP 準備／保留檔");insert(entry);}
    try {workers.execute(()-> {
      Object data=null;OperationResult terminal;
      var context=new OperationProgress(entry.id,operation,entry::progress);entry.context=context;if(entry.cancelRequested)context.cancel();
      try(context;var budget=new org.worldgit.core.normalize.DecodeBudget(limits.decodedBytes(),limits.readBytes(),limits.nodes(),limits.objects(),limits.work()).open()){OperationProgress.report(dimension,"queued",0L,null,OperationProgress.Unit.OBJECT);data=work.run(entry);OperationProgress.report(dimension,"complete",1L,1L,OperationProgress.Unit.OBJECT);org.worldgit.hub.web.BoundedJson.encode(json,data);}
      catch(Exception e){
        String code=e instanceof ApiError.Conflict?"conflict":e instanceof SecurityException?"forbidden":e instanceof org.worldgit.core.normalize.DecodeBudget.Exceeded?"budget":e instanceof InterruptedIOException?"cancelled":"operation-failed";
        var report=reports.report(code,entry.id,operation,entry.dimension,Objects.toString(e.getMessage(),"操作失敗"),secrets);
        terminal=context.result(code.equals("cancelled")?OperationResult.Status.CANCELLED:OperationResult.Status.FAILED,entry.dimension,Map.of("message",report.message()),List.of(),report);entry.finish(terminal,null);deleteArtifact(entry);return;
      }
      terminal=context.result(OperationResult.Status.SUCCESS,dimension,Map.of("message",switch(operation){case "merge"->"PR 合併完成";case "preview"->"合併預覽計算完成";case "world-zip","release-zip"->"世界 ZIP 已準備完成";case "push-processing"->"索引與 Webhook 排程完成";default->"操作已完成";}),List.of(),null);entry.finish(terminal,data);
    });}catch(RejectedExecutionException e){synchronized(this){entries.remove(entry.id);}throw new ApiError.Unavailable("操作佇列已滿");}
    return entry;
  }
  public Entry partial(WorldRow world,User user,String operation,DimensionId dimension,String message){
    var entry=new Entry(UUID.randomUUID(),operation);entry.world=world;entry.actor=user.id();entry.dimension=dimension;
    var report=reports.report("partial",entry.id,operation,dimension,message,reports.secrets(null));
    entry.finish(new OperationResult(entry.id,operation,OperationResult.Status.PARTIAL,dimension,Map.of("message",report.message()),0,List.of("檢查 refs 並重新嘗試後處理"),report),null);
    synchronized(this){insert(entry);}return entry;
  }
  public synchronized List<Map<String,Object>> list(String world,String actor,int limit){
    if(limit<1 || limit>100)throw new IllegalArgumentException("operation limit 必須介於 1–100");cleanup();
    var rows=new ArrayList<>(entries.values());Collections.reverse(rows);
    return rows.stream().filter(e->e.world!=null && e.world.id().equals(world) && e.actor.equals(actor)).limit(limit).map(Entry::snapshot).toList();
  }
  public synchronized Entry find(String id){try{var e=entries.get(UUID.fromString(id));if(e==null || e.expired(System.currentTimeMillis()))throw new ApiError.NotFound("找不到操作");return e;}catch(IllegalArgumentException e){throw new ApiError.NotFound("找不到操作");}}
  public Path artifact(Entry entry)throws IOException {var path=artifacts.resolve(entry.id+".zip");entry.artifact=path;return path;}
  public synchronized void consumed(Entry entry){deleteArtifact(entry);}
  public synchronized long activeFor(String world){return entries.values().stream().filter(e->e.world!=null && e.world.id().equals(world) && e.result==null).count();}
  public synchronized int size(){return entries.size();}
  @Scheduled(fixedDelay=60000) public synchronized void cleanup(){long now=System.currentTimeMillis();var it=entries.values().iterator();while(it.hasNext()){var e=it.next();if(e.expired(now)){deleteArtifact(e);it.remove();}}}
  private void deleteArtifact(Entry e){var p=e.artifact;e.artifact=null;if(p!=null)try{Files.deleteIfExists(p);}catch(IOException ignored){}}
  @PreDestroy public void close(){workers.shutdownNow();try{workers.awaitTermination(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}synchronized(this){entries.values().forEach(this::deleteArtifact);entries.clear();}}
}
