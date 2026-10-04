package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;

class WorldLayoutRemoteMetadataTest {
  @TempDir Path world;
  private void gzip(Path file,Nbt.Compound tag) throws Exception {
    Files.createDirectories(file.getParent());
    try(var out=new GZIPOutputStream(Files.newOutputStream(file))) {out.write(Nbt.write(tag));}
  }
  @Test void freshVanilla26SharedSettingsProvideRequiredCloneAliasWithoutChangingExistingData() throws Exception {
    gzip(world.resolve("level.dat"),new Nbt.Compound().with("Data",new Nbt.Compound().with("DataVersion",4903)));
    Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
    var settings=new Nbt.Compound().with("data",new Nbt.Compound().with("seed",1234L));
    gzip(world.resolve("data/minecraft/world_gen_settings.dat"),settings);
    var layout=WorldLayout.discover(world);var captured=layout.worldMetadata();
    assertArrayEquals(Nbt.write(settings),captured.get("minecraft.overworld.world_gen_settings.dat.nbt"));
    assertArrayEquals(captured.get("world_gen_settings.dat.nbt"),captured.get("minecraft.overworld.world_gen_settings.dat.nbt"));
    assertFalse(Files.exists(world.resolve("dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat")));
    var existing=new Nbt.Compound().with("data",new Nbt.Compound().with("seed",5678L));
    gzip(world.resolve("dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat"),existing);
    captured=layout.worldMetadata();
    assertArrayEquals(Nbt.write(existing),captured.get("minecraft.overworld.world_gen_settings.dat.nbt"));
    assertArrayEquals(Nbt.write(settings),captured.get("world_gen_settings.dat.nbt"));
  }
}
