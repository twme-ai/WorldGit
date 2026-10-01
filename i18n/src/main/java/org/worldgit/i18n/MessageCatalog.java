package org.worldgit.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 四端共用的多語言訊息表（決定 #22）。
 *
 * <p>訊息以 YAML 存放（#21），值是 MiniMessage 字串，參數用 MiniMessage 的 {@code <name>} 標籤，
 * 由各平台以 TagResolver 填入。本類別只負責找出字串，不解析 MiniMessage，所以 core 與 CLI 不需要依賴 Adventure。
 *
 * <p>查找順序：伺服器管理者覆寫檔的該語言 → 內建的該語言 → 覆寫檔的 en_us → 內建的 en_us → 鍵名本身。
 * 語言代碼一律轉成 Minecraft 客戶端使用的小寫底線格式（例如 {@code zh_tw}）。
 */
public final class MessageCatalog {
    public static final String FALLBACK_LOCALE = "en_us";
    /** 內建的語言；新增語言時同時加入 resources 的 lang 目錄。 */
    public static final List<String> BUNDLED_LOCALES = List.of("en_us", "zh_tw");

    private static final String RESOURCE_DIR = "/org/worldgit/i18n/lang/";

    private final Map<String, Map<String, String>> bundled = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> overrides = new ConcurrentHashMap<>();
    private final Path overrideDir;

    private MessageCatalog(Path overrideDir) {
        this.overrideDir = overrideDir;
    }

    /** 只用內建訊息。 */
    public static MessageCatalog bundled() {
        return new MessageCatalog(null);
    }

    /**
     * 內建訊息加上管理者覆寫目錄（例如 {@code plugins/WorldGit/lang/}），目錄內的 {@code <locale>.yml} 只需要列出要改的鍵。
     */
    public static MessageCatalog withOverrides(Path overrideDir) {
        return new MessageCatalog(overrideDir);
    }

    /** 把 {@code zh-TW}、{@code zh_TW}、{@link Locale} 等形式轉成 {@code zh_tw}。 */
    public static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return FALLBACK_LOCALE;
        }
        return locale.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    public static String normalizeLocale(Locale locale) {
        return locale == null ? FALLBACK_LOCALE : normalizeLocale(locale.toLanguageTag());
    }

    /** 回傳 MiniMessage 樣板字串；找不到時回傳鍵名，讓缺漏在畫面上看得出來。 */
    public String raw(String locale, String key) {
        return find(normalizeLocale(locale), key).orElse(key);
    }

    public Optional<String> find(String locale, String key) {
        String normalized = normalizeLocale(locale);
        for (String candidate : normalized.equals(FALLBACK_LOCALE)
                ? List.of(FALLBACK_LOCALE)
                : List.of(normalized, FALLBACK_LOCALE)) {
            String value = override(candidate).get(key);
            if (value == null) {
                value = bundle(candidate).get(key);
            }
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** 某語言內建表缺少、但 en_us 有的鍵；給測試與翻譯檢查用。 */
    public Set<String> missingKeys(String locale) {
        Set<String> missing = new TreeSet<>(bundle(FALLBACK_LOCALE).keySet());
        missing.removeAll(bundle(normalizeLocale(locale)).keySet());
        return missing;
    }

    /** 重新讀取覆寫檔（例如 {@code /wg reload}）。 */
    public void reload() {
        overrides.clear();
    }

    private Map<String, String> bundle(String locale) {
        return bundled.computeIfAbsent(locale, l -> {
            try (InputStream in = MessageCatalog.class.getResourceAsStream(RESOURCE_DIR + l + ".yml")) {
                return in == null ? Map.of() : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), l);
            } catch (IOException e) {
                throw new IllegalStateException("無法讀取內建語言檔 " + l, e);
            }
        });
    }

    private Map<String, String> override(String locale) {
        if (overrideDir == null) {
            return Map.of();
        }
        return overrides.computeIfAbsent(locale, l -> {
            Path file = overrideDir.resolve(l + ".yml");
            if (!Files.isRegularFile(file)) {
                return Map.of();
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[8192];
                for (int n; (n = reader.read(buffer)) > 0; ) {
                    text.append(buffer, 0, n);
                }
                return parse(text.toString(), file.toString());
            } catch (IOException e) {
                throw new IllegalStateException("無法讀取語言覆寫檔 " + file, e);
            }
        });
    }

    /** 巢狀 YAML 攤平成以點分隔的鍵（{@code commit.done}）。 */
    static Map<String, String> parse(String yamlText, String source) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
        Map<String, String> out = new HashMap<>();
        if (root == null) {
            return out;
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(source + "：語言檔最外層必須是 mapping");
        }
        flatten("", map, out, source);
        return Map.copyOf(out);
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, String> out, String source) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                flatten(key + ".", child, out, source);
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                out.put(key, String.valueOf(value));
            } else {
                throw new IllegalArgumentException(source + "：鍵 " + key + " 的值必須是字串");
            }
        }
    }
}
