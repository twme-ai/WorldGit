package org.worldgit.hub.collaboration;
import java.util.*;
/** 所有 Phase 4 列表的分頁上限 100；offset 防止無界跳頁。 */
public record Page<T>(List<T> items,int offset,int limit,boolean hasMore) {
  public static int limit(int v){if(v<1 || v>100)throw new IllegalArgumentException("limit 必須 1–100");return v;}
  public static int offset(int v){if(v<0 || v>10000)throw new IllegalArgumentException("offset 必須 0–10000");return v;}
  public static <T> Page<T> of(List<T> rows,int offset,int limit){boolean more=rows.size()>limit;return new Page<>(List.copyOf(rows.subList(0,Math.min(rows.size(),limit))),offset,limit,more);}
}
