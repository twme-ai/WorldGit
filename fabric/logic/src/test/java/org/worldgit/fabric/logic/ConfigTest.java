package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.protocol.DiffPalette;

class ConfigTest {
  @TempDir Path temp;

  @Test
  void serverDefaultsAndGeneratedFileAreTheSame() throws Exception {
    var defaults = ServerConfig.defaults();
    var loaded = ServerConfig.load(temp);
    assertEquals(defaults, loaded);
    assertTrue(java.nio.file.Files.exists(temp.resolve(ServerConfig.FILE_NAME)));
    assertEquals("creative", loaded.defaultTemplate());
    assertEquals(WorldGitConfig.Track.ALL, loaded.track());
    assertTrue(loaded.autoCommit().onLogout());
    assertEquals(10, loaded.autoCommit().intervalMinutes());
    assertEquals(100_000, loaded.preview().maxGhostCells());
  }

  @Test
  void serverOverridesAreParsed() throws Exception {
    var c =
        ServerConfig.parse(
            "default-template: survival\npermission-level: 3\nauto-commit:\n  interval-minutes: 0\n  on-logout: false\n"
                + "preview:\n  max-ghost-cells: 500\n",
            "test");
    assertEquals("survival", c.defaultTemplate());
    assertEquals(3, c.writePermissionLevel());
    assertEquals(0, c.autoCommit().intervalMinutes());
    assertFalse(c.autoCommit().onLogout());
    assertEquals(500, c.preview().maxGhostCells());
  }

  @Test
  void serverRejectsBadInput() {
    assertThrows(IOException.class, () -> ServerConfig.parse("nonsense: 1\n", "t"));
    assertThrows(IOException.class, () -> ServerConfig.parse("auto-commit:\n  typo: 1\n", "t"));
    assertThrows(IOException.class, () -> ServerConfig.parse("default-template: both\n", "t"));
    assertThrows(IOException.class, () -> ServerConfig.parse("permission-level: 9\n", "t"));
    assertThrows(IOException.class, () -> ServerConfig.parse("preview:\n  max-ghost-cells: 100001\n", "t"));
    assertThrows(IOException.class, () -> ServerConfig.parse("a: 1\na: 2\n", "t"));
    assertThrows(IOException.class, () -> ServerConfig.parse("identity:\n  server-name: 'a<b'\n", "t"));
    assertThrows(IOException.class, () -> ServerConfig.parse("- list\n", "t"));
  }

  @Test
  void clientPaletteResolution() throws Exception {
    assertEquals(DiffPalette.DEFAULT, ClientConfig.defaults().resolve(null));
    assertEquals(DiffPalette.COLORBLIND, ClientConfig.defaults().resolve(DiffPalette.COLORBLIND));
    assertEquals(DiffPalette.DEFAULT, ClientConfig.defaults().withPalette("default").resolve(DiffPalette.COLORBLIND));
    assertEquals(DiffPalette.COLORBLIND, ClientConfig.defaults().withPalette("colorblind").resolve(DiffPalette.DEFAULT));
  }

  @Test
  void clientConfigRoundTripsThroughFile() throws Exception {
    var changed = ClientConfig.load(temp).withPalette("colorblind").withSeeThrough(true);
    ClientConfig.save(temp, changed);
    var again = ClientConfig.load(temp);
    assertEquals(changed, again);
    assertThrows(IOException.class, () -> ClientConfig.parse("detail-distance: 1\n", "t"));
    assertThrows(IOException.class, () -> ClientConfig.parse("palette: neon\n", "t"));
  }

  @Test
  void aliasesFeedbackAndIgnorePermissionAreConfigurable() throws Exception {
    var defaults = ServerConfig.defaults();
    assertTrue(defaults.aliases().wgit() && defaults.aliases().git());
    assertEquals(2, defaults.ignorePermissionLevel());
    assertTrue(defaults.feedback().bossbar() && defaults.feedback().hud());
    assertEquals(3, defaults.feedback().terminalSeconds());
    var custom = ServerConfig.parse("aliases:\n  git: false\nfeedback:\n  terminal-seconds: 5\n  hud: false\nignore-permission-level: 3\n", "t");
    assertFalse(custom.aliases().git());
    assertTrue(custom.aliases().wgit());
    assertEquals(5, custom.feedback().terminalSeconds());
    assertFalse(custom.feedback().hud());
    assertEquals(3, custom.ignorePermissionLevel());
    assertThrows(IOException.class, () -> ServerConfig.parse("aliases:\n  worldgit: false\n", "t"));
    assertThrows(IOException.class, () -> ServerConfig.parse("feedback:\n  terminal-seconds: 0\n", "t"));
  }

  @Test
  void errorMessagesAreClassifiedForTheCopyButton() {
    assertTrue(MessageKeys.isError(MessageKeys.COMMON_ERROR));
    assertTrue(MessageKeys.isError(MessageKeys.ERROR_TEMPLATE));
    assertTrue(MessageKeys.isError(MessageKeys.REMOTE_ERROR_FORBIDDEN));
    assertTrue(MessageKeys.isError(MessageKeys.APPLY_PARTIAL));
    assertTrue(MessageKeys.isError(MessageKeys.TOUCH_DUPLICATE));
    assertFalse(MessageKeys.isError(MessageKeys.COMMIT_DONE));
    assertFalse(MessageKeys.isError(MessageKeys.RESULT_LINE));
    assertFalse(MessageKeys.isError(MessageKeys.GRAPH_TITLE));
  }
}
