package org.worldgit.hub.assets;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 客戶端資源的來源：client jar（ZIP）或已解開的目錄。路徑以 jar 內路徑表示，例如 assets/minecraft/blockstates/stone.json。 */
interface ResourceSource extends Closeable {
  boolean exists(String path);

  byte[] read(String path) throws IOException;

  /** prefix 底下的所有檔案（遞迴，完整路徑，已排序）。 */
  List<String> list(String prefix) throws IOException;

  static ResourceSource zip(Path jar) throws IOException {
    ZipFile zip = new ZipFile(jar.toFile());
    return new ResourceSource() {
      @Override
      public boolean exists(String path) {
        return zip.getEntry(path) != null;
      }

      @Override
      public byte[] read(String path) throws IOException {
        ZipEntry e = zip.getEntry(path);
        if (e == null) throw new NoSuchFileException(path);
        try (var in = zip.getInputStream(e)) {
          return in.readAllBytes();
        }
      }

      @Override
      public List<String> list(String prefix) {
        var out = new ArrayList<String>();
        for (var en = zip.entries(); en.hasMoreElements(); ) {
          ZipEntry e = en.nextElement();
          if (!e.isDirectory() && e.getName().startsWith(prefix)) out.add(e.getName());
        }
        Collections.sort(out);
        return out;
      }

      @Override
      public void close() throws IOException {
        zip.close();
      }
    };
  }

  static ResourceSource directory(Path root) {
    return new ResourceSource() {
      @Override
      public boolean exists(String path) {
        return Files.isRegularFile(root.resolve(path));
      }

      @Override
      public byte[] read(String path) throws IOException {
        return Files.readAllBytes(root.resolve(path));
      }

      @Override
      public List<String> list(String prefix) throws IOException {
        Path dir = root.resolve(prefix.endsWith("/") ? prefix : prefix.substring(0, Math.max(0, prefix.lastIndexOf('/') + 1)));
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
          return s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
              .filter(n -> n.startsWith(prefix)).sorted().toList();
        }
      }

      @Override
      public void close() {}
    };
  }
}
