package org.worldgit.core.model;

import java.util.Objects;

public record DimensionId(String value) implements Comparable<DimensionId> {
  public static final DimensionId OVERWORLD = new DimensionId("minecraft:overworld");

  public DimensionId {
    Objects.requireNonNull(value);
    if (!value.matches("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*:[a-z0-9_-]+(?:[./][a-z0-9_-]+)*"))
      throw new IllegalArgumentException("維度 id 無效：" + value);
  }

  public String namespace() {
    return value.substring(0, value.indexOf(':'));
  }

  public String path() {
    return value.substring(value.indexOf(':') + 1);
  }

  /** '/' 用 '%' 編碼，防止自訂維度路徑穿越及 a/b 與 a.b 碰撞。 */
  public String directoryName() {
    return namespace().replace(".", "%2E") + "." + path().replace(".", "%2E").replace("/", "%2F");
  }

  @Override
  public int compareTo(DimensionId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
