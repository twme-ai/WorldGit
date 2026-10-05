package org.worldgit.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.DimensionId;

class CliTest {
  @TempDir Path temp;

  private record Result(int code, String out, String err) {}

  private Result run(String... arguments) {
    return runAt(temp, arguments);
  }

  private Result runAt(Path world, String... arguments) {
    var out = new StringWriter();
    var err = new StringWriter();
    var args = new ArrayList<>(List.of("--world", world.toString()));
    args.addAll(List.of(arguments));
    int code=Wgit.execute(args.toArray(String[]::new),new PrintWriter(out,true),new PrintWriter(err,true));
    // 所有下方指令共用此驗收：終止結果與 exit code 必須一致，不能靜默結束。
    if(args.contains("--format=json")) {
      try {
        var result=new ObjectMapper().readTree(out.toString()).path("result");
        assertFalse(result.path("operationId").isMissingNode(),out.toString());
        var status=org.worldgit.core.operation.OperationResult.Status.valueOf(result.path("status").asText());
        int expected=switch(status) { case SUCCESS,NO_OP->0;case PARTIAL->2;case FAILED->1;case CANCELLED->130; };
        assertEquals(expected,code,out+"\n"+err);
      } catch(IOException failure) { fail("JSON 終止結果無效："+out,failure); }
    } else {
      String last=out.toString().strip().lines().reduce((a,b)->b).orElse("");
      assertTrue(last.contains(" ms）"),"缺少明確終止結果："+out);
      assertTrue(List.of("success","no-op","partial","failed","cancelled").stream().map(CliMessages::text).anyMatch(last::startsWith),last);
    }
    return new Result(code,out.toString(),err.toString());
  }

  private JsonNode data(String output) throws Exception { return new ObjectMapper().readTree(output).path("data"); }
  private JsonNode single(String output) throws Exception { return data(output).path("minecraft:overworld").path("data"); }

  @Test void dimensionScopeIgnoreGraphAndPartialResultsShareOperationId() throws Exception {
    Path fixture=Path.of(System.getProperty("worldgit.projectRoot"),"core/src/test/resources/fixtures/26.2");
    try(var files=Files.walk(fixture)) { for(Path file:files.toList()) { Path target=temp.resolve(fixture.relativize(file)); if(Files.isDirectory(file))Files.createDirectories(target);else Files.copy(file,target); } }
    var first=run("init","--only","--format=json");assertEquals(0,first.code,first.err);assertEquals(1,data(first.out).path("dimensions").size());
    Path nether=WorldLayout.discover(temp).dimensions().get(new DimensionId("minecraft:the_nether")).directory();
    var second=runAt(nether,"init","--format=json");assertEquals(0,second.code,second.err);assertTrue(data(second.out).path("dimensions").has("minecraft:the_nether"));
    assertEquals(0,run("branch","duplicate").code);
    var partial=run("branch","duplicate","--all","--format=json");assertEquals(2,partial.code,partial.err);
    var result=new ObjectMapper().readTree(partial.out);assertEquals("PARTIAL",result.path("result").path("status").asText());
    assertEquals("FAILED",result.path("data").path("minecraft:overworld").path("result").path("status").asText());
    assertEquals("SUCCESS",result.path("data").path("minecraft:the_nether").path("result").path("status").asText());
    assertEquals(result.path("result").path("operationId"),result.path("data").path("minecraft:the_nether").path("result").path("operationId"));
    assertFalse(result.path("result").path("error").isMissingNode());
    var graph=run("log","--graph","--all","--color=never");assertEquals(0,graph.code);assertTrue(graph.out.contains("* "));assertTrue(graph.out.contains("duplicate"));assertFalse(graph.out.contains("Label["));assertFalse(graph.out.contains("\033"));assertFalse(graph.err.contains("\033"));
    Path ignore=WorldLayout.discover(temp).repository(DimensionId.OVERWORLD).resolve(".wgignore");String original=Files.readString(ignore);
    assertEquals(0,run("ignore","add","entity minecraft:item","--dry-run","--format=json").code);assertEquals(original,Files.readString(ignore));
    assertEquals(0,run("ignore","add","entity minecraft:item").code);
    var tested=run("ignore","test","entity minecraft:item 0,64,0","--format=json");assertEquals(0,tested.code,tested.err);assertTrue(data(tested.out).path("preview").path("excluded").asBoolean());assertTrue(data(tested.out).path("preview").path("line").asInt()>0);
    var noOp=run("commit","-m","rules","--format=json");assertEquals(0,noOp.code,noOp.err);
    var unchanged=run("commit","-m","no change","--format=json");assertEquals("NO_OP",new ObjectMapper().readTree(unchanged.out).path("result").path("status").asText());
    var failed=run("--dimension","minecraft:the_nether","switch","missing","--format=json");assertEquals(1,failed.code);assertEquals("FAILED",new ObjectMapper().readTree(failed.out).path("result").path("status").asText());
  }

  @Test
  void remoteClonePullTagsAndExportAreUsableAsJson() throws Exception {
    Path source =
        Path.of(
            System.getProperty("worldgit.projectRoot"), "core/src/test/resources/fixtures/26.2");
    try (var files = Files.walk(source)) {
      for (Path p : files.toList()) {
        Path target = temp.resolve(source.relativize(p));
        if (Files.isDirectory(p)) Files.createDirectories(target);
        else Files.copy(p, target);
      }
    }
    assertEquals(0, run("init", "--with-dimensions=all").code);
    Path hosted = temp.resolve("hosted");
    Files.createDirectories(hosted);
    for (var d : WorldLayout.discover(temp).dimensions().keySet())
      try (var ignored =
          new org.worldgit.core.store.JGitStore(
              hosted.resolve(d.directoryName() + ".git"), true)) {}
    String url = hosted.toUri() + "{dimension}.git";
    var json = new ObjectMapper();
    assertEquals(0, run("remote", "add", "origin", url).code);
    assertEquals(0, run("remote", "set-url", "origin", url, "--dry-run").code);
    assertTrue(single(run("remote", "list", "--format=json").out).has("origin"));
    assertEquals(0, run("tag", "v1", "-m", "release", "--all").code);
    assertEquals(1, single(run("tag", "-l", "--format=json").out).size());
    assertEquals(0, run("push", "--all", "--tags", "--format=json").code);
    Path b = temp.resolve("copy");
    var cloned = run("clone", url, b.toString(), "--format=json");
    assertEquals(0, cloned.code, cloned.err);
    assertEquals(3, data(cloned.out).path("dimensions").size());
    var missingMain=run("clone",url,temp.resolve("missing-main").toString(),"--dimension=minecraft:the_nether","--format=json");
    assertEquals(1,missingMain.code);assertFalse(Files.exists(temp.resolve("missing-main")));
    var selectedClone=run("clone",url,temp.resolve("selected-clone").toString(),"--dimension=minecraft:overworld,minecraft:the_nether","--format=json");
    assertEquals(0,selectedClone.code,selectedClone.err);assertEquals(2,data(selectedClone.out).path("dimensions").size());
    setSection("minecraft:gold_block");
    assertEquals(0, run("commit", "-m", "update").code);
    assertEquals(0, run("push", "--all").code);
    var preview = runAt(b, "pull", "--dry-run", "--format=json");
    assertEquals(0, preview.code, preview.err);
    assertEquals("DRY_RUN", data(preview.out).path("result").path("state").asText());
    assertEquals(
        1,
        data(runAt(b, "status", "--format=json").out)
            .path("tracking")
            .get(0)
            .path("behind")
            .asInt());
    var applied = runAt(b, "pull", "--ff-only", "--format=json");
    assertEquals(0, applied.code, applied.err);
    assertTrue(data(applied.out).path("fastForward").asBoolean());
    assertEquals(0, runAt(b, "verify").code);
    var unchangedPull=runAt(b,"pull","--format=json");assertEquals(0,unchangedPull.code,unchangedPull.err);
    assertEquals("NO_OP",new ObjectMapper().readTree(unchangedPull.out).path("result").path("status").asText());
    setSection(b,"minecraft:diamond_block");assertEquals(0,runAt(b,"commit","-m","local divergence").code);
    setSection("minecraft:emerald_block");assertEquals(0,run("commit","-m","remote divergence").code);assertEquals(0,run("push","--all").code);
    var conflict=runAt(b,"pull","--format=json");assertEquals(2,conflict.code,conflict.err);
    var conflictJson=new ObjectMapper().readTree(conflict.out);assertEquals("PARTIAL",conflictJson.path("result").path("status").asText());
    assertFalse(conflictJson.path("result").path("error").isNull());assertTrue(conflictJson.path("data").path("result").path("remaining").asInt()>0);
    assertEquals(0,runAt(b,"merge","--abort").code);assertEquals(0,runAt(b,"verify").code);
    assertEquals(
        0, runAt(b, "export", "v1", temp.resolve("world.zip").toString(), "--format=json").code);
    assertEquals(0, runAt(b, "tag", "-d", "v1").code);
    assertEquals(0, runAt(b, "remote", "remove", "origin", "--all").code);
    assertEquals(0, single(runAt(b, "remote", "list", "--format=json").out).size());
  }

  @Test
  void fiveCommandsJsonAndColors() throws Exception {
    Path source =
        Path.of(
            System.getProperty("worldgit.projectRoot"), "core/src/test/resources/fixtures/26.2");
    try (var files = Files.walk(source)) {
      for (Path p : files.toList()) {
        Path dest = temp.resolve(source.relativize(p));
        if (Files.isDirectory(p)) Files.createDirectories(dest);
        else Files.copy(p, dest);
      }
    }
    Result init = run("init", "--template=survival", "--track=modified-only", "--format=json");
    assertEquals(0, init.code, init.err);
    assertEquals(1, data(init.out).path("dimensions").size());
    Result status = run("status", "--color=never");
    assertEquals(0, status.code, status.err);
    assertTrue(status.out.contains("+0 -0 ~0 !0"));
    assertFalse(status.out.contains("\033"));
    Result commit = run("commit", "-m", "nothing");
    assertEquals(0, commit.code, commit.err);
    assertTrue(commit.out.contains("沒有變動"));
    Result log = run("log", "--format=json");
    assertEquals(0, log.code, log.err);
    assertEquals(1, data(log.out).path("minecraft:overworld").path("nodes").size());
    Result diff = run("diff", "--format=json");
    assertEquals(0, diff.code, diff.err);
    assertEquals(1, data(diff.out).size());
    assertEquals(1, run("diff", "missing", "HEAD").code);
  }

  @Test
  void phase2CommandsJsonDryRunAndInvalidOptions() throws Exception {
    Path source =
        Path.of(
            System.getProperty("worldgit.projectRoot"), "core/src/test/resources/fixtures/26.2");
    try (var files = Files.walk(source)) {
      for (Path p : files.toList()) {
        Path target = temp.resolve(source.relativize(p));
        if (Files.isDirectory(p)) Files.createDirectories(target);
        else Files.copy(p, target);
      }
    }
    assertEquals(0, run("init").code);
    assertEquals(0, run("branch", "A").code);
    assertEquals(
        1,
        single(run("branch", "--format=json").out)
            .get(0)
            .path("commits")
            .size());
    var layout = WorldLayout.discover(temp);
    var pos = new org.worldgit.core.model.ChunkPos(0, 0);
    Path path = layout.dimensions().get(DimensionId.OVERWORLD).region().resolve("r.0.0.mca");
    Nbt.Compound raw;
    try (var r = new RegionFile(path)) {
      raw = r.read(0);
    }
    for (Object value : raw.list("sections").values()) {
      var s = (Nbt.Compound) value;
      if (s.integer("Y", 0) == 9)
        s.put(
            "block_states",
            new Nbt.Compound()
                .with(
                    "palette",
                    new Nbt.ListTag(
                        10, List.of(new Nbt.Compound().with("Name", "minecraft:gold_block")))));
    }
    RegionFile.update(path, Map.of(0, raw), 2);
    var dry = run("restore", "A", "--dry-run", "--format=json");
    assertEquals(0, dry.code, dry.err);
    assertEquals("DRY_RUN", data(dry.out).path("state").asText());
    assertEquals(1, run("switch", "A").code);
    assertEquals(0, run("stash", "push", "-m", "CLI stash").code);
    assertEquals(1, single(run("stash", "list", "--format=json").out).size());
    assertEquals(0, run("stash", "pop").code);
    assertEquals(1, run("verify", "A").code);
    assertEquals(0, run("reset", "--hard").code);
    assertEquals(0, run("verify", "A").code);
    assertEquals(0, run("switch", "A").code);
    assertEquals(1, run("restore", "A", "--chunks=0,0,1", "--box=0,0,0,1,1,1").code);
    assertEquals(0, run("switch", "main", "--dimension=minecraft:overworld").code);
    assertEquals(1, run("reset", "--hard", "A").code);
  }

  @Test
  void mergeConflictCliJsonDryRunAndAbort() throws Exception {
    Path source =
        Path.of(
            System.getProperty("worldgit.projectRoot"), "core/src/test/resources/fixtures/26.2");
    try (var paths = Files.walk(source)) {
      for (Path p : paths.toList()) {
        Path dest = temp.resolve(source.relativize(p));
        if (Files.isDirectory(p)) Files.createDirectories(dest);
        else Files.copy(p, dest);
      }
    }
    assertEquals(0, run("init").code);
    assertEquals(0, run("branch", "B").code);
    setSection("minecraft:gold_block");
    assertEquals(0, run("commit", "-m", "A").code);
    assertEquals(0, run("branch", "A").code);
    assertEquals(0, run("switch", "B").code);
    setSection("minecraft:diamond_block");
    assertEquals(0, run("commit", "-m", "B").code);
    assertEquals(0, run("switch", "A").code);
    var mapper = new ObjectMapper();
    var dry = run("--dimension","minecraft:overworld","merge", "B", "--dry-run", "--format=json");
    assertEquals(0, dry.code, dry.err);
    assertEquals("DRY_RUN", data(dry.out).path("state").asText());
    assertEquals(0, single(run("conflicts", "--format=json").out).size());
    var merge = run("merge", "B", "--format=json");
    assertEquals(2, merge.code, merge.err);
    assertEquals("MERGING", data(merge.out).path("state").asText());
    assertEquals(1, data(run("status", "--format=json").out).path("repositories").path("minecraft:overworld").path("remaining").asInt());
    assertTrue(run("conflicts", "--color=never").out.contains("! #1"));
    assertEquals(1, run("merge", "--continue").code);
    assertEquals(1, run("resolve", "all", "--ours", "--theirs").code);
    assertEquals(0, run("resolve", "all", "--theirs").code);
    assertEquals(0, run("verify", "B").code);
    assertEquals(0, run("resolve", "1", "--base").code);
    assertEquals(0, run("resolve", "1", "--ours").code);
    assertEquals(0, run("verify", "A").code);
    assertEquals(0, run("merge", "--abort").code);
    assertEquals(0, run("verify", "A").code);
    assertEquals(0, run("merge", "B", "--strategy-option=theirs", "--no-commit").code);
    assertEquals(0, run("merge", "--continue").code);
    assertEquals(0, run("verify").code);
  }

  private void setSection(String block) throws Exception {
    setSection(temp,block);
  }

  private void setSection(Path world,String block) throws Exception {
    var layout = WorldLayout.discover(world);
    Path path = layout.dimensions().get(DimensionId.OVERWORLD).region().resolve("r.0.0.mca");
    Nbt.Compound raw;
    try (var r = new RegionFile(path)) {
      raw = r.read(0);
    }
    for (Object value : raw.list("sections").values()) {
      var section = (Nbt.Compound) value;
      if (section.integer("Y", 0) == 9)
        section.put(
            "block_states",
            new Nbt.Compound()
                .with(
                    "palette",
                    new Nbt.ListTag(10, List.of(new Nbt.Compound().with("Name", block)))));
    }
    RegionFile.update(path, Map.of(0, raw), 3);
  }
}
