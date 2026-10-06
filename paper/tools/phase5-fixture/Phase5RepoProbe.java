import java.nio.file.Path;
import org.worldgit.core.store.*;
import org.worldgit.core.normalize.SnapshotCodec;

/** 只讀不可變 HEAD tree，不能以檔名代替真正 entity blob 的 UUID 斷言。 */
public class Phase5RepoProbe {
  public static void main(String[] args) throws Exception {
    try(var store=new JGitStore(Path.of(args[0]),false)) {walk(store,store.readCommit(store.head()).tree());}
  }
  private static void walk(JGitStore store,String tree) throws Exception {
    for(var entry:store.readTree(tree).values()) {
      if(entry.kind()==ObjectStore.Kind.TREE)walk(store,entry.id());
      else if(entry.name().equals("entities.bin"))for(var entity:SnapshotCodec.entities(store.readBlob(entry.id())))System.out.println(entity.uuid());
    }
  }
}
