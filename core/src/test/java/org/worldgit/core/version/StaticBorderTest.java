package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.normalize.MetadataNormalizer;

class StaticBorderTest {
  private static Nbt.Compound border(long time,double target) {
    return new Nbt.Compound().with("center_x",12d).with("center_z",-42d).with("size",1234d)
        .with("safe_zone",3d).with("damage_per_block",.4d).with("warning_blocks",7)
        .with("warning_time",18).with("lerp_time",time).with("lerp_target",target);
  }
  private static byte[] saved(int version,Nbt.Compound border) throws Exception {
    return Nbt.write(new Nbt.Compound().with("DataVersion",version).with("data",border));
  }
  @Test void inactiveDfuTargetMatchesNativeResaveWithoutChangingBorderSettingsOrInput() throws Exception {
    for(int version:List.of(4671,4790,4903,5023)) {
      byte[] dfu=saved(version,border(0,0)),nativeBytes=saved(version,border(0,1234));
      var input=Map.of("world_border.dat.nbt",dfu,"minecraft.overworld.world_border.dat.nbt",dfu);
      var out=MetadataNormalizer.nativeLayout(input);
      for(String key:input.keySet())assertArrayEquals(nativeBytes,out.get(key));
      assertEquals(0d,Nbt.read(dfu).compound("data").get("lerp_target"));
      assertEquals(12d,Nbt.read(out.get("world_border.dat.nbt")).compound("data").get("center_x"));
      assertEquals(7,Nbt.read(out.get("world_border.dat.nbt")).compound("data").get("warning_blocks"));
    }
  }
  @Test void activeInterpolationAndUnknownWrappersPayloadsOrTypesRemainByteIdentical() throws Exception {
    var custom=border(0,0).with("custom:preserve",42);
    var wrongType=border(0,0).with("lerp_time",0);
    var missing=border(0,0);missing.remove("lerp_time");
    var wrapper=new Nbt.Compound().with("DataVersion",5023).with("data",border(0,0)).with("custom",42);
    for(byte[] bytes:List.of(saved(5023,border(100,4321)),saved(5023,custom),saved(5023,wrongType),
        saved(5023,missing),saved(6000,border(0,0)),Nbt.write(wrapper)))
      assertArrayEquals(bytes,MetadataNormalizer.nativeLayout(Map.of("world_border.dat.nbt",bytes)).get("world_border.dat.nbt"));
    byte[] opaque=saved(5023,border(0,0));
    assertArrayEquals(opaque,MetadataNormalizer.nativeLayout(Map.of("custom_world_border.dat.nbt",opaque)).get("custom_world_border.dat.nbt"));
  }
}
