package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;
import org.worldgit.i18n.MessageCatalog;
import org.worldgit.protocol.DiffPalette;

class MessagesTest {
  private static String text(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
  private static boolean hasColor(Component component, int rgb) {
    return TextColor.color(rgb).equals(component.color()) || component.children().stream().anyMatch(c -> hasColor(c, rgb));
  }

  @AfterEach
  void reset() { Messages.configure(MessageCatalog.bundled(), "en_us"); }

  @Test
  void everyDiffKindUsesSharedPaletteAndClickableCoordinates() {
    for (var locale : MessageCatalog.BUNDLED_LOCALES) for (var palette : List.of(DiffPalette.DEFAULT, DiffPalette.COLORBLIND)) for (var kind : ChangeKind.values()) {
      Messages.inLocale(locale, () -> {
        var symbol = Messages.symbol(kind, 7, palette);
        assertEquals(DiffPalette.symbol(kind) + "7", text(symbol));
        assertTrue(hasColor(symbol, palette.rgb(kind)));
        var block = new WorldDiff.BlockChange(new WorldDiff.BlockPos(-8, 64, 17), kind, BlockState.AIR, new BlockState("minecraft:stone"), null, null);
        var component = Messages.block(DimensionId.OVERWORLD, block, palette);
        assertTrue(hasColor(component, palette.rgb(kind)));
        assertEquals(ClickEvent.suggestCommand("/execute in minecraft:overworld run tp @s -8 64 17"), component.clickEvent());
        assertNotNull(component.hoverEvent());
        return null;
      });
    }
  }

  @Test
  void localeScopeRestoresAfterFailureAndParametersCannotInjectTags() {
    Messages.configure(MessageCatalog.bundled(), "en_us");
    assertThrows(IllegalStateException.class, () -> Messages.inLocale("zh-TW", () -> {
      assertTrue(text(Messages.permission("status")).contains("沒有權限"));
      throw new IllegalStateException();
    }));
    assertTrue(text(Messages.permission("status")).contains("permission"));
    var injection = "<click:run_command:'/op attacker'>unsafe</click>";
    var component = Messages.line("common.error", "message", injection);
    assertTrue(text(component).contains(injection));
    assertTrue(component.iterable(ComponentIteratorType.DEPTH_FIRST).iterator().hasNext());
    for (var child : component.iterable(ComponentIteratorType.DEPTH_FIRST)) assertNull(child.clickEvent());
  }

  @Test
  void remoteHubTextRemainsLiteralInBothLocales() {
    String injection="<red><click:run_command:/op attacker>literal</click>";
    for(String locale:MessageCatalog.BUNDLED_LOCALES) Messages.inLocale(locale,()->{
      var row=Messages.line("paper.remote.pr-row","number",1,"title",injection,"state",injection,"source",injection,"target",injection);
      assertTrue(text(row).contains(injection));
      for(var part:row.iterable(ComponentIteratorType.DEPTH_FIRST))assertNull(part.clickEvent());
      assertTrue(text(Messages.line("paper.remote.preview-changed")).contains("/wg pull"));
      return null;
    });
  }

  @Test
  void languageOverridesReloadAndPaperKeysMatch(@TempDir Path dir) throws Exception {
    var bundled = MessageCatalog.bundled();
    for (var locale : MessageCatalog.BUNDLED_LOCALES) assertTrue(bundled.missingKeys(locale).isEmpty());
    Files.writeString(dir.resolve("en_us.yml"), "paper:\n  reload:\n    done: 'First'\n");
    Messages.configure(MessageCatalog.withOverrides(dir), "en_us");
    assertTrue(text(Messages.line("paper.reload.done")).endsWith("First"));
    Files.writeString(dir.resolve("en_us.yml"), "paper:\n  reload:\n    done: 'Second'\n");
    Messages.reload();
    assertTrue(text(Messages.line("paper.reload.done")).endsWith("Second"));
    assertEquals("（完整比對）", Messages.inLocale("zh_tw", Messages::fullSuffix));
  }
}
