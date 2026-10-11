package org.worldgit.paper;

import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.tree.*;
import io.papermc.paper.command.brigadier.*;
import io.papermc.paper.command.brigadier.argument.*;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;

/** Compiled only by adapters whose API supplies lifecycle Brigadier commands. */
final class ComponentsCommandRegistration {
  static void register(WorldGitPlugin plugin,CommandTree.Actions actions,CommandSuggestions suggestions) {
    CommandMessages.serializer=MessageComponentSerializer.message()::serialize;
    plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event->{
      var registrar=event.registrar();
      for(String name:List.of("wg","worldgit:git","wgit","git")) {
        if(name.equals("wgit") && !plugin.getConfig().getBoolean("aliases.wgit",true))continue;
        if(name.equals("git") && (!plugin.getConfig().getBoolean("aliases.git",true) || registrar.getDispatcher().getRoot().getChild("git")!=null))continue;
        var dispatcher=new CommandDispatcher<CommandSource>();
        var root=dispatcher.register(new CommandTree(actions,suggestions,kind->switch(kind){
          case "position"->ArgumentTypes.blockPosition();case "dimension"->ArgumentTypes.key();case "player"->ArgumentTypes.player();default->throw new IllegalArgumentException(kind);
        }).build(name));
        registrar.register((LiteralCommandNode<CommandSourceStack>)map(root,dispatcher),"WorldGit",name.equals("wg")?List.of("worldgit"):List.of());
      }
    });
  }
  private static CommandNode<CommandSourceStack> map(CommandNode<CommandSource> node,CommandDispatcher<CommandSource> dispatcher) {
    ArgumentBuilder<CommandSourceStack,?> builder;
    if(node instanceof ArgumentCommandNode<CommandSource,?> argument) {
      var child=RequiredArgumentBuilder.<CommandSourceStack,Object>argument(node.getName(),wire(argument.getType()));
      if(argument.getCustomSuggestions()!=null)child.suggests((context,suggestions)->{
        var input=input(context,dispatcher);
        int cursor=suggestions.getStart()+suggestions.getRemaining().length()-input.offset();
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input.text(),new Source(context.getLastChild().getSource())),cursor)
            .thenApply(result->shift(result,input.offset()));
      });
      builder=child;
    } else builder=LiteralArgumentBuilder.<CommandSourceStack>literal(node.getName());
    builder.requires(source->node.canUse(new Source(source)));
    if(node.getCommand()!=null)builder.executes(context->{
      var input=input(context,dispatcher);
      try {return dispatcher.execute(input.text(),new Source(context.getSource()));}
      catch(com.mojang.brigadier.exceptions.CommandSyntaxException error) {
        if(error.getInput()==null || error.getCursor()<0)throw error;
        throw new com.mojang.brigadier.exceptions.CommandSyntaxException(error.getType(),error.getRawMessage(),context.getInput(),error.getCursor()+input.offset());
      }
    });
    for(var child:node.getChildren())builder.then(map(child,dispatcher));
    return builder.build();
  }
  private record Input(String text,int offset) {}
  /** Paper aliases and execute redirects keep the original input; only the parsed root is replaced. */
  private static Input input(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,CommandDispatcher<CommandSource> dispatcher) {
    context=context.getLastChild();
    String root=dispatcher.getRoot().getChildren().iterator().next().getName();
    // 1.20.6／1.21.1 的 registrar 預設用 redirect 註冊短名稱；child context 不再含根 literal。
    if(context.getNodes().isEmpty())return new Input(root,context.getInput().length()-root.length());
    var first=context.getNodes().getFirst();
    String parsed=first.getNode().getName();
    if(parsed.equals(root) || parsed.endsWith(":"+root)) {
      int end=first.getRange().getEnd();
      return new Input(root+context.getInput().substring(end),end-root.length());
    }
    int start=first.getRange().getStart();
    return new Input(root+" "+context.getInput().substring(start),start-root.length()-1);
  }
  private static com.mojang.brigadier.suggestion.Suggestions shift(com.mojang.brigadier.suggestion.Suggestions suggestions,int offset) {
    if(offset==0)return suggestions;
    var range=suggestions.getRange();
    var shifted=com.mojang.brigadier.context.StringRange.between(range.getStart()+offset,range.getEnd()+offset);
    var list=suggestions.getList().stream().map(value->new com.mojang.brigadier.suggestion.Suggestion(
        com.mojang.brigadier.context.StringRange.between(value.getRange().getStart()+offset,value.getRange().getEnd()+offset),value.getText(),value.getTooltip())).toList();
    return new com.mojang.brigadier.suggestion.Suggestions(shifted,list);
  }
  @SuppressWarnings("unchecked") private static ArgumentType<Object> wire(ArgumentType<?> type) {
    if(!(type instanceof WireArgument<?,?> custom))return (ArgumentType<Object>)type;
    return new CustomArgumentType<Object,Object>() {
      public ArgumentType<Object> getNativeType(){return (ArgumentType<Object>)custom.getNativeType();}
      public Object parse(StringReader reader) throws com.mojang.brigadier.exceptions.CommandSyntaxException{return custom.parse(reader);}
      public <S> Object parse(StringReader reader,S source) throws com.mojang.brigadier.exceptions.CommandSyntaxException{return custom.parse(reader);}
    };
  }
  record Source(CommandSourceStack value) implements CommandSource {
    public CommandSender getSender(){return value.getSender();}
    public Entity getExecutor(){return value.getExecutor();}
    public Location getLocation(){return value.getLocation();}
    public CommandSource withLocation(Location location){return new Source(((PaperCommandSourceStack)value).getHandle().withPosition(new net.minecraft.world.phys.Vec3(location.getX(),location.getY(),location.getZ())).withLevel(((org.bukkit.craftbukkit.CraftWorld)location.getWorld()).getHandle()));}
    public CommandSource withExecutor(Entity entity){return new Source(((PaperCommandSourceStack)value).getHandle().withEntity(((org.bukkit.craftbukkit.entity.CraftEntity)entity).getHandle()));}
    public Position position(Object raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      var pos=((io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver)raw).resolve(value);
      return new Position(pos.blockX(),pos.blockY(),pos.blockZ());
    }
    public Player player(Object raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      return ((io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver)raw).resolve(value).getFirst();
    }
  }
}
