package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.merge.MergeReport.Choice;

class RegionCommandTest {
  @Test void selectAndResolveSharePermissionAndUnambiguousArguments() {
    assertEquals("resolve", Commands.permission("conflict-select"));
    assertEquals("resolve", Commands.permission("resolve"));
    assertEquals(new RegionCommand(12, Choice.THEIRS), RegionCommand.parse(new String[]{"#12", "theirs"}));
    assertEquals(new RegionCommand(0, Choice.MANUAL), RegionCommand.parse(new String[]{"all", "manual"}));
    for (var args : new String[][]{{"0", "ours"}, {"-1", "base"}, {"1", "invalid"}, {"1"}, {"all", "ours", "extra"}})
      assertThrows(IllegalArgumentException.class, () -> RegionCommand.parse(args));
    assertEquals(java.util.List.of("ours","theirs","base","manual"), RegionCommand.suggestions(new String[]{"conflict-select", "1", ""}, null));
    assertEquals(java.util.List.of("all"), RegionCommand.suggestions(new String[]{"resolve", ""}, null));
  }
}
