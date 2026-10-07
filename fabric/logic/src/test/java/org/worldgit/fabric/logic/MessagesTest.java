package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.worldgit.i18n.MessageCatalog;
import org.yaml.snakeyaml.Yaml;

/** 語言鍵與語言檔一致：每個鍵都有 en_us／zh_tw、樣板只用宣告過的參數、語言檔沒有沒註冊的 fabric.* 孤兒鍵。 */
class MessagesTest {
  private static final Set<String> STYLE_TAGS =
      Set.of("gold", "gray", "white", "aqua", "green", "red", "yellow", "bold", "italic", "wg_added", "wg_removed", "wg_modified", "wg_conflict", "newline");
  private static final Pattern TAG = Pattern.compile("(?<!\\\\)<([a-z_]+)>");

  @Test
  void everyKeyExistsInEveryBundledLocale() {
    var catalog = MessageCatalog.bundled();
    for (String locale : MessageCatalog.BUNDLED_LOCALES) {
      assertTrue(catalog.missingKeys(locale).isEmpty(), locale + " 缺少：" + catalog.missingKeys(locale));
      for (String key : MessageKeys.all().keySet())
        assertTrue(catalog.find(locale, key).isPresent(), locale + " 沒有鍵 " + key);
    }
  }

  @Test
  void templatesUseOnlyDeclaredPlaceholdersAndEveryArgIsUsedInEnglish() {
    var catalog = MessageCatalog.bundled();
    for (String locale : MessageCatalog.BUNDLED_LOCALES)
      for (var entry : MessageKeys.all().entrySet()) {
        String template = catalog.raw(locale, entry.getKey());
        var used = new TreeSet<String>();
        var m = TAG.matcher(template);
        while (m.find()) if (!STYLE_TAGS.contains(m.group(1))) used.add(m.group(1));
        assertTrue(entry.getValue().containsAll(used), locale + " " + entry.getKey() + " 使用了未宣告的參數 " + used + "，宣告：" + entry.getValue());
        if (locale.equals("en_us"))
          assertEquals(entry.getValue(), used, "en_us " + entry.getKey() + " 應該用到所有宣告的參數");
      }
  }

  @SuppressWarnings("unchecked")
  private static void flatten(String prefix, Map<String, Object> map, Set<String> out) {
    for (var e : map.entrySet()) {
      String key = prefix + e.getKey();
      if (e.getValue() instanceof Map<?, ?> child) flatten(key + ".", (Map<String, Object>) child, out);
      else out.add(key);
    }
  }

  @Test
  void noOrphanFabricKeysInLanguageFiles() throws Exception {
    for (String locale : MessageCatalog.BUNDLED_LOCALES)
      try (InputStream in = MessageCatalog.class.getResourceAsStream("/org/worldgit/i18n/lang/" + locale + ".yml")) {
        var keys = new TreeSet<String>();
        flatten("", new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8)), keys);
        var orphans = new TreeSet<>(keys);
        orphans.removeIf(k -> !k.startsWith("fabric."));
        orphans.removeAll(MessageKeys.all().keySet());
        assertTrue(orphans.isEmpty(), locale + " 有沒註冊的鍵：" + orphans);
      }
  }

  @Test
  void remoteUsageIsStaticAndReportsInvalidInput() {
    assertTrue(Msg.prefixed(MessageKeys.REMOTE_USAGE).args().isEmpty());
    assertTrue(MessageKeys.isError(MessageKeys.REMOTE_USAGE));
    for (String locale : MessageCatalog.BUNDLED_LOCALES) {
      String text = MessageCatalog.bundled().raw(locale, MessageKeys.REMOTE_USAGE);
      for (String placeholder : List.of("code", "title", "text"))
        assertTrue(text.contains("\\<" + placeholder + ">"), locale + " " + placeholder);
    }
  }

  @Test
  void msgValidatesArguments() {
    assertThrows(IllegalArgumentException.class, () -> Msg.of("fabric.nope"));
    assertThrows(IllegalArgumentException.class, () -> Msg.of(MessageKeys.STATUS_MORE));
    assertThrows(IllegalArgumentException.class, () -> Msg.of(MessageKeys.STATUS_MORE, "count", 1, "extra", 2));
    assertThrows(IllegalArgumentException.class, () -> Msg.of(MessageKeys.STATUS_MORE, "count"));
    var m = Msg.prefixed(MessageKeys.STATUS_MORE, "count", 3);
    assertTrue(m.prefixed());
    assertEquals("3", m.args().get("count"));
  }

  @Test
  void commandArgsErrorsAreTranslatable() {
    var e = assertThrows(CommandArgs.Invalid.class, () -> CommandArgs.parse("--nope", Set.of(), Set.of()));
    assertEquals(MessageKeys.ERROR_UNKNOWN_OPTION, e.msg().key());
    assertEquals("--nope", e.msg().args().get("option"));
  }
}
