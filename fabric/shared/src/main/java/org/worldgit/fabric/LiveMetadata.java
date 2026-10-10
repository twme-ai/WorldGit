package org.worldgit.fabric;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.ApplyPlan;
import org.worldgit.core.apply.OfflineApplier;

/** 線上不允許覆寫遊戲仍快取的 saved-data。先完整預檢，避免寫到一半才拒絕。 */
final class LiveMetadata {
    static void validate(ServerRuntime runtime, ApplyPlan plan) throws IOException {
        for(var entry:plan.worldMeta().entrySet()) {
            if(!entry.getKey().equals("level.nbt") || entry.getValue()==null)
                throw new IOException("此世界設定需離線 restore："+org.worldgit.core.anvil.SavedData.displayName(entry.getKey()));
            var target=Nbt.read(entry.getValue());
            var current=org.worldgit.core.anvil.WorldLayout.readGzip(runtime.worldRoot().resolve("level.dat")).compound("Data");
            for(String field:OfflineApplier.LEVEL_FIELDS)
                if(!supported(field) && !org.worldgit.fabric.logic.MetadataCompatibility.equivalent(field,target.get(field),current.get(field)))
                    throw new IOException("此世界設定需離線 restore："+field);
        }
    }
    private static boolean supported(String field) {
        return field.startsWith("Border") || Set.of("GameRules","game_rules","SpawnX","SpawnY","SpawnZ","SpawnAngle","spawn","Difficulty","DifficultyLocked").contains(field);
    }
    static void apply(ServerRuntime runtime, ApplyPlan plan) throws IOException {
        for(var entry:plan.worldMeta().entrySet()) Platform.applyLevelMetadata(runtime.server(),FabricApply.vanilla(Nbt.read(entry.getValue())));
    }
}
