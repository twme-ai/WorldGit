package org.worldgit.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.*;

/** The common entry point references only API available in 1.20.1. */
final class CommandRegistration {
  static void register(WorldGitPlugin plugin,CommandTree.Actions actions,CommandSuggestions suggestions) {
    if(!Set.of("1.20.1","1.20.4").contains(plugin.bridge().minecraftVersion())) {
      try {
        Class.forName("org.worldgit.paper."+(Set.of("1.20.6","1.21.1").contains(plugin.bridge().minecraftVersion())
            ? "ComponentsCommandRegistration" : "ModernCommandRegistration"),true,plugin.getClass().getClassLoader())
            .getDeclaredMethod("register",WorldGitPlugin.class,CommandTree.Actions.class,CommandSuggestions.class)
            .invoke(null,plugin,actions,suggestions);
        return;
      } catch(ReflectiveOperationException error) { throw new IllegalStateException("Command registration failed",error); }
    }
    var dispatcher=new CommandDispatcher<CommandSource>();
    for(String name:List.of("wg","worldgit","worldgit:git","wgit","git")) {
      if(name.equals("wgit") && !plugin.getConfig().getBoolean("aliases.wgit",true))continue;
      if(name.equals("git") && (!plugin.getConfig().getBoolean("aliases.git",true) || plugin.getServer().getCommandMap().getCommand("git")!=null))continue;
      dispatcher.register(new CommandTree(actions,suggestions,plugin.bridge().commandTypes()::apply).build(name));
      var command=new Command(name,"WorldGit","/"+name+" help",List.of()) {
        public boolean execute(CommandSender sender,String label,String[] arguments) {
          var source=plugin.bridge().commandSource(sender);
          try { dispatcher.execute(name+(arguments.length==0?"":" "+String.join(" ",arguments)),source); }
          catch(CommandSyntaxException error) { sender.sendMessage(Messages.error(error.getMessage())); }
          return true;
        }
        public List<String> tabComplete(CommandSender sender,String label,String[] arguments) {
          var source=plugin.bridge().commandSource(sender);
          var future=dispatcher.getCompletionSuggestions(dispatcher.parse(name+" "+String.join(" ",arguments),source));
          return future.isDone()?future.join().getList().stream().map(s->s.getText()).toList():List.of();
        }
      };
      plugin.getServer().getCommandMap().register("worldgit",command);
    }
  }
  record LegacySource(CommandSender getSender,Entity getExecutor,Location getLocation) implements CommandSource {
    public CommandSource withLocation(Location location){return new LegacySource(getSender,getExecutor,location);}
    public CommandSource withExecutor(Entity entity){return new LegacySource(getSender,entity,getLocation);}
    public Position position(Object value) throws CommandSyntaxException {
      if(!(value instanceof NativeValue nativeValue))return CommandSource.super.position(value);
      var words=nativeValue.words();
      try {
        return new Position(coordinate(words.get(0),getLocation.getX()),coordinate(words.get(1),getLocation.getY()),coordinate(words.get(2),getLocation.getZ()));
      }catch(NumberFormatException error){throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(()->"Invalid coordinate").create();}
    }
    private static int coordinate(String value,double origin) {return (int)Math.floor(value.startsWith("~")?origin+(value.length()==1?0:Double.parseDouble(value.substring(1))):Double.parseDouble(value));}
    public Player player(Object value) throws CommandSyntaxException {return CommandSource.super.player(value instanceof NativeValue nativeValue?nativeValue.words().getFirst():value);}
  }
}
