package wgproto;

import org.eclipse.jgit.lib.ObjectId;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public final class RepoStats {
    public static void size(Path repoDir) throws IOException {
        long loose = 0, looseN = 0, pack = 0, packN = 0;
        Path obj = repoDir.resolve("objects");
        try (var s = Files.walk(obj)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = obj.relativize(p).toString();
                long sz = Files.size(p);
                if (rel.startsWith("pack/")) { pack += sz; if (rel.endsWith(".pack")) packN++; }
                else if (rel.matches("[0-9a-f]{2}/[0-9a-f]{38}")) { loose += sz; looseN++; }
            }
        }
        System.out.printf("SIZE looseObjects=%d looseBytes=%d packFiles=%d packBytes=%d%n", looseN, loose, packN, pack);
        // 以檔案系統實際占用（含 4K 區塊）
        try {
            Process pr = new ProcessBuilder("du", "-sk", obj.toString()).start();
            System.out.println("du -sk objects: " + new String(pr.getInputStream().readAllBytes()).trim());
        } catch (IOException ignored) {}
    }

    static long pct(List<Long> sorted, double p) { return sorted.get(Math.min(sorted.size() - 1, (int) (p * sorted.size()))); }

    public static void stats(Path repoDir, String rev) throws IOException {
        try (Repo repo = new Repo(repoDir, false)) {
            ObjectId c = repo.resolve(rev);
            Repo.Node root = repo.rootOf(c);
            Set<ObjectId> trees = new HashSet<>();
            Map<String, Set<ObjectId>> uniq = new TreeMap<>();
            Map<String, Integer> entries = new TreeMap<>();
            Map<String, List<Long>> sizes = new TreeMap<>(); // 唯一 blob 的儲存大小（zstd 後）
            Map<String, Long> raw = new TreeMap<>();
            Map<String, Set<ObjectId>> seenSize = new TreeMap<>();
            Deque<Repo.Node> stack = new ArrayDeque<>();
            Deque<String> names = new ArrayDeque<>();
            stack.push(root); names.push("");
            int chunks = 0;
            while (!stack.isEmpty()) {
                Repo.Node n = stack.pop(); String path = names.pop();
                trees.add(n.id);
                for (var e : n.kids().entrySet()) {
                    Repo.Node k = e.getValue();
                    if (k.isTree) {
                        if (e.getKey().startsWith("c.")) chunks++;
                        stack.push(k); names.push(path + "/" + e.getKey());
                    } else {
                        String nm = e.getKey();
                        String kind = nm.startsWith("s.") ? "section" : nm.replaceAll("\\.bin$", "");
                        entries.merge(kind, 1, Integer::sum);
                        uniq.computeIfAbsent(kind, x -> new HashSet<>()).add(k.id);
                        if (seenSize.computeIfAbsent(kind, x -> new HashSet<>()).add(k.id)) {
                            long sz = repo.reader.getObjectSize(k.id, org.eclipse.jgit.lib.Constants.OBJ_BLOB);
                            sizes.computeIfAbsent(kind, x -> new ArrayList<>()).add(sz);
                            if (!kind.equals("world-meta")) raw.merge(kind, (long) Codec.unzstd(repo.blob(k.id)).length, Long::sum);
                        }
                    }
                }
            }
            System.out.println("commit " + c.name() + ": chunks=" + chunks + " uniqueTrees=" + trees.size());
            for (String kind : entries.keySet()) {
                List<Long> s = sizes.get(kind); Collections.sort(s);
                long sum = s.stream().mapToLong(Long::longValue).sum();
                int ent = entries.get(kind), un = uniq.get(kind).size();
                System.out.printf("  %-11s entries=%6d unique=%6d dedup=%.1f%%  zstd-bytes(unique): sum=%d min=%d p50=%d p90=%d p99=%d max=%d  rawNormalized=%d%n",
                        kind, ent, un, 100.0 * (ent - un) / ent, sum, s.get(0), pct(s, .5), pct(s, .9), pct(s, .99), s.get(s.size() - 1), raw.getOrDefault(kind, 0L));
            }
            // section 大小分佈直方圖
            List<Long> s = sizes.getOrDefault("section", List.of());
            if (!s.isEmpty()) {
                long[] edges = {32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, Long.MAX_VALUE};
                int[] h = new int[edges.length];
                for (long v : s) for (int i = 0; i < edges.length; i++) if (v <= edges[i]) { h[i]++; break; }
                StringBuilder sb = new StringBuilder("  section-size histogram (unique blobs, bytes):");
                for (int i = 0; i < edges.length; i++) sb.append(String.format(" <=%s:%d", edges[i] == Long.MAX_VALUE ? "inf" : edges[i], h[i]));
                System.out.println(sb);
            }
        }
    }
}
