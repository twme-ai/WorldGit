package org.worldgit.fabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import org.worldgit.core.config.EntityTagRegistry;

/**
 * 模組內建 datapack 的 entity tag：Fabric 把 mod id 當成內建 pack 名稱（level.dat 的 DataPacks.Enabled 會出現
 * {@code fabric-convention-tags-v2} 之類），所以直接從已載入的 mod jar 讀 {@code data/<ns>/tags/entity_type/*.json}。
 * 這樣 CLI 離線讀不到的 #c:… tag，在模組裡是完整解析的。
 */
final class ModPacks implements EntityTagRegistry.PackResolver {
    private static final Pattern TAG = Pattern.compile("data/([a-z0-9_.-]+)/tags/entity_type/([a-z0-9_./-]+)\\.json");
    static final ModPacks INSTANCE = new ModPacks();

    private ModPacks() {}

    @Override
    public Map<String, byte[]> tags(String pack) throws IOException {
        var container = FabricLoader.getInstance().getModContainer(pack);
        if (container.isEmpty()) return null; // 不是已載入的 mod：交回 core 的預設處理
        var result = new TreeMap<String, byte[]>();
        for (Path root : container.get().getRootPaths()) {
            Path data = root.resolve("data");
            if (!Files.isDirectory(data)) continue;
            try (var files = Files.walk(data)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    var m = TAG.matcher(root.relativize(file).toString().replace('\\', '/'));
                    if (m.matches()) result.put(m.group(1) + ":" + m.group(2), Files.readAllBytes(file));
                }
            }
        }
        return result;
    }
}
