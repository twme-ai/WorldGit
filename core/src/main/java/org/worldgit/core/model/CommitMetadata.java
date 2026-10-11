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
    List<Contribution> contributions,
    String mcVersion,
    org.worldgit.core.version.ConversionReport versionChange) {
  /** 相容既有呼叫端與舊 repo；版本名稱由已知 DataVersion 推斷。 */
  public CommitMetadata(Identity author, Identity committer, String message, Instant time,
      int mcDataVersion, DimensionId dimension, Source source, boolean auto, UUID snapshot,
      List<Contribution> contributions) {
    this(author, committer, message, time, mcDataVersion, dimension, source, auto, snapshot, contributions,
        org.worldgit.core.version.MinecraftVersion.name(mcDataVersion), null);
  }
  public CommitMetadata withVersionChange(org.worldgit.core.version.ConversionReport report) {
    return new CommitMetadata(author,committer,message,time,mcDataVersion,dimension,source,auto,snapshot,contributions,mcVersion,report);
  }
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
    if(mcVersion == null || mcVersion.isBlank()) mcVersion = org.worldgit.core.version.MinecraftVersion.name(mcDataVersion);
    if(mcVersion.matches("(?s).*[\\r\\n].*")) throw new IllegalArgumentException("Minecraft version 無效");
    if (message == null || message.isBlank()) throw new IllegalArgumentException("commit 訊息不可空白");
    if (mcDataVersion < 0) throw new IllegalArgumentException("DataVersion");
    if(versionChange!=null&&versionChange.to()!=mcDataVersion)throw new IllegalArgumentException("版本報告目標與 commit DataVersion 不符");
  }
}
