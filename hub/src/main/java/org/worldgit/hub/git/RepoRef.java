package org.worldgit.hub.git;

import org.worldgit.core.model.DimensionId;
import org.worldgit.hub.storage.NameRules;

/** 請求路徑 owner/world/維度目錄名[.git] 解析結果。 */
public record RepoRef(String owner, String world, DimensionId dimension) {
  /** @return 路徑格式不符時為 null */
  public static RepoRef parse(String name) {
    if (name == null) return null;
    String[] p = name.replaceAll("^/+", "").split("/");
    if (p.length != 3) return null;
    String dir = p[2].endsWith(".git") ? p[2].substring(0, p[2].length() - 4) : p[2];
    DimensionId dim = NameRules.dimensionFromDirectory(dir);
    if (dim == null || !NameRules.validSlug(p[0]) || !NameRules.validSlug(p[1])) return null;
    return new RepoRef(p[0], p[1], dim);
  }
}
