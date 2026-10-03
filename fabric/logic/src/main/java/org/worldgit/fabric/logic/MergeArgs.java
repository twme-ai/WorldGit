package org.worldgit.fabric.logic;

import java.util.*;
import org.worldgit.core.merge.MergeReport.Choice;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.service.WorldOperations;

/** 無 Minecraft 相依的 Phase 3 指令驗證。 */
public record MergeArgs(String revision, boolean abort, boolean resume, boolean noCommit,
    boolean dryRun, int distance, Choice strategy, int region, Choice choice) {
  public static MergeArgs parse(String command, String text) {
    var args = OperationArgs.parse(text, Set.of("--abort", "--continue", "--no-commit", "--dry-run", "--ours", "--theirs", "--base", "--manual"),
        Map.of("--distance", 1, "--strategy-option", 1));
    boolean abort=args.flag("--abort"), resume=args.flag("--continue");
    var pos=args.positional();
    Choice choice=null;
    for(var c:Choice.values()) if(args.flag("--"+c.name().toLowerCase(Locale.ROOT))) {
      if(choice!=null) throw new IllegalArgumentException("請只選一種版本"); choice=c;
    }
    boolean resolve=command.equals("resolve");
    if(resolve) {
      // 遠端 Fabric UI 與 Paper 使用位置參數；舊單人旗標語法保持相容。
      if(pos.size()==2 && choice==null) {
        choice=Choice.valueOf(pos.get(1).toUpperCase(Locale.ROOT));
        pos=pos.subList(0,1);
      }
      if(pos.size()!=1 || abort || resume || args.flag("--no-commit") || !args.values().isEmpty()) throw new IllegalArgumentException();
      int id=pos.getFirst().equals("all") ? 0 : Integer.parseInt(pos.getFirst().replaceFirst("^#",""));
      if(id<0 || id==0 && !pos.getFirst().equals("all")) throw new IllegalArgumentException();
      return new MergeArgs(null,false,false,false,args.flag("--dry-run"),1,null,id,choice==null ? Choice.MANUAL : choice);
    }
    if(choice!=null || abort && resume || (abort || resume) && (!command.equals("merge") || !pos.isEmpty()
        || args.flag("--no-commit") || !args.values().isEmpty()) || !abort && !resume && pos.size()!=1) throw new IllegalArgumentException();
    int distance=args.values().containsKey("--distance") ? args.number("--distance") : 1;
    if(distance<0 || distance>16) throw new IllegalArgumentException();
    Choice strategy=null;
    if(args.values().containsKey("--strategy-option")) {
      strategy=Choice.valueOf(args.values().get("--strategy-option").getFirst().toUpperCase(Locale.ROOT));
      if(strategy!=Choice.OURS && strategy!=Choice.THEIRS) throw new IllegalArgumentException();
    }
    return new MergeArgs(pos.isEmpty() ? null : pos.getFirst(),abort,resume,args.flag("--no-commit"),args.flag("--dry-run"),distance,strategy,0,null);
  }
  public WorldOperations.MergeOptions options(CommitMetadata.Identity author) {
    // noCommit=true lets the caller finish after the platform's existing apply/verify barrier.
    return new WorldOperations.MergeOptions(true,strategy,distance,dryRun,author,CommitMetadata.Source.MOD);
  }
}
