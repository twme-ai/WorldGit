package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;

class PluginSettingsTest {
  @Test
  void yamlRejectsWrongTypesAndInvalidRanges() throws Exception {
    var config = new YamlConfiguration();
    config.loadFromString("commit:\n  chunks-per-tick: '8'\n");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> PluginSettings.from(config)).getMessage().contains("commit.chunks-per-tick"));
    config.set("commit.chunks-per-tick", 0);
    assertThrows(IllegalArgumentException.class, () -> PluginSettings.from(config));
    config.set("commit.chunks-per-tick", 8);
    config.set("auto-commit.enabled", "true");
    assertThrows(IllegalArgumentException.class, () -> PluginSettings.from(config));
    config.set("auto-commit.enabled", true);
    config.set("auto-commit.interval-minutes", 30);
    config.set("auto-commit.max-wait-minutes", 15);
    assertThrows(IllegalArgumentException.class, () -> PluginSettings.from(config));
    config.set("auto-commit.max-wait-minutes", 60);
    config.set("server-identity.name", "name\nWorldGit-Auto: true");
    assertThrows(IllegalArgumentException.class, () -> PluginSettings.from(config));
    config.set("server-identity.name", "Builder server");
    assertEquals(60, PluginSettings.from(config).autoMaxWaitMinutes());
  }

  @Test
  void autoGateMergesSmallChangesButNeverTriggersEntityOnlyByDefault() {
    var config = new YamlConfiguration();
    config.set("auto-commit.min-changed-sections", 100);
    var settings = PluginSettings.from(config);
    var section = new WorldDiff.SectionChange(new ChunkPos(0, 0), 4, ChangeKind.ADDED, List.of(), new WorldDiff.Counts(1, 0, 0, 0));
    var small = new WorldDiff(DimensionId.OVERWORLD, List.of(section), List.of(), List.of(), List.of());
    assertFalse(AutoCommit.qualifies(small, settings, false));
    assertTrue(AutoCommit.qualifies(small, settings, true));
    var entity = new WorldDiff.EntityChange(UUID.randomUUID(), ChangeKind.ADDED, null, new ChunkPos(0, 0), null, null);
    var entities = new WorldDiff(DimensionId.OVERWORLD, List.of(), List.of(entity), List.of(), List.of());
    assertFalse(AutoCommit.qualifies(entities, settings, false));
    assertFalse(AutoCommit.qualifies(entities, settings, true));
    config.set("auto-commit.entity-only-triggers", true);
    assertTrue(AutoCommit.qualifies(entities, PluginSettings.from(config), false));
    assertFalse(AutoCommit.qualifies(new WorldDiff(DimensionId.OVERWORLD, List.of(), List.of(), List.of(), List.of()), settings, true));
  }
}
