package wgproto;

import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** JGit bare repo 的薄包裝 + 記憶體樹節點（惰性載入、只重寫被改動的路徑）。 */
public final class Repo implements AutoCloseable {
    public final Repository repo;
    public final ObjectReader reader;
    public final ObjectInserter ins;
    public static final String BRANCH = "refs/heads/main";

    public Repo(Path dir, boolean create) throws IOException {
        var b = new org.eclipse.jgit.internal.storage.file.FileRepository(dir.toFile());
        if (create && !Files.exists(dir.resolve("HEAD"))) {
            Files.createDirectories(dir);
            b.create(true);
            RefUpdate ru = b.updateRef("HEAD"); ru.disableRefLog(); ru.link(BRANCH);
        }
        repo = b;
        reader = repo.newObjectReader();
        ins = repo.newObjectInserter();
    }

    @Override public void close() { ins.close(); reader.close(); repo.close(); }

    public ObjectId head() throws IOException { return repo.resolve(BRANCH); }

    public ObjectId resolve(String s) throws IOException {
        ObjectId id = repo.resolve(s);
        if (id == null) throw new IOException("cannot resolve " + s);
        return id;
    }

    public byte[] blob(ObjectId id) throws IOException { return reader.open(id, Constants.OBJ_BLOB).getBytes(); }

    public ObjectId treeOf(ObjectId commit) throws IOException {
        try (RevWalk rw = new RevWalk(repo)) { return rw.parseCommit(commit).getTree().getId(); }
    }

    public ObjectId commit(ObjectId tree, ObjectId parent, String msg, long timeSec) throws IOException {
        CommitBuilder cb = new CommitBuilder();
        cb.setTreeId(tree);
        if (parent != null) cb.setParentId(parent);
        PersonIdent who = new PersonIdent("worldgit-proto", "wg@example.invalid", new Date(timeSec * 1000), TimeZone.getTimeZone("UTC"));
        cb.setAuthor(who); cb.setCommitter(who);
        cb.setMessage(msg);
        ObjectId id = ins.insert(cb);
        ins.flush();
        RefUpdate ru = repo.updateRef(BRANCH);
        ru.setNewObjectId(id);
        ru.setForceUpdate(true);
        ru.disableRefLog();
        ru.update();
        return id;
    }

    // ---------------- 樹節點 ----------------
    public final class Node {
        public ObjectId id;          // 已存在的物件（未 dirty 時有效）
        public boolean isTree;
        TreeMap<String, Node> kids;  // 樹節點：已載入的子節點
        boolean dirty;

        Node(ObjectId id, boolean isTree) { this.id = id; this.isTree = isTree; }

        public TreeMap<String, Node> kids() {
            if (kids == null) {
                kids = new TreeMap<>();
                if (id != null) {
                    try {
                        CanonicalTreeParser p = new CanonicalTreeParser();
                        p.reset(reader, id);
                        while (!p.eof()) {
                            String name = p.getEntryPathString();
                            kids.put(name, new Node(p.getEntryObjectId(), (p.getEntryRawMode() & FileMode.TYPE_MASK) == FileMode.TYPE_TREE));
                            p.next();
                        }
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                }
            }
            return kids;
        }

        public Node get(String name) { return kids().get(name); }

        /** 取得（必要時建立）子樹。 */
        public Node tree(String name) {
            Node n = kids().get(name);
            if (n == null) { n = new Node(null, true); n.kids = new TreeMap<>(); n.dirty = true; kids.put(name, n); dirty = true; }
            return n;
        }

        public void putBlob(String name, ObjectId blobId) {
            Node old = kids().get(name);
            if (old != null && !old.isTree && old.id.equals(blobId)) return;
            kids.put(name, new Node(blobId, false));
            dirty = true;
        }

        public void remove(String name) { if (kids().remove(name) != null) dirty = true; }

        /** 寫出（只寫有變動的節點），空樹回傳 null。 */
        public ObjectId write() throws IOException {
            if (!isTree || kids == null) return id;
            boolean changed = dirty;
            for (var e : new ArrayList<>(kids.entrySet())) {
                Node k = e.getValue();
                ObjectId before = k.id;
                ObjectId nid = k.write();
                if (nid == null) { kids.remove(e.getKey()); changed = true; }
                else { if (!nid.equals(before)) changed = true; k.id = nid; }
            }
            if (!changed && id != null) return id;
            if (kids.isEmpty()) return null;
            TreeFormatter tf = new TreeFormatter();
            for (String n : sortedNames()) {
                Node k = kids.get(n);
                tf.append(n, k.isTree ? FileMode.TREE : FileMode.REGULAR_FILE, k.id);
            }
            id = ins.insert(tf);
            dirty = false;
            return id;
        }

        List<String> sortedNames() {
            List<String> names = new ArrayList<>(kids.keySet());
            names.sort((a, b) -> {
                byte[] x = (a + (kids.get(a).isTree ? "/" : "")).getBytes(StandardCharsets.UTF_8);
                byte[] y = (b + (kids.get(b).isTree ? "/" : "")).getBytes(StandardCharsets.UTF_8);
                return Arrays.compareUnsigned(x, y);
            });
            return names;
        }
    }

    public Node rootOf(ObjectId commitOrNull) throws IOException {
        if (commitOrNull == null) { Node n = new Node(null, true); n.kids = new TreeMap<>(); n.dirty = true; return n; }
        return new Node(treeOf(commitOrNull), true);
    }
}
