package wgproto;

import wgproto.Nbt.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.io.*;

/** 08 專用離線 fixture 與驗證；沿用 02 的 NBT、Anvil 與正規化 codec。 */
public final class Offline {
    public static String hash(byte[] b) throws Exception {
        return b == null ? "air" : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
    public static List<int[]> coords(int width, int height) {
        List<int[]> r = new ArrayList<>();
        for (int z=8; z<8+height; z++) for (int x=4; x<4+width; x++) for (int island=0; island<3; island++) r.add(new int[]{island*128+x,z});
        return r;
    }
    static Codec.State state(String n) { return new Codec.State("minecraft:"+n, new TreeMap<>()); }
    public static String pattern(boolean b, int sy, int x, int y, int z) {
        if (sy==8) return y<(b?12:4) ? (y%3==0?"dirt":"stone") : "air";
        if (sy==9) return (b ? ((x==1||x==14||z==1||z==14)&&y<12 || y==0) : ((x==4||x==11||z==4||z==11)&&y<6)) ? (b?"stone_bricks":"oak_planks") : "air";
        if (sy==10) return b ? (y<2 ? ((x+z)%2==0?"glass":"stone_bricks") : "air") : (y==0&&x==z ? "oak_planks":"air");
        if (sy==11) {
            int h=b?5:2;
            if (y<=h) return b?"dirt":"stone";
            if (x==2&&z==2&&y==h+1) return "chest";
            if (x==(b?8:7)&&z==7&&y==h+1) return "lectern";
            // 展示框背板兩版本一致，避免懸掛方塊被移除。
            if (x==10&&z==10&&y==9) return "stone";
            if (y==h+1 && x%4==0 && z%4==0) return b?"sea_lantern":"glowstone";
        }
        return "air";
    }
    public static NCompound section(boolean b,int sy) {
        Codec.Section s = new Codec.Section(); Map<String,Integer> ids=new LinkedHashMap<>();
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            String n=pattern(b,sy,x,y,z);
            Integer id=ids.get(n); if(id==null){id=ids.size();ids.put(n,id);s.palette.add(state(n));}
            s.idx[(y<<8)|(z<<4)|x]=id;
        }
        return new NCompound().put2("Y",(byte)sy).put2("block_states",Codec.toMcBlockStates(s))
            .put2("biomes",new NCompound().put2("palette",new NList((byte)8).add("minecraft:plains")));
    }
    public static List<NCompound> bes(boolean b,int cx,int cz) {
        int y=176+(b?6:3);
        NCompound item=new NCompound().put2("Slot",(byte)0).put2("id",b?"minecraft:emerald":"minecraft:diamond").put2("count",b?7:3);
        return List.of(new NCompound().put2("id","minecraft:chest").put2("x",cx*16+2).put2("y",y).put2("z",cz*16+2).put2("Items",new NList((byte)10).add(item)),
            new NCompound().put2("id","minecraft:lectern").put2("x",cx*16+(b?8:7)).put2("y",y).put2("z",cz*16+7));
    }
    public static void main(String[] a) throws Exception {
        if(a[0].equals("fixture")) {
            Path world=Path.of(a[1]); int dv=Integer.parseInt(a[2]);
            Path reg=world.resolve("region"); Files.createDirectories(reg);
            Map<Integer,NCompound> secs=new HashMap<>(); for(int sy=-4;sy<20;sy++) secs.put(sy,section(false,sy));
            for(int island=0;island<3;island++) {
                Region r=new Region(reg.resolve("r."+(island*4)+".0.mca"));
                for(int z=0;z<32;z++)for(int x=0;x<32;x++) {
                    int cx=island*128+x; NList ss=new NList((byte)10); for(int sy=-4;sy<20;sy++)ss.add(secs.get(sy));
                    NList be=new NList((byte)10); for(NCompound v:bes(false,cx,z))be.add(v);
                    NCompound c=new NCompound().put2("DataVersion",dv).put2("xPos",cx).put2("zPos",z).put2("yPos",-4)
                        .put2("Status","minecraft:full").put2("LastUpdate",0L).put2("InhabitedTime",0L).put2("isLightOn",(byte)0)
                        .put2("sections",ss).put2("block_entities",be).put2("block_ticks",new NList((byte)10)).put2("fluid_ticks",new NList((byte)10))
                        .put2("structures",new NCompound().put2("starts",new NCompound()).put2("References",new NCompound()))
                        .put2("PostProcessing",new NList((byte)9));
                    r.put(Region.idx(cx,z),c,(int)(System.currentTimeMillis()/1000));
                }
                r.save();
            }
            System.out.println("fixture: 3072 full chunks, three 32x32 islands; 1008 switch chunks by default");
        } else if(a[0].equals("verify")) {
            Path world=Path.of(a[1]), expected=Path.of(a[2]); String v=a[3];
            int checked=0,missing=0,mismatch=0; List<String> bad=new ArrayList<>(); Map<String,Region> cache=new HashMap<>();
            for(String ln:Files.readAllLines(expected.resolve("sections-"+v+".tsv"))) {
                String[] p=ln.split("\\t"); int cx=Integer.parseInt(p[0]),cz=Integer.parseInt(p[1]),sy=Integer.parseInt(p[2]);
                String rn="r."+(cx>>5)+"."+(cz>>5)+".mca"; Region r=cache.get(rn); if(r==null){r=new Region(world.resolve("region").resolve(rn));cache.put(rn,r);}
                NCompound c=r.read(Region.idx(cx,cz)); checked++;
                if(c==null){missing++;continue;}
                List<NCompound> be=new ArrayList<>(); if(c.list("block_entities")!=null)for(Object o:c.list("block_entities").items)if(((NCompound)o).intv("y",0)>>4==sy)be.add((NCompound)o);
                NCompound sec=null; for(Object o:c.list("sections").items)if(((NCompound)o).intv("Y",99)==sy)sec=(NCompound)o;
                String h=hash(sec==null?null:Codec.encodeSection(sec,be,sy));
                if(!h.equals(p[3])){mismatch++; if(bad.size()<20)bad.add(cx+","+cz+","+sy+":"+h+" != "+p[3]);}
            }
            Map<String,String> want=new TreeMap<>();
            for(String ln:Files.readAllLines(expected.resolve("entities-"+v+".tsv"))){String[] p=ln.split("\\t");want.put(p[0],p[1]);}
            Map<String,Integer> counts=new TreeMap<>();Map<String,String> found=new TreeMap<>(); int unexpected=0;
            for(Path path:Region.list(world.resolve("entities"))) {
                Region r=new Region(path); for(int idx=0;idx<1024;idx++)if(r.has(idx)) {
                    NCompound c=r.read(idx);NList es=c.list("Entities");if(es==null)continue;
                    for(Object o:es.items) {NCompound e=(NCompound)o; String id=Codec.uuidKey(e);
                        counts.merge(id,1,Integer::sum); found.put(id,hash(Nbt.toBytes(Codec.normEntity(e,true),true)));
                        if(!want.containsKey(id))unexpected++;
                    }
                }
            }
            int poiChecked=0,poiMismatch=0; Set<String> chunkCoords=new HashSet<>();
            for(String ln:Files.readAllLines(expected.resolve("sections-"+v+".tsv"))) {
                String[] p=ln.split("\\t"); if(!chunkCoords.add(p[0]+","+p[1]))continue;
                int cx=Integer.parseInt(p[0]),cz=Integer.parseInt(p[1]); String rn="poi/r."+(cx>>5)+"."+(cz>>5)+".mca";
                Region r=cache.get(rn);if(r==null){r=new Region(world.resolve(rn));cache.put(rn,r);}
                NCompound c=r.read(Region.idx(cx,cz));List<String> records=new ArrayList<>();
                if(c!=null&&c.comp("Sections")!=null)for(Object o:c.comp("Sections").values()) {
                    NList rs=((NCompound)o).list("Records");if(rs!=null)for(Object ob:rs.items){NCompound rec=(NCompound)ob;records.add(rec.str("type")+":"+Arrays.toString((int[])rec.get("pos")));}
                }
                String wantPoi="minecraft:librarian:"+Arrays.toString(new int[]{cx*16+(v.equals("B")?8:7),176+(v.equals("B")?6:3),cz*16+7});
                poiChecked++;if(!records.equals(List.of(wantPoi)))poiMismatch++;
            }
            int emissing=0,ediff=0,dup=0;List<String> ebad=new ArrayList<>();
            for(var e:want.entrySet()){if(!found.containsKey(e.getKey()))emissing++;else if(!e.getValue().equals(found.get(e.getKey()))){ediff++;if(ebad.size()<10)ebad.add(e.getKey());}}
            for(int n:counts.values())if(n>1)dup+=n-1;
            String json="{\"sectionsChecked\":"+checked+",\"missingChunks\":"+missing+",\"sectionMismatches\":"+mismatch+",\"entitiesExpected\":"+want.size()+",\"entitiesFound\":"+found.size()+",\"entityMissing\":"+emissing+",\"entityMismatches\":"+ediff+",\"duplicateUUIDs\":"+dup+",\"unexpectedEntities\":"+unexpected+",\"poiChunksChecked\":"+poiChecked+",\"poiMismatches\":"+poiMismatch+"}";
            System.out.println(json); if(!bad.isEmpty())System.err.println(bad);if(!ebad.isEmpty())System.err.println("entity diffs "+ebad);
            if(missing+mismatch+emissing+ediff+dup+unexpected+poiMismatch>0)System.exit(2);
        } else throw new IllegalArgumentException("fixture <overworldFolder> <DataVersion> | verify <overworldFolder> <manifestFolder> A|B");
    }
}
