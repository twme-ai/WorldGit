package org.worldgit.hub;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.worldgit.core.model.*;
import org.worldgit.core.store.*;
import org.worldgit.hub.account.*;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.collaboration.*;
import org.worldgit.hub.config.CollaborationProperties;
import org.worldgit.hub.history.MergePreviewService;
import org.worldgit.hub.storage.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CollaborationTest {
  static Path data,work;static final String BOOT="phase4-test-bootstrap";static final ObjectMapper JSON=new ObjectMapper();
  @DynamicPropertySource static void props(DynamicPropertyRegistry r)throws Exception {
    data=Files.createTempDirectory("p4-hub-data");work=Files.createTempDirectory("p4-hub-fixture");HubTestDatabase.configure(r);
    r.add("server.address",()->"127.0.0.1");r.add("server.port",()->"8096");r.add("worldgit.hub.data-dir",()->data.toString());r.add("worldgit.hub.bootstrap.admin-token",()->BOOT);r.add("worldgit.hub.bootstrap.admin-password",()->"acceptance-password");
    r.add("worldgit.hub.collaboration.webhooks.allowed-hosts",()->"127.0.0.1");r.add("worldgit.hub.collaboration.webhooks.retry-seconds",()->"1");r.add("worldgit.hub.collaboration.webhooks.poll-millis",()->"3600000");
  }
  @LocalServerPort int port;@Autowired AccountService accounts;@Autowired RepoStorage storage;@Autowired JdbcClient db;@Autowired PullRequests prs;@Autowired BranchPolicy policy;@Autowired EventService events;@Autowired Webhooks hooks;@Autowired WorldGroups groups;@Autowired Releases releases;@Autowired OwnerQuota quota;@Autowired OrganizationService orgs;
  User owner,writer,reader,admin;String token,writeToken,readToken,adminToken;final HttpClient http=HttpClient.newHttpClient();
  @BeforeAll void users(){owner=accounts.createUser("p4-owner","test-password",false);writer=accounts.createUser("p4-writer","test-password",false);reader=accounts.createUser("p4-reader","test-password",false);admin=accounts.createUser("p4-admin","test-password",false);token=accounts.createToken(owner,"test",false);writeToken=accounts.createToken(writer,"test",false);readToken=accounts.createToken(reader,"test",false);adminToken=accounts.createToken(admin,"test",false);}
  @AfterAll void cleanup()throws Exception {for(Path root:List.of(data,work))try(var paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());}}
  String url(String path){return "http://127.0.0.1:"+port+path;}
  String base(WorldRow w){return "/api/v1/worlds/"+w.ownerSlug()+"/"+w.slug();}
  HttpResponse<byte[]> request(String method,String path,String pat,Object body)throws Exception {
    var b=HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json");if(pat!=null)b.header("Authorization","Bearer "+pat);
    return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
  }
  JsonNode ok(String method,String path,String pat,Object body)throws Exception {var r=request(method,path,pat,body);assertEquals(200,r.statusCode(),new String(r.body()));return JSON.readTree(r.body());}
  record Fixture(WorldRow world,Map<DimensionId,Path> paths) {}
  Fixture fixture()throws Exception {
    String slug="test-"+UUID.randomUUID().toString().substring(0,8);var w=accounts.createWorld(owner,owner.username(),slug,slug,"",false).orElseThrow();accounts.grant(w,owner,writer.username(),Role.WRITER);accounts.grant(w,owner,reader.username(),Role.READER);accounts.grant(w,owner,admin.username(),Role.ADMIN);
    var paths=org.worldgit.hub.tools.MergeFixture.create(work.resolve(slug));
    // sparse Phase 2 的 fixture 補全 group 身分，與正式 core init 的 group pins 相同。
    for(var d:paths.keySet())try(var s=new JGitStore(paths.get(d),false)){
      String initial=s.readCommit(s.resolve("ours")).parents().getFirst();
      for(var snap:List.of(org.worldgit.hub.tools.BranchFixture.INITIAL,org.worldgit.hub.tools.BranchFixture.MAIN,org.worldgit.hub.tools.BranchFixture.FEATURE)){
        String id=snap.equals(org.worldgit.hub.tools.BranchFixture.MAIN)?s.resolve("main"):snap.equals(org.worldgit.hub.tools.BranchFixture.FEATURE)?s.resolve("feature"):initial;
        s.updateRef("refs/worldgit/groups/"+snap,null,id);
      }
    }
    for(var d:paths.keySet()) push(paths.get(d),w,d,token,"refs/heads/*:refs/heads/*","refs/worldgit/groups/*:refs/worldgit/groups/*");return new Fixture(w,paths);
  }
  List<RemoteRefUpdate> push(Path path,WorldRow w,DimensionId d,String pat,String... refs)throws Exception {
    for(int attempt=0;;attempt++)try(var git=Git.open(path.toFile())) {var result=new ArrayList<RemoteRefUpdate>();git.push().setRemote(url("/git/"+w.ownerSlug()+"/"+w.slug()+"/"+d.directoryName()+".git")).setRefSpecs(Arrays.stream(refs).map(RefSpec::new).toList()).setCredentialsProvider(new UsernamePasswordCredentialsProvider("test",pat)).call().forEach(r->result.addAll(r.getRemoteUpdates()));return result;
    }catch(org.eclipse.jgit.api.errors.TransportException e){if(attempt>=20 || !e.getMessage().contains("429"))throw e;Thread.sleep(50);}
  }
  void branch(Fixture f,String target,String source)throws Exception{for(var d:f.paths.keySet())try(var s=new JGitStore(f.paths.get(d),false)){org.worldgit.hub.tools.BranchFixture.ref(f.paths.get(d),target,s.resolve(source));push(f.paths.get(d),f.world,d,token,"refs/heads/"+target+":refs/heads/"+target);}}
  void advance(Fixture f,String branch)throws Exception {
    UUID snapshot=UUID.randomUUID();for(var d:f.paths.keySet())try(var s=new JGitStore(f.paths.get(d),false)) {var old=s.readCommit(s.resolve(branch));var m=old.metadata();var meta=new CommitMetadata(m.author(),m.committer(),"advance",Instant.now(),m.mcDataVersion(),d,CommitMetadata.Source.HUB,false,snapshot,List.of());String next=s.createCommit(old.tree(),List.of(old.id()),meta,Map.of());s.updateRef("refs/heads/"+branch,old.id(),next);s.updateRef("refs/worldgit/groups/"+snapshot,null,next);push(f.paths.get(d),f.world,d,token,"refs/heads/"+branch+":refs/heads/"+branch,"refs/worldgit/groups/*:refs/worldgit/groups/*");}
  }
  PullRequests.Pull create(Fixture f,String source,String target)throws Exception{return prs.create(f.world,writer,source,target,"PR test","description");}
  @Test void roleAndScopeMatrixOnGitAndRest()throws Exception {
    var f=fixture();String b=base(f.world),git="/git/"+owner.username()+"/"+f.world.slug()+"/minecraft.overworld.git/info/refs?service=";
    for(String pat:List.of(token,adminToken,writeToken,readToken)) {
      assertEquals(200,request("GET",git+"git-upload-pack",pat,null).statusCode());
      assertEquals(pat.equals(readToken)?403:200,request("GET",git+"git-receive-pack",pat,null).statusCode());
      assertEquals(pat.equals(readToken)?403:200,request("POST",b+"/pulls",pat,Map.of("source","theirs","target","ours","title","matrix")).statusCode());
      assertEquals(pat.equals(readToken)||pat.equals(writeToken)?403:200,request("GET",b+"/permissions",pat,null).statusCode());
    }
    for(String scope:List.of("read","write","admin")) {
      String pat=accounts.createToken(owner,scope,false,null,scope);
      assertEquals(200,request("GET",git+"git-upload-pack",pat,null).statusCode());
      assertEquals(scope.equals("read")?403:200,request("GET",git+"git-receive-pack",pat,null).statusCode());
      assertEquals(scope.equals("admin")?200:403,request("GET",b+"/permissions",pat,null).statusCode());
      assertEquals(scope.equals("admin")?200:403,request("POST","/api/v1/tokens",pat,Map.of("name","new","scope","admin")).statusCode());
    }
    for(String path:List.of("/pulls","/comments?pinned=true","/releases","/webhooks")){assertEquals(404,request("GET",b+path,null,null).statusCode());assertEquals(404,request("GET",b.replace(f.world.slug(),"missing")+path,readToken,null).statusCode());}
    accounts.visibility(f.world,true);assertEquals(200,request("GET",b+"/pulls",null,null).statusCode());assertEquals(400,request("GET",b+"/pulls?limit=101",token,null).statusCode());
  }
  @Test void protectedBranchesRejectDirectForceDeleteAndAllowApprovedPr()throws Exception {
    var f=fixture();branch(f,"protected","ours");policy.set(f.world,new BranchPolicy.Rule("protected",true,1));
    for(String ref:List.of("+refs/heads/theirs:refs/heads/protected",":refs/heads/protected","refs/heads/main:refs/heads/protected")) {
      var updates=push(f.paths.get(DimensionId.OVERWORLD),f.world,DimensionId.OVERWORLD,token,ref);assertTrue(updates.stream().anyMatch(u->u.getStatus()!=RemoteRefUpdate.Status.OK && u.getStatus()!=RemoteRefUpdate.Status.UP_TO_DATE));
    }
    var p=create(f,"theirs","protected");var d=prs.detail(f.world,p.id());assertEquals("needs-review",d.mergeability());assertThrows(SecurityException.class,()->prs.review(f.world,writer,p.id(),d.pr().fingerprint(),"approve"));
    var choices=new TreeMap<Integer,String>();d.preview().regions().forEach(r->choices.put(r.id(),"theirs"));prs.choices(f.world,writer,p.id(),d.pr().fingerprint(),choices);
    assertThrows(org.worldgit.hub.web.ApiError.Conflict.class,()->prs.merge(f.world,owner,p.id(),d.pr().fingerprint()));prs.review(f.world,owner,p.id(),d.pr().fingerprint(),"request-changes");assertEquals("changes-requested",prs.detail(f.world,p.id()).mergeability());
    prs.review(f.world,owner,p.id(),d.pr().fingerprint(),"approve");var merged=prs.merge(f.world,writer,p.id(),d.pr().fingerprint());assertEquals("merged",merged.status());
    var tips=groups.branchTips(f.world,"protected");UUID snapshot=null;
    for(var en:tips.entrySet())try(var git=Git.open(storage.repoPath(owner.username(),f.world.slug(),en.getKey()).toFile());var s=JGitStore.readOnly(git.getRepository())){var c=s.readCommit(en.getValue());assertEquals(2,c.parents().size());if(snapshot==null)snapshot=c.metadata().snapshot();assertEquals(snapshot,c.metadata().snapshot());assertEquals("HUB",c.metadata().source().name());}
  }
  @Test void protectedPublicationCannotClaimDifferentOtherDimension()throws Exception {
    var f=fixture();policy.set(f.world,new BranchPolicy.Rule("main",true,0));
    var expected=WorldGroups.strings(groups.currentHeads(f.world,"main"));var wrong=new TreeMap<>(expected);
    String other=wrong.keySet().stream().filter(d->!d.equals(DimensionId.OVERWORLD.value())).findFirst().orElseThrow();wrong.put(other,"0".repeat(40));
    try(var store=new JGitStore(f.paths.get(DimensionId.OVERWORLD),false)) {
      var head=store.readCommit(store.resolve("main"));String parent=null;
      for(var commits:List.of(wrong,expected)) {
        String blob=store.writeBlob(JSON.writeValueAsBytes(Map.of("branch","main","operation",UUID.randomUUID().toString(),"commits",commits)));
        String tree=store.writeTree(List.of(new ObjectStore.Entry("publication.yml",ObjectStore.Kind.BLOB,blob)));
        String marker=store.createCommit(tree,List.of(),head.metadata(),Map.of());store.updateRef("refs/worldgit/publications/main",parent,marker);parent=marker;
        var update=push(f.paths.get(DimensionId.OVERWORLD),f.world,DimensionId.OVERWORLD,token,"refs/worldgit/publications/main:refs/worldgit/publications/main").getFirst();
        assertEquals(commits==wrong?RemoteRefUpdate.Status.REJECTED_OTHER_REASON:RemoteRefUpdate.Status.OK,update.getStatus());
        if(commits==wrong)assertTrue(update.getMessage().contains("全維度"));
      }
    }
    assertEquals(expected,WorldGroups.strings(groups.currentHeads(f.world,"main")));
  }
  @Test void cleanMergeLifecycleAndClosedState()throws Exception {
    var f=fixture();var p=create(f,"feature","main");assertEquals("clean",prs.detail(f.world,p.id()).mergeability());assertEquals("merged",prs.merge(f.world,owner,p.id(),p.fingerprint()).status());
    var closed=create(f,"theirs","ours");prs.edit(f.world,writer,closed.id(),"edited",null,"closed");assertEquals(1,prs.list(f.world,"closed",0,1).items().size());assertThrows(org.worldgit.hub.web.ApiError.Conflict.class,()->prs.merge(f.world,owner,closed.id(),closed.fingerprint()));
    assertFalse(events.notifications(writer,0,100).items().isEmpty());accounts.grant(f.world,owner,writer.username(),Role.NONE);assertTrue(events.notifications(writer,0,100).items().stream().noneMatch(n->n.toString().contains(f.world.slug())));
  }
  @FunctionalInterface interface LockedAction { void run(ReentrantLock lock,CountDownLatch release)throws Exception; }
  void withOwnerLockHeld(WorldRow w,LockedAction action)throws Exception {
    var lock=quota.lock(w.ownerSlug());var held=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var executor=Executors.newSingleThreadExecutor()) {
      var holder=executor.submit(()->{lock.lock();try{held.countDown();assertTrue(release.await(20,TimeUnit.SECONDS),"持鎖測試未釋放");return null;}finally{lock.unlock();}});
      try{assertTrue(held.await(10,TimeUnit.SECONDS));action.run(lock,release);}finally{release.countDown();}
      holder.get(10,TimeUnit.SECONDS);
    }
  }
  @Test void mergeWaitsForOwnerMaintenanceAndSucceedsAfterRelease()throws Exception {
    var f=fixture();var p=create(f,"feature","main");
    withOwnerLockHeld(f.world,(lock,release)->{
      try(var executor=Executors.newSingleThreadExecutor()) {
        var thread=new AtomicReference<Thread>();
        var merge=executor.submit(()->{thread.set(Thread.currentThread());return prs.merge(f.world,owner,p.id(),p.fingerprint());});
        try {
          // 觀察真正排入鎖佇列才釋放 latch；舊版立即拒絕會在這裡確定失敗。
          await().atMost(Duration.ofSeconds(5)).until(()->thread.get()!=null && lock.hasQueuedThread(thread.get()));
          assertFalse(merge.isDone());release.countDown();
          assertEquals("merged",merge.get(10,TimeUnit.SECONDS).status());
        }finally{release.countDown();}
      }
    });
    assertEquals("merged",prs.find(f.world,p.id()).status());
  }
  @Autowired MergePreviewService previews;
  @Test void mergeTimesOutWhileOwnerMaintenanceKeepsLock()throws Exception {
    var f=fixture();var p=create(f,"feature","main");var before=groups.branchTips(f.world,"main");
    var timeout=Duration.ofMillis(100);var props=new CollaborationProperties(null,null,null,null,timeout);
    var bounded=new PullRequests(db,accounts,groups,previews,policy,quota,repos,events,transactions,props);
    withOwnerLockHeld(f.world,(lock,release)->{
      long started=System.nanoTime();
      var error=assertTimeout(Duration.ofSeconds(5),()->assertThrows(org.worldgit.hub.web.ApiError.Unavailable.class,()->bounded.merge(f.world,owner,p.id(),p.fingerprint())));
      assertTrue(System.nanoTime()-started>=timeout.toNanos(),"不能立即拒絕，需等到設定的逾時");
      assertTrue(error.getMessage().contains("逾時"));assertTrue(lock.isLocked());assertFalse(lock.isHeldByCurrentThread());
    });
    assertEquals("open",prs.find(f.world,p.id()).status());assertEquals(before,groups.branchTips(f.world,"main"));
    assertEquals("merged",prs.merge(f.world,owner,p.id(),p.fingerprint()).status());
  }
  @Test void interruptedMergeRestoresInterruptAndLeavesOwnerLockUntouched()throws Exception {
    var f=fixture();var p=create(f,"feature","main");var before=groups.branchTips(f.world,"main");
    withOwnerLockHeld(f.world,(lock,release)->{
      try(var executor=Executors.newSingleThreadExecutor()) {
        var thread=new AtomicReference<Thread>();
        var merge=executor.submit(()->{thread.set(Thread.currentThread());var error=assertThrows(org.worldgit.hub.web.ApiError.Unavailable.class,()->prs.merge(f.world,owner,p.id(),p.fingerprint()));assertTrue(error.getMessage().contains("中斷"));return Thread.currentThread().isInterrupted();});
        try {
          await().atMost(Duration.ofSeconds(5)).until(()->thread.get()!=null && lock.hasQueuedThread(thread.get()));
          thread.get().interrupt();assertTrue(merge.get(10,TimeUnit.SECONDS));assertTrue(lock.isLocked());
        }finally{release.countDown();}
      }
    });
    assertEquals("open",prs.find(f.world,p.id()).status());assertEquals(before,groups.branchTips(f.world,"main"));
    assertEquals("merged",prs.merge(f.world,owner,p.id(),p.fingerprint()).status());
  }
  @Test void choicesAndReviewsInvalidateOnAnyDimensionTipAndMergeLeaseRace()throws Exception {
    var f=fixture();var p=create(f,"theirs","ours");var d=prs.detail(f.world,p.id());var choices=new TreeMap<Integer,String>();d.preview().regions().forEach(r->choices.put(r.id(),"base"));prs.choices(f.world,writer,p.id(),p.fingerprint(),choices);prs.review(f.world,owner,p.id(),p.fingerprint(),"approve");
    var first=choices.firstKey();choices.put(first,"theirs");assertTrue(prs.choices(f.world,writer,p.id(),p.fingerprint(),choices).reviews().isEmpty());prs.review(f.world,owner,p.id(),p.fingerprint(),"approve");
    advance(f,"theirs");var changed=prs.detail(f.world,p.id());assertTrue(changed.pr().selectionsInvalidated());assertTrue(changed.choices().isEmpty());assertTrue(changed.reviews().isEmpty());assertNotEquals(p.fingerprint(),changed.pr().fingerprint());assertThrows(org.worldgit.hub.web.ApiError.Conflict.class,()->prs.merge(f.world,owner,p.id(),p.fingerprint()));
    var before=groups.branchTips(f.world,"ours");assertThrows(org.worldgit.hub.web.ApiError.Conflict.class,()->prs.choices(f.world,writer,p.id(),p.fingerprint(),choices));assertEquals(before,groups.branchTips(f.world,"ours"));
  }
  @Test void competingMergeOnlyPublishesOnceAndCrashFinalizeReconciles()throws Exception {
    var f=fixture();var p=create(f,"feature","main");var gate=new java.util.concurrent.CountDownLatch(1);
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
      var tasks=new ArrayList<java.util.concurrent.Future<Integer>>();for(int i=0;i<2;i++)tasks.add(executor.submit(()->{gate.await();return request("POST",base(f.world)+"/pulls/"+p.id()+"/merge",token,Map.of("fingerprint",p.fingerprint())).statusCode();}));gate.countDown();var codes=new ArrayList<Integer>();for(var task:tasks)codes.add(task.get());assertEquals(1,codes.stream().filter(c->c==200).count());assertTrue(codes.stream().allMatch(c->Set.of(200,409,503).contains(c)),codes.toString());
    }
    var expected=groups.branchTips(f.world,"main");db.sql("DELETE FROM hub_events WHERE world_id=? AND type='pr.merged'").param(f.world.id()).update();db.sql("UPDATE pull_requests SET status='open',merge_pending=1,merged_snapshot=NULL,merge_commits=NULL WHERE id=?").param(p.id()).update();
    var lock=quota.lock(f.world.ownerSlug());lock.lock();try{prs.reconcile(f.world);prs.reconcile(f.world);}finally{lock.unlock();}assertEquals("merged",prs.find(f.world,p.id()).status());assertEquals(expected,groups.branchTips(f.world,"main"));assertEquals(1,db.sql("SELECT COUNT(*) FROM hub_events WHERE world_id=? AND type='pr.merged'").param(f.world.id()).query(Integer.class).single());
  }
  @Test void coordinateCommentsRepliesIdorAndDeletion()throws Exception {
    var f=fixture();var p=create(f,"theirs","ours");String b=base(f.world);var pin=Map.of("dimension","minecraft:overworld","x",8,"y",65,"z",8,"maxX",9,"maxY",66,"maxZ",9);
    var comment=ok("POST",b+"/pulls/"+p.id()+"/comments",readToken,Map.of("body","<img src=x onerror=alert(1)>","pin",pin));String id=comment.get("id").asText();
    ok("POST",b+"/pulls/"+p.id()+"/comments",writeToken,Map.of("parentId",id,"body","reply"));assertEquals(403,request("PATCH",b+"/comments/"+id,writeToken,Map.of("body","steal")).statusCode());
    ok("PATCH",b+"/comments/"+id,readToken,Map.of("body","edited"));assertEquals(1,ok("GET",b+"/comments?pinned=true",token,null).get("items").size());
    var other=fixture();assertEquals(404,request("DELETE",base(other.world)+"/comments/"+id,token,null).statusCode());ok("DELETE",b+"/comments/"+id,adminToken,null);
    var list=ok("GET",b+"/comments?pr="+p.id(),token,null).get("items");assertEquals(2,list.size());assertTrue(list.get(0).get("deleted").asBoolean());assertEquals(0,ok("GET",b+"/comments?pinned=true",token,null).get("items").size());
  }
  @Test void teamGrantRequiresCurrentOrgMembershipAndLastOwnerIsProtected()throws Exception {
    String slug="org-"+UUID.randomUUID().toString().substring(0,8);accounts.createOrganization(owner,slug,slug);accounts.setMember(owner,slug,reader.username(),Role.READER);String org=orgs.org(owner,slug,Role.OWNER),team=orgs.create(org,"builders");orgs.member(org,team,reader.username(),true);
    var w=accounts.createWorld(owner,slug,"castle","Castle","",false).orElseThrow();orgs.grant(w,owner,"builders",Role.WRITER);assertEquals(Role.WRITER,accounts.roleOn(reader,w));accounts.setMember(owner,slug,reader.username(),Role.NONE);assertEquals(Role.NONE,accounts.roleOn(reader,w));assertThrows(IllegalArgumentException.class,()->accounts.setMember(owner,slug,owner.username(),Role.NONE));assertThrows(IllegalArgumentException.class,()->accounts.grant(w,owner,reader.username(),Role.OWNER));
  }
  @Test void concurrentOwnerRemovalKeepsOneOwner() throws Exception {
    String slug = "org-" + UUID.randomUUID().toString().substring(0, 8);
    accounts.createOrganization(owner, slug, slug);
    accounts.setMember(owner, slug, writer.username(), Role.OWNER);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> {
        gate.await();
        return request("PUT", "/api/v1/orgs/" + slug + "/members/" + owner.username(),
            token, Map.of("role", "none")).statusCode();
      });
      var second = executor.submit(() -> {
        gate.await();
        return request("PUT", "/api/v1/orgs/" + slug + "/members/" + writer.username(),
            writeToken, Map.of("role", "none")).statusCode();
      });
      gate.countDown();
      var codes = new ArrayList<>(List.of(first.get(), second.get()));
      Collections.sort(codes);
      assertEquals(List.of(200, 400), codes);
    }
    var members = orgs.members(accounts.findOwnerId(slug).orElseThrow());
    assertEquals(1, members.stream().filter(m -> m.get("role").equals("owner")).count());
  }
  @Test @Order(100) void successfulPatTrafficDoesNotConsumeFailureBudget()throws Exception {
    for(int i=0;i<100;i++)assertEquals(200,request("GET","/api/v1/me",token,null).statusCode());
    for(int i=0;i<5;i++)assertEquals(401,request("GET","/api/v1/me","incorrect",null).statusCode());assertEquals(429,request("GET","/api/v1/me","incorrect",null).statusCode());assertEquals(200,request("GET","/api/v1/me",token,null).statusCode());
  }
  @Test void actualGitWriteReadAndUnprotectedForceDeleteMatrix()throws Exception {
    var f=fixture();branch(f,"rewrite","ours");var path=f.paths.get(DimensionId.OVERWORLD);
    assertThrows(org.eclipse.jgit.api.errors.TransportException.class,()->push(path,f.world,DimensionId.OVERWORLD,readToken,"refs/heads/main:refs/heads/new-read"));
    for(String actor:List.of(writeToken,adminToken,token)) {
      var updates=push(path,f.world,DimensionId.OVERWORLD,actor,"+refs/heads/theirs:refs/heads/rewrite");
      assertEquals(actor.equals(writeToken)?RemoteRefUpdate.Status.REJECTED_OTHER_REASON:actor.equals(adminToken)?RemoteRefUpdate.Status.OK:RemoteRefUpdate.Status.UP_TO_DATE,updates.getFirst().getStatus());
    }
    assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,push(path,f.world,DimensionId.OVERWORLD,writeToken,":refs/heads/rewrite").getFirst().getStatus());assertEquals(RemoteRefUpdate.Status.OK,push(path,f.world,DimensionId.OVERWORLD,adminToken,":refs/heads/rewrite").getFirst().getStatus());
    for(String actor:List.of(readToken,writeToken,adminToken,token))try(var git=Git.init().setBare(true).setDirectory(work.resolve(UUID.randomUUID().toString()).toFile()).call()){git.fetch().setRemote(url("/git/"+owner.username()+"/"+f.world.slug()+"/minecraft.overworld.git")).setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).setCredentialsProvider(new UsernamePasswordCredentialsProvider("test",actor)).call();assertNotNull(git.getRepository().exactRef("refs/heads/main"));}
  }
  @Test void rejectsBlobAndTreeTagsClearly()throws Exception {
    var f=fixture();for(String kind:List.of("blob","tree"))try(var git=Git.open(f.paths.get(DimensionId.OVERWORLD).toFile());var s=new JGitStore(f.paths.get(DimensionId.OVERWORLD),false);var walk=new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())){
      String id=kind.equals("blob")?s.writeBlob(new byte[]{1}):s.readCommit(s.resolve("main")).tree();s.flush();git.tag().setName("bad-"+kind).setObjectId(walk.parseAny(ObjectId.fromString(id))).setTagger(new PersonIdent("test","test@example.test")).setMessage("bad").call();
      var updates=push(f.paths.get(DimensionId.OVERWORLD),f.world,DimensionId.OVERWORLD,token,"refs/tags/bad-"+kind+":refs/tags/bad-"+kind);assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,updates.getFirst().getStatus());assertTrue(updates.getFirst().getMessage().contains("commit"));
    }
  }
  @Test void webhookSignsRetriesRejectsRedirectAndHidesSecret()throws Exception {
    var f=fixture();var bodies=new ArrayList<String>();var signatures=new ArrayList<String>();var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/hook",ex->{String body=new String(ex.getRequestBody().readAllBytes());bodies.add(body);signatures.add(ex.getRequestHeaders().getFirst("X-WorldGit-Signature-256"));ex.sendResponseHeaders(bodies.size()==1?503:204,-1);ex.close();});server.start();
    try{String secret="test-webhook-secret-1234567890123456";var hook=hooks.create(f.world,"http://127.0.0.1:"+server.getAddress().getPort()+"/hook",secret,List.of("release"),true);events.emit(f.world,"release",Map.of("tag","v1"),Set.of());hooks.deliverDue();var delivery=hooks.deliveries(f.world,hook.id(),0,10).items().getFirst();assertEquals("PENDING",delivery.status());assertEquals(1,delivery.attempt());
      db.sql("UPDATE webhook_deliveries SET next_at=0 WHERE id=?").param(delivery.id()).update();hooks.deliverDue();assertEquals("DELIVERED",hooks.deliveries(f.world,hook.id(),0,10).items().getFirst().status());assertEquals(bodies.get(0),bodies.get(1));assertEquals(Webhooks.signature(secret,bodies.getFirst()),signatures.getFirst());assertFalse(ok("GET",base(f.world)+"/webhooks",token,null).toString().contains(secret));
      server.createContext("/redirect",ex->{ex.getResponseHeaders().set("Location","http://127.0.0.1:1/private");ex.sendResponseHeaders(302,-1);ex.close();});hooks.update(f.world,hook.id(),"http://127.0.0.1:"+server.getAddress().getPort()+"/redirect",null,List.of("release"),true);events.emit(f.world,"release",Map.of("tag","v2"),Set.of());hooks.deliverDue();assertTrue(hooks.deliveries(f.world,hook.id(),0,10).items().stream().anyMatch(d->Integer.valueOf(302).equals(d.responseCode())));
    }finally{server.stop(0);}
  }
  @Test void webhookDoesNotDrainUnboundedResponseBody()throws Exception {
    var f=fixture();var stopped=new java.util.concurrent.atomic.AtomicBoolean();var disconnected=new java.util.concurrent.CountDownLatch(1);
    var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var serverExecutor=java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());server.setExecutor(serverExecutor);
    server.createContext("/body",ex->{try{ex.getRequestBody().readAllBytes();ex.sendResponseHeaders(200,0);byte[] body=new byte[8192];while(!stopped.get()){ex.getResponseBody().write(body);ex.getResponseBody().flush();Thread.sleep(20);}}catch(Exception ignored){}finally{disconnected.countDown();ex.close();}});server.start();
    try {
      var hook=hooks.create(f.world,"http://127.0.0.1:"+server.getAddress().getPort()+"/body","test-webhook-secret-1234567890123456",List.of("release"),true);
      events.emit(f.world,"release",Map.of("tag","body"),Set.of());
      assertTimeoutPreemptively(Duration.ofSeconds(3),()->hooks.deliverDue());
      assertEquals("DELIVERED",hooks.deliveries(f.world,hook.id(),0,10).items().getFirst().status());
      assertTrue(disconnected.await(3,java.util.concurrent.TimeUnit.SECONDS));
    }finally{stopped.set(true);server.stop(0);serverExecutor.close();}
  }
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Test void releaseZipPinsTagHasPermissionsBudgetAndConcurrentDownloadLimit()throws Exception {
    String slug="zip-"+UUID.randomUUID().toString().substring(0,8);Path world=work.resolve(slug).resolve("world");HubIntegrationTest.copy(Path.of("../core/src/test/resources/fixtures/26.2"),world);
    var layout=org.worldgit.core.anvil.WorldLayout.discover(world);var local=new org.worldgit.core.service.WorldRepositories(layout);assertTrue(local.init(null,"creative",org.worldgit.core.config.WorldGitConfig.Track.ALL,new CommitMetadata.Identity("test","test@example.test")).success());
    var w=accounts.createWorld(owner,owner.username(),slug,slug,"",false).orElseThrow();accounts.grant(w,owner,reader.username(),Role.READER);
    for(var d:local.tracked().keySet()){Path path=layout.repositoryRoot().resolve(d.directoryName());try(var store=new JGitStore(path,false)){store.updateRef("refs/tags/v1",null,store.head());}push(path,w,d,token,"refs/heads/main:refs/heads/main","refs/tags/v1:refs/tags/v1","refs/worldgit/groups/*:refs/worldgit/groups/*");}
    var release=ok("POST",base(w)+"/releases",token,Map.of("tag","v1","title","Release","body","test"));String id=release.get("id").asText();assertEquals(404,request("GET",base(w)+"/releases/"+id+"/zip",null,null).statusCode());
    var download=request("GET",base(w)+"/releases/"+id+"/zip",readToken,null);assertEquals(200,download.statusCode());assertEquals("private, no-store",download.headers().firstValue("Cache-Control").orElseThrow());
    var entries=new HashSet<String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(download.body()))){for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry()){entries.add(e.getName());zip.readAllBytes();}}assertTrue(entries.contains("level.dat"));assertTrue(entries.stream().anyMatch(n->n.endsWith(".mca")));assertTrue(entries.stream().noneMatch(n->n.contains(".worldgit")||n.contains("playerdata")||n.equals("session.lock")));
    var props=new org.worldgit.hub.config.CollaborationProperties(null,null,new org.worldgit.hub.config.CollaborationProperties.Downloads(1,1,1),null,null);var hub=new org.worldgit.hub.config.HubProperties(data,null,null,null,null,null,null,null,null,null);
    var limited=new Releases(db,groups,repos,events,quota,props,hub,transactions);
    var failedResponse=new org.springframework.mock.web.MockHttpServletResponse();
    assertThrows(org.worldgit.core.normalize.DecodeBudget.Exceeded.class,()->limited.zip(w,id,failedResponse));
    assertEquals("application/json",failedResponse.getContentType());assertNull(failedResponse.getHeader("Content-Disposition"));
    // 即使外層一般 API 的聚合解析額度不足，完整世界下載仍使用自己的有限額度並還原 scope。
    try(var decode=new org.worldgit.core.normalize.DecodeBudget(1,1,1,1,1).open()) {
      var fullResponse=new org.springframework.mock.web.MockHttpServletResponse();releases.zip(w,id,fullResponse);
      assertEquals("application/zip",fullResponse.getContentType());assertTrue(fullResponse.getContentAsByteArray().length>100);
      assertThrows(org.worldgit.core.normalize.DecodeBudget.Exceeded.class,()->org.worldgit.core.normalize.DecodeBudget.read(2));
    }
    try(var files=Files.list(data.resolve("downloads"))){assertEquals(0,files.count());}
    var gate=new java.util.concurrent.CountDownLatch(1);var entered=new java.util.concurrent.CountDownLatch(1);
    var concurrent=new Releases(db,groups,repos,events,quota,new org.worldgit.hub.config.CollaborationProperties(null,null,new org.worldgit.hub.config.CollaborationProperties.Downloads(536870912,300,1),null,null),hub,transactions);
    var response=new org.springframework.mock.web.MockHttpServletResponse(){@Override public jakarta.servlet.ServletOutputStream getOutputStream(){return new jakarta.servlet.ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(jakarta.servlet.WriteListener l){}public void write(int b)throws IOException {entered.countDown();try{if(!gate.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("timeout");}catch(InterruptedException ex){throw new IOException(ex);}}};}};
    try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){var future=executor.submit(()->{concurrent.zip(w,id,response);return true;});try{assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));assertThrows(org.worldgit.hub.web.ApiError.Unavailable.class,()->concurrent.zip(w,id,new org.springframework.mock.web.MockHttpServletResponse()));}finally{gate.countDown();}assertTrue(future.get());}
  }
  @Autowired org.worldgit.hub.git.RepoCache repos;
  @Test @Order(1) void cookieSessionCsrfOriginAndGitCannotUseSession()throws Exception {
    var cookies=new CookieManager();cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);var client=HttpClient.newBuilder().cookieHandler(cookies).build();
    var login=HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"p4-owner\",\"password\":\"test-password\"}")).build();assertEquals(200,client.send(login,HttpResponse.BodyHandlers.ofString()).statusCode());
    client.send(HttpRequest.newBuilder(URI.create(url("/api/v1/me"))).build(),HttpResponse.BodyHandlers.ofString());String csrf=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue();
    var b=HttpRequest.newBuilder(URI.create(url("/api/v1/tokens"))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"csrf\",\"scope\":\"read\"}"));assertEquals(403,client.send(b.build(),HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(403,client.send(b.copy().header("Authorization", " ").build(),HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(403,client.send(b.copy().header("X-XSRF-TOKEN",csrf).header("Origin","https://attacker.test").build(),HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(200,client.send(b.header("X-XSRF-TOKEN",csrf).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    var f=fixture();assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(url("/git/"+owner.username()+"/"+f.world.slug()+"/minecraft.overworld.git/info/refs?service=git-upload-pack"))).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
  }
}
