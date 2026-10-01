package org.worldgit.hub.account;

/** 帳號／世界的資料列。 */
public final class Models {
  private Models() {}

  public record User(String id, String ownerId, String username, boolean admin) {}

  public record WorldRow(
      String id,
      String ownerId,
      String ownerSlug,
      String slug,
      String displayName,
      String description,
      boolean isPublic,
      long createdAt) {}

  public enum Role {
    NONE,
    READER,
    WRITER,
    OWNER;

    public boolean atLeast(Role other) {
      return ordinal() >= other.ordinal();
    }
  }
}
