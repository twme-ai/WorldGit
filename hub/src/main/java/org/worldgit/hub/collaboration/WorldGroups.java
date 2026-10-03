package org.worldgit.hub.collaboration;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.remote.*;
import org.worldgit.core.store.*;
import org.worldgit.hub.account.Models.WorldRow;
import org.worldgit.hub.git.RepoCache;
import org.worldgit.hub.history.BranchService;
import org.worldgit.hub.storage.RepoStorage;
import org.worldgit.hub.web.ApiError;

/** publication 的完整 commit map 必須在每個 repo 與分支 heads 相符；中途發布拒絕合併／下載。 */
@Service
public class WorldGroups {
  private final RepoStorage storage;private final RepoCache repos;
  public WorldGroups(RepoStorage storage,RepoCache repos){this.storage=storage;this.repos=repos;}
  public SortedMap<DimensionId,Path> paths(WorldRow w) throws IOException {
    var result=new TreeMap<DimensionId,Path>();var dims=storage.dimensions(w.ownerSlug(),w.slug());BranchService.checkDimensions(dims.size());
    for(var d:dims)result.put(d,storage.repoPath(w.ownerSlug(),w.slug(),d));
    if(result.isEmpty())throw new ApiError.Conflict("世界尚無完整快照");return result;
  }
  public Path root(WorldRow w) throws IOException {return paths(w).values().iterator().next().getParent();}
  /** 呼叫者持 owner 寫鎖；只讀 heads，供保護規則驗證補發 marker，不要求舊 marker 已完整。 */
  public SortedMap<DimensionId,String> currentHeads(WorldRow w,String branch) throws IOException {
    BranchPolicy.branch(branch);var result=new TreeMap<DimensionId,String>();
    for(var d:paths(w).keySet())try(var h=repos.open(w.ownerSlug(),w.slug(),d)) {
      var ref=h.repository().exactRef("refs/heads/"+branch);
      if(ref==null || ref.getObjectId()==null)throw new ApiError.Conflict("分支缺少維度："+d);
      result.put(d,ref.getObjectId().name());
    }
    return result;
  }
  public SortedMap<DimensionId,String> branchTips(WorldRow w,String branch) throws IOException {
    BranchPolicy.branch(branch);var result=new TreeMap<DimensionId,String>();
    var paths=paths(w);
    for(var d:paths.keySet())try(var h=repos.open(w.ownerSlug(),w.slug(),d)) {
      var ref=h.repository().exactRef("refs/heads/"+branch);if(ref==null || ref.getObjectId()==null)throw new ApiError.Conflict("分支缺少維度："+d);
      result.put(d,ref.getObjectId().name());
    }
    var commits=new TreeMap<DimensionId,RefStore.Commit>();
    for(var d:paths.keySet())try(var h=repos.open(w.ownerSlug(),w.slug(),d)){commits.put(d,h.store().readCommit(result.get(d)));}
    RepositoryGroup.validateSnapshot(commits,(d,s)->{try(var h=repos.open(w.ownerSlug(),w.slug(),d)){return h.store().resolve("refs/worldgit/groups/"+s);}});
    // 主世界宣告的維度也必須已存在，不能只推主世界就被視為完整。
    if(commits.containsKey(DimensionId.OVERWORLD))try(var h=repos.open(w.ownerSlug(),w.slug(),DimensionId.OVERWORLD)) {
      var entry=TreeEditor.find(h.store(),commits.get(DimensionId.OVERWORLD).tree(),"dimensions");
      if(entry!=null){var manifest=BoundedYaml.parse(new String(h.store().readBlob(entry.id()),java.nio.charset.StandardCharsets.UTF_8));
        if(manifest.get("dimensions") instanceof Map<?,?> declared)for(var id:declared.keySet())if(!result.containsKey(new DimensionId(id.toString())))throw new ApiError.Conflict("世界尚未完整推送："+id);
      }
    }
    var expected=new TreeMap<String,String>();result.forEach((d,id)->expected.put(d.value(),id));
    String operation=null;int markers=0;
    for(var d:paths.keySet())try(var h=repos.open(w.ownerSlug(),w.slug(),d)) {
      var r=h.repository().exactRef("refs/worldgit/publications/"+branch);
      if(r==null)continue;markers++;
      var c=h.store().readCommit(r.getObjectId().name());var entry=TreeEditor.find(h.store(),c.tree(),"publication.yml");
      if(entry==null)throw new ApiError.Conflict("publication 缺少清單");
      byte[] bytes=h.store().readBlob(entry.id());if(bytes.length>65536)throw new ApiError.Conflict("publication 清單過大");
      var map=BoundedYaml.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
      if(!branch.equals(map.get("branch")) || !expected.equals(map.get("commits")) || !(map.get("operation") instanceof String op))throw new ApiError.Conflict("全維度發布尚未完成，請完成原 push 再重試");
      if(operation!=null && !operation.equals(op))throw new ApiError.Conflict("publication operation 不一致");operation=op;
    }
    if(markers>0 && markers!=paths.size())throw new ApiError.Conflict("publication 尚未完整推送");
    return result;
  }
  public static Map<String,String> strings(Map<DimensionId,String> tips){var out=new TreeMap<String,String>();tips.forEach((d,id)->out.put(d.value(),id));return out;}
}
