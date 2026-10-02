package org.worldgit.core.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.capture.SnapshotSource;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;
import org.worldgit.core.store.*;

/** 一組維度的離線復原入口。全組 repo/session 鎖、預檢、journal、寫回、驗證、HEAD barrier。 */
public final class WorldOperations implements AutoCloseable {
  public enum State { COMPLETE, DRY_RUN, PARTIAL }
  public record Result(State state, SortedMap<DimensionId,ApplyPlan.Stats> dimensions, String error) {
    public Result { dimensions=Collections.unmodifiableSortedMap(new TreeMap<>(dimensions)); }
    public boolean success() { return state != State.PARTIAL; }
  }
  public record Branch(String name, boolean current, SortedMap<DimensionId,String> commits, boolean consistent) {}
  public record Stash(String id,String time,String message,SortedMap<DimensionId,String> commits,SortedMap<DimensionId,String> bases) {}
  @FunctionalInterface public interface Writer { void apply(ApplyPlan plan,WorldSessionLock lock) throws IOException; }
  /** 線上平台持有遊戲的 session；全組套用必須有跨維度 UUID removal barrier。
   * 呼叫端在建構之前鎖定編輯並 flush，直到 close 之後才解鎖。禁止在遊戲 owner 執行緒呼叫。 */
  public interface LiveAccess {
    SnapshotSource source(WorldLayout.Dimension dimension) throws IOException;
    void validate(ApplyPlan plan) throws IOException;
    void applyAll(Collection<ApplyPlan> plans) throws IOException;
    default EntityTagRegistry.PackResolver packs() { return null; }
  }
  public static WorldOperations live(WorldLayout layout, LiveAccess access) throws IOException {
    return new WorldOperations(layout, null, Objects.requireNonNull(access));
  }
  private record Prepared(SortedMap<DimensionId,RefStore.Commit> commits,SortedMap<DimensionId,ApplyPlan> plans) {}
  private final WorldLayout layout;
  private final WorldRepositories worlds;
  private final SortedMap<DimensionId,DimensionRepository> repos=new TreeMap<>();
  private final RepoLock groupLock;
  private final WorldSessionLock session;
  private final OfflineApplier applier;
  private final Writer writer;
  private final boolean groupedWriter;
  private final LiveAccess live;
  private final double tolerance;

  public WorldOperations(WorldLayout layout) throws IOException { this(layout,null); }
  /** writer 注入用於平台以外的離線 adapter／故障驗證；預設正式 Anvil writer。 */
  public WorldOperations(WorldLayout layout,Writer writer) throws IOException {
    this(layout,writer,null);
  }
  private WorldOperations(WorldLayout layout,Writer writer,LiveAccess live) throws IOException {
    this.layout=layout; worlds=new WorldRepositories(layout); applier=new OfflineApplier(layout);
    this.live=live;
    this.writer=writer==null ? applier::apply : writer;
    groupedWriter=writer==null;
    tolerance=WorldGitConfig.readLocal(worlds.root().resolve("worldgit.yml")).entityTolerance();
    groupLock=RepoLock.acquire(worlds.root());
    WorldSessionLock acquired=null;
    try {
      if(live==null) acquired=WorldSessionLock.acquire(layout);
      for(var entry:worlds.tracked().entrySet()) repos.put(entry.getKey(),new DimensionRepository(entry.getValue(),entry.getKey(),false));
      if(repos.isEmpty()) throw new IOException("世界尚未 init");
      session=acquired;
    } catch(Exception ex) {
      for(var repo:repos.values()) repo.close();
      if(acquired!=null) acquired.close(); groupLock.close(); throw ex;
    }
  }

  public Map<String,Object> journal() throws IOException { return OperationState.read(worlds.root().resolve("apply-state.yml")); }
  private void requireComplete() throws IOException {
    if(OperationState.partial(worlds.root())) throw new IOException("世界為 PARTIAL；請用 switch --force 或 reset --hard 全範圍重新套用以恢復。");
  }
  private SortedMap<DimensionId,DimensionRepository> selected(DimensionId dimension) throws IOException {
    if(dimension==null) return repos;
    var repo=repos.get(dimension);
    if(repo==null) throw new IOException("維度尚未 init："+dimension);
    var result=new TreeMap<DimensionId,DimensionRepository>(); result.put(dimension,repo); return result;
  }
  private SortedMap<DimensionId,RefStore.Commit> resolve(String revision) throws IOException {
    // 分支名稱在全維度同步。單一 hash／HEAD~n 由入口維度的 snapshot 及祖先配對不變維度。
    boolean branch=false;
    for(var repo:repos.values()) branch |= repo.refs().branches().containsKey(revision);
    var result=new TreeMap<DimensionId,RefStore.Commit>();
    if(branch || revision.equals("HEAD")) {
      for(var entry:repos.entrySet()) {
        String id=branch ? entry.getValue().refs().branches().get(revision) : entry.getValue().refs().head();
        if(id==null) throw new IOException("維度缺少目標分支／HEAD："+entry.getKey());
        result.put(entry.getKey(),entry.getValue().refs().readCommit(id));
      }
      return result;
    }
    DimensionId anchor=repos.containsKey(DimensionId.OVERWORLD) ? DimensionId.OVERWORLD : repos.firstKey();
    RefStore.Commit commit;
    try { var refs=repos.get(anchor).refs(); commit=refs.readCommit(refs.resolve(revision)); }
    catch(IOException ex) {
      // 接受另一維度的 commit hash 作為 snapshot 入口。
      commit=null;
      for(var entry:repos.entrySet()) try { commit=entry.getValue().refs().readCommit(entry.getValue().refs().resolve(revision)); anchor=entry.getKey(); break; }
      catch(IOException ignored) {}
      if(commit==null) throw ex;
    }
    var snapshots=new ArrayList<UUID>();
    var refs=repos.get(anchor).refs();
    var cursor=commit;
    while(true) {
      snapshots.add(cursor.metadata().snapshot());
      if(cursor.parents().isEmpty()) break;
      cursor=refs.readCommit(cursor.parents().getFirst());
    }
    for(var entry:repos.entrySet()) {
      if(entry.getKey().equals(anchor)) { result.put(anchor,commit); continue; }
      try {
        var other=entry.getValue().refs();
        result.put(entry.getKey(),other.readCommit(other.resolve("refs/worldgit/groups/"+commit.metadata().snapshot())));
        continue;
      } catch(IOException missingGroup) { /* Phase 1 repo：退回 anchor 的 first-parent snapshot 配對。 */ }
      var bySnapshot=new HashMap<UUID,RefStore.Commit>();
      for(var candidate:entry.getValue().refs().allCommits()) {
        var existing=bySnapshot.putIfAbsent(candidate.metadata().snapshot(),candidate);
        if(existing!=null && !existing.id().equals(candidate.id())) throw new IOException("snapshot 對應多個 commit："+candidate.metadata().snapshot());
      }
      RefStore.Commit chosen=null;
      for(UUID snapshot:snapshots) if(bySnapshot.containsKey(snapshot)) { chosen=bySnapshot.get(snapshot); break; }
      if(chosen==null) throw new IOException("無法將 snapshot 配對到維度："+entry.getKey()+"；請改用同步分支名稱。");
      result.put(entry.getKey(),chosen);
    }
    return result;
  }

  private ApplyPlanner.Options options(DimensionRepository repo,RefStore.Commit target,boolean meta,boolean delete) throws IOException {
    DataVersions.requireSame(target.metadata().mcDataVersion(),layout.dataVersion());
    if(!target.metadata().dimension().equals(repo.dimension())) throw new IOException("目標 commit 的維度不符");
    String configPath=repo.dimension().equals(DimensionId.OVERWORLD) ? "world-meta/worldgit.yml" : "worldgit.yml";
    var targetConfig=TreeEditor.find(repo.objects(),target.tree(),configPath);
    if(targetConfig!=null) WorldGitConfig.readRepo(new String(repo.objects().readBlob(targetConfig.id()),StandardCharsets.UTF_8),configPath);
    var ruleEntry=TreeEditor.find(repo.objects(),target.tree(),".wgignore");
    String targetRules=ruleEntry==null ? "" : new String(repo.objects().readBlob(ruleEntry.id()),StandardCharsets.UTF_8);
    String currentRules=Files.exists(repo.ignorePath()) ? Files.readString(repo.ignorePath()) : "";
    if(!targetRules.equals(currentRules)) throw new IOException("目標與工作區的 .wgignore 不同；請先調整成一致，避免把未追蹤內容當空氣覆蓋。");
    var rules=IgnoreRules.parse(currentRules);
    if(delete && rules.hasAreas()) throw new IOException("有 area 排除規則時不可刪除 untracked chunk");
    return new ApplyPlanner.Options(rules,rules,EntityTagRegistry.load(layout.world(),layout.dataVersion(),live==null ? null : live.packs()),tolerance,delete,meta,target.metadata().mcDataVersion());
  }
  private String capture(DimensionRepository repo) throws IOException {
    try(var source=live==null ? new OfflineSnapshotSource(layout,layout.dimensions().get(repo.dimension())) : live.source(layout.dimensions().get(repo.dimension()))) {
      return repo.workingTree(source,worlds.manifest(),tolerance);
    }
  }
  private Prepared prepare(SortedMap<DimensionId,RefStore.Commit> targets,DimensionId dimension,Scope scope,boolean meta,boolean delete) throws IOException {
    var plans=new TreeMap<DimensionId,ApplyPlan>();
    var commits=new TreeMap<DimensionId,RefStore.Commit>();
    for(var entry:selected(dimension).entrySet()) {
      var target=targets.get(entry.getKey());
      var options=options(entry.getValue(),target,meta && entry.getKey().equals(DimensionId.OVERWORLD),delete);
      var plan=ApplyPlanner.plan(entry.getValue().objects(),entry.getKey(),capture(entry.getValue()),target.tree(),scope,options);
      applier.validateMetadata(plan);
      if(live!=null) live.validate(plan);
      plans.put(entry.getKey(),plan); commits.put(entry.getKey(),target);
    }
    return new Prepared(commits,plans);
  }
  private static SortedMap<DimensionId,ApplyPlan.Stats> stats(Prepared prepared) {
    var result=new TreeMap<DimensionId,ApplyPlan.Stats>(); prepared.plans.forEach((id,p)->result.put(id,p.stats())); return result;
  }
  public SortedMap<DimensionId,ApplyPlan> plan(String revision,DimensionId dimension,Scope scope,boolean metadata,boolean delete) throws IOException {
    return Collections.unmodifiableSortedMap(prepare(resolve(revision),dimension,scope,metadata,delete).plans);
  }
  public Result verify(String revision,DimensionId dimension,Scope scope,boolean metadata) throws IOException {
    var prepared=prepare(resolve(revision),dimension,scope,metadata,false);
    boolean zero=prepared.plans.values().stream().allMatch(ApplyPlan::empty);
    return new Result(zero ? State.COMPLETE : State.PARTIAL,stats(prepared),zero ? null : "世界與目標仍有差異");
  }
  public Result restore(String revision,DimensionId dimension,Scope scope,boolean dryRun,boolean delete) throws IOException {
    requireComplete();
    return execute("restore",prepare(resolve(revision),dimension,scope,scope.kind()==Scope.Kind.ALL,delete),null,false,dryRun);
  }
  public Result switchTo(String revision,boolean stash,boolean force,boolean dryRun,boolean delete) throws IOException {
    if(stash && force) throw new IOException("--stash 與 --force 不可同時使用");
    if(!force) requireComplete();
    var prepared=prepare(resolve(revision),null,Scope.all(),true,delete);
    if(dirty() && !force && !stash) throw new IOException("工作區有未提交變動；請 commit、stash push、switch --stash 或 --force。");
    if(stash && !dryRun && dirty()) saveStash("switch 前自動 stash");
    boolean branch=repos.values().stream().allMatch(r->{try{return r.refs().branches().containsKey(revision);}catch(IOException e){return false;}});
    return execute("switch",prepared,branch ? revision : null,true,dryRun);
  }
  public Result resetHard(String revision,boolean force,boolean dryRun) throws IOException {
    if(revision!=null && !force) throw new IOException("reset --hard <commit> 會改寫歷史，必須加 --force；不可用於已 push 的歷史。");
    return execute("reset",prepare(resolve(revision==null ? "HEAD" : revision),null,Scope.all(),true,false),null,revision!=null,dryRun);
  }

  private boolean dirty() throws IOException {
    return dirty(false);
  }
  private boolean dirty(boolean includeUntracked) throws IOException {
    for(var repo:repos.values()) {
      String head=repo.refs().head();
      if(head==null) return true;
      String tree=capture(repo);
      var editor=new TreeEditor(repo.objects(),tree);
      // switch 保留且標記的 untracked chunk 不阻擋後續切換；explicit commit 會重新追蹤它們。
      if(!includeUntracked) for(var pos:untracked(repo.directory())) if(TreeEditor.find(repo.objects(),repo.refs().readCommit(head).tree(),pos.treePath())==null) editor.remove(pos.treePath());
      String tracked=editor.write(); repo.objects().flush();
      if(!new DiffEngine(repo.objects()).compare(repo.dimension(),repo.refs().readCommit(head).tree(),tracked,tolerance,DiffEngine.Detail.SUMMARY).empty()) return true;
    }
    return false;
  }

  private Result execute(String mode,Prepared prepared,String branch,boolean moveHead,boolean dryRun) throws IOException {
    if(dryRun) return new Result(State.DRY_RUN,stats(prepared),null);
    var old=new TreeMap<DimensionId,RefStore.Head>();
    var journal=new LinkedHashMap<String,Object>();
    journal.put("version",1); journal.put("operation",UUID.randomUUID().toString()); journal.put("mode",mode);
    journal.put("state","APPLYING"); journal.put("time",Instant.now().toString());
    var rows=new TreeMap<String,Object>();
    for(var entry:prepared.commits.entrySet()) {
      var repo=repos.get(entry.getKey()); var head=repo.refs().headState(); old.put(entry.getKey(),head);
      var row=new LinkedHashMap<String,Object>(); row.put("from",head.commit()); row.put("from-branch",head.branch()); row.put("to",entry.getValue().id()); row.put("scope",prepared.plans.get(entry.getKey()).scope().toString()); row.put("applied",false);
      rows.put(entry.getKey().value(),row);
      // pin 原始與目標 commit；journal 可供重套，即使原分支後來移動也不會 GC 掉。
      String pin="refs/worldgit/operations/"+journal.get("operation");
      if(head.commit()!=null) repo.refs().updateRef(pin+"/from",null,head.commit());
      repo.refs().updateRef(pin+"/to",null,entry.getValue().id());
    }
    journal.put("dimensions",rows); writeJournal(journal);
    try {
      if(live!=null) live.applyAll(prepared.plans.values());
      else if(groupedWriter) applier.applyAll(prepared.plans.values(),session);
      for(var entry:prepared.plans.entrySet()) {
        if(live==null && !groupedWriter) writer.apply(entry.getValue(),session);
        repos.get(entry.getKey()).invalidateIndex();
        @SuppressWarnings("unchecked") var row=(Map<String,Object>)rows.get(entry.getKey().value());
        row.put("applied",true); writeJournal(journal);
      }
      var checked=prepare(prepared.commits,nullForSelection(prepared),prepared.plans.get(prepared.plans.firstKey()).scope(),
          prepared.plans.values().stream().anyMatch(p->!p.worldMeta().isEmpty()),false);
      for(var entry:checked.plans.entrySet()) if(!entry.getValue().empty()) throw new IOException("套用驗證失敗："+entry.getKey()+" "+entry.getValue().stats());
      if(moveHead) for(var entry:prepared.commits.entrySet()) {
        var repo=repos.get(entry.getKey()); var prior=old.get(entry.getKey());
        if(mode.equals("reset") && prior.branch()!=null) {
          repo.refs().updateRef("refs/heads/"+prior.branch(),prior.commit(),entry.getValue().id());
        } else repo.refs().checkout(prior,new RefStore.Head(entry.getValue().id(),branch));
      }
      for(var entry:prepared.plans.entrySet()) {
        var repo=repos.get(entry.getKey());
        var kept=new TreeSet<>(entry.getValue().untrackedKept());
        for(var pos:untracked(repo.directory())) if(!entry.getValue().scope().touchesChunk(pos)) kept.add(pos);
        saveUntracked(repo.directory(),new ArrayList<>(kept));
        if(moveHead) restoreConfig(repo,prepared.commits.get(entry.getKey()));
        repo.invalidateIndex();
      }
      journal.put("state","COMPLETE"); writeJournal(journal);
      return new Result(State.COMPLETE,stats(prepared),null);
    } catch(Exception ex) {
      var errors=new ArrayList<String>(); errors.add(message(ex));
      for(var entry:old.entrySet()) try {
        var refs=repos.get(entry.getKey()).refs(); var current=refs.headState(); var prior=entry.getValue();
        if(prior.branch()!=null && !Objects.equals(refs.branches().get(prior.branch()),prior.commit()))
          refs.updateRef("refs/heads/"+prior.branch(),refs.branches().get(prior.branch()),prior.commit());
        if(!refs.headState().equals(prior)) refs.checkout(refs.headState(),prior);
      } catch(Exception rollback) { errors.add("HEAD 回復失敗："+entry.getKey()+" "+message(rollback)); }
      journal.put("state","PARTIAL"); journal.put("error",String.join("；",errors)); writeJournal(journal);
      return new Result(State.PARTIAL,stats(prepared),String.join("；",errors));
    }
  }
  private DimensionId nullForSelection(Prepared prepared) { return prepared.plans.size()==repos.size() ? null : prepared.plans.firstKey(); }
  private void restoreConfig(DimensionRepository repo,RefStore.Commit target) throws IOException {
    String path=repo.dimension().equals(DimensionId.OVERWORLD) ? "world-meta/worldgit.yml" : "worldgit.yml";
    var config=TreeEditor.find(repo.objects(),target.tree(),path);
    if(config!=null) { var bytes=repo.objects().readBlob(config.id()); WorldGitConfig.readRepo(new String(bytes,StandardCharsets.UTF_8),path); RegionFile.atomicWrite(repo.configPath(),bytes); }
  }
  private void writeJournal(Map<String,Object> journal) throws IOException { OperationState.write(worlds.root().resolve("apply-state.yml"),journal); }
  private static String message(Exception ex) { return ex.getMessage()==null ? ex.getClass().getSimpleName() : ex.getMessage(); }

  public List<Branch> branches() throws IOException {
    var names=new TreeSet<String>(); for(var repo:repos.values()) names.addAll(repo.refs().branches().keySet());
    var result=new ArrayList<Branch>();
    for(String name:names) {
      var commits=new TreeMap<DimensionId,String>(); boolean current=true;
      for(var entry:repos.entrySet()) { String id=entry.getValue().refs().branches().get(name); if(id!=null) commits.put(entry.getKey(),id); current &= name.equals(entry.getValue().refs().headState().branch()); }
      result.add(new Branch(name,current,commits,commits.size()==repos.size()));
    }
    return result;
  }
  public void createBranch(String name,String start) throws IOException {
    requireComplete(); JGitStore.validateBranch(name);
    var targets=resolve(start==null ? "HEAD" : start);
    for(var repo:repos.values()) if(repo.refs().branches().containsKey(name)) throw new IOException("分支已存在："+name);
    mutateBranch(name,targets,false);
  }
  public void deleteBranch(String name) throws IOException {
    requireComplete(); JGitStore.validateBranch(name);
    var targets=new TreeMap<DimensionId,RefStore.Commit>();
    for(var entry:repos.entrySet()) {
      var refs=entry.getValue().refs(); var head=refs.headState();
      if(name.equals(head.branch())) throw new IOException("不可刪除目前分支："+name);
      String id=refs.branches().get(name); if(id==null) throw new IOException("維度缺少分支："+entry.getKey());
      if(!refs.isAncestor(id,head.commit())) throw new IOException("分支尚未合併："+name);
      targets.put(entry.getKey(),refs.readCommit(id));
    }
    mutateBranch(name,targets,true);
  }
  private void mutateBranch(String name,SortedMap<DimensionId,RefStore.Commit> targets,boolean delete) throws IOException {
    var completed=new ArrayList<DimensionId>();
    try { for(var entry:targets.entrySet()) { repos.get(entry.getKey()).refs().updateRef("refs/heads/"+name,delete ? entry.getValue().id() : null,delete ? null : entry.getValue().id()); completed.add(entry.getKey()); } }
    catch(IOException ex) {
      for(var id:completed) try { repos.get(id).refs().updateRef("refs/heads/"+name,delete ? null : targets.get(id).id(),delete ? targets.get(id).id() : null); }
      catch(IOException rollback) { ex.addSuppressed(rollback); }
      if(ex.getSuppressed().length>0) { var state=new LinkedHashMap<String,Object>(); state.put("state","PARTIAL"); state.put("mode","branch"); state.put("error",message(ex)); writeJournal(state); }
      throw ex;
    }
  }

  @SuppressWarnings("unchecked") public List<Stash> stashes() throws IOException {
    var state=OperationState.read(worlds.root().resolve("stash.yml"));
    var result=new ArrayList<Stash>();
    for(var value:(List<Map<String,Object>>)state.getOrDefault("entries",List.of())) {
      var commits=new TreeMap<DimensionId,String>(); var bases=new TreeMap<DimensionId,String>();
      ((Map<String,String>)value.get("commits")).forEach((k,v)->commits.put(new DimensionId(k),v));
      ((Map<String,String>)value.get("bases")).forEach((k,v)->bases.put(new DimensionId(k),v));
      result.add(new Stash((String)value.get("id"),(String)value.get("time"),(String)value.get("message"),commits,bases));
    }
    return result;
  }
  private void writeStashes(List<Stash> stashes) throws IOException {
    var entries=new ArrayList<Map<String,Object>>();
    for(var stash:stashes) {
      var commits=new TreeMap<String,String>(); var bases=new TreeMap<String,String>(); stash.commits.forEach((k,v)->commits.put(k.value(),v)); stash.bases.forEach((k,v)->bases.put(k.value(),v));
      entries.add(Map.of("id",stash.id,"time",stash.time,"message",stash.message,"commits",commits,"bases",bases));
    }
    OperationState.write(worlds.root().resolve("stash.yml"),Map.of("version",1,"entries",entries));
  }
  private Stash saveStash(String message) throws IOException {
    String id=UUID.randomUUID().toString(); var commits=new TreeMap<DimensionId,String>(); var bases=new TreeMap<DimensionId,String>();
    var author=new CommitMetadata.Identity("WorldGit stash","worldgit@localhost");
    var now=Instant.now();
    // capture 全組完成才發布清單；失敗留下 dangling/pinned 物件，但不丟失工作區。
    var trees=new TreeMap<DimensionId,String>(); for(var entry:repos.entrySet()) trees.put(entry.getKey(),capture(entry.getValue()));
    for(var entry:repos.entrySet()) {
      String base=entry.getValue().refs().head(); bases.put(entry.getKey(),base);
      var metadata=new CommitMetadata(author,author,message,now,layout.dataVersion(),entry.getKey(),CommitMetadata.Source.CLI,false,UUID.fromString(id),List.of());
      String commit=entry.getValue().refs().createCommit(trees.get(entry.getKey()),base,metadata);
      entry.getValue().refs().updateRef("refs/worldgit/stash/"+id,null,commit); commits.put(entry.getKey(),commit);
    }
    var stash=new Stash(id,now.toString(),message,commits,bases);
    var stashes=new ArrayList<>(stashes()); stashes.addFirst(stash); writeStashes(stashes); return stash;
  }
  public Result stashPush(String message,boolean dryRun) throws IOException {
    requireComplete();
    var prepared=prepare(resolve("HEAD"),null,Scope.all(),true,true);
    if(dryRun) return new Result(State.DRY_RUN,stats(prepared),null);
    if(!dirty() && prepared.plans.values().stream().allMatch(ApplyPlan::empty)) return new Result(State.COMPLETE,stats(prepared),null);
    saveStash(message==null ? "工作區 stash" : message);
    return execute("stash-push",prepared,null,false,false);
  }
  private Stash stashAt(int index) throws IOException { var list=stashes(); if(index<0 || index>=list.size()) throw new IOException("找不到 stash@{"+index+"}"); return list.get(index); }
  public Result stashPop(int index,boolean dryRun) throws IOException {
    requireComplete(); var stash=stashAt(index);
    if(!stash.commits.keySet().equals(repos.keySet())) throw new IOException("stash 維度組與目前不一致");
    for(var entry:repos.entrySet()) if(!Objects.equals(entry.getValue().refs().head(),stash.bases.get(entry.getKey()))) throw new IOException("stash 基底與 HEAD 不同；請先切回原基底。跨分支 stash 合併留待 Phase 3。");
    if(dirty(true)) throw new IOException("stash pop 要求乾淨工作區（包含保留的 untracked chunk）；請先 commit 或 stash push。");
    var targets=new TreeMap<DimensionId,RefStore.Commit>(); stash.commits.forEach((id,c)-> { try { targets.put(id,repos.get(id).refs().readCommit(c)); } catch(IOException ex) { throw new UncheckedIOException(ex); } });
    var result=execute("stash-pop",prepare(targets,null,Scope.all(),true,true),null,false,dryRun);
    if(result.success() && !dryRun) stashDrop(index); return result;
  }
  public void stashDrop(int index) throws IOException {
    requireComplete(); var stash=stashAt(index); var list=new ArrayList<>(stashes()); list.remove(index);
    // 先更新目錄，ref 清理失敗只會保守保留物件；不會留下不可讀的 stash。
    writeStashes(list);
    for(var entry:stash.commits.entrySet()) {
      var refs=repos.get(entry.getKey()).refs();
      refs.updateRef("refs/worldgit/stash/"+stash.id,entry.getValue(),null);
      refs.updateRef("refs/worldgit/snapshots/"+stash.id+"/"+entry.getValue(),entry.getValue(),null);
    }
  }

  public static Set<ChunkPos> untracked(Path directory) throws IOException {
    var result=new TreeSet<ChunkPos>();
    Object values=OperationState.read(directory.resolve("untracked.yml")).getOrDefault("chunks",List.of());
    if(!(values instanceof List<?> list)) throw new IOException("untracked 清單損毀");
    for(Object value:list) { String[] pair=value.toString().split(","); if(pair.length!=2) throw new IOException("untracked 座標無效"); result.add(new ChunkPos(Integer.parseInt(pair[0]),Integer.parseInt(pair[1]))); }
    return result;
  }
  private static void saveUntracked(Path directory,List<ChunkPos> positions) throws IOException {
    OperationState.write(directory.resolve("untracked.yml"),Map.of("chunks",positions.stream().map(p->p.x()+","+p.z()).toList()));
  }
  @Override public void close() throws IOException {
    IOException error=null;
    for(var repo:repos.values()) try { repo.close(); } catch(IOException ex) { error=ex; }
    try { if(session!=null) session.close(); } catch(IOException ex) { error=ex; }
    try { groupLock.close(); } catch(IOException ex) { error=ex; }
    if(error!=null) throw error;
  }
}
