package org.worldgit.paper;

import java.util.*;
import org.worldgit.core.merge.MergeReport.Choice;
import org.worldgit.core.merge.MergeState;

/** resolve 與 conflict-select 共用參數與補全；id=0 僅由 all 產生。 */
record RegionCommand(int id, Choice choice) {
  static RegionCommand parse(String[] args) {
    if (args.length != 2) throw new IllegalArgumentException();
    int id = args[0].equals("all") ? 0 : Integer.parseInt(args[0].replaceFirst("^#", ""));
    if (id < 0 || (id == 0 && !args[0].equals("all"))) throw new IllegalArgumentException();
    return new RegionCommand(id, Choice.valueOf(args[1].toUpperCase(Locale.ROOT)));
  }

  static List<String> ids(MergeState state, boolean all) {
    var result = new ArrayList<String>();
    if (all) result.add("all");
    if (state != null) state.regions().forEach(r -> result.add(Integer.toString(r.id())));
    return result;
  }

  static List<String> suggestions(String[] args, MergeState state) {
    return args.length == 2 ? ids(state, true)
        : args.length == 3 ? List.of("ours", "theirs", "base", "manual") : List.of();
  }
}
