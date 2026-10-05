import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;
/** 開世界驗證失敗時保留精確 metadata 欄位證據。 */
class Phase5Metadata {
  public static void main(String[] args) throws Exception {
    var layout=WorldLayout.discover(Path.of(args[0]));var id=new DimensionId(args[1]);
    try(var repo=new DimensionRepository(new WorldRepositories(layout).tracked().get(id),id,false)) {
      var target=repo.refs().readCommit(repo.refs().resolve(args[2]));
      var source = new OfflineSnapshotSource(layout, layout.dimensions().get(id));
      var tree = repo.workingTree(source, Map.of(), 2);
      var changes = new DiffEngine(repo.objects()).compare(id, target.tree(), tree, 2);
      for (var section : changes.sections())
        for (var block : section.blocks()) System.out.println("block " + block);
      for (var change : changes.metadata()) {
        System.out.println("blob " + change);
        if (change.beforeId() != null && change.afterId() != null && change.path().endsWith(".nbt"))
          diff(change.path(), Nbt.read(repo.objects().readBlob(change.beforeId())), Nbt.read(repo.objects().readBlob(change.afterId())));
        if (change.beforeId() != null && change.afterId() != null && (change.path().endsWith("ticks.bin") || change.path().endsWith("structures.bin"))) {
          int kind = change.path().endsWith("ticks.bin") ? 4 : 5;
          diff(change.path(), Nbt.read(SnapshotCodec.nbt(kind, repo.objects().readBlob(change.beforeId()), true)),
              Nbt.read(SnapshotCodec.nbt(kind, repo.objects().readBlob(change.afterId()), true)));
        }
      }
      var current=MetadataNormalizer.normalize(id.equals(DimensionId.OVERWORLD)?layout.worldMetadata():layout.dimensionMetadata(id),IgnoreRules.parse(Files.readString(repo.ignorePath())));
      String metadataTree=id.equals(DimensionId.OVERWORLD)?"world-meta":"dimension-meta";
      var root=TreeEditor.find(repo.objects(),target.tree(),metadataTree);if(root==null)return;
      for(var entry:repo.objects().readTree(root.id()).values()) {
        if(!entry.name().endsWith(".nbt"))continue;
        byte[] value=current.get(entry.name());
        if(value==null)System.out.println(entry.name()+" missing current");
        else diff(entry.name(),Nbt.read(repo.objects().readBlob(entry.id())),Nbt.read(value));
      }
    }
  }
  static void diff(String name,Object before,Object after) {
    if(Objects.deepEquals(before,after))return;
    if(before instanceof Map<?,?> a && after instanceof Map<?,?> b) {
      var keys=new TreeSet<String>();a.keySet().forEach(k->keys.add(k.toString()));b.keySet().forEach(k->keys.add(k.toString()));
      for(String key:keys)diff(name+"/"+key,a.get(key),b.get(key));
    } else if (before instanceof Nbt.ListTag a && after instanceof Nbt.ListTag b) {
      if (a.type() != b.type() || a.values().size() != b.values().size())
        System.out.println(name + ": target=" + a + " current=" + b);
      else for (int i=0;i<a.values().size();i++)diff(name+"/"+i,a.values().get(i),b.values().get(i));
    } else System.out.println(name+": target="+format(before)+" current="+format(after));
  }
  static String format(Object value) {
    if (value instanceof int[] array) return Arrays.toString(array);
    if (value instanceof long[] array) return Arrays.toString(array);
    if (value instanceof byte[] array) return Arrays.toString(array);
    return String.valueOf(value);
  }
}
