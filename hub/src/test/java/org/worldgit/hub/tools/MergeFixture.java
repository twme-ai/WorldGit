package org.worldgit.hub.tools;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.core.store.*;

/** 小型三維度合併場景；保存不相鄰的自動变動與鄰接衝突、完整 fence state。 */
public final class MergeFixture {
  private MergeFixture() {}
  public static Map<DimensionId, Path> create(Path directory) throws Exception {
    var paths=BranchFixture.create(directory);
    for(var d:paths.keySet()) try(var s=new JGitStore(paths.get(d),false)) {
      String initial=s.readCommit(s.head()).parents().isEmpty()?s.head():s.readCommit(s.head()).parents().getFirst();
      String base=s.readCommit(initial).tree();
      for(String name:List.of("ours","theirs","version","rules-a","rules-b","packs")) {
        BranchFixture.ref(paths.get(d),name,initial); BranchFixture.head(paths.get(d),name);
        var e=new TreeEditor(s,base);
        var entry=TreeEditor.find(s,base,"r.0.0/c.0.0/s.4.bin");
        var blocks=new ArrayList<>(SnapshotCodec.section(s.readBlob(entry.id())).blocks());
        if(name.equals("ours")||name.equals("theirs")) {
          boolean ours=name.equals("ours");
          blocks.set((1<<8)|(8<<4)|8,new BlockState(ours?"minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]":"minecraft:nether_brick_fence[east=false,north=false,south=false,waterlogged=false,west=false]"));
          blocks.set((1<<8)|(8<<4)|9,new BlockState(ours?"minecraft:redstone_block":"minecraft:gold_block"));
          blocks.set((2<<8)|(8<<4)|8,new BlockState(ours?"minecraft:emerald_block":"minecraft:diamond_block"));
          // bbox 內未衝突的角落只能保留 ours 的自動合併，不能因區域 base 切換被覆蓋。
          if(ours) blocks.set((2<<8)|(8<<4)|9,new BlockState("minecraft:obsidian"));
          blocks.set((1<<8)|(3<<4)|3,new BlockState(ours?"minecraft:emerald_block":"minecraft:diamond_block"));
          blocks.set((1<<8)|(12<<4)|(ours?12:13),new BlockState(ours?"minecraft:emerald_block":"minecraft:diamond_block"));
        }
        e.putBlob("r.0.0/c.0.0/s.4.bin",SnapshotCodec.section(new Section(blocks,Map.of())));
        if(name.startsWith("rules-")) e.putBlob(".wgignore",("area "+(name.equals("rules-a")?"0 0 0 1 1 1":"2 2 2 3 3 3")+"\n").getBytes(StandardCharsets.UTF_8));
        if(name.equals("packs")) {
          var level=new org.worldgit.core.anvil.Nbt.Compound();var packs=new org.worldgit.core.anvil.Nbt.Compound();
          packs.put("Enabled",new org.worldgit.core.anvil.Nbt.ListTag(8,List.of("vanilla","file/test")));level.put("DataPacks",packs);
          e.putBlob("world-meta/level.nbt",org.worldgit.core.anvil.Nbt.write(level));
        }
        String tree=e.write();
        int v=name.equals("version")?4671:4903;
        var author=new CommitMetadata.Identity(name.equals("theirs")?"Bob":"Alice",name+"@example.test");
        var metadata=new CommitMetadata(author,author,name,Instant.parse("2026-10-02T08:00:00Z"),v,d,CommitMetadata.Source.HUB,false,UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),List.of());
        String tip=s.commit(tree,initial,metadata);
        s.updateRef("refs/worldgit/groups/"+metadata.snapshot(),null,tip);
      }
      BranchFixture.head(paths.get(d),"main");s.flush();
    }
    return paths;
  }
  public static void main(String[] args) throws Exception { create(Path.of(args[0])); }
}
