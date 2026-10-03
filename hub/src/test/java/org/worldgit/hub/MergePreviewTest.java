package org.worldgit.hub;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.store.*;
import org.worldgit.hub.history.MergePreviewService;
import com.fasterxml.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
class MergePreviewTest extends AbstractBranchTest {
  @org.springframework.beans.factory.annotation.Autowired MergePreviewService previews;
  @DynamicPropertySource static void props(DynamicPropertyRegistry r) throws Exception {
    prepareDirs(); r.add("server.address",()->"127.0.0.1"); r.add("server.port",()->"18099");r.add("worldgit.hub.auth.attempts",()->"100000");
    r.add("worldgit.hub.data-dir",()->data.toString()); r.add("worldgit.hub.bootstrap.admin-token",()->TOKEN);
    r.add("worldgit.hub.bootstrap.admin-password",()->"test-password-1");
    HubTestDatabase.configure(r);
  }
  @BeforeAll void extraFixture() throws Exception {
    // inherited buildWorld 已提供 Phase 2 場景，另建立 Phase 3 分支與 group refs。
    var more=org.worldgit.hub.tools.MergeFixture.create(work.resolve("merge"));
    paths=more;
    for(var d:paths.keySet()) {
      for(String branch:List.of("ours","theirs","version","rules-a","rules-b","packs")) pushRef(d,branch);
      for(int attempt=0;;attempt++) try(var git=org.eclipse.jgit.api.Git.open(paths.get(d).toFile())) {
        git.push().setRemote(url("/git/admin/"+WORLD+"/"+d.directoryName()+".git"))
            .setRefSpecs(new org.eclipse.jgit.transport.RefSpec("refs/worldgit/groups/*:refs/worldgit/groups/*"))
            .setCredentialsProvider(new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("admin",TOKEN)).call();break;
      } catch(org.eclipse.jgit.api.errors.TransportException e) {if(attempt>=20||!String.valueOf(e.getMessage()).contains("429"))throw e;Thread.sleep(250);}
    }
  }
  String endpoint() {return "/api/v1/worlds/admin/"+WORLD+"/merge-preview";}
  JsonNode report(String ours,String theirs) throws Exception {return json(endpoint()+"?ours="+ours+"&theirs="+theirs,TOKEN);}
  String view(JsonNode r,String mode,String choice,String kind) { return endpoint()+"/view/"+kind+"?ours=ours&theirs=theirs&fingerprint="+r.get("fingerprint").asText()+"&dim=minecraft:overworld&view="+mode+"&choices="+choice+"&x0=0&z0=0&x1=0&z1=0"; }

  @Test void zeroConflictMultiDimensionAndMissingSourcePreservesOurs() throws Exception {
    var r=report("main","feature"); assertTrue(r.get("canMerge").asBoolean());assertTrue(r.get("zeroIntervention").asBoolean());
    assertEquals(0,r.get("regions").size());assertEquals(3,r.get("dimensions").size());assertEquals(1,r.get("automaticallyMergedSections").asInt());
    var missing=report("main","side");assertTrue(missing.get("canMerge").asBoolean());
    assertEquals(2,java.util.stream.StreamSupport.stream(missing.get("dimensions").spliterator(),false).filter(d->d.get("status").asText().equals("keep-ours")).count());
  }
  @Test void coreRegionsMatchAndStateSelectionIsExactAndReadOnly() throws Exception {
    var before=repoDigest(); var r=report("ours","theirs");
    assertTrue(r.get("canMerge").asBoolean());assertFalse(r.get("zeroIntervention").asBoolean());
    assertEquals(6,r.get("regions").size());
    for(var dim:paths.keySet()) try(var s=new JGitStore(paths.get(dim),false)) {
      String o=s.resolve("ours"),t=s.resolve("theirs"),b=MergeBases.unique(s,o,t);
      var core=new MergeEngine(s,dim,s.readCommit(b).tree(),s.readCommit(o).tree(),s.readCommit(t).tree(),List.of(),List.of()).merge(1);
      var rows=new ArrayList<JsonNode>();r.get("regions").forEach(n->{if(n.get("dimension").asText().equals(dim.value()))rows.add(n);});
      assertEquals(core.report().regions().size(),rows.size());
      for(int i=0;i<rows.size();i++) {
        var cr=core.report().regions().get(i);assertEquals(JSON.valueToTree(cr.bounds()),rows.get(i).get("bounds"));assertEquals(cr.blockCount(),rows.get(i).get("blockCount").asInt());
      }
    }
    var region=java.util.stream.StreamSupport.stream(r.get("regions").spliterator(),false).filter(n->n.get("dimension").asText().equals("minecraft:overworld")&&n.get("blockCount").asInt()==3).findFirst().orElseThrow();
    assertTrue(region.get("redstone").asBoolean());assertTrue(region.get("oursAuthors").toString().contains("Alice"));assertTrue(region.get("theirsAuthors").toString().contains("Bob"));
    int id=region.get("id").asInt();
    for(String mode:List.of("ours","theirs","base","auto","selected")) {
      var response=get(view(r,mode,id+":theirs","chunks"),TOKEN);assertEquals(200,response.statusCode(),new String(response.body()));
      var decoded=decode(response.body());
      String expected=switch(mode){case "theirs","selected"->"minecraft:nether_brick_fence[east=false,north=false,south=false,waterlogged=false,west=false]";case "base"->"minecraft:air";default->"minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]";};
      assertEquals(expected,decoded.get((1<<8)|(8<<4)|8));
      assertEquals("minecraft:obsidian",decoded.get((2<<8)|(8<<4)|9),"精確 atoms 保留 bbox 內自動合併角落");
      assertEquals("minecraft:emerald_block",decoded.get((1<<8)|(12<<4)|12));assertEquals("minecraft:diamond_block",decoded.get((1<<8)|(12<<4)|13));
      // 每格 state 比對到 core select 的候選，不觸發任何 shape 推導。
      try(var s=new JGitStore(paths.get(DimensionId.OVERWORLD),false)) {
        var o=s.readCommit(s.resolve("ours"));var t=s.readCommit(s.resolve("theirs"));var b=s.readCommit(MergeBases.unique(s,o.id(),t.id()));
        var core=new MergeEngine(s,DimensionId.OVERWORLD,b.tree(),o.tree(),t.tree(),List.of(),List.of()).merge(1);String tree=core.tree();
        for(var cr:core.report().regions()) {
          String source=mode.equals("theirs")?t.tree():mode.equals("base")?b.tree():o.tree();
          if(mode.equals("selected")&&cr.blockCount()==3)source=t.tree();
          tree=MergeEngine.select(s,tree,source,cr);
        }
        var section=org.worldgit.core.normalize.SnapshotCodec.section(s.readBlob(TreeEditor.find(s,tree,"r.0.0/c.0.0/s.4.bin").id()));
        assertEquals(section.blocks().stream().map(BlockState::canonical).toList(),decoded);
      }
    }
    assertEquals(before,repoDigest(),"預覽／選擇不得增加 repo objects／refs／狀態檔");
  }
  @Test void incompatibleVersionsRulesAndPacksHaveExplicitReasons() throws Exception {
    for(var pair:List.of(new String[]{"ours","version","data-version"},new String[]{"rules-a","rules-b","rules"},new String[]{"ours","packs","data-packs"})) {
      var r=report(pair[0],pair[1]);assertFalse(r.get("canMerge").asBoolean());assertFalse(r.get("zeroIntervention").asBoolean());
      assertEquals(pair[2],r.get("problems").get(0).get("code").asText());assertFalse(r.get("problems").get(0).get("reason").asText().isBlank());
      if(pair[2].equals("rules"))assertTrue(r.get("ruleDifferences").get(0).get("merged").isNull());
    }
  }
  @Test void authBeforeWarmCacheAndInvalidChoices() throws Exception {
    var r=report("ours","theirs");
    assertEquals(404,get(endpoint()+"?ours=ours&theirs=theirs",null).statusCode());
    assertEquals(404,get(endpoint()+"/ours...theirs",null).statusCode());
    assertEquals(404,get(view(r,"selected","1:ours","chunks"),null).statusCode());
    var bob=accounts.createUser("merge-bob","merge-password-1",false);
    var token=accounts.createToken(bob,"merge-test",false);
    assertEquals(404,get(endpoint()+"?ours=ours&theirs=theirs",token).statusCode());
    assertEquals(200,get(endpoint()+"/ours...theirs",TOKEN).statusCode());
    assertEquals(400,get(view(r,"selected","999:ours","chunks"),TOKEN).statusCode());
    assertEquals(400,get(view(r,"selected","1:ours,1:base","chunks"),TOKEN).statusCode());
    assertEquals(400,get(view(r,"auto","","chunks").replace(r.get("fingerprint").asText(),"old-tips"),TOKEN).statusCode());
  }
  @Test void boundedCacheAndTipChangesInvalidate() throws Exception {
    previews.clearCache();var first=report("ours","theirs").get("fingerprint").asText();
    for(String a:List.of("main","feature","side","ours")) for(String b:List.of("main","feature","side")) report(a,b);
    assertTrue(previews.cacheEntries()<=MergePreviewService.MAX_CACHE_ENTRIES);assertTrue(previews.cacheBytes()<=MergePreviewService.MAX_CACHE_BYTES);
    // 同名分支移動會產生新指紋；只有本測試專用分支變動。
    try(var s=new JGitStore(paths.get(DimensionId.OVERWORLD),false)){org.worldgit.hub.tools.BranchFixture.ref(paths.get(DimensionId.OVERWORLD),"moving",MergeBases.unique(s,s.resolve("ours"),s.resolve("theirs")));}
    pushRef(DimensionId.OVERWORLD,"moving");var old=report("moving","theirs").get("fingerprint").asText();
    try(var s=new JGitStore(paths.get(DimensionId.OVERWORLD),false)) {org.worldgit.hub.tools.BranchFixture.ref(paths.get(DimensionId.OVERWORLD),"moving",s.resolve("theirs"));}
    // Hub 拒絕非快進 push；分支前進（base → theirs）同樣改變 tip。
    try(var git=org.eclipse.jgit.api.Git.open(paths.get(DimensionId.OVERWORLD).toFile())){git.push().setRemote(url("/git/admin/"+WORLD+"/minecraft.overworld.git")).setRefSpecs(new org.eclipse.jgit.transport.RefSpec("refs/heads/moving:refs/heads/moving")).setCredentialsProvider(new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("admin",TOKEN)).call().forEach(x->x.getRemoteUpdates().forEach(u->assertEquals(org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK,u.getStatus())));}
    assertNotEquals(old,report("moving","theirs").get("fingerprint").asText());assertEquals(first,report("ours","theirs").get("fingerprint").asText());
  }
  @Test void hashUsesGroupRefsIncludingUnchangedDimension() throws Exception {
    String id;try(var s=new JGitStore(paths.get(DimensionId.OVERWORLD),false)){id=s.resolve("ours");}
    var r=report(id,"theirs");assertEquals(3,r.get("dimensions").size());assertEquals(6,r.get("regions").size());
  }
  Map<String,String> repoDigest() throws Exception {
    var result=new TreeMap<String,String>();Path root=data.resolve("repos");
    try(var walk=Files.walk(root)){for(Path p:walk.filter(Files::isRegularFile).toList())result.put(root.relativize(p).toString(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}return result;
  }
  static List<String> decode(byte[] bytes) throws Exception {
    var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));assertEquals("WGCK",new String(in.readNBytes(4)));assertEquals(1,in.readUnsignedByte());
    var states=new ArrayList<String>();int n=in.readUnsignedShort();for(int i=0;i<n;i++)states.add(utf(in));n=in.readUnsignedShort();for(int i=0;i<n;i++)utf(in);
    int chunks=in.readInt();for(int c=0;c<chunks;c++) {in.readInt();in.readInt();int sections=in.readUnsignedByte();for(int sec=0;sec<sections;sec++) {
      int sy=in.readByte(),pc=in.readUnsignedShort();int[] palette=new int[pc];for(int i=0;i<pc;i++)palette[i]=in.readUnsignedShort();int bits=in.readUnsignedByte();byte[] packed=in.readNBytes((4096*bits+7)/8);
      if(sy==4){var out=new ArrayList<String>();for(int i=0;i<4096;i++){int bit=i*bits,value=0;for(int b=0;b<bits;b++)value|=((packed[(bit+b)/8]>>>((bit+b)%8))&1)<<b;out.add(states.get(palette[value]));}return out;}
      int be=in.readUnsignedShort();for(int i=0;i<be;i++){in.readUnsignedShort();utf(in);}
    }}throw new AssertionError("no section");
  }
  static String utf(java.io.DataInputStream in)throws Exception{return new String(in.readNBytes(in.readUnsignedShort()),java.nio.charset.StandardCharsets.UTF_8);}
}
