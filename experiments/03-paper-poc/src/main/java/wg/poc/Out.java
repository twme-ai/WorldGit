package wg.poc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 結果輸出：一行 JSON，寫入 server log（前綴 [WGPOC]）與 plugins/WorldGitPoc/results.jsonl。 */
public final class Out {
    private static Path file;
    public static void init(Path dir) {
        try { Files.createDirectories(dir); file = dir.resolve("results.jsonl"); } catch (IOException e) { e.printStackTrace(); }
    }

    public static Map<String, Object> m(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }

    public static synchronized void res(String kind, Object... kv) {
        Map<String, Object> map = m("kind", kind);
        map.put("t", System.currentTimeMillis());
        map.putAll(m(kv));
        String json = json(map);
        PocPlugin.get().getLogger().info("[WGPOC] " + json);
        if (file != null) {
            try { Files.writeString(file, json + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException e) { e.printStackTrace(); }
        }
    }

    @SuppressWarnings("unchecked")
    public static String json(Object o) {
        if (o == null) return "null";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> mm) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : ((Map<Object, Object>) mm).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(json(String.valueOf(e.getKey()))).append(':').append(json(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object x : c) { if (!first) sb.append(','); first = false; sb.append(json(x)); }
            return sb.append(']').toString();
        }
        if (o.getClass().isArray() && o instanceof int[] a) { List<Object> l = new ArrayList<>(); for (int x : a) l.add(x); return json(l); }
        String s = o.toString();
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); }
            }
        }
        return sb.append('"').toString();
    }
}
