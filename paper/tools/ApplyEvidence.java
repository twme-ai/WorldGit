import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;

/** 存檔後讀取光照、POI 與全維度 UUID；不更動世界。 */
class ApplyEvidence {
  static final Map<UUID,Integer> ids=new TreeMap<>();
  static void entities(Nbt.Compound entity) {
    if(entity.get("UUID") instanceof int[] a && a.length==4)
      ids.merge(new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL)),1,Integer::sum);
    for(Object child:entity.list("Passengers").values()) entities((Nbt.Compound)child);
  }
  static int nibble(Nbt.Compound section,String key,int x,int y,int z) {
    if(!(section.get(key) instanceof byte[] bytes) || bytes.length!=2048) return -1;
    int index=(x&15)|((z&15)<<4)|((y&15)<<8);
    return ((bytes[index>>1]&255) >> ((index&1)*4))&15;
  }
  public static void main(String[] args) throws Exception {
    var layout=WorldLayout.discover(Path.of(args[0]));
    for(var dim:layout.dimensions().values()) {
      var folder=dim.directory().resolve("entities");
      if(Files.isDirectory(folder)) try(var files=Files.list(folder)) {
        for(var file:files.filter(p->p.toString().endsWith(".mca")).toList()) try(var region=new RegionFile(file)) {
          for(int i=0;i<1024;i++) if(region.has(i)) for(Object entity:region.read(i).list("Entities").values()) entities((Nbt.Compound)entity);
        }
      }
    }
    var pos=new ChunkPos(0,0); var dim=layout.dimensions().get(DimensionId.OVERWORLD);
    int blocks=0,sky=0,source=-1,adjacent=-1,sun=-1;
    try(var region=new RegionFile(dim.region().resolve(pos.regionName()+".mca"))) {
      for(Object value:region.read(pos.regionIndex()).list("sections").values()) {
        var sec=(Nbt.Compound)value;
        if(sec.get("BlockLight") instanceof byte[] bytes && bytes.length==2048) blocks++;
        if(sec.get("SkyLight") instanceof byte[] bytes && bytes.length==2048) sky++;
        if(sec.integer("Y",0)==4) {
          source=nibble(sec,"BlockLight",4,68,4); adjacent=nibble(sec,"BlockLight",5,68,4); sun=nibble(sec,"SkyLight",4,69,4);
        }
      }
    }
    var poi=new ArrayList<String>(); var path=dim.directory().resolve("poi").resolve(pos.regionName()+".mca");
    if(Files.isRegularFile(path)) try(var region=new RegionFile(path)) {
      var root=region.read(pos.regionIndex());
      if(root!=null) for(var value:root.compound("Sections").values()) for(Object record:((Nbt.Compound)value).list("Records").values()) {
        var n=(Nbt.Compound)record; if(n.get("pos") instanceof int[] p) poi.add(Arrays.toString(p));
      }
    }
    System.out.print("{\"duplicates\":"+ids.values().stream().filter(n->n>1).count()+",\"controlledUuidCount\":"+ids.getOrDefault(UUID.fromString(args[1]),0)+",\"blockLightSections\":"+blocks+",\"skyLightSections\":"+sky+",\"source\":"+source+",\"adjacent\":"+adjacent+",\"sun\":"+sun+",\"poi\":[");
    for(int i=0;i<poi.size();i++) { if(i>0) System.out.print(','); System.out.print('"'+poi.get(i)+'"'); }
    System.out.println("]}");
  }
}
