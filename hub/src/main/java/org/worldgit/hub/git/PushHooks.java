package org.worldgit.hub.git;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.Constants;
import org.worldgit.core.normalize.DecodeBudget;
import org.worldgit.hub.storage.OwnerQuota;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.store.CommitTrailers;
import org.worldgit.hub.account.AccountService;
import org.worldgit.hub.account.Models.User;

/** push 的驗證與記錄：main 的最新 commit 必須帶有符合維度的 WorldGit trailers。 */
final class PushHooks {
  private static final Logger log = LoggerFactory.getLogger(PushHooks.class);

  private PushHooks() {}

  static final class Pre implements PreReceiveHook {
    private final RepoRef ref;
    private final AccountService accounts;
    private final String worldId;
    private final User user;

    private final OwnerQuota quota;
    private final org.worldgit.hub.collaboration.BranchPolicy policy;
    private final org.worldgit.hub.collaboration.WorldGroups groups;

    Pre(RepoRef ref, AccountService accounts, String worldId, User user, OwnerQuota quota, org.worldgit.hub.collaboration.BranchPolicy policy, org.worldgit.hub.collaboration.WorldGroups groups) {
      this.ref = ref;
      this.accounts = accounts;
      this.worldId = worldId;
      this.user = user;
      this.quota = quota;
      this.policy = policy;
      this.groups = groups;
    }

    private void validatePublication(ReceivePack rp,ReceiveCommand cmd,Collection<ReceiveCommand> commands) throws IOException {
      String branch=cmd.getRefName().substring("refs/worldgit/publications/".length());
      org.worldgit.hub.collaboration.BranchPolicy.branch(branch);
      try(var store=org.worldgit.core.store.JGitStore.readOnly(rp.getRepository())) {
        var c=store.readCommit(cmd.getNewId().name());
        var entry=org.worldgit.core.store.TreeEditor.find(store,c.tree(),"publication.yml");
        if(entry==null)throw new IOException("publication 缺少清單");
        byte[] bytes=store.readBlob(entry.id());if(bytes.length>65536)throw new IOException("publication 超過 64 KiB");
        var map=org.worldgit.hub.collaboration.BoundedYaml.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        if(!branch.equals(map.get("branch")) || !(map.get("operation") instanceof String op) || !(map.get("commits") instanceof java.util.Map<?,?> commits) || commits.size()>32 || !commits.containsKey(ref.dimension().value()))throw new IOException("publication 清單無效");
        try{java.util.UUID.fromString(op);}catch(IllegalArgumentException ex){throw new IOException("publication operation 無效");}
        for(var en:commits.entrySet()) {
          new org.worldgit.core.model.DimensionId(en.getKey().toString());
          if(!(en.getValue() instanceof String value) || !value.matches("[0-9a-f]{40}"))throw new IOException("publication commit 無效");
        }
        String tip=store.branches().get(branch);
        for(var change:commands)if(change.getRefName().equals("refs/heads/"+branch))tip=change.getType()==ReceiveCommand.Type.DELETE?null:change.getNewId().name();
        if(!java.util.Objects.equals(tip,commits.get(ref.dimension().value())))throw new IOException("publication 與本維度分支不符");
        // owner 寫鎖涵蓋本次 receive；protected marker 必須吻合整組既有 heads。
        if(policy.find(worldId,branch).isPresent() && policy.find(worldId,branch).get().prOnly()) {
          for(var change:commands)if(change.getRefName().equals("refs/heads/"+branch))throw new IOException("受保護分支只能經 PR 合併");
          var world=accounts.findWorld(ref.owner(),ref.world()).orElseThrow();
          if(!org.worldgit.hub.collaboration.WorldGroups.strings(groups.currentHeads(world,branch)).equals(commits))throw new IOException("受保護分支 publication 必須與全維度 heads 相同");
        }
      }
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
      try {
        quota.check(ref.owner());
        var world=accounts.findWorld(ref.owner(),ref.world()).orElseThrow();
        var role=accounts.roleOn(user,world);
        for(var cmd:commands) {
          if(cmd.getResult()!=ReceiveCommand.Result.NOT_ATTEMPTED) continue;
          String name=cmd.getRefName();
          boolean delete=cmd.getType()==ReceiveCommand.Type.DELETE;
          boolean force=cmd.getType()==ReceiveCommand.Type.UPDATE_NONFASTFORWARD;
          if(name.startsWith("refs/heads/")) {
            var rule=policy.find(worldId,name.substring(11));
            if(rule.isPresent() && (delete || force || rule.get().prOnly()))
              throw new IOException("受保護分支 "+name.substring(11)+"：禁止 force push／刪除"+(rule.get().prOnly()?"，只能經 PR 合併":""));
            if((force || delete) && !role.atLeast(org.worldgit.hub.account.Models.Role.ADMIN)) throw new IOException("改寫或刪除分支需要 admin");
          } else if(name.startsWith("refs/tags/") || name.startsWith("refs/worldgit/groups/") || name.startsWith("refs/worldgit/transfers/")) {
            if(cmd.getType()!=ReceiveCommand.Type.CREATE) throw new IOException("tag／group／transfer refs 不可覆寫或刪除");
          } else if(name.startsWith("refs/worldgit/publications/")) {
            if(delete || force) throw new IOException("publication 不可刪除或非快轉");
            validatePublication(rp,cmd,commands);
          } else throw new IOException("不允許此 ref namespace");
        }
        // 驗證所有新歷史，避免將昂貴 trailer 藏在 tip 的 parent／其他分支。
        try (var walk = new RevWalk(rp.getRepository()); var reader = rp.getRepository().newObjectReader()) {
          walk.setRetainBody(false);
          for (var existing : rp.getRepository().getRefDatabase().getRefs()) {
            var peeled = rp.getRepository().getRefDatabase().peel(existing);
            var id = peeled.getPeeledObjectId() == null ? peeled.getObjectId() : peeled.getPeeledObjectId();
            if (id != null) {
              var object=walk.peel(walk.parseAny(id));
              if(object instanceof RevCommit c) walk.markUninteresting(c);
            }
          }
          for (ReceiveCommand cmd : commands) {
            if (cmd.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED && cmd.getType() != ReceiveCommand.Type.DELETE)
            {
              var object=walk.peel(walk.parseAny(cmd.getNewId()));
              if(!(object instanceof RevCommit c)) throw new IOException("ref／tag 必須指向 commit，不能指向 blob 或 tree");
              walk.markStart(c);
            }
          }
          int count = 0;
          for (var commit : walk) {
            if (++count > 10_000) throw new IOException("每次 push 最多驗證 10000 個新 commit");
            long size = reader.open(commit, Constants.OBJ_COMMIT).getSize();
            if (size > 1_048_576) throw new IOException("commit 物件超過 1 MiB");
            DecodeBudget.read(size);
            walk.parseBody(commit);
            CommitMetadata meta = parse(commit);
            if (!meta.dimension().equals(ref.dimension())) throw new IOException("commit 的維度與 repo 不符");
            commit.disposeBody();
          }
        }
      } catch (IOException | RuntimeException e) {
        for (ReceiveCommand cmd : commands) {
          if (cmd.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED) continue;
          cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "push 被拒：" + e.getMessage());
          accounts.recordPush(worldId, ref.dimension().value(), cmd.getRefName(), id(cmd.getOldId()), id(cmd.getNewId()),
              null, user == null ? null : user.id(), "REJECTED", e.getMessage());
        }
      }
    }
  }

  static final class Post implements PostReceiveHook {
    private final RepoRef ref;
    private final AccountService accounts;
    private final String worldId;
    private final User user;
    private final Maintenance maintenance;
    private final HttpServletRequest request;
    private final org.worldgit.hub.collaboration.EventService events;

    Post(RepoRef ref, AccountService accounts, String worldId, User user, Maintenance maintenance, HttpServletRequest request, org.worldgit.hub.collaboration.EventService events) {
      this.ref = ref;
      this.accounts = accounts;
      this.worldId = worldId;
      this.user = user;
      this.maintenance = maintenance;
      this.request = request;
      this.events = events;
    }

    @Override
    public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
      boolean any = false;
      for (ReceiveCommand cmd : commands) {
        if (cmd.getResult() != ReceiveCommand.Result.OK) continue;
        request.setAttribute(GitAuthFilter.ATTR_ACCEPTED, Boolean.TRUE);
        String snapshot = null;
        if (cmd.getRefName().equals("refs/heads/main") && cmd.getType() != ReceiveCommand.Type.DELETE) {
          try {
            RevCommit tip = rp.getRevWalk().parseCommit(cmd.getNewId());
            rp.getRevWalk().parseBody(tip);
            snapshot = parse(tip).snapshot().toString();
          } catch (IOException | RuntimeException e) {
            log.warn("push 後解析 commit 失敗：{}", e.toString());
          }
          any = true;
        }
        if(cmd.getRefName().startsWith("refs/heads/")) {
          try { events.emit(accounts.findWorld(ref.owner(),ref.world()).orElseThrow(),"push",java.util.Map.of("dimension",ref.dimension().value(),"ref",cmd.getRefName(),"old",cmd.getOldId().name(),"new",cmd.getNewId().name()),java.util.Set.of()); }
          catch(RuntimeException ex){log.error("push 事件保存失敗",ex);}
        }
        accounts.recordPush(
            worldId, ref.dimension().value(), cmd.getRefName(), id(cmd.getOldId()), id(cmd.getNewId()),
            snapshot, user == null ? null : user.id(), "OK", null);
      }
      if (any) maintenance.afterPush(ref);
    }
  }

  static CommitMetadata parse(RevCommit c) throws IOException {
    var a = c.getAuthorIdent();
    var b = c.getCommitterIdent();
    return CommitTrailers.parse(
        new CommitMetadata.Identity(a.getName(), a.getEmailAddress()),
        new CommitMetadata.Identity(b.getName(), b.getEmailAddress()),
        b.getWhenAsInstant(),
        c.getFullMessage());
  }

  private static String id(org.eclipse.jgit.lib.ObjectId id) {
    return id == null || org.eclipse.jgit.lib.ObjectId.zeroId().equals(id) ? null : id.name();
  }
}
