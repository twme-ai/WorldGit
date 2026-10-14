package org.worldgit.fabric.logic;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.worldgit.core.version.MinecraftVersion;
import static org.junit.jupiter.api.Assertions.*;
class VersionSelectorTest {
  @Test void catalogSelectsTenExactModulesAndRejectsNearbyVersions() throws Exception {
    assertEquals(10,MinecraftVersion.SUPPORTED.size());
    for(var v:MinecraftVersion.SUPPORTED) assertEquals("mc"+v.id().replace('.', '_'),VersionSelector.module(v.id()));
    for(String v:new String[]{"1.20", "1.20.2", "1.20.10", "1.21.11-pre1", "26.2.0", "26.20"}) assertThrows(IOException.class,()->VersionSelector.module(v));
    assertDoesNotThrow(()->VersionSelector.require("1.20.1",3465,"1.20.1"));
    assertThrows(IOException.class,()->VersionSelector.require("1.20.1",3700,"1.20.1"));
    assertThrows(IOException.class,()->VersionSelector.require("1.20.4",3700,"1.20.1"));
    assertThrows(IOException.class,()->VersionSelector.require("1.20.6",3839,"1.20.6"));
  }
}
