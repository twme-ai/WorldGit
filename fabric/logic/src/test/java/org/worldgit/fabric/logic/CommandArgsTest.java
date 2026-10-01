package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandArgsTest {
  @Test
  void flagsValuesAndPositionalsInAnyOrder() {
    var a = CommandArgs.parse("HEAD~1 --show  HEAD --blocks", Set.of("--show", "--blocks"), Set.of());
    assertTrue(a.flag("--show"));
    assertTrue(a.flag("--blocks"));
    assertFalse(a.flag("--full"));
    assertEquals(java.util.List.of("HEAD~1", "HEAD"), a.positional());
    var b = CommandArgs.parse("--template survival --track=modified-only", Set.of(), Set.of("--template", "--track"));
    assertEquals("survival", b.value("--template").orElseThrow());
    assertEquals("modified-only", b.value("--track").orElseThrow());
    assertTrue(CommandArgs.parse("", Set.of("--x"), Set.of()).positional().isEmpty());
    assertTrue(CommandArgs.parse(null, Set.of("--x"), Set.of()).positional().isEmpty());
  }

  @Test
  void rejectsUnknownAndMalformed() {
    assertThrows(IllegalArgumentException.class, () -> CommandArgs.parse("--nope", Set.of("--show"), Set.of()));
    assertThrows(IllegalArgumentException.class, () -> CommandArgs.parse("--template", Set.of(), Set.of("--template")));
    assertThrows(IllegalArgumentException.class, () -> CommandArgs.parse("--show=1", Set.of("--show"), Set.of()));
    assertThrows(IllegalArgumentException.class, () -> CommandArgs.parse("--template=", Set.of(), Set.of("--template")));
  }
}
