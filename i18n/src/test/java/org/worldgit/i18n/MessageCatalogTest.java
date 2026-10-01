package org.worldgit.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageCatalogTest {
    @Test
    void bundledLocalesHaveEveryKey() {
        MessageCatalog catalog = MessageCatalog.bundled();
        for (String locale : MessageCatalog.BUNDLED_LOCALES) {
            assertTrue(catalog.missingKeys(locale).isEmpty(), locale + " 缺少：" + catalog.missingKeys(locale));
        }
    }

    @Test
    void fallsBackToEnglishThenKey() {
        MessageCatalog catalog = MessageCatalog.bundled();
        assertEquals(catalog.raw("en_us", "common.error"), catalog.raw("ja_jp", "common.error"));
        assertEquals("no.such.key", catalog.raw("zh_tw", "no.such.key"));
        assertEquals(catalog.raw("zh_tw", "common.error"), catalog.raw("zh-TW", "common.error"));
    }

    @Test
    void overridesWinOverBundled(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("zh_tw.yml"), "common:\n  error: \"<red>出錯：<message></red>\"\n");
        MessageCatalog catalog = MessageCatalog.withOverrides(dir);
        assertEquals("<red>出錯：<message></red>", catalog.raw("zh_tw", "common.error"));
        assertEquals(MessageCatalog.bundled().raw("zh_tw", "common.prefix"), catalog.raw("zh_tw", "common.prefix"));
    }
}
