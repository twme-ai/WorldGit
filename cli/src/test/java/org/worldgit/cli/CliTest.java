package org.worldgit.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CliTest {
  @TempDir Path temp;

  private record Result(int code, String out, String err) {}

  private Result run(String... arguments) {
    var out = new StringWriter();
    var err = new StringWriter();
    var args = new ArrayList<>(List.of("--world", temp.toString()));
    args.addAll(List.of(arguments));
    return new Result(
        Wgit.execute(
            args.toArray(String[]::new), new PrintWriter(out, true), new PrintWriter(err, true)),
        out.toString(),
        err.toString());
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
}
