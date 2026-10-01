package wgproto;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** 版本/目錄差異 adapter：把「伺服器根目錄」對應成 維度 id → region/entities/poi 目錄。 */
public final class Layout {
    public record Dim(String id, Path region, Path entities, Path poi) {}

    public final Path serverRoot;
    public final String kind; // "legacy-paper"(1.21.x) 或 "dimensions"(26.x)
    public final List<Dim> dims = new ArrayList<>();
    public final Path levelDat;

    public Layout(Path serverRoot) throws IOException {
        this.serverRoot = serverRoot;
        Path w = serverRoot.resolve("world");
        levelDat = w.resolve("level.dat");
        if (Files.isDirectory(w.resolve("dimensions"))) {
            kind = "dimensions";
            try (var ns = Files.list(w.resolve("dimensions"))) {
                for (Path n : ns.sorted().toList()) {
                    try (var ps = Files.list(n)) {
                        for (Path p : ps.sorted().toList()) {
                            if (Files.isDirectory(p.resolve("region")))
                                dims.add(new Dim(n.getFileName() + "/" + p.getFileName(), p.resolve("region"), p.resolve("entities"), p.resolve("poi")));
                        }
                    }
                }
            }
        } else {
            kind = "legacy-paper";
            addLegacy("minecraft/overworld", w);
            Path nether = Files.isDirectory(serverRoot.resolve("world_nether")) ? serverRoot.resolve("world_nether/DIM-1") : w.resolve("DIM-1");
            Path end = Files.isDirectory(serverRoot.resolve("world_the_end")) ? serverRoot.resolve("world_the_end/DIM1") : w.resolve("DIM1");
            addLegacy("minecraft/the_nether", nether);
            addLegacy("minecraft/the_end", end);
        }
        dims.sort(Comparator.comparing(Dim::id));
    }

    private void addLegacy(String id, Path base) {
        if (Files.isDirectory(base.resolve("region"))) dims.add(new Dim(id, base.resolve("region"), base.resolve("entities"), base.resolve("poi")));
    }

    public Dim dim(String id) {
        for (Dim d : dims) if (d.id.equals(id)) return d;
        return null;
    }

    public int dataVersion() throws IOException {
        try (var in = new java.io.DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(levelDat))))) {
            Nbt.NCompound root = Nbt.readRoot(in);
            return root.comp("Data").intv("DataVersion", 0);
        }
    }
}
