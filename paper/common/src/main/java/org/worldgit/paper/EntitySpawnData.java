package org.worldgit.paper;

import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.EntitySnapshot;

/** 正規化省略 passenger Pos；LOAD 前補母實體位置，避免在原點／錯誤 Folia region 加入。 */
public final class EntitySpawnData {
  private EntitySpawnData() {}
  public static Nbt.Compound prepare(EntitySnapshot snapshot) {
    var data=snapshot.data();
    positions(data,data.list("Pos"));
    return data;
  }
  private static void positions(Nbt.Compound data,Nbt.ListTag parent) {
    data.putIfAbsent("Pos",Nbt.copy(parent));
    for(Object child:data.list("Passengers").values()) positions((Nbt.Compound)child,data.list("Pos"));
  }
}
