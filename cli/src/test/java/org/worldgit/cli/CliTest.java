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
    return new Result(
        Wgit.execute(
            args.toArray(String[]::new), new PrintWriter(out, true), new PrintWriter(err, true)),
        out.toString(),
        err.toString());
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
    assertEquals(0, run("init").code);
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
    assertTrue(json.readTree(run("remote", "list", "--format=json").out).has("origin"));
    assertEquals(0, run("tag", "v1", "-m", "release").code);
    assertEquals(1, json.readTree(run("tag", "-l", "--format=json").out).size());
    assertEquals(0, run("push", "--tags", "--format=json").code);
    Path b = temp.resolve("copy");
    var cloned = run("clone", url, b.toString(), "--format=json");
    assertEquals(0, cloned.code, cloned.err);
    assertEquals(3, json.readTree(cloned.out).path("dimensions").size());
    setSection("minecraft:gold_block");
    assertEquals(0, run("commit", "-m", "update").code);
    assertEquals(0, run("push").code);
    var preview = runAt(b, "pull", "--dry-run", "--format=json");
    assertEquals(0, preview.code, preview.err);
    assertEquals("DRY_RUN", json.readTree(preview.out).path("result").path("state").asText());
    assertEquals(
        1,
        json.readTree(runAt(b, "status", "--format=json").out)
            .path("tracking")
            .get(0)
            .path("behind")
            .asInt());
    var applied = runAt(b, "pull", "--ff-only", "--format=json");
    assertEquals(0, applied.code, applied.err);
    assertTrue(json.readTree(applied.out).path("fastForward").asBoolean());
    assertEquals(0, runAt(b, "verify").code);
    assertEquals(
        0, runAt(b, "export", "v1", temp.resolve("world.zip").toString(), "--format=json").code);
    assertEquals(0, runAt(b, "tag", "-d", "v1").code);
    assertEquals(0, runAt(b, "remote", "remove", "origin").code);
    assertEquals(0, json.readTree(runAt(b, "remote", "list", "--format=json").out).size());
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
    assertEquals(3, new ObjectMapper().readTree(init.out).path("dimensions").size());
    Result status = run("status", "--color=never");
    assertEquals(0, status.code, status.err);
    assertTrue(status.out.contains("+0 -0 ~0 !0"));
    assertFalse(status.out.contains("\033"));
    Result commit = run("commit", "-m", "nothing");
    assertEquals(0, commit.code, commit.err);
    assertTrue(commit.out.contains("沒有變動"));
    Result log = run("log", "--format=json");
    assertEquals(0, log.code, log.err);
    assertEquals(1, new ObjectMapper().readTree(log.out).size());
    Result diff = run("diff", "--format=json");
    assertEquals(0, diff.code, diff.err);
    assertEquals(3, new ObjectMapper().readTree(diff.out).size());
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
        3,
        new ObjectMapper()
            .readTree(run("branch", "--format=json").out)
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
    assertEquals("DRY_RUN", new ObjectMapper().readTree(dry.out).path("state").asText());
    assertEquals(1, run("switch", "A").code);
    assertEquals(0, run("stash", "push", "-m", "CLI stash").code);
    assertEquals(1, new ObjectMapper().readTree(run("stash", "list", "--format=json").out).size());
    assertEquals(0, run("stash", "pop").code);
    assertEquals(1, run("verify", "A").code);
    assertEquals(0, run("reset", "--hard").code);
    assertEquals(0, run("verify", "A").code);
    assertEquals(0, run("switch", "A").code);
    assertEquals(1, run("restore", "A", "--chunks=0,0,1", "--box=0,0,0,1,1,1").code);
    assertEquals(1, run("switch", "main", "--dimension=minecraft:overworld").code);
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
    var dry = run("merge", "B", "--dry-run", "--format=json");
    assertEquals(0, dry.code, dry.err);
    assertEquals("DRY_RUN", mapper.readTree(dry.out).path("state").asText());
    assertEquals(0, mapper.readTree(run("conflicts", "--format=json").out).size());
    var merge = run("merge", "B", "--format=json");
    assertEquals(0, merge.code, merge.err);
    assertEquals("MERGING", mapper.readTree(merge.out).path("state").asText());
    assertEquals(1, mapper.readTree(run("status", "--format=json").out).path("remaining").asInt());
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
    var layout = WorldLayout.discover(temp);
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
