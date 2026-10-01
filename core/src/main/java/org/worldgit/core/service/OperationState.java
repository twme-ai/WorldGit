package org.worldgit.core.service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.config.WorldGitConfig;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 持久化 YAML journal 與 stash 清單；樹物件由 refs/worldgit 保持可達。 */
public final class OperationState {
  private OperationState() {}
  public static Map<String,Object> read(Path path) throws IOException {
    if(!Files.exists(path)) return new LinkedHashMap<>();
    if(Files.size(path)>1_048_576) throw new IOException("操作紀錄過大："+path);
    var options=new LoaderOptions(); options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(12); options.setCodePointLimit(1_048_576);
    try {
      Object value=new Yaml(new SafeConstructor(options)).load(Files.readString(path));
      if(!(value instanceof Map<?,?> map)) throw new IOException("操作紀錄格式無效："+path);
      var result=new LinkedHashMap<String,Object>();
      for(var entry:map.entrySet()) {
        if(!(entry.getKey() instanceof String key)) throw new IOException("操作紀錄鍵無效");
        result.put(key,entry.getValue());
      }
      return result;
    } catch(RuntimeException ex) { throw new IOException("操作紀錄損毀："+path,ex); }
  }
  public static void write(Path path,Map<String,?> value) throws IOException {
    WorldGitConfig.write(path,new Yaml().dump(value));
  }
  public static boolean partial(Path root) throws IOException {
    var state=read(root.resolve("apply-state.yml"));
    return !state.isEmpty() && !Objects.equals(state.get("state"),"COMPLETE");
  }
}
