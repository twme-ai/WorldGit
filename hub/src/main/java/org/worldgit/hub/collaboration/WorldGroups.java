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

/** 世界容器的 repo 發現；各維度 revision 獨立，舊 publication 只保留歷史。 */
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
    return result;
  }
  public Path path(WorldRow w,DimensionId d) throws IOException {
    var path=paths(w).get(d);if(path==null)throw new ApiError.NotFound("找不到維度");return path;
  }
  public SortedMap<DimensionId,String> branchTips(WorldRow w,DimensionId d,String branch) throws IOException {
    BranchPolicy.branch(branch);path(w,d);
    try(var h=repos.open(w.ownerSlug(),w.slug(),d)) {
      var ref=h.repository().exactRef("refs/heads/"+branch);
      if(ref==null || ref.getObjectId()==null)throw new ApiError.NotFound("找不到維度分支："+d+" / "+branch);
      return new TreeMap<>(Map.of(d,ref.getObjectId().name()));
    }
  }
  public static Map<String,String> strings(Map<DimensionId,String> tips){var out=new TreeMap<String,String>();tips.forEach((d,id)->out.put(d.value(),id));return out;}
}
