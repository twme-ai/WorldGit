package org.worldgit.fabric.logic;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.version.NbtUpgrader;
import static org.junit.jupiter.api.Assertions.*;

class FabricMetadataProjectionTest {
  private static List<?> projectedPacks(List<String> source,List<String> disabled,List<String> target,List<String> targetDisabled) throws Exception {
    var nativeDfu=(NbtUpgrader)(type,input,from,to)->input;
    var backend=FabricMetadataProjection.wrap(nativeDfu,3700,new Nbt.Compound(),target,target,targetDisabled);
    var packs=new Nbt.Compound().with("Enabled",new Nbt.ListTag(8,new ArrayList<Object>(source)))
        .with("Disabled",new Nbt.ListTag(8,new ArrayList<Object>(disabled)));
    var input=new TreeMap<String,byte[]>();input.put("level.nbt",Nbt.write(new Nbt.Compound().with("DataPacks",packs)));
    return Nbt.read(backend.projectMetadata(input,3465,3700).get("level.nbt")).compound("DataPacks").list("Enabled").values();
  }
  @Test void projectsOnlyAutomaticFabricApiDefaultPacks() throws Exception {
    var old=List.of("vanilla","fabric");var modern=List.of("vanilla","fabric","fabric-convention-tags-v1");
    assertEquals(modern,projectedPacks(old,List.of(),modern,List.of()));
    assertEquals(old,projectedPacks(modern,List.of(),old,List.of()));
    assertEquals(modern,projectedPacks(List.of("vanilla"),List.of(),modern,List.of()));
    var client=List.of("vanilla","fabric","fabric-convention-tags-v1","fabric-gametest-api-v1");
    assertEquals(client,projectedPacks(old,List.of(),client,List.of()));
  }
  @Test void preservesCustomOrderAndExplicitPackChoices() throws Exception {
    var old=List.of("vanilla","fabric");var modern=List.of("vanilla","fabric","fabric-convention-tags-v1");
    var custom=List.of("vanilla","fabric","file/custom");
    assertEquals(custom,projectedPacks(custom,List.of(),modern,List.of()));
    var reordered=List.of("fabric","vanilla");
    assertEquals(reordered,projectedPacks(reordered,List.of(),modern,List.of()));
    assertEquals(old,projectedPacks(old,List.of("fabric-convention-tags-v1"),modern,List.of()));
    assertEquals(modern,projectedPacks(modern,List.of(),old,List.of("fabric-convention-tags-v1")));
    assertEquals(old,projectedPacks(old,List.of(),custom,List.of()));
  }
  @Test void addsOnlyMissingNativeRulesAndProjectsVanillaDisabledPacks() throws Exception {
    var calls=new AtomicInteger();NbtUpgrader nativeDfu=(type,input,from,to)->{calls.incrementAndGet();return input;};
    var rules=new Nbt.Compound().with("keepInventory","false").with("doMobSpawning","true");
    var backend=FabricMetadataProjection.wrap(nativeDfu,3700,rules,List.of("vanilla","trade_rebalance"));
    var packs=new Nbt.Compound().with("Enabled",new Nbt.ListTag(8,List.of("vanilla"))).with("Disabled",new Nbt.ListTag(8,List.of()));
    var source=new Nbt.Compound().with("GameRules",new Nbt.Compound().with("keepInventory","true")).with("DataPacks",packs);
    var input=new TreeMap<String,byte[]>();input.put("level.nbt",Nbt.write(source));
    var result=Nbt.read(backend.projectMetadata(input,3465,3700).get("level.nbt"));
    assertEquals("true",result.compound("GameRules").string("keepInventory"));
    assertEquals("true",result.compound("GameRules").string("doMobSpawning"));
    assertEquals(List.of("trade_rebalance"),result.compound("DataPacks").list("Disabled").values());
    assertArrayEquals(Nbt.write(source),input.get("level.nbt"));assertEquals(0,calls.get());
    source.compound("DataPacks").put("Disabled",new Nbt.ListTag(8,List.of("custom:package")));input.put("level.nbt",Nbt.write(source));
    result=Nbt.read(backend.projectMetadata(input,3465,3700).get("level.nbt"));
    assertEquals(List.of("custom:package"),result.compound("DataPacks").list("Disabled").values());
    var other=FabricMetadataProjection.wrap(nativeDfu,3700,rules,List.of("vanilla"));
    assertNotEquals(backend.projectionFingerprint(),other.projectionFingerprint());
  }
}
