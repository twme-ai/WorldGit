package org.worldgit.paper;

import org.worldgit.core.anvil.Nbt;

/** 除原生 persistent=false 外，capture 再排除插件自己的 display 標記。 */
public final class TransientDisplays {
  private TransientDisplays() {}
  public static boolean excluded(Nbt.Compound entity) {
    String id=entity.string("id");
    return (id.equals("minecraft:text_display") || id.equals("minecraft:block_display"))
        && entity.list("Tags").values().stream().anyMatch(t->t.equals("worldgit_preview") || t.equals("worldgit_comment"));
  }
}
