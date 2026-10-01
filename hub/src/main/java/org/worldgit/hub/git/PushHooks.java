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

    Pre(RepoRef ref, AccountService accounts, String worldId, User user, OwnerQuota quota) {
      this.ref = ref;
      this.accounts = accounts;
      this.worldId = worldId;
      this.user = user;
      this.quota = quota;
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
      try {
        quota.check(ref.owner());
        // 驗證所有新歷史，避免將昂貴 trailer 藏在 tip 的 parent／其他分支。
        try (var walk = new RevWalk(rp.getRepository()); var reader = rp.getRepository().newObjectReader()) {
          walk.setRetainBody(false);
          for (var existing : rp.getRepository().getRefDatabase().getRefs()) {
            var peeled = rp.getRepository().getRefDatabase().peel(existing);
            var id = peeled.getPeeledObjectId() == null ? peeled.getObjectId() : peeled.getPeeledObjectId();
            if (id != null) walk.markUninteresting(walk.parseCommit(id));
          }
          for (ReceiveCommand cmd : commands) {
            if (cmd.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED && cmd.getType() != ReceiveCommand.Type.DELETE)
              walk.markStart(walk.parseCommit(cmd.getNewId()));
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

    Post(RepoRef ref, AccountService accounts, String worldId, User user, Maintenance maintenance, HttpServletRequest request) {
      this.ref = ref;
      this.accounts = accounts;
      this.worldId = worldId;
      this.user = user;
      this.maintenance = maintenance;
      this.request = request;
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
