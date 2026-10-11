import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.version.*;
import org.yaml.snakeyaml.Yaml;

/** Generate public registry facts, without distributing Minecraft classes or mappings. Run under bench.lock. */
class VanillaDefaults {
  public static void main(String[] args)throws Exception {
    var worker=new DfuWorker(MojangRuntime.prepare(MinecraftVersion.byId("26.3")));
    Method method=DfuWorker.class.getDeclaredMethod("method",String.class,String.class,int.class);method.setAccessible(true);
    Method field=DfuWorker.class.getDeclaredMethod("field",String.class,String.class);field.setAccessible(true);
    ((Method)method.invoke(worker,"net.minecraft.server.Bootstrap","bootStrap",0)).invoke(null);
    Object types=((Field)field.invoke(worker,"net.minecraft.core.registries.BuiltInRegistries","ENTITY_TYPE")).get(null);
    Object health=((Field)field.invoke(worker,"net.minecraft.world.entity.ai.attributes.Attributes","MAX_HEALTH")).get(null);
    Method supplier=(Method)method.invoke(worker,"net.minecraft.world.entity.ai.attributes.DefaultAttributes","getSupplier",1),getKey=Arrays.stream(types.getClass().getMethods()).filter(m->m.getName().equals("getKey")&&m.getParameterCount()==1).findFirst().orElseThrow();
    var values=new TreeMap<String,Double>();
    for(Object type:(Iterable<?>)types) {Object attributes=supplier.invoke(null,type);if(attributes==null)continue;Method base=Arrays.stream(attributes.getClass().getMethods()).filter(m->m.getName().equals("getBaseValue")&&m.getParameterCount()==1).findFirst().orElseThrow();values.put(getKey.invoke(types,type).toString(),((Number)base.invoke(attributes,health)).doubleValue());}
    Files.writeString(Path.of(args[0]),new Yaml().dump(values));System.out.println("living defaults="+values.size());
  }
}
