package org.worldgit.core.diff;

public enum ChangeKind {
  ADDED,
  REMOVED,
  MODIFIED,
  CONFLICT;

  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
