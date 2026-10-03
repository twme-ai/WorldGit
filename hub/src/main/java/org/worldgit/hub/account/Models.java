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
    ADMIN,
    OWNER;

    public static Role parse(String name) {
      if (name == null) throw new IllegalArgumentException("需要角色");
      return switch (name.toLowerCase(java.util.Locale.ROOT)) {
        case "read", "reader" -> READER;
        case "write", "writer" -> WRITER;
        case "admin" -> ADMIN;
        case "owner" -> OWNER;
        case "none" -> NONE;
        default -> throw new IllegalArgumentException("角色無效");
      };
    }
    public String api() { return switch (this) { case READER -> "read"; case WRITER -> "write"; default -> name().toLowerCase(java.util.Locale.ROOT); }; }


    public boolean atLeast(Role other) {
      return ordinal() >= other.ordinal();
    }
  }
}
