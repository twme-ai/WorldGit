package org.worldgit.core.version;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.worldgit.core.anvil.Nbt;

/** 一個目標版本一個工作 JVM；close 必定停止子程序，失敗不發布輸出。 */
public final class DfuProcess implements NbtUpgrader {
  private final Process process;
  private final DataInputStream in;
  private final DataOutputStream out;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Path log;

  public DfuProcess(MinecraftVersion target) throws IOException {
    Path dir = MojangRuntime.prepare(target);
    log = dir.resolve("dfu.log");
    Path classpath = workerClasspath(MojangRuntime.cache());
    process =
        new ProcessBuilder(
                MojangRuntime.java(target).toString(),
                "-Xmx1G",
                "-XX:ActiveProcessorCount=3",
                "-cp",
                classpath.toString(),
                DfuWorker.class.getName(),
                dir.toString())
            .directory(dir.toFile())
            .redirectError(log.toFile())
            .start();
    in = new DataInputStream(process.getInputStream());
    out = new DataOutputStream(process.getOutputStream());
    try {
      if (read(() -> in.readInt()) != 0x57474446) throw new IOException("DFU 握手無效");
    } catch (IOException e) {
      close();
      throw e;
    }
  }

  /** Worker 僅依賴 JDK；從目前 classloader 取自有 bytecode，兼容 Boot 等巢狀 jar。 */
  static Path workerClasspath(Path cache) throws IOException {
    String resource = DfuWorker.class.getName().replace('.', '/') + ".class";
    byte[] bytes;
    try (var input = DfuWorker.class.getResourceAsStream("/" + resource)) {
      if (input == null) throw new IOException("發行產物缺少 DFU worker");
      bytes = input.readNBytes(256 * 1024 + 1);
      if (bytes.length > 256 * 1024) throw new IOException("DFU worker 超過大小限制");
    }
    String hash = MojangRuntime.sha1(bytes);
    Path root = cache.toAbsolutePath().resolve("workers").resolve(hash);
    Path file = root.resolve(resource);
    if (!Files.isRegularFile(file) || Files.size(file) != bytes.length
        || !MojangRuntime.sha1(Files.readAllBytes(file)).equals(hash)) {
      Files.createDirectories(file.getParent());
      org.worldgit.core.anvil.RegionFile.atomicWrite(file, bytes);
    }
    return root;
  }

  private <T> T read(Callable<T> call) throws IOException {
    var future = io.submit(call);
    long start = System.nanoTime();
    try {
      for (; ; ) {
        org.worldgit.core.operation.OperationProgress.check();
        try {
          return future.get(250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException waiting) {
          if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(180)) throw waiting;
        }
      }
    } catch (InterruptedIOException e) {
      process.destroyForcibly();
      throw e;
    } catch (Exception e) {
      process.destroyForcibly();
      throw new IOException("原版 DFU 失敗；證據：" + log, e);
    }
  }

  @Override
  public synchronized Nbt.Compound upgrade(String type, Nbt.Compound input, int from, int to)
      throws IOException {
    if (from >= to) return Nbt.copy(input);
    byte[] b = Nbt.write(input);
    out.writeUTF(type);
    out.writeInt(from);
    out.writeInt(to);
    out.writeInt(b.length);
    out.write(b);
    out.flush();
    byte[] bytes =
        read(
            () -> {
              int size = in.readInt();
              if (size < 0 || size > Nbt.MAX_BYTES) throw new IOException("DFU 長度無效");
              byte[] output = in.readNBytes(size);
              if (output.length != size) throw new EOFException();
              return output;
            });
    return Nbt.read(bytes);
  }

  @Override
  public void close() throws IOException {
    try {
      out.close();
    } finally {
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
      } finally {
        io.shutdownNow();
        in.close();
      }
    }
  }
}
