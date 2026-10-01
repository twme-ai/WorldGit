package org.worldgit.hub.storage;

import java.util.regex.Pattern;
import org.worldgit.core.model.DimensionId;

/** owner／world 的 slug 規則與維度 repo 目錄名的反向解析。 */
public final class NameRules {
  private NameRules() {}

  private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9_-]{0,38}[a-z0-9])?");
  private static final Pattern RESERVED = Pattern.compile("(?:api|git|assets|actuator|login|new|static|settings|users)");

  public static boolean validSlug(String s) {
    return s != null && SLUG.matcher(s).matches() && !RESERVED.matcher(s).matches();
  }

  public static void requireSlug(String s) {
    if (s == null || !SLUG.matcher(s).matches()) throw new IllegalArgumentException("名稱無效：" + s);
  }

  /** {@link DimensionId#directoryName()} 的反向：第一個 '.' 分隔 namespace 與 path，%2E／%2F 還原。 */
  public static DimensionId dimensionFromDirectory(String dir) {
    int dot = dir.indexOf('.');
    if (dot <= 0 || dot == dir.length() - 1) return null;
    try {
      String ns = dir.substring(0, dot).replace("%2E", ".");
      String path = dir.substring(dot + 1).replace("%2E", ".").replace("%2F", "/");
      DimensionId id = new DimensionId(ns + ":" + path);
      return id.directoryName().equals(dir) ? id : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
