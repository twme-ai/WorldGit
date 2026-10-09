package org.worldgit.paper;

import org.worldgit.core.service.*;
import org.worldgit.platform.*;
import java.util.concurrent.*;

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

/** 一組維度的線上復原入口。全組 repo 鎖、預檢、journal、owner 套用、驗證、HEAD barrier。 */
final class PaperOperations implements AutoCloseable {
  public enum State { COMPLETE, DRY_RUN, PARTIAL }
  public record Result(State state, SortedMap<DimensionId,ApplyPlan.Stats> dimensions, String error) {
    public Result { dimensions=Collections.unmodifiableSortedMap(new TreeMap<>(dimensions)); }
    public boolean success() { return state != State.PARTIAL; }
  }
  public record Branch(String name, boolean current, SortedMap<DimensionId,String> commits, boolean consistent) {}
  public record Stash(String id,String time,String message,SortedMap<DimensionId,String> commits,SortedMap<DimensionId,String> bases) {}
  private record Prepared(SortedMap<DimensionId,RefStore.Commit> commits,SortedMap<DimensionId,ApplyPlan> plans) {}
  private final WorldLayout layout;
  private final WorldRepositories worlds;
  private final SortedMap<DimensionId,DimensionRepository> repos=new TreeMap<>();
  private final RepoLock groupLock;
  private final Path stateRoot;
  private final DimensionId selectedDimension;
  private WorldOperations core;
  private final WorldGitPlugin plugin;
  private final WorldMapper.Mapping mapping;
  private final ApplyQueue queue;
  private final Map<DimensionId,PaperLiveWorld> live = new TreeMap<>();
  private final OfflineApplier applier;
  private final Map<DimensionId,String> captured=new HashMap<>();
  private final Map<UUID,Nbt.Compound> removedEntities=new ConcurrentHashMap<>();

  private boolean regionMode;
  private Map<DimensionId,Set<ChunkPos>> regionChunks=Map.of();
  private final double tolerance;

  PaperOperations(WorldGitPlugin plugin, WorldMapper.Mapping mapping, ApplyQueue queue) throws IOException {
    this(plugin,mapping,queue,false);
  }
  PaperOperations(WorldGitPlugin plugin, WorldMapper.Mapping mapping, ApplyQueue queue, boolean merge) throws IOException {
    this(plugin, mapping, queue, merge, mapping.layout().currentDimension());
  }
  PaperOperations(WorldGitPlugin plugin, WorldMapper.Mapping mapping, ApplyQueue queue, boolean merge, DimensionId dimension) throws IOException {
    this.selectedDimension=dimension; this.stateRoot=new WorldRepositories(mapping.layout()).tracked().get(dimension);
    if(stateRoot==null) throw new IOException("維度尚未 init："+dimension);
    this.plugin=plugin; this.mapping=mapping; this.queue=queue; this.layout=mapping.layout();
    worlds=new WorldRepositories(layout); applier=new OfflineApplier(layout);
    tolerance=WorldGitConfig.readLocal(stateRoot.resolve("worldgit.yml")).entityTolerance();
    groupLock=null;
    try {
      for(var entry:worlds.tracked().entrySet()) {
        if(!entry.getKey().equals(dimension)) continue;
        if(!mapping.worlds().containsKey(entry.getKey())) throw new IOException("維度未在線："+entry.getKey());
        if(!merge) repos.put(entry.getKey(),new DimensionRepository(entry.getValue(),entry.getKey(),false));
        live.put(entry.getKey(),new PaperLiveWorld(plugin,plugin.state(entry.getKey(),mapping.worlds().get(entry.getKey())),mapping.worlds().get(entry.getKey()),layout,false));
      }
      if(live.isEmpty()) throw new IOException("世界尚未 init");
      if(merge) core=WorldOperations.live(layout,new WorldOperations.LiveAccess() {
        public SnapshotSource source(WorldLayout.Dimension dimension) throws IOException {
          var world=mapping.worlds().get(dimension.id());
          if(world==null) throw new IOException("維度未在線："+dimension.id());
          var source=new PaperLiveWorld(plugin,plugin.state(dimension.id(),world),world,layout,false);
          source.receipts(atomicReceipts.getOrDefault(dimension.id(),Map.of()));
          try(var timing=OperationTimings.stage("flush")) { await(source.flush()); return source; } catch(IOException e) { source.close(); throw e; }
        }
        public SnapshotSource source(WorldLayout.Dimension dimension,Set<ChunkPos> chunks) throws IOException {
          var world=mapping.worlds().get(dimension.id());
          var source=new PaperLiveWorld(plugin,plugin.state(dimension.id(),world),world,layout,false);
          source.receipts(atomicReceipts.getOrDefault(dimension.id(),Map.of()));
          try(var timing=OperationTimings.stage("flush")) {
            await(source.flush(chunks)); source.captureOnly(chunks); return source;
          } catch(IOException ex) { source.close(); throw ex; }
        }
        public Set<ChunkPos> entityChunks(WorldLayout.Dimension dimension,Set<UUID> ids) throws IOException {
          return matchingEntityChunks(dimension.id(),ids);
        }
        public AutoCloseable lockChunks(Map<DimensionId,Set<ChunkPos>> chunks) throws IOException {
          var locks=new ArrayList<AutoCloseable>();
          try(var timing=OperationTimings.stage("lock")) {
            for(var entry:chunks.entrySet()) locks.add(live.get(entry.getKey()).lockEdits(entry.getValue(),"WorldGit region"));
          } catch(Exception ex) {
            for(var lock:locks) try { lock.close(); } catch(Exception cleanup) { ex.addSuppressed(cleanup); }
            throw ex instanceof IOException io ? io : new IOException(ex);
          }
          regionChunks=chunks;
          return ()->{ for(int i=locks.size()-1;i>=0;i--) locks.get(i).close(); };
        }
        public void applyRegions(Collection<ApplyPlan> plans) throws IOException {
          var actual=new TreeMap<DimensionId,Set<ChunkPos>>();
          for(var plan:plans) {
            var chunks=new TreeSet<ChunkPos>(plan.scope().chunks());
            actual.put(plan.dimension(),chunks);
          }
          regionChunks=actual;
          regionMode=true;
          try { applyAll(plans); } finally { regionMode=false; }
        }
        public void beforeComplete() throws IOException {
          if(queue.stopping || queue.cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("操作已取消或插件關閉中");
        }
        public AutoCloseable guardApply(Collection<ApplyPlan> plans) throws IOException { return guardAffectedChunks(plans); }
        public void validate(ApplyPlan plan) throws IOException { beforeComplete(); validateOnline(plan); }
        public void applyAll(Collection<ApplyPlan> plans) throws IOException {
          var all=new TreeMap<DimensionId,ApplyPlan>(); plans.forEach(p->all.put(p.dimension(),p));
          var operation=UUID.randomUUID();
          var protectedChunks=new TreeMap<DimensionId,Set<ChunkPos>>();
          for(var p:plans) {
            var chunks=new TreeSet<ChunkPos>(p.chunks().keySet());
            for(var e:p.entities()) { if(e.hint()!=null) chunks.add(e.hint()); if(e.target()!=null) chunks.add(e.targetChunk()); }
            protectedChunks.put(p.dimension(),chunks);
          }
          plugin.repo().planned(plans.stream().mapToInt(p->p.stats().sections()).sum());
          try {
            for(var e:protectedChunks.entrySet()) await(live.get(e.getKey()).protectPlayers(PlayerProtection.operation(e.getValue(),operation,true)));
            PaperOperations.this.applyAll(all);
          } finally {
            if(!queue.stopping) for(var e:protectedChunks.entrySet()) await(live.get(e.getKey()).protectPlayers(PlayerProtection.operation(e.getValue(),operation,false)));
          }
        }
      }, dimension);
    } catch(Exception ex) { close(); throw ex; }
  }
  WorldOperations core() { return Objects.requireNonNull(core); }
  private <T> T await(CompletionStage<T> stage) throws IOException {
    try { return stage.toCompletableFuture().get(plugin.settings().commitTimeoutSeconds(),TimeUnit.SECONDS); }
    catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("操作中斷",ex); }
    catch(ExecutionException|TimeoutException ex) { throw new IOException(ex.getCause()==null ? ex.getMessage() : ex.getCause().toString(),ex); }
  }

  String head() throws IOException { return repos.get(repos.containsKey(DimensionId.OVERWORLD) ? DimensionId.OVERWORLD : repos.firstKey()).refs().head(); }

  public Map<String,Object> journal() throws IOException { return OperationState.read(stateRoot.resolve("apply-state.yml")); }
  private void requireNotMerging() throws IOException {
    if(org.worldgit.core.merge.MergeState.read(stateRoot.resolve("merge-state.bin"))!=null) throw new IOException("世界為 MERGING；請 resolve 後 merge --continue，或 merge --abort。若為 PARTIAL 必須 merge --abort。");
  }
  private void requireComplete() throws IOException {
    requireNotMerging();
    if(OperationState.partial(stateRoot)) throw new IOException("世界為 PARTIAL；請用 switch --force 或 reset --hard 全範圍重新套用以恢復。");
  }
  private SortedMap<DimensionId,DimensionRepository> selected(DimensionId dimension) throws IOException {
    if(dimension==null) return repos;
    var repo=repos.get(dimension);
    if(repo==null) throw new IOException("維度尚未 init："+dimension);
    var result=new TreeMap<DimensionId,DimensionRepository>(); result.put(dimension,repo); return result;
  }
  private SortedMap<DimensionId,RefStore.Commit> resolve(String revision) throws IOException {
    var result=new TreeMap<DimensionId,RefStore.Commit>();
    for(var entry:repos.entrySet()) {
      var refs=entry.getValue().refs(); result.put(entry.getKey(), refs.readCommit(refs.resolve(revision)));
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
    return new ApplyPlanner.Options(rules,rules,EntityTagRegistry.load(layout.world(),layout.dataVersion(),null),tolerance,delete,meta,target.metadata().mcDataVersion());
  }
  private String capture(DimensionRepository repo) throws IOException {
    if(captured.containsKey(repo.dimension())) return captured.get(repo.dimension());
    var source=live.get(repo.dimension());
    await(source.flush());
    String tree=repo.workingTree(source,worlds.manifest(),tolerance);
    captured.put(repo.dimension(),tree); return tree;
  }

  private Prepared prepare(SortedMap<DimensionId,RefStore.Commit> targets,DimensionId dimension,Scope scope,boolean meta,boolean delete) throws IOException {
    var plans=new TreeMap<DimensionId,ApplyPlan>();
    var commits=new TreeMap<DimensionId,RefStore.Commit>();
    for(var entry:selected(dimension).entrySet()) {
      var target=targets.get(entry.getKey());
      var options=options(entry.getValue(),target,meta,delete);
      var plan=ApplyPlanner.plan(entry.getValue().objects(),entry.getKey(),capture(entry.getValue()),target.tree(),scope,options);
      validateOnline(plan);

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
    try(var full=new org.worldgit.core.capture.CaptureOptions(true)) {
      captured.clear();
      var prepared=prepare(resolve(revision),dimension,scope,metadata,false);
      boolean zero=prepared.plans.values().stream().allMatch(ApplyPlan::empty);
      return new Result(zero ? State.COMPLETE : State.PARTIAL,stats(prepared),zero ? null : "世界與目標仍有差異");
    }
  }
  public Result restore(String revision,DimensionId dimension,Scope scope,boolean dryRun,boolean delete) throws IOException {
    requireComplete();
    return execute("restore",prepare(resolve(revision),dimension,scope,scope.kind()==Scope.Kind.ALL,delete),null,false,dryRun);
  }
  public Result switchTo(String revision,boolean stash,boolean force,boolean dryRun,boolean delete) throws IOException {
    requireNotMerging();
    if(stash && force) throw new IOException("--stash 與 --force 不可同時使用");
    if(!force) requireComplete();
    var prepared=prepare(resolve(revision),null,Scope.all(),true,delete);
    if(dirty() && !force && !stash) throw new IOException("工作區有未提交變動；請 commit、stash push、switch --stash 或 --force。");
    if(stash && !dryRun && dirty()) saveStash("switch 前自動 stash");
    boolean branch=repos.values().stream().allMatch(r->{try{return r.refs().branches().containsKey(revision);}catch(IOException e){return false;}});
    if(!force && !stash && repos.size()==1) {
      var repo=repos.get(repos.firstKey());var target=prepared.commits.get(repo.dimension());var prior=repo.refs().headState();
      if(prior.commit()!=null && target.tree().equals(repo.refs().readCommit(prior.commit()).tree())
          && target.tree().equals(prepared.plans.get(repo.dimension()).baseTree())) {
        if(dryRun) return new Result(State.DRY_RUN,stats(prepared),null);
        synchronized(plugin.repo()) {
          if(queue.cancelled.get() || queue.stopping) throw new IOException("操作已取消");
          String operation=UUID.randomUUID().toString();var row=new LinkedHashMap<String,Object>();
          row.put("from",prior.commit());row.put("from-branch",prior.branch());row.put("to",target.id());
          row.put("scope",prepared.plans.get(repo.dimension()).scope().toString());row.put("applied",true);
          var journal=new LinkedHashMap<String,Object>();journal.put("version",1);journal.put("operation",operation);
          journal.put("mode","switch");journal.put("state","COMPLETE");journal.put("head-only",true);
          journal.put("time",Instant.now().toString());journal.put("dimensions",Map.of(repo.dimension().value(),row));
          repo.refs().updateRef("refs/worldgit/operations/"+operation+"/from",null,prior.commit());
          repo.refs().updateRef("refs/worldgit/operations/"+operation+"/to",null,target.id());
          var next=new RefStore.Head(target.id(),branch ? revision : null);repo.refs().checkout(prior,next);
          try {repo.rebindIndex();writeJournal(journal);} catch(IOException failure) {
            try {repo.refs().checkout(next,prior);}catch(IOException rollback){failure.addSuppressed(rollback);}throw failure;
          }
        }
        return new Result(State.COMPLETE,stats(prepared),null);
      }
    }
    return execute("switch",prepared,branch ? revision : null,true,dryRun);
  }
  public Result resetHard(String revision,boolean force,boolean dryRun) throws IOException {
    requireNotMerging();
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
    boolean writes=prepared.plans.values().stream().anyMatch(p->!p.empty());
    try(var pause=writes ? guardAffectedChunks(prepared.plans.values()) : (AutoCloseable)()->{}) {
      if(writes) {
        captured.clear();
        for(var entry:prepared.plans.entrySet()) {
          var repo=repos.get(entry.getKey());
          if(!new DiffEngine(repo.objects()).compare(entry.getKey(),entry.getValue().baseTree(),
              capture(repo),tolerance,DiffEngine.Detail.SUMMARY,LiveApplyVerification.affected(entry.getValue())).empty())
            throw new IOException("預檢期間世界已變動，尚未寫入；請重試操作");
        }
      }
      return executeGuarded(mode,prepared,branch,moveHead);
    } catch(IOException e) {throw e;} catch(Exception e) {throw new IOException("世界 tick 屏障失敗",e);}
  }

  private Set<ChunkPos> matchingEntityChunks(DimensionId dimension,Set<UUID> ids) throws IOException {
    var source=live.get(dimension);
    var found=new TreeSet<ChunkPos>(source.storedEntityChunks(ids));
    var world=mapping.worlds().get(dimension);
    // Census is only a candidate list; lock chunks containing the requested UUIDs.
    for(var pos:plugin.state(dimension,world).refresh(plugin.bridge(),world).entityChunks()) {
      var done=new CompletableFuture<Boolean>();
      plugin.platform().region(world,pos.x(),pos.z(),()->{
        try { var raw=plugin.bridge().copy(world,pos.x(),pos.z());
          var matches=new HashSet<UUID>(); if(raw!=null) raw.entities().forEach(n->collectIds(n,matches));
          done.complete(!Collections.disjoint(ids,matches));
        } catch(Throwable e) { done.completeExceptionally(e); }
      });
      if(await(done)) found.add(pos);
    }
    return found;
  }

  private boolean atomicApply;
  private final Map<DimensionId,Map<ChunkPos,NmsBridge.RawChunk>> atomicReceipts=new TreeMap<>();
  private AutoCloseable guardAffectedChunks(Collection<ApplyPlan> plans) throws IOException {
    atomicApply=!plugin.platform().folia() && plans.size()==1 && plans.stream().allMatch(p->plugin.settings().atomicApply().eligible(p)
        && live.get(p.dimension()).entityChunks().stream().allMatch(c->p.entities().isEmpty() || LiveApplyVerification.affected(p).contains(c))
        && plugin.state(p.dimension(),mapping.worlds().get(p.dimension())).refresh(plugin.bridge(),mapping.worlds().get(p.dimension())).loaded().containsAll(LiveApplyVerification.affected(p)));
    if(atomicApply) {
      var plan=plans.iterator().next();var world=mapping.worlds().get(plan.dimension());var pos=LiveApplyVerification.affected(plan).iterator().next();
      var safe=new CompletableFuture<Boolean>();plugin.platform().region(world,pos.x(),pos.z(),()->{try {safe.complete(plugin.bridge().atomicLightingSafe(world,plan));}catch(Throwable error){safe.completeExceptionally(error);}});
      try {atomicApply=safe.get(plugin.settings().commitTimeoutSeconds(),TimeUnit.SECONDS);}catch(Exception error){throw new IOException("Atomic lighting preflight failed",error);}
    }
    if(atomicApply) return ()->{atomicApply=false;};
    var locks=new ArrayList<AutoCloseable>();
    try {
      for(var plan:plans) if(!plan.empty()) {
        var chunks=new TreeSet<>(plan.chunks().keySet());
        var ids=new HashSet<UUID>();
        for(var entity:plan.entities()) { ids.add(entity.uuid()); if(entity.hint()!=null)chunks.add(entity.hint());if(entity.targetChunk()!=null)chunks.add(entity.targetChunk()); }
        if(!ids.isEmpty())chunks.addAll(matchingEntityChunks(plan.dimension(),ids));
        locks.add(live.get(plan.dimension()).lockEdits(chunks,"WorldGit apply"));
      }
      return ()->{for(int i=locks.size()-1;i>=0;i--) locks.get(i).close();};
    } catch(IOException e) {for(var lock:locks) try {lock.close();}catch(Exception cleanup){e.addSuppressed(cleanup);}throw e;}
  }

  private Result executeGuarded(String mode,Prepared prepared,String branch,boolean moveHead) throws IOException {
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
    var operation=UUID.fromString(journal.get("operation").toString());
    var protectedChunks=new TreeMap<DimensionId,Set<ChunkPos>>();
    for(var entry:prepared.plans.entrySet()) {
      var chunks=new TreeSet<ChunkPos>(entry.getValue().chunks().keySet());
      for(var e:entry.getValue().entities()) { if(e.hint()!=null) chunks.add(e.hint()); if(e.target()!=null) chunks.add(e.targetChunk()); }
      protectedChunks.put(entry.getKey(),chunks);
    }
    plugin.repo().planned(prepared.plans.values().stream().mapToInt(p->p.stats().sections()).sum());
    try {
      for(var entry:protectedChunks.entrySet()) await(live.get(entry.getKey()).protectPlayers(PlayerProtection.operation(entry.getValue(),operation,true)));
      applyAll(prepared.plans);
      captured.clear();
      for(var entry:prepared.plans.entrySet()) {
        if(entry.getValue().scope().kind()==Scope.Kind.ALL) restoreConfig(repos.get(entry.getKey()),prepared.commits.get(entry.getKey()));
        var changed=new TreeSet<>(entry.getValue().chunks().keySet());
        for(var entity:entry.getValue().entities()) {
          if(entity.hint()!=null) changed.add(entity.hint());
          if(entity.targetChunk()!=null) changed.add(entity.targetChunk());
        }
        repos.get(entry.getKey()).forceRecapture(changed);
        @SuppressWarnings("unchecked") var row=(Map<String,Object>)rows.get(entry.getKey().value());
        row.put("applied",true); writeJournal(journal);
      }
      org.worldgit.core.operation.OperationProgress.report(selectedDimension,"verify",0,null,org.worldgit.core.operation.OperationProgress.Unit.CHUNK);
      var checkedPlans=new TreeMap<DimensionId,ApplyPlan>();
      for(var entry:prepared.plans.entrySet()) {
        var repo=repos.get(entry.getKey());var plan=entry.getValue();
        var observed=LiveApplyVerification.observedFootprint(repo.objects(),plan,capture(repo));
        var check=ApplyPlanner.plan(repo.objects(),entry.getKey(),observed,plan.targetTree(),plan.scope(),options(repo,prepared.commits.get(entry.getKey()),!plan.worldMeta().isEmpty(),false));
        if(!check.empty()) throw new IOException("受影響 chunk 套用驗證失敗："+entry.getKey()+" "+check.stats());
        checkedPlans.put(entry.getKey(),check);
      }
      var checked=new Prepared(prepared.commits,checkedPlans);
      synchronized(plugin.repo()) {
        if(queue.cancelled.get() || queue.stopping) throw new IOException("操作已取消");
        if(moveHead) for(var entry:prepared.commits.entrySet()) {
          var repo=repos.get(entry.getKey()); var prior=old.get(entry.getKey());
          if(mode.equals("reset") && prior.branch()!=null) {
            repo.refs().updateRef("refs/heads/"+prior.branch(),prior.commit(),entry.getValue().id());
          } else repo.refs().checkout(prior,new RefStore.Head(entry.getValue().id(),branch));
        }
        for(var entry:prepared.plans.entrySet()) {
          var repo=repos.get(entry.getKey());
          var kept=new TreeSet<>(entry.getValue().untrackedKept());
          kept.addAll(checked.plans.get(entry.getKey()).untrackedKept());
          for(var pos:untracked(repo.directory())) if(!entry.getValue().scope().touchesChunk(pos)) kept.add(pos);
          saveUntracked(repo.directory(),new ArrayList<>(kept));
          if(moveHead) restoreConfig(repo,prepared.commits.get(entry.getKey()));
          repo.rebindIndex();
        }
        if(queue.cancelled.get() || queue.stopping) throw new IOException("操作已取消");
        journal.put("state","COMPLETE"); writeJournal(journal);
      }
      return new Result(State.COMPLETE,stats(prepared),null);
    } catch(Exception ex) {
      if(queue.stopping) {
        // 停服 journal 仍由持 repo lock 的 worker 寫，owner 不做磁碟 IO。
        boolean interrupted=Thread.interrupted();
        try {journal.put("state","PARTIAL");journal.put("error","插件關閉中");writeJournal(journal);}
        finally {if(interrupted)Thread.currentThread().interrupt();}
        return new Result(State.PARTIAL,stats(prepared),"插件關閉中");
      }
      var errors=new ArrayList<String>(); errors.add(message(ex));
      for(var entry:old.entrySet()) try {
        var refs=repos.get(entry.getKey()).refs(); var current=refs.headState(); var prior=entry.getValue();
        if(prior.branch()!=null && !Objects.equals(refs.branches().get(prior.branch()),prior.commit()))
          refs.updateRef("refs/heads/"+prior.branch(),refs.branches().get(prior.branch()),prior.commit());
        if(!refs.headState().equals(prior)) refs.checkout(refs.headState(),prior);
      } catch(Exception rollback) { errors.add("HEAD 回復失敗："+entry.getKey()+" "+message(rollback)); }
      journal.put("state","PARTIAL"); journal.put("error",String.join("；",errors)); writeJournal(journal);
      return new Result(State.PARTIAL,stats(prepared),String.join("；",errors));
    } finally {
      if(!queue.stopping) for(var entry:protectedChunks.entrySet()) await(live.get(entry.getKey()).protectPlayers(PlayerProtection.operation(entry.getValue(),operation,false)));
    }
  }
  private DimensionId nullForSelection(Prepared prepared) { return prepared.plans.size()==repos.size() ? null : prepared.plans.firstKey(); }
  private void restoreConfig(DimensionRepository repo,RefStore.Commit target) throws IOException {
    org.worldgit.core.capture.PlayerTouchedEntities.restore(repo.objects(),target.tree(),repo.directory());
    String path=repo.dimension().equals(DimensionId.OVERWORLD) ? "world-meta/worldgit.yml" : "worldgit.yml";
    var config=TreeEditor.find(repo.objects(),target.tree(),path);
    if(config!=null) { var bytes=repo.objects().readBlob(config.id()); WorldGitConfig.readRepo(new String(bytes,StandardCharsets.UTF_8),path); RegionFile.atomicWrite(repo.configPath(),bytes); }
  }
  private void writeJournal(Map<String,Object> journal) throws IOException {
    synchronized(plugin.repo()) {
      if(queue.stopping) { journal.put("state","PARTIAL"); journal.put("error","插件關閉中"); }
      OperationState.write(stateRoot.resolve("apply-state.yml"),journal);
    }
  }
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
    var state=OperationState.read(stateRoot.resolve("stash.yml"));
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
    OperationState.write(stateRoot.resolve("stash.yml"),Map.of("version",1,"entries",entries));
  }
  private Stash saveStash(String message) throws IOException {
    String id=UUID.randomUUID().toString(); var commits=new TreeMap<DimensionId,String>(); var bases=new TreeMap<DimensionId,String>();
    var author=new CommitMetadata.Identity("WorldGit stash","worldgit@localhost");
    var now=Instant.now();
    // capture 全組完成才發布清單；失敗留下 dangling/pinned 物件，但不丟失工作區。
    var trees=new TreeMap<DimensionId,String>(); for(var entry:repos.entrySet()) trees.put(entry.getKey(),capture(entry.getValue()));
    for(var entry:repos.entrySet()) {
      String base=entry.getValue().refs().head(); bases.put(entry.getKey(),base);
      var metadata=new CommitMetadata(author,author,message,now,layout.dataVersion(),entry.getKey(),CommitMetadata.Source.PLUGIN,false,UUID.fromString(id),List.of());
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

  private void validateOnline(ApplyPlan plan) throws IOException {
    applier.validateMetadata(plan);
    var targetIds=new HashSet<UUID>();for(var entity:plan.entities())if(entity.target()!=null)collectIds(entity.target().data(),targetIds);
    if(!targetIds.isEmpty())for(var entry:mapping.worlds().entrySet()) {
      if(entry.getKey().equals(plan.dimension()))continue;
      var world=entry.getValue();var state=plugin.state(entry.getKey(),world);
      try(var source=new PaperLiveWorld(plugin,state,world,layout,false)) {
        var census=state.refresh(plugin.bridge(),world);var stored=source.storedEntityChunks(targetIds);var candidates=new TreeSet<>(stored);candidates.addAll(census.entityChunks());
        for(var pos:candidates) {
          if(!census.loaded().contains(pos)) {
            if(stored.contains(pos))throw new IOException("實體 UUID 已存在另一維度："+entry.getKey()+" chunk="+pos);
            continue;
          }
          var found=new CompletableFuture<Set<UUID>>();
          plugin.platform().region(world,pos.x(),pos.z(),()->{
            try {var ids=new HashSet<UUID>();var raw=plugin.bridge().copy(world,pos.x(),pos.z());if(raw!=null)for(var n:raw.entities())collectIds(n,ids);ids.retainAll(targetIds);found.complete(ids);}
            catch(Throwable error) {found.completeExceptionally(error);}
          });
          var duplicate=await(found);if(!duplicate.isEmpty())throw new IOException("實體 UUID "+duplicate+" 已存在另一維度："+entry.getKey());
        }
      }
    }
    for(var op:plan.chunks().values()) if(op.delete()) throw new IOException("線上不刪除 chunk；stash 請先 commit 新增地形，或離線清理");
    if(!plan.worldMeta().isEmpty()) throw new IOException("線上 world-meta 還原尚不支援："+plan.worldMeta().keySet()+"；請離線還原設定");
  }
  private void applyAtomic(ApplyPlan plan) throws IOException {
    var world=mapping.worlds().get(plan.dimension());var path=worlds.tracked().get(plan.dimension());
    var checks=new TreeMap<ChunkPos,AtomicChunkCheck>();var rules=IgnoreRules.parse(plan.ignoreRules());
    var source=live.get(plan.dimension());var registry=source.registry();
    // Core already owns RepoLock for merge; a read-only object handle does not reacquire it.
    try(var store=new org.worldgit.core.store.JGitStore(path,false)) {
      var config=WorldGitConfig.readRepo(path.resolve("worldgit-repo.yml"));
      var touched=config.entities()==WorldGitConfig.Entities.PLAYER_TOUCHED ? org.worldgit.core.capture.PlayerTouchedEntities.read(path) : null;
      for(var pos:LiveApplyVerification.affected(plan))checks.put(pos,new AtomicChunkCheck(store,plan.baseTree(),pos,touched));
      for(var op:plan.chunks().values())for(var section:op.sections().values())section.section();
    }
    var done=new CompletableFuture<Void>();var first=checks.firstKey();
    plugin.platform().region(world,first.x(),first.z(),()->{
      try {
        if(queue.cancelled.get() || queue.stopping)throw new IOException("操作已取消");
        plugin.quiesceAxiom(world);
        long start=System.nanoTime();
        // Last preflight, all writes and detached verification data share this owner tick.
        for(var entry:checks.entrySet()) {
          var pos=entry.getKey();var raw=plugin.bridge().copy(world,pos.x(),pos.z());
          if(raw==null || !entry.getValue().matches(new org.worldgit.core.normalize.ChunkNormalizer(rules,registry).normalize(pos,raw.terrain(),raw.entities())))
            throw new IOException("受影響 chunk 在預檢後已變動，尚未寫入；請重試");
        }
        var ids=new HashSet<UUID>();for(var op:plan.entities()) {ids.add(op.uuid());collectIds(op.target()==null ? null : op.target().data(),ids);}
        for(var pos:checks.keySet()) {var raw=plugin.bridge().copy(world,pos.x(),pos.z());if(raw!=null)for(var entity:raw.entities())rememberEntity(entity,ids);plugin.bridge().removeEntities(world,pos.x(),pos.z(),ids);}
        for(var op:plan.chunks().values())plugin.bridge().applyChunk(world,op,rules);
        for(var op:plan.entities())if(op.target()!=null) {var n=op.target().data();mergeEntityFields(n,rules);plugin.edits().spawn(world,new EntitySnapshot(op.uuid(),n));}
        var receipts=new TreeMap<ChunkPos,NmsBridge.RawChunk>();
        for(var pos:checks.keySet())receipts.put(pos,plugin.bridge().copy(world,pos.x(),pos.z()));
        atomicReceipts.put(plan.dimension(),Map.copyOf(receipts));source.receipts(receipts);
        plugin.getLogger().info("WorldGit atomic chunks="+checks.size()+" sections="+plan.stats().sections()+" ownerNanos="+(System.nanoTime()-start));
        done.complete(null);
      } catch(Throwable e) {done.completeExceptionally(e);}
    });
    await(done);await(source.finishApply(checks.keySet()));await(source.flush(checks.keySet()));
  }

  private ApplyBudget budget() { return plugin.getServer().getOnlinePlayers().isEmpty() ? ApplyBudget.DEFAULT : ApplyBudget.WITH_PLAYERS; }
  private void applyAll(SortedMap<DimensionId,ApplyPlan> plans) throws IOException {
    if(atomicApply) {applyAtomic(plans.values().iterator().next());return;}
    if(plans.values().stream().allMatch(ApplyPlan::empty)) return;
    org.worldgit.core.operation.OperationProgress.report(selectedDimension,"apply",0,null,org.worldgit.core.operation.OperationProgress.Unit.SECTION);
    var timings=OperationTimings.current();
    var ids=new HashSet<UUID>();
    for(var plan:plans.values()) for(var op:plan.entities()) collectIds(op.target()==null ? null : op.target().data(),ids);
    for(var plan:plans.values()) for(var op:plan.entities()) ids.add(op.uuid());
    // 全維度、含未載入 entity storage chunk，先移除所有 UUID。完畢才開始任何生成。
    var tasks=new ArrayList<ApplyQueue.Task>();
    if(!ids.isEmpty()) for(var entry:live.entrySet()) {
      var source=entry.getValue(); var world=mapping.worlds().get(entry.getKey());
      var candidates=new TreeSet<ChunkPos>();
      if(regionMode) candidates.addAll(regionChunks.getOrDefault(entry.getKey(),Set.of()));
      else { candidates.addAll(source.storedEntityChunks(ids)); candidates.addAll(source.entityChunks()); }
      var own=plans.get(entry.getKey());
      if(own!=null) for(var e:own.entities()) if(e.hint()!=null) candidates.add(e.hint());
      for(var pos:candidates) tasks.add(new ApplyQueue.Task(world,pos,(w,c)->{ var raw=plugin.bridge().copy(w,pos.x(),pos.z()); if(raw!=null) for(var entity:raw.entities()) rememberEntity(entity,ids);
        plugin.bridge().removeEntities(w,pos.x(),pos.z(),ids); return CompletableFuture.completedFuture(null); }));
    }
    boolean attempted=false; IOException failure=null;
    try {
      if(queue.cancelled.get()) throw new IOException("操作已取消");
      attempted=true;
      await(queue.run(tasks,budget(),false));
      tasks.clear();
      for(var entry:plans.entrySet()) {
        var world=mapping.worlds().get(entry.getKey()); var rules=IgnoreRules.parse(entry.getValue().ignoreRules());
        for(var op:entry.getValue().chunks().values()) tasks.add(new ApplyQueue.Task(world,op.pos(),(w,c)->queue.apply(w,op,rules,budget(),timings)));
      }
      await(queue.run(tasks,budget(),false));
      if(queue.cancelled.get()) throw new IOException("操作已取消");
      tasks.clear();
      for(var entry:plans.entrySet()) {
        var world=mapping.worlds().get(entry.getKey());
        var chunks=new TreeMap<ChunkPos,List<EntitySnapshot>>();
        for(var op:entry.getValue().entities()) if(op.target()!=null) chunks.computeIfAbsent(op.targetChunk(),k->new ArrayList<>()).add(op.target());
        for(var entryChunk:chunks.entrySet()) tasks.add(new ApplyQueue.Task(world,entryChunk.getKey(),(w,c)->{
          for(var entity:entryChunk.getValue()) { if(queue.cancelled.get()) break; var n=entity.data();
            mergeEntityFields(n,IgnoreRules.parse(entry.getValue().ignoreRules()));
            plugin.edits().spawn(w,new EntitySnapshot(entity.uuid(),n)); }
          return CompletableFuture.completedFuture(null);
        }));
      }
      await(queue.run(tasks,budget(),false));
      if(queue.cancelled.get()) throw new IOException("操作已取消");
    } catch(Exception ex) {
      failure=ex instanceof IOException io ? io : new IOException("套用失敗",ex);
    } finally {
      if(!queue.stopping) {
        if(attempted) for(var entry:live.entrySet()) try(var timing=OperationTimings.stage("flush-after-apply")) {
          if(regionMode) { var chunks=regionChunks.get(entry.getKey()); if(chunks!=null) await(entry.getValue().flush(chunks)); }
          else await(entry.getValue().flush());
        }
        catch(IOException ex) { if(failure==null) failure=ex; else failure.addSuppressed(ex); }
        try(var timing=OperationTimings.stage("notification-schedule")) { plugin.repo().clearPreviews(); }
        catch(RuntimeException ex) { if(failure==null) failure=new IOException("清理預覽失敗",ex); else failure.addSuppressed(ex); }
      }
      plugin.getLogger().info("WorldGit apply sections="+queue.sections.get()+" ticketPeak="+queue.peak.get()+" ticketRemaining="+queue.tickets.get());
    }
    if(failure!=null) throw failure;
  }
  private void rememberEntity(Nbt.Compound n,Set<UUID> ids) {
    if(n.get("UUID") instanceof int[] a && a.length==4) {
      var id=new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL));
      if(ids.contains(id)) removedEntities.putIfAbsent(id,n);
    }
    for(Object child:n.list("Passengers").values()) rememberEntity((Nbt.Compound)child,ids);
  }
  private void mergeEntityFields(Nbt.Compound n,IgnoreRules rules) {
    if(n.get("UUID") instanceof int[] a && a.length==4) {
      var old=removedEntities.get(new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL)));
      if(old!=null && old.string("id").equals(n.string("id")))
        old.forEach((k,v)->{ if(rules.ignoredField(n.string("id"),k,false)) n.put(k,Nbt.copy(v)); });
    }
    for(Object child:n.list("Passengers").values()) mergeEntityFields((Nbt.Compound)child,rules);
  }
  private static void collectIds(Nbt.Compound n,Set<UUID> ids) {
    if(n==null) return;
    if(n.get("UUID") instanceof int[] a && a.length==4) ids.add(new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL)));
    for(var child:n.list("Passengers").values()) collectIds((Nbt.Compound)child,ids);
  }

  @Override public void close() throws IOException {
    IOException error=null;
    for(var repo:repos.values()) try { repo.close(); } catch(IOException ex) { error=ex; }
    for(var source:live.values()) try { source.close(); } catch(IOException ex) { error=ex; }
    if(core!=null) try { core.close(); } catch(IOException ex) { error=ex; }
    if(groupLock!=null) try { groupLock.close(); } catch(IOException ex) { error=ex; }
    if(error!=null) throw error;
  }
}
