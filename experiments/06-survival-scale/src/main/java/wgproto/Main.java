package wgproto;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class Main {
    static String opt(List<String> a, String name, String def) {
        int i = a.indexOf(name);
        if (i < 0) return def;
        String v = a.get(i + 1); a.remove(i + 1); a.remove(i);
        return v;
    }
    static boolean flag(List<String> a, String name) { return a.remove(name); }

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) { usage(); return; }
        List<String> a = new ArrayList<>(Arrays.asList(argv));
        String cmd = a.remove(0);
        switch (cmd) {
            case "init", "commit" -> {
                Snapshot.streamInit = flag(a, "--stream");
                Region.lazyRead = Snapshot.streamInit;
                IgnoreRules.extraNoise = flag(a, "--extra-noise");
                String ignore = opt(a, "--ignore", "");
                Snapshot.rules = new IgnoreRules(ignore.isEmpty() ? null : Path.of(ignore));
                double tol = Double.parseDouble(opt(a, "--tol", "2"));
                String msg = opt(a, "-m", cmd);
                boolean full = flag(a, "--full");
                boolean empty = flag(a, "--allow-empty");
                var r = Snapshot.snapshot(Path.of(a.get(0)), Path.of(a.get(1)), msg, tol, cmd.equals("init") || full, empty);
                System.out.println(cmd + ": " + r);
                System.out.println("SUMMARY committed=" + r.committed + " chunksRecomputed=" + r.chunksRecomputed + " sectionsChanged=" + r.sectionsChanged
                        + " chunksTracked=" + r.chunksTracked + " chunksScanned=" + r.chunksScanned + " entityChunksChanged=" + r.entityChunksChanged + " millis=" + r.millis + (r.committed ? " commit=" + r.commit.name() : ""));
            }
            case "diff" -> {
                boolean v = flag(a, "-v");
                try (Repo repo = new Repo(Path.of(a.get(0)), false)) {
                    ObjectId x = repo.resolve(a.get(1)), y = repo.resolve(a.get(2));
                    var st = Diff.diff(repo, x, y, v, System.out);
                    System.out.println(st);
                    if (!st.blockTransitions.isEmpty()) System.out.println("block transitions (top 12): " + st.blockTransitions.entrySet().stream()
                            .sorted((p, q) -> q.getValue() - p.getValue()).limit(12).map(e -> e.getKey() + " x" + e.getValue()).collect(java.util.stream.Collectors.toList()));
                    for (var e : st.entCategories.entrySet()) System.out.println("CATEGORY " + e.getKey() + " added=" + e.getValue()[0] + " removed=" + e.getValue()[1] + " modified=" + e.getValue()[2]);
                    if (!st.entFieldDiffs.isEmpty()) System.out.println("entity field diffs: " + st.entFieldDiffs + " by type: " + st.entTypeModified);
                    System.out.println("SUMMARY chunksAdded=" + st.chunksAdded + " chunksRemoved=" + st.chunksRemoved + " sectionsChanged=" + st.sectionsChanged() + " secAdded=" + st.secAdded + " secRemoved=" + st.secRemoved + " secModified=" + st.secModified
                            + " blocksChanged=" + st.blocksChanged + " blocksNewChunks=" + st.blocksNewChunks + " blocksExistingChanged=" + (st.blocksChanged-st.blocksNewChunks) + " sectionsNewChunks=" + st.sectionsNewChunks + " beChanged=" + st.beChanged + " biomes=" + st.biomesChanged + " ticks=" + st.ticksChanged
                            + " entAdded=" + st.entAdded + " entRemoved=" + st.entRemoved + " entModified=" + st.entModified + " entRelocated=" + st.entRelocated
                            + " entChanges=" + (st.entAdded + st.entRemoved + st.entModified + st.entRelocated));
                }
            }
            case "transport" -> Transport.run(a);
            case "check" -> Checks.run();
            case "restore" -> {
                String poi = opt(a, "--poi", "keep");
                Restore.restore(Path.of(a.get(0)), Path.of(a.get(1)), a.get(2), a.get(3), Integer.parseInt(a.get(4)), Integer.parseInt(a.get(5)),
                        Integer.parseInt(a.get(6)), Integer.parseInt(a.get(7)), poi, System.out);
            }
            case "stats" -> RepoStats.stats(Path.of(a.get(0)), a.size() > 1 ? a.get(1) : "refs/heads/main");
            case "gc" -> {
                long t0 = System.nanoTime();
                try (Repo repo = new Repo(Path.of(a.get(0)), false)) { Git.wrap(repo.repo).gc().call(); }
                System.out.println("gc done in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                RepoStats.size(Path.of(a.get(0)));
            }
            case "size" -> RepoStats.size(Path.of(a.get(0)));
            case "log" -> {
                try (Repo repo = new Repo(Path.of(a.get(0)), false); var rw = new org.eclipse.jgit.revwalk.RevWalk(repo.repo)) {
                    rw.markStart(rw.parseCommit(repo.head()));
                    for (var c : rw) System.out.println(c.getId().name() + " " + c.getShortMessage());
                }
            }
            default -> usage();
        }
    }

    static void usage() {
        System.out.println("""
                usage:
                  init    <serverRoot> <repo.git> [--tol 2] [-m msg]
                  commit  <serverRoot> <repo.git> [--tol 2] [-m msg] [--full] [--allow-empty]
                  diff    <repo.git> <revA> <revB> [-v]
                  restore <serverRoot> <repo.git> <rev> <dimension e.g. minecraft/overworld> <cx1> <cz1> <cx2> <cz2> [--poi keep|delete]
                  stats   <repo.git> [rev]
                  gc      <repo.git>
                  size    <repo.git>
                  log     <repo.git>""");
    }
}
