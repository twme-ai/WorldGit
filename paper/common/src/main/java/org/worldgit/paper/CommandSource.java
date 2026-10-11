package org.worldgit.paper;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
/** Command contract loadable on the oldest supported API. */
public interface CommandSource {
  CommandSender getSender();
  Entity getExecutor();
  Location getLocation();
  CommandSource withLocation(Location location);
  CommandSource withExecutor(Entity entity);
  record Position(int blockX,int blockY,int blockZ) {}
  default Position position(Object value) throws CommandSyntaxException {
    if(value instanceof Position p)return p;
    throw new IllegalArgumentException("Unsupported position: "+value);
  }
  default Player player(Object value) throws CommandSyntaxException {
    if(value instanceof Player p)return p;
    var player=org.bukkit.Bukkit.getPlayerExact(String.valueOf(value));
    if(player==null)throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(() -> "Unknown player").create();
    return player;
  }
  record NativeValue(String kind,java.util.List<String> words) {}
  record NativeArgument(String kind) implements ArgumentType<Object> {
    public Object parse(StringReader reader) throws CommandSyntaxException {
      var words=new java.util.ArrayList<String>();
      for(int i=0;i<(kind.equals("position")?3:1);i++){if(i>0)reader.skipWhitespace();words.add(CommandArguments.token(reader));}
      return kind.equals("dimension")?net.kyori.adventure.key.Key.key(words.getFirst()):new NativeValue(kind,words);
    }
  }
  CommandTree.NativeTypes LEGACY_TYPES=NativeArgument::new;
}
