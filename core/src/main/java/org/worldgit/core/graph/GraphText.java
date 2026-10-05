package org.worldgit.core.graph;

import java.util.*;

/** ASCII lane 連線由共用圖結構產生；UI 只負責 commit 文字與標籤。 */
public final class GraphText {
  private GraphText() {}
  public static String node(CommitGraph.Node node) {
    var text = new StringBuilder();
    for (int lane = 0; lane < node.before().size(); lane++) text.append(lane == node.lane() ? "* " : "| ");
    return text.toString();
  }
  public static Optional<String> transition(CommitGraph.Node node) {
    var links = new ArrayList<int[]>();
    for (int lane=0; lane<node.before().size(); lane++) if(lane != node.lane()) {
      int target=node.after().indexOf(node.before().get(lane)); if(target >= 0) links.add(new int[]{lane,target});
    }
    for (var edge : node.edges()) if(edge.toLane() >= 0) links.add(new int[]{edge.fromLane(),edge.toLane()});
    if(links.stream().allMatch(link -> link[0]==link[1])) return Optional.empty();
    char[] row=new char[Math.max(node.before().size(),node.after().size())*2]; Arrays.fill(row,' ');
    for(int[] link:links) {
      int from=link[0]*2,to=link[1]*2;
      if(from==to) put(row,from,'|');
      else {
        int step=to>from?1:-1;
        for(int index=from+step; step>0?index<=to:index>=to; index+=step)
          put(row,index,index==from+step ? step>0?'\\':'/' : '-');
      }
    }
    return Optional.of(new String(row).stripTrailing());
  }
  private static void put(char[] row,int at,char value) {
    row[at]=row[at]==' ' || row[at]==value ? value : '+';
  }
}
