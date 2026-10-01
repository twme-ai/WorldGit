package org.worldgit.core.model;

import java.time.*;
import java.util.*;

/** 作者歸屬是 chunk 級事件摘要；author 是 git 主要作者，多人寫入 co-author trailers。 */
public record CommitMetadata(
    Identity author,
    Identity committer,
    String message,
    Instant time,
    int mcDataVersion,
    DimensionId dimension,
    Source source,
    boolean auto,
    UUID snapshot,
    List<Contribution> contributions) {
  public enum Source {
    CLI,
    PLUGIN,
    MOD,
    HUB
  }

  public record Identity(String name, String email) {
    public Identity {
      if (name == null
          || name.isBlank()
          || email == null
          || email.isBlank()
          || name.matches("(?s).*[\\r\\n<>].*")
          || email.matches("(?s).*[\\r\\n<>].*"))
        throw new IllegalArgumentException("作者姓名/email 無效");
    }

    public String git() {
      return name + " <" + email + ">";
    }
  }

  public record Contribution(Identity author, UUID playerId, Set<ChunkPos> chunks, String cause) {
    public Contribution {
      Objects.requireNonNull(author);
      chunks = Set.copyOf(chunks);
      if (cause == null || cause.matches("(?s).*[\\r\\n].*"))
        throw new IllegalArgumentException("cause");
    }
  }

  public CommitMetadata {
    Objects.requireNonNull(author);
    Objects.requireNonNull(committer);
    Objects.requireNonNull(time);
    Objects.requireNonNull(dimension);
    Objects.requireNonNull(source);
    Objects.requireNonNull(snapshot);
    contributions = List.copyOf(contributions);
    if (message == null || message.isBlank()) throw new IllegalArgumentException("commit 訊息不可空白");
    if (mcDataVersion < 0) throw new IllegalArgumentException("DataVersion");
  }
}
