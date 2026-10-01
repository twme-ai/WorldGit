package org.worldgit.core.store;

import java.io.*;
import java.time.Instant;
import java.util.*;
import org.worldgit.core.model.*;
import org.worldgit.core.model.CommitMetadata.*;

public final class CommitTrailers {
  private CommitTrailers() {}

  public static String message(CommitMetadata m) {
    StringBuilder b = new StringBuilder(m.message().stripTrailing()).append("\n\n");
    append(b, "WorldGit-DataVersion", Integer.toString(m.mcDataVersion()));
    append(b, "WorldGit-Dimension", m.dimension().value());
    append(b, "WorldGit-Source", m.source().name().toLowerCase(Locale.ROOT));
    append(b, "WorldGit-Auto", Boolean.toString(m.auto()));
    append(b, "WorldGit-Snapshot", m.snapshot().toString());
    for (var c : m.contributions()) {
      append(b, "Co-authored-by", c.author().git());
      var n =
          new org.worldgit.core.anvil.Nbt.Compound()
              .with("name", c.author().name())
              .with("email", c.author().email())
              .with("cause", c.cause());
      if (c.playerId() != null) n.put("player", c.playerId().toString());
      n.put(
          "chunks",
          new org.worldgit.core.anvil.Nbt.ListTag(
              8, c.chunks().stream().sorted().map(p -> (Object) (p.x() + "," + p.z())).toList()));
      append(
          b,
          "WorldGit-Contribution",
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(org.worldgit.core.anvil.Nbt.write(n)));
    }
    return b.toString();
  }

  private static void append(StringBuilder b, String key, String value) {
    b.append(key).append(": ").append(value).append('\n');
  }

  public static CommitMetadata parse(Identity author, Identity committer, Instant time, String full)
      throws IOException {
    int split = full.stripTrailing().lastIndexOf("\n\n");
    if (split < 0) throw new IOException("缺少 WorldGit trailers");
    String message = full.substring(0, split);
    var values = new HashMap<String, String>();
    var contributions = new ArrayList<Contribution>();
    try {
      for (String line : full.substring(split + 2).split("\\R")) {
        int colon = line.indexOf(": ");
        if (colon < 0) throw new IOException("trailer 格式無效");
        String key = line.substring(0, colon), value = line.substring(colon + 2);
        if (key.equals("WorldGit-Contribution")) {
          var n = org.worldgit.core.anvil.Nbt.read(Base64.getUrlDecoder().decode(value));
          var chunks = new TreeSet<ChunkPos>();
          for (Object v : n.list("chunks").values()) {
            String[] p = ((String) v).split(",");
            chunks.add(new ChunkPos(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
          }
          contributions.add(
              new Contribution(
                  new Identity(n.string("name"), n.string("email")),
                  n.containsKey("player") ? UUID.fromString(n.string("player")) : null,
                  chunks,
                  n.string("cause")));
        } else if (key.startsWith("WorldGit-") && values.put(key, value) != null)
          throw new IOException("重複 trailer：" + key);
      }
      String auto = values.get("WorldGit-Auto");
      if (!Set.of("true", "false").contains(auto)) throw new IOException("WorldGit-Auto 無效");
      return new CommitMetadata(
          author,
          committer,
          message,
          time,
          Integer.parseInt(values.get("WorldGit-DataVersion")),
          new DimensionId(values.get("WorldGit-Dimension")),
          Source.valueOf(values.get("WorldGit-Source").toUpperCase(Locale.ROOT)),
          Boolean.parseBoolean(auto),
          UUID.fromString(values.get("WorldGit-Snapshot")),
          contributions);
    } catch (RuntimeException e) {
      throw new IOException("WorldGit trailers 無效：" + e.getMessage(), e);
    }
  }
}
