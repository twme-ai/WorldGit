package org.worldgit.fabric.logic;

import java.util.*;
import org.worldgit.core.apply.Scope;

/** Phase 2 範圍與互斥旗標解析，不依賴 Minecraft。 */
public record OperationArgs(List<String> positional,Set<String> flags,Map<String,List<String>> values) {
    public static OperationArgs parse(String text,Set<String> flags,Map<String,Integer> values) {
        var args=text==null || text.isBlank() ? List.<String>of() : Arrays.asList(text.trim().split("\\s+"));
        var positions=new ArrayList<String>(); var selected=new HashSet<String>(); var options=new HashMap<String,List<String>>();
        for(int i=0;i<args.size();i++) {
            String token=args.get(i),key=token.contains("=") ? token.substring(0,token.indexOf('=')) : token;
            if(flags.contains(key)) {
                if(!token.equals(key) || !selected.add(key)) throw new IllegalArgumentException("重複或非法旗標："+token);
            } else if(values.containsKey(key)) {
                if(options.containsKey(key)) throw new IllegalArgumentException("重複選項："+key);
                var list=new ArrayList<String>();
                if(!key.equals(token)) list.add(token.substring(token.indexOf('=')+1));
                while(list.size()<values.get(key)) {
                    if(++i>=args.size() || args.get(i).startsWith("--")) throw new IllegalArgumentException("選項缺少值："+key);
                    list.add(args.get(i));
                }
                if(list.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("選項缺少值："+key);
                options.put(key,List.copyOf(list));
            } else if(token.startsWith("--")) throw new IllegalArgumentException("未知選項："+token);
            else positions.add(token);
        }
        if(selected.contains("--force") && selected.contains("--stash")) throw new IllegalArgumentException("--force／--stash 互斥");
        if(options.containsKey("--chunks") && options.containsKey("--box")) throw new IllegalArgumentException("--chunks／--box 互斥");
        return new OperationArgs(List.copyOf(positions),Set.copyOf(selected),Map.copyOf(options));
    }
    public boolean flag(String name) { return flags.contains(name); }
    public int number(String name) { return Integer.parseInt(values.get(name).getFirst()); }
    public Scope scope(int cx,int cz) {
        if(values.containsKey("--chunks")) return Scope.chunkRadius(cx,cz,number("--chunks"));
        if(values.containsKey("--box")) {
            var a=values.get("--box").stream().mapToInt(Integer::parseInt).toArray();
            return Scope.box(a[0],a[1],a[2],a[3],a[4],a[5]);
        }
        return Scope.all();
    }
}
