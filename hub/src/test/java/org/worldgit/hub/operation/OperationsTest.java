package org.worldgit.hub.operation;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.worldgit.core.operation.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.hub.config.HubProperties;
import org.worldgit.hub.account.Models.*;
class OperationsTest {
  @TempDir Path dir;
  Operations service()throws Exception {
    var reports=mock(Reports.class);when(reports.secrets(any())).thenReturn(List.of());
    when(reports.report(anyString(),any(),anyString(),any(),anyString(),anyCollection())).thenAnswer(a->OperationResult.ErrorReport.create(a.getArgument(0),a.getArgument(1),a.getArgument(2),a.getArgument(3),"test","Hub test",a.getArgument(4),a.getArgument(5)));
    return new Operations(reports,new HubProperties(dir,null,null,null,null,null,null,null,null,null),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
  }
  final User user=new User("u","o","owner",false);
  final WorldRow world=new WorldRow("w","o","owner","world","world","",false,0);
  OperationResult success(UUID id){return new OperationResult(id,"test",OperationResult.Status.SUCCESS,DimensionId.OVERWORLD,Map.of(),0,List.of(),null);}
  @Test void eventAndRecordCapsAndExpirationDeleteArtifact()throws Exception {
    var ops=service();try {
      var request=new MockHttpServletRequest();request.setAttribute("worldgit.actor",user);request.setAttribute("worldgit.world",world);
      for(int i=0;i<150;i++){var e=ops.transientEntry(UUID.randomUUID(),"test");for(int n=0;n<90;n++)e.progress(new OperationProgress.Event(e.id,"test",null,"count",n,90L,OperationProgress.Unit.OBJECT,null,false,null));assertEquals(64,((List<?>)e.snapshot().get("events")).size());e.finish(success(e.id),null);ops.retain(request,e);}
      assertEquals(128,ops.size());
      var e=ops.transientEntry(UUID.randomUUID(),"zip");e.finish(success(e.id),null);ops.retain(request,e);Path file=ops.artifact(e);java.nio.file.Files.writeString(file,"zip");
      var field=Operations.Entry.class.getDeclaredField("finished");field.setAccessible(true);field.setLong(e,System.currentTimeMillis()-Operations.TTL_MILLIS-1);
      ops.cleanup();assertFalse(java.nio.file.Files.exists(file));assertThrows(org.worldgit.hub.web.ApiError.NotFound.class,()->ops.find(e.id.toString()));
    }finally{ops.close();}
  }
  @Test void perActorQueueCapCancellationAndAsyncBudget()throws Exception {
    var ops=service();var gate=new CountDownLatch(1);try {
      var all=new ArrayList<Operations.Entry>();for(int i=0;i<8;i++)all.add(ops.submit(world,user,"preview",DimensionId.OVERWORLD,true,false,List.of(),e->{gate.await();OperationProgress.check();return Map.of();}));
      assertThrows(org.worldgit.hub.web.ApiError.Unavailable.class,()->ops.submit(world,user,"preview",DimensionId.OVERWORLD,true,false,List.of(),e->Map.of()));
      var partial=ops.partial(world,user,"push-processing",DimensionId.OVERWORLD,"refs 已接受，後處理未排程");assertEquals(OperationResult.Status.PARTIAL,partial.result.status());assertNotNull(partial.result.error());
      var cancelled=all.getLast();cancelled.cancelRequested=true;gate.countDown();
      await().atMost(Duration.ofSeconds(5)).until(()->all.stream().allMatch(e->e.result!=null));assertEquals(OperationResult.Status.CANCELLED,cancelled.result.status());
      var budget=ops.submit(world,user,"preview",DimensionId.OVERWORLD,true,false,List.of(),e->{org.worldgit.core.normalize.DecodeBudget.read(129L<<20);return Map.of();});
      await().atMost(Duration.ofSeconds(5)).until(()->budget.result!=null);assertEquals("budget",budget.result.error().code());
    }finally{gate.countDown();ops.close();}
  }
}
