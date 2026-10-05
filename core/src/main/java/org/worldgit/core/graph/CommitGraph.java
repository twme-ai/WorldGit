package org.worldgit.core.graph;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.store.RefStore;

/** 單維度、拓樸優先且同層依精確時間／hash 排序；lane 不依賴 UI。 */
public record CommitGraph(List<Node> nodes, boolean truncated, int lanes) {
  public record Label(String kind, String name) {}
  public record Edge(String parent, int fromLane, int toLane) {}
  public record Node(String id, List<String> parents, String snapshot, String time,
      String message, String author, int lane, List<String> before, List<String> after,
      List<Edge> edges, List<Label> labels) {}
  public CommitGraph { nodes = List.copyOf(nodes); }
  public static CommitGraph read(RefStore refs, int limit, boolean all) throws IOException {
    if (limit < 1 || limit > 10000) throw new IOException("graph limit 必須介於 1–10000");
    var labels = new HashMap<String, List<Label>>();
    var tips = new TreeSet<String>();
    for (String prefix : List.of("refs/heads/", "refs/tags/", "refs/remotes/"))
      for (var entry : refs.refsByPrefix(prefix).entrySet()) {
        String id = refs.resolve(entry.getKey());
        labels.computeIfAbsent(id, k -> new ArrayList<>()).add(new Label(
            prefix.equals("refs/heads/") ? "branch" : prefix.equals("refs/tags/") ? "tag" : "tracking",
            entry.getKey().substring(prefix.length())));
        if (all) tips.add(id);
      }
    String head = refs.head();
    if (head != null) {
      tips.add(head); labels.computeIfAbsent(head, k -> new ArrayList<>()).add(new Label("HEAD", "HEAD"));
    }
    var commits = new HashMap<String, RefStore.Commit>();
    var queue = new ArrayDeque<>(tips);
    while (!queue.isEmpty() && commits.size() < 20000) {
      String id = queue.remove(); if (commits.containsKey(id)) continue;
      var commit = refs.readCommit(id); commits.put(id, commit); queue.addAll(commit.parents());
    }
    boolean cut = !queue.isEmpty();
    var children = new HashMap<String, Integer>();
    for (var commit : commits.values()) for (String parent : commit.parents())
      if (commits.containsKey(parent)) children.merge(parent, 1, Integer::sum);
    var ready = new PriorityQueue<RefStore.Commit>(Comparator
        .comparing((RefStore.Commit c) -> c.metadata().time()).reversed().thenComparing(RefStore.Commit::id));
    for (var commit : commits.values()) if (!children.containsKey(commit.id())) ready.add(commit);
    var active = new ArrayList<String>();
    var rows = new ArrayList<Node>(); int max = 0;
    while (!ready.isEmpty() && rows.size() < limit) {
      var commit = ready.remove();
      int lane = active.indexOf(commit.id());
      if (lane < 0) { lane = active.size(); active.add(commit.id()); }
      var before = List.copyOf(active); active.remove(lane);
      int insertion = lane;
      for (String parent : commit.parents()) {
        if (!active.contains(parent)) active.add(Math.min(insertion++, active.size()), parent);
        if (commits.containsKey(parent) && children.merge(parent, -1, Integer::sum) == 0)
          ready.add(commits.get(parent));
      }
      var edges = new ArrayList<Edge>();
      for (String parent : commit.parents()) edges.add(new Edge(parent, lane, active.indexOf(parent)));
      var metadata = commit.metadata();
      rows.add(new Node(commit.id(), commit.parents(), metadata.snapshot().toString(),
          metadata.time().toString(), metadata.message(), metadata.author().git(), lane, before,
          List.copyOf(active), List.copyOf(edges), List.copyOf(labels.getOrDefault(commit.id(), List.of()))));
      max = Math.max(max, Math.max(before.size(), active.size()));
    }
    return new CommitGraph(rows, cut || rows.size() < commits.size(), max);
  }
}
