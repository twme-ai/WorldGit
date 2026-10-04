package org.worldgit.fabric.logic;

import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.normalize.MetadataNormalizer;

/** 預檢使用與快照一致的可攜資料包契約，實際資料包與順序仍受保護。 */
public final class MetadataCompatibility {
  private MetadataCompatibility() {}

  public static boolean equivalent(String field, Object target, Object current) {
    if (field.equals("DataPacks") && target instanceof Nbt.Compound a && current instanceof Nbt.Compound b) {
      target = MetadataNormalizer.portablePacks(a);
      current = MetadataNormalizer.portablePacks(b);
    }
    return target == null || current == null
        ? target == current
        : Nbt.equal(new Nbt.Compound().with("v", target), new Nbt.Compound().with("v", current));
  }
}
