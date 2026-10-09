package org.worldgit.core.capture;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.worldgit.core.anvil.RegionFile;
import org.worldgit.core.model.ChunkPos;

/** 可丟棄的本機 binary cache（不是設定）；綁定 HEAD、規則及 working snapshot tree。 */
public record ScanIndex(
    String head, String rules, String tree, long scannedAt, Map<ChunkPos, Stamp> chunks) {
  public record Stamp(
      String terrainFile,
      int terrainTime,
      int terrainLocation,
      String terrainHash,
      String entityFile,
      int entityTime,
      int entityLocation,
      String entityHash) {}

  public ScanIndex {
    chunks = Map.copyOf(chunks);
  }

  public static ScanIndex empty() {
    return new ScanIndex("", "", "", 0, Map.of());
  }

  public static ScanIndex read(Path path) throws IOException {
    if (!Files.exists(path)) return empty();
    if (Files.size(path) > 128L * 1024 * 1024) throw new IOException("index 太大");
    byte[] encoded;
    try(var input=Files.newInputStream(path)) {
      encoded=input.readNBytes(128*1024*1024+1);
      if(encoded.length>128*1024*1024) throw new IOException("index 太大");
    }
    if(encoded.length<36) throw new IOException("index 截斷");
    int length=encoded.length-32;
    var digest=digest();digest.update(encoded,0,length);
    if(!MessageDigest.isEqual(digest.digest(),Arrays.copyOfRange(encoded,length,encoded.length)))
      throw new IOException("index checksum 無效");
    try (var in = new DataInputStream(new ByteArrayInputStream(encoded,0,length))) {
      if (in.readInt() != 0x57474932) throw new IOException("index 版本無效");
      String head = in.readUTF(), rules = in.readUTF(), tree = in.readUTF();
      long scannedAt = in.readLong();
      int n = in.readInt();
      if (n < 0 || n > 1_000_000) throw new IOException("index chunk 數量無效");
      var chunks = new HashMap<ChunkPos, Stamp>();
      for (int i = 0; i < n; i++) {
        var pos = new ChunkPos(in.readInt(), in.readInt());
        var s =
            new Stamp(
                in.readUTF(),
                in.readInt(),
                in.readInt(),
                in.readUTF(),
                in.readUTF(),
                in.readInt(),
                in.readInt(),
                in.readUTF());
        if (chunks.put(pos, s) != null) throw new IOException("index 重複 chunk");
      }
      if (in.read() != -1) throw new IOException("index 多餘資料");
      return new ScanIndex(head, rules, tree, scannedAt, chunks);
    }
  }

  public void save(Path path) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeInt(0x57474932);
    out.writeUTF(head);
    out.writeUTF(rules);
    out.writeUTF(tree);
    out.writeLong(scannedAt);
    out.writeInt(chunks.size());
    for (var e : new TreeMap<>(chunks).entrySet()) {
      out.writeInt(e.getKey().x());
      out.writeInt(e.getKey().z());
      var s = e.getValue();
      out.writeUTF(s.terrainFile);
      out.writeInt(s.terrainTime);
      out.writeInt(s.terrainLocation);
      out.writeUTF(s.terrainHash);
      out.writeUTF(s.entityFile);
      out.writeInt(s.entityTime);
      out.writeInt(s.entityLocation);
      out.writeUTF(s.entityHash);
    }
    out.flush();
    byte[] payload=bytes.toByteArray();
    out.write(digest().digest(payload));out.flush();
    RegionFile.atomicWrite(path, bytes.toByteArray());
  }

  private static MessageDigest digest() {
    try {return MessageDigest.getInstance("SHA-256");}
    catch(NoSuchAlgorithmException e) {throw new AssertionError(e);}
  }
}
