package wgproto;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import wgproto.Nbt.*;

/** 此實驗只支援 entity TYPE [!persistent] 與 !entity 加回；不默默接受其他語法。 */
public final class IgnoreRules {
    record Rule(String type, boolean nonPersistent, boolean include) {}
    final List<Rule> rules = new ArrayList<>();
    public final String source;
    public static boolean extraNoise;
    public IgnoreRules(Path p) throws IOException {
        source = p != null && Files.exists(p) ? Files.readString(p) : "";
        int lineNo = 0;
        for (String line : source.split("\n")) {
            lineNo++;
            line = line.replaceFirst("\\s+#.*$", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\s+");
            if (f.length < 2 || f.length > 3 || !(f[0].equals("entity") || f[0].equals("!entity"))
                || (f.length == 3 && !f[2].equals("!persistent"))
                || !(f[1].equals("*") || f[1].matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")))
                throw new IOException("unsupported .wgignore line " + lineNo + ": " + line);
            rules.add(new Rule(f[1], f.length == 3, f[0].startsWith("!")));
        }
    }
    static boolean flag(NCompound e, String name) { return e.get(name) instanceof Number n && n.intValue() != 0; }
    /** 檔案快照無法查 Bukkit removeWhenFarAway；明確標記 + 固定建築/載具視為 persistent。 */
    public static boolean persistent(NCompound e) {
        String id = e.str("id");
        return flag(e,"PersistenceRequired") || flag(e,"NoAI") || flag(e,"Tame")
            || e.containsKey("CustomName") || e.containsKey("Owner")
            || Codec.ENT_STATIC_TYPES.contains(id) || (id != null && (id.contains("boat") || id.contains("minecart")));
    }
    public boolean ignored(NCompound e) {
        if ("minecraft:player".equals(e.str("id"))) return true;
        boolean ignored = false;
        for (Rule r : rules)
            if ((r.type.equals("*") || r.type.equals(e.str("id"))) && (!r.nonPersistent || !persistent(e))) ignored = !r.include;
        return ignored;
    }
    static Object stripDamage(Object v) {
        if (v instanceof NCompound c) {
            NCompound r = new NCompound();
            for (var en : c.entrySet()) if (!en.getKey().equals("minecraft:damage") && !en.getKey().equals("Damage"))
                r.put(en.getKey(), stripDamage(en.getValue()));
            return r;
        }
        if (v instanceof NList l) { NList r = new NList(l.type); for(Object o:l.items) r.add(stripDamage(o)); return r; }
        return Nbt.copyVal(v);
    }
    static void extraNormalize(NCompound r) {
        if (!extraNoise) return;
        r.remove("Health");
        // 保留掉落物種類/components；只排除合併/拆堆的 count，避免抹掉物品身份。
        if ("minecraft:item".equals(r.str("id"))) {
            for(String k : List.of("Item", "item")) if(r.comp(k) != null) {
                NCompound item = r.comp(k); item.remove("count"); item.remove("Count");
            }
        }
        for (String k : List.of("equipment", "ArmorItems", "HandItems")) if(r.containsKey(k)) r.put(k,stripDamage(r.get(k)));
    }
}
