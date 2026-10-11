package org.worldgit.core.version;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** 原版 DFU 隔離工作程序；stdout 只承載長度限制的二進位協定。 */
public final class DfuWorker {
  private final ClassLoader loader;
  private final Map<String, String> classes = new HashMap<>(), members = new HashMap<>();
  private final Object fixer, ops, accounter;
  private final Method read, write, update;
  private final Constructor<?> dynamic;

  public DfuWorker(Path dir) throws Exception {
    Path mappings = dir.resolve("server.txt");
    if (Files.isRegularFile(mappings)) {
      String owner = "";
      for (String line : Files.readAllLines(mappings)) {
        if (line.startsWith("#") || !line.contains(" -> ")) continue;
        String[] p = line.split(" -> ", 2);
        if (!line.startsWith(" ")) {
          owner = p[0];
          classes.put(owner, p[1].replace(":", ""));
        } else {
          String signature = p[0].trim().replaceFirst("^(?:\\d+:\\d+:)", "");
          int par = signature.indexOf('(');
          String name =
              par < 0
                  ? signature.substring(signature.lastIndexOf(' ') + 1)
                  : signature
                      .substring(0, par)
                      .substring(signature.substring(0, par).lastIndexOf(' ') + 1);
          members.put(owner + "#" + name, p[1]);
        }
      }
    }
    var urls = new ArrayList<URL>();
    try (var paths = Files.walk(dir.resolve("runtime"))) {
      for (Path p : paths.filter(p -> p.toString().endsWith(".jar")).sorted().toList())
        urls.add(p.toUri().toURL());
    }
    loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    Thread.currentThread().setContextClassLoader(loader);
    method("net.minecraft.SharedConstants", "tryDetectVersion", 0).invoke(null);
    fixer = method("net.minecraft.util.datafix.DataFixers", "getDataFixer", 0).invoke(null);
    ops = field("net.minecraft.nbt.NbtOps", "INSTANCE").get(null);
    accounter = method("net.minecraft.nbt.NbtAccounter", "unlimitedHeap", 0).invoke(null);
    read =
        Arrays.stream(clazz("net.minecraft.nbt.NbtIo").getMethods())
            .filter(
                m ->
                    m.getName().equals(member("net.minecraft.nbt.NbtIo", "read"))
                        && Arrays.equals(
                            m.getParameterTypes(),
                            new Class<?>[] {
                              DataInput.class, clazzUnchecked("net.minecraft.nbt.NbtAccounter")
                            }))
            .findFirst()
            .orElseThrow();
    write =
        Arrays.stream(clazz("net.minecraft.nbt.NbtIo").getMethods())
            .filter(
                m ->
                    m.getName().equals(member("net.minecraft.nbt.NbtIo", "write"))
                        && Arrays.equals(
                            m.getParameterTypes(),
                            new Class<?>[] {
                              clazzUnchecked("net.minecraft.nbt.CompoundTag"), DataOutput.class
                            }))
            .findFirst()
            .orElseThrow();
    dynamic =
        clazz("com.mojang.serialization.Dynamic")
            .getConstructor(clazz("com.mojang.serialization.DynamicOps"), Object.class);
    update =
        Arrays.stream(clazz("com.mojang.datafixers.DataFixer").getMethods())
            .filter(m -> m.getName().equals("update") && m.getParameterCount() == 4)
            .findFirst()
            .orElseThrow();
  }

  private Class<?> clazz(String name) throws ClassNotFoundException {
    return loader.loadClass(classes.getOrDefault(name, name));
  }

  private Class<?> clazzUnchecked(String name) {
    try {
      return clazz(name);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String member(String owner, String name) {
    return members.getOrDefault(owner + "#" + name, name);
  }

  private Field field(String owner, String name) throws Exception {
    return clazz(owner).getField(member(owner, name));
  }

  private Method method(String owner, String name, int n) throws Exception {
    return Arrays.stream(clazz(owner).getMethods())
        .filter(m -> m.getName().equals(member(owner, name)) && m.getParameterCount() == n)
        .findFirst()
        .orElseThrow();
  }

  public byte[] convert(String type, byte[] input, int from, int to) throws Exception {
    Object tag = read.invoke(null, new DataInputStream(new ByteArrayInputStream(input)), accounter);
    Object ref;
    try {
      ref = field("net.minecraft.util.datafix.fixes.References", type).get(null);
    } catch (NoSuchFieldException absent) {
      if (!type.startsWith("SAVED_DATA_")) throw absent;
      try {
        if (!type.equals("SAVED_DATA_TICKETS")) throw absent;
        ref =
            field("net.minecraft.util.datafix.fixes.References", "SAVED_DATA_FORCED_CHUNKS")
                .get(null);
      } catch (NoSuchFieldException older) {
        ref = field("net.minecraft.util.datafix.fixes.References", "SAVED_DATA").get(null);
      }
    }
    Object result = update.invoke(fixer, ref, dynamic.newInstance(ops, tag), from, to);
    Object output = result.getClass().getMethod("getValue").invoke(result);
    var bytes = new ByteArrayOutputStream();
    write.invoke(null, output, new DataOutputStream(bytes));
    return bytes.toByteArray();
  }

  public static void main(String[] args) throws Exception {
    var protocol = new DataOutputStream(new FileOutputStream(FileDescriptor.out));
    System.setOut(System.err);
    var worker = new DfuWorker(Path.of(args[0]));
    protocol.writeInt(0x57474446);
    protocol.flush();
    var in = new DataInputStream(System.in);
    try {
      for (; ; ) {
        String type = in.readUTF();
        int from = in.readInt(), to = in.readInt(), size = in.readInt();
        if (size < 0 || size > 32 * 1024 * 1024) throw new IOException("DFU input 大小無效");
        byte[] input = in.readNBytes(size);
        if (input.length != size) throw new EOFException();
        byte[] output = worker.convert(type, input, from, to);
        if (output.length > 32 * 1024 * 1024) throw new IOException("DFU output 超過限制");
        protocol.writeInt(output.length);
        protocol.write(output);
        protocol.flush();
      }
    } catch (EOFException done) {
    }
  }
}
