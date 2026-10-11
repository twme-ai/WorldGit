package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.SavedData;

class SavedDataAliasesTest {
  private static String key(String path) {
    return "minecraft.the_end.saved."
        + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.getBytes(StandardCharsets.UTF_8)) + ".nbt";
  }

  @Test
  void dimensionRaidClocksAreTransientButRaidContentRemainsTracked() throws Exception {
    for (String path : List.of("data/raids.dat", "data/raids_nether.dat", "data/raids_end.dat")) {
      for (int version : List.of(3465, 4671)) {
        String clock = version <= 4189 ? "Tick" : "tick";
        String id = version <= 4189 ? "NextAvailableID" : "next_id";
        var a = new Nbt.Compound().with("DataVersion", version)
            .with("data", new Nbt.Compound().with(clock, 4511).with(id, 1));
        var b = Nbt.copy(a);
        b.compound("data").put(clock, 4799);
        byte[] normalized = SavedData.normalize(key(path), Nbt.write(a));
        assertArrayEquals(normalized, SavedData.normalize(key(path), Nbt.write(b)), path);
        assertEquals(1, Nbt.read(normalized).compound("data").integer(id, 0));
        b.compound("data").put(id, 2);
        assertFalse(java.util.Arrays.equals(normalized,
            SavedData.normalize(key(path), Nbt.write(b))), path);

        var target = Nbt.read(normalized);
        var restored = SavedData.materialize(Path.of("world").resolve(path), target, b);
        assertEquals(4799, restored.compound("data").integer(clock, -1), path);
        assertEquals(1, restored.compound("data").integer(id, 0), path);
        assertEquals(0, SavedData.materialize(Path.of("world").resolve(path),
            Nbt.read(normalized), new Nbt.Compound()).compound("data").integer(clock, -1), path);
      }
    }
  }

  @Test
  void modNamespaceRaidClocksRemainOpaque() throws Exception {
    String path = "data/example/raids_end.dat";
    var root = new Nbt.Compound().with("DataVersion", 4671)
        .with("data", new Nbt.Compound().with("tick", 4511));
    byte[] bytes = Nbt.write(root);
    assertArrayEquals(bytes, SavedData.normalize(key(path), bytes));
    assertEquals(4511, SavedData.materialize(Path.of("world").resolve(path),
        Nbt.copy(root), new Nbt.Compound()).compound("data").integer("tick", -1));
  }
}
