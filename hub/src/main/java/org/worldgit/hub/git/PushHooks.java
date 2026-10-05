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
            var rule=policy.find(worldId,ref.dimension().value(),name.substring(11));
            if(rule.isPresent() && (delete || force || rule.get().prOnly()))
              throw new IOException("受保護分支 "+name.substring(11)+"：禁止 force push／刪除"+(rule.get().prOnly()?"，只能經 PR 合併":""));
            if((force || delete) && !role.atLeast(org.worldgit.hub.account.Models.Role.ADMIN)) throw new IOException("改寫或刪除分支需要 admin");
          } else if(name.startsWith("refs/tags/") || name.startsWith("refs/worldgit/groups/") || name.startsWith("refs/worldgit/transfers/")) {
            if(cmd.getType()!=ReceiveCommand.Type.CREATE) throw new IOException("tag／group／transfer refs 不可覆寫或刪除");
          } else if(name.startsWith("refs/worldgit/publications/")) {
            if(delete || force) throw new IOException("publication 不可刪除或非快轉");
            // 歷史 marker 不再參與授權或分支發布；不能用 marker 更新 heads。
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
      boolean any = false;var changes=new java.util.ArrayList<java.util.Map<String,String>>();
      for (ReceiveCommand cmd : commands) {
        if (cmd.getResult() != ReceiveCommand.Result.OK) continue;
        request.setAttribute(GitAuthFilter.ATTR_ACCEPTED, Boolean.TRUE);
        if(cmd.getRefName().startsWith("refs/heads/") && cmd.getType()!=ReceiveCommand.Type.DELETE)try {
          if(rp.getRepository().resolve("HEAD")==null)rp.getRepository().updateRef("HEAD").link(cmd.getRefName());
        }catch(IOException ex){log.warn("預設分支初始化失敗",ex);}
        String snapshot = null;
        if (cmd.getRefName().startsWith("refs/heads/") && cmd.getType() != ReceiveCommand.Type.DELETE) {
          try {
            RevCommit tip = rp.getRevWalk().parseCommit(cmd.getNewId());
            rp.getRevWalk().parseBody(tip);
            snapshot = parse(tip).snapshot().toString();
          } catch (IOException | RuntimeException e) {
            log.warn("push 後解析 commit 失敗：{}", e.toString());
          }
          any = true;
        }
        if(cmd.getRefName().startsWith("refs/heads/") || cmd.getRefName().startsWith("refs/tags/")) {
          changes.add(java.util.Map.of("dimension",ref.dimension().value(),"ref",cmd.getRefName(),"old",cmd.getOldId().name(),"new",cmd.getNewId().name()));

        }
        accounts.recordPush(
            worldId, ref.dimension().value(), cmd.getRefName(), id(cmd.getOldId()), id(cmd.getNewId()),
            snapshot, user == null ? null : user.id(), "OK", null);
      }
      if (any || !changes.isEmpty())try{var operation=maintenance.afterPush(ref,user,java.util.List.copyOf(changes));rp.sendMessage("WorldGit operation="+operation);request.setAttribute("worldgit.push-operation",operation);}catch(RuntimeException ex){log.error("push 後處理排程失敗",ex);rp.sendMessage("WorldGit push 已接受，後處理失敗，請查看 Hub log");}
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
