package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DfuProcessPackagingTest {
  @TempDir Path temp;

  @Test
  void childJvmStartsWorkerWithoutTheParentApplicationClasspath() throws Exception {
    Path classpath = DfuProcess.workerClasspath(temp.resolve("worker cache"));
    Path vanilla = temp.resolve("vanilla");
    Files.createDirectories(vanilla.resolve("runtime"));
    var process = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", classpath.toString(), DfuWorker.class.getName(), vanilla.toString())
        .redirectErrorStream(true).start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS));
      String output = new String(process.getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      // The self-contained worker reached the vanilla loader; this fixture has no Mojang jar.
      assertEquals(1, process.exitValue());
      assertTrue(output.contains("ClassNotFoundException: net.minecraft.SharedConstants"), output);
      assertFalse(output.contains("Could not find or load main class"), output);
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void corruptedCachedWorkerIsReplacedByThePackagedBytecode() throws Exception {
    Path cache = temp.resolve("cache");
    Path root = DfuProcess.workerClasspath(cache);
    Path file = root.resolve(DfuWorker.class.getName().replace('.', '/') + ".class");
    byte[] original = Files.readAllBytes(file);
    byte[] corrupted = original.clone();
    corrupted[0] ^= 1;
    Files.write(file, corrupted);
    assertEquals(root, DfuProcess.workerClasspath(cache));
    assertArrayEquals(original, Files.readAllBytes(file));
  }
}
