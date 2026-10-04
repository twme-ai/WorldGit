package org.worldgit.fabric.logic;

/** remote list / PR list/view 與留言讀取沿讀取級別；所有變更沿寫入級別。 */
public final class RemoteAccess {
  private RemoteAccess() {}
  public static boolean writes(String command,String[] args) {
    return switch(command) {
      case "fetch","comments"->false;
      case "remote"->args.length==0 || !args[0].equals("list");
      case "pr"->args.length==0 || !java.util.Set.of("list","view").contains(args[0]);
      default->true;
    };
  }
}
