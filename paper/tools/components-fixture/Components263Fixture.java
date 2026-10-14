package org.worldgit.fixture;

import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** 驗收專用：以原生 codec 輸出玩家手上真物品的 component wire bytes。 */
public final class Components263Fixture extends JavaPlugin {
  public void onEnable() {
    getCommand("wg263fixture").setExecutor((sender,command,label,args)->{
      var player=Bukkit.getPlayerExact(args[0]);if(player==null)return false;
      player.getScheduler().run(this,task->{
        try {
          var item=CraftItemStack.asNMSCopy(player.getInventory().getItemInMainHand());
          var registry=((CraftWorld)player.getWorld()).getHandle().registryAccess();
          int count=0;
          for(var entry:item.getComponentsPatch().split().added()) {
            var type=entry.type();
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),registry);
            try {
              buffer.writeVarInt(BuiltInRegistries.DATA_COMPONENT_TYPE.getId(type));
              @SuppressWarnings("rawtypes") var codec=(net.minecraft.network.codec.StreamCodec)type.streamCodec();
              codec.encode(buffer,entry.value());
              byte[] bytes=new byte[buffer.readableBytes()];buffer.readBytes(bytes);
              sender.sendMessage("WG263WIRE "+BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)+" "+HexFormat.of().formatHex(bytes));
              count++;
            }finally {buffer.release();}
          }
          sender.sendMessage("WG263WIRE complete count="+count);
        }catch(Throwable error){sender.sendMessage("WG263WIRE failed "+error);error.printStackTrace();}
      },null);return true;
    });
  }
}
