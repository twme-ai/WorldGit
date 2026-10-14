package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.normalize.MetadataNormalizer;

class DragonGatewayNativeFormsTest {
  private static Nbt.Compound level(int version,Object gateways) {
    return new Nbt.Compound().with("DataVersion",version).with("DragonFight",new Nbt.Compound()
        .with("DragonKilled",(byte)1).with("PreviouslyKilled",(byte)1).with("NeedsStateScanning",(byte)0)
        .with("Dragon",new int[]{1,2,3,4}).with("Gateways",gateways)
        .with("ExitPortalLocation",new Nbt.Compound().with("X",12).with("Y",64).with("Z",-21)));
  }
  private static byte[] normalized(Nbt.Compound input) throws Exception {
    return MetadataNormalizer.nativeLayout(Map.of("level.nbt",Nbt.write(input))).get("level.nbt");
  }
  @Test void dfuIntArrayMatchesNativeOrderedListWithoutLosingDragonStateOrMutatingInput() throws Exception {
    for(int version:List.of(3465,3700,3839,3955,4189,4440,4671)) {
      var dfu=level(version,new int[]{12,3,18,3});
      var nativeLevel=level(version,new Nbt.ListTag(3,List.of(12,3,18,3)));
      assertArrayEquals(Nbt.write(nativeLevel),normalized(dfu));
      assertArrayEquals(Nbt.write(nativeLevel),normalized(nativeLevel));
      assertInstanceOf(int[].class,dfu.compound("DragonFight").get("Gateways"));
    }
  }
  @Test void consumedGatewaysMatchTheNativeEmptyList() throws Exception {
    assertArrayEquals(Nbt.write(level(4671,new Nbt.ListTag(0,List.of()))),normalized(level(4671,new int[0])));
  }
  @Test void unknownSchemaVersionAndWrongTypeRemainUnchanged() throws Exception {
    var custom=level(4671,new int[]{2,1});custom.compound("DragonFight").put("custom:keep",42);
    for(var input:List.of(custom,level(4790,new int[]{2,1}),level(0,new int[]{2,1}),
        level(4671,new Nbt.ListTag(8,List.of("2","1")))))
      assertArrayEquals(Nbt.write(input),normalized(input));
  }

  private static final String SAVED = "minecraft.the_end.saved."
      + Base64.getUrlEncoder().withoutPadding().encodeToString(
          "data/minecraft/ender_dragon_fight.dat".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".nbt";
  private static Nbt.Compound saved(int version, Object gateways) {
    return new Nbt.Compound().with("DataVersion", version).with("data", new Nbt.Compound()
        .with("dragon_killed", (byte)1).with("previously_killed", (byte)1)
        .with("needs_state_scanning", (byte)0).with("dragon_uuid", new int[]{1, 2, 3, 4})
        .with("exit_portal_location", new int[]{12, 64, -21}).with("gateways", gateways));
  }
  @Test void namespacedNativeArrayAndDfuListKeepIdenticalOrderedDragonState() throws Exception {
    for (int version : List.of(4790, 4903, 5023)) {
      var nativeData = saved(version, new int[]{12, 3, 18, 3});
      var dfu = saved(version, new Nbt.ListTag(3, List.of(12, 3, 18, 3)));
      assertArrayEquals(Nbt.write(dfu), MetadataNormalizer.nativeLayout(Map.of(SAVED, Nbt.write(nativeData))).get(SAVED));
      assertArrayEquals(Nbt.write(dfu), MetadataNormalizer.nativeLayout(Map.of(SAVED, Nbt.write(dfu))).get(SAVED));
      assertInstanceOf(int[].class, nativeData.compound("data").get("gateways"));
      assertFalse(Arrays.equals(
          MetadataNormalizer.nativeLayout(Map.of(SAVED, Nbt.write(saved(version, new int[]{3, 12, 18, 3})))).get(SAVED),
          Nbt.write(dfu)));
    }
  }
  @Test void namespacedConsumedGatewaysRemainExplicitAndMatchEmptyDfuList() throws Exception {
    assertArrayEquals(Nbt.write(saved(5023, new Nbt.ListTag(0, List.of()))),
        MetadataNormalizer.nativeLayout(Map.of(SAVED, Nbt.write(saved(5023, new int[0])))).get(SAVED));
  }
  @Test void namespacedUnknownSchemaVersionPathAndWrongTypeArePreserved() throws Exception {
    var custom = saved(5023, new int[]{2, 1});custom.compound("data").put("custom:keep", 42);
    var wrapper = saved(5023, new int[]{2, 1});wrapper.put("custom:keep", 42);
    for (var input : List.of(custom, wrapper, saved(4671, new int[]{2, 1}), saved(5024, new int[]{2, 1}),
        saved(5023, new Nbt.ListTag(8, List.of("2", "1")))))
      assertArrayEquals(Nbt.write(input), MetadataNormalizer.nativeLayout(Map.of(SAVED, Nbt.write(input))).get(SAVED));
    var modded = SAVED.replace("ZGF0YS9taW5lY3JhZnQv", "ZGF0YS9jdXN0b20v");
    var input = Nbt.write(saved(5023, new int[]{2, 1}));
    assertArrayEquals(input, MetadataNormalizer.nativeLayout(Map.of(modded, input)).get(modded));
  }
}
