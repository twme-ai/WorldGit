package org.worldgit.hub.collaboration;
import java.io.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
public final class BoundedYaml {
  private BoundedYaml() {}
  public static Map<?,?> parse(String text) throws IOException {
    if(text.length()>65536) throw new IOException("YAML 超過 64 KiB");
    var o=new LoaderOptions();o.setAllowDuplicateKeys(false);o.setMaxAliasesForCollections(0);o.setNestingDepthLimit(8);o.setCodePointLimit(65536);
    try{Object value=new Yaml(new SafeConstructor(o)).load(text);if(value instanceof Map<?,?> m)return m;throw new IOException("YAML 必須 mapping");}
    catch(RuntimeException ex){throw new IOException("YAML 無效");}
  }
}
