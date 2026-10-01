package org.worldgit.core.store;

import java.io.*;
import java.util.*;

/** 惰性載入 tree；保留未展開子樹的 id，只寫受影響的祖先。 */
public final class TreeEditor {
  private final ObjectStore store;
  private final Node root;

  private final class Node {
    String id;
    ObjectStore.Kind kind;
    SortedMap<String, Node> children;

    Node(String id, ObjectStore.Kind kind) {
      this.id = id;
      this.kind = kind;
    }

    SortedMap<String, Node> children() throws IOException {
      if (kind != ObjectStore.Kind.TREE) throw new IOException("不是 tree");
      if (children == null) {
        children = new TreeMap<>();
        for (var e : store.readTree(id).values())
          children.put(e.name(), new Node(e.id(), e.kind()));
      }
      return children;
    }

    String write() throws IOException {
      if (kind == ObjectStore.Kind.BLOB || children == null) return id;
      var entries = new ArrayList<ObjectStore.Entry>();
      for (var e : children.entrySet()) {
        String id = e.getValue().write();
        if (id != null) entries.add(new ObjectStore.Entry(e.getKey(), e.getValue().kind, id));
      }
      if (entries.isEmpty()) return null;
      id = store.writeTree(entries);
      return id;
    }
  }

  public TreeEditor(ObjectStore store, String root) {
    this.store = store;
    this.root = new Node(root, ObjectStore.Kind.TREE);
  }

  public void replaceTree(String path, Map<String, byte[]> blobs) throws IOException {
    Node parent = parent(path, true);
    String name = name(path);
    var n = new Node(null, ObjectStore.Kind.TREE);
    n.children = new TreeMap<>();
    for (var e : blobs.entrySet())
      n.children.put(e.getKey(), new Node(store.writeBlob(e.getValue()), ObjectStore.Kind.BLOB));
    parent.children().put(name, n);
  }

  public void putBlob(String path, byte[] data) throws IOException {
    parent(path, true)
        .children()
        .put(name(path), new Node(store.writeBlob(data), ObjectStore.Kind.BLOB));
  }

  public void remove(String path) throws IOException {
    Node parent = parent(path, false);
    if (parent != null) parent.children().remove(name(path));
  }

  private static String name(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private Node parent(String path, boolean create) throws IOException {
    Node current = root;
    String[] parts = path.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      var children = current.children();
      Node next = children.get(parts[i]);
      if (next == null) {
        if (!create) return null;
        next = new Node(null, ObjectStore.Kind.TREE);
        children.put(parts[i], next);
      }
      current = next;
    }
    return current;
  }

  public String write() throws IOException {
    String id = root.write();
    return id == null ? store.writeTree(List.of()) : id;
  }

  public static ObjectStore.Entry find(ObjectStore store, String tree, String path)
      throws IOException {
    String[] parts = path.split("/");
    ObjectStore.Entry entry = null;
    for (int i = 0; i < parts.length; i++) {
      entry = store.readTree(tree).get(parts[i]);
      if (entry == null) return null;
      if (i < parts.length - 1 && entry.kind() != ObjectStore.Kind.TREE) return null;
      tree = entry.id();
    }
    return entry;
  }
}
