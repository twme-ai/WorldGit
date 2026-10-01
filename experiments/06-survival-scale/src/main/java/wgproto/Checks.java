package wgproto;
import java.nio.file.*;
import java.util.*;
import wgproto.Nbt.*;
/** 驗證會影響追蹤範圍的政策與黏性錨點，並非只重述編碼實作。 */
public final class Checks {
    static void require(boolean v) { if(!v) throw new AssertionError(); }
    static NCompound entity(String id,double x) {
        NList p=new NList((byte)6);p.add(x);p.add(64.0);p.add(0.0);
        return new NCompound().put2("id",id).put2("Pos",p).put2("UUID",new int[]{1,2,3,4});
    }
    public static void run() throws Exception {
        Path p=Files.createTempFile(Path.of(".work/survival-scale"),"rules-",".txt");
        try {
            Files.writeString(p,"entity * !persistent\n!entity minecraft:item # keep items\n");
            IgnoreRules r=new IgnoreRules(p);
            require(r.ignored(entity("minecraft:zombie",0)));
            require(!r.ignored(entity("minecraft:zombie",0).put2("CustomName","pet")));
            require(!r.ignored(entity("minecraft:item",0)));
            require(r.ignored(entity("minecraft:player",0)));
            Files.writeString(p,"area 1 2 3 4 5 6\n");
            boolean rejected=false;try{new IgnoreRules(p);}catch(java.io.IOException e){rejected=true;}require(rejected);
            require(Codec.stickyEqual(entity("minecraft:cow",0),entity("minecraft:cow",1.9),2));
            require(!Codec.stickyEqual(entity("minecraft:cow",0),entity("minecraft:cow",2.1),2));
            require(!Codec.stickyEqual(entity("minecraft:armor_stand",0),entity("minecraft:armor_stand",0.01),4));
            IgnoreRules.extraNoise=true;
            NCompound item=entity("minecraft:item",0).put2("Item",new NCompound().put2("id","minecraft:stone").put2("count",1));
            NCompound other=(NCompound)Nbt.copyVal(item);other.comp("Item").put("count",8);
            require(Codec.stickyEqual(Codec.normEntity(item,true),Codec.normEntity(other,true),0));
            other.comp("Item").put("id","minecraft:dirt");
            require(!Codec.stickyEqual(Codec.normEntity(item,true),Codec.normEntity(other,true),0));
            System.out.println("CHECK PASS policy, reinclusion, player exclusion, unsupported syntax, sticky anchor, static position, item identity");
        } finally { Files.deleteIfExists(p); }
    }
}
