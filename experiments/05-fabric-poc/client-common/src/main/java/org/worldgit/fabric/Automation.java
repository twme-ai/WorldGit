package org.worldgit.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import java.nio.file.*;

/** 僅 -Dwgpoc.autotest=true 啟用；由本機文字檔驅動真正的客戶端操作及截圖。 */
public final class Automation {
    private static final boolean ENABLED=Boolean.getBoolean("wgpoc.autotest");
    private static final Path ROOT=Path.of(System.getProperty("wgpoc.root",".")).toAbsolutePath().normalize();
    private static final Path CONTROL=ROOT.resolve(".work/fabric-poc/control-"+Adapter.VERSION+".txt");
    private static int ticks;
    private static boolean connected,logged;
    private static String last="";
    public static void tick(Minecraft client) {
        if(!ENABLED)return;ticks++;
        if(!connected && ticks>50 && Adapter.readyToConnect()){
            connected=true;Adapter.hideHud();client.options.enableVsync().set(false);client.options.framerateLimit().set(260);
            client.options.renderDistance().set(6);
            String addr="127.0.0.1:"+Integer.getInteger("wgpoc.port",25631);
            WorldGitClient.LOG.info("WGPOC AUTO_CONNECT {}",addr);
            ConnectScreen.startConnecting(new TitleScreen(),client,ServerAddress.parseString(addr),new ServerData("WorldGit PoC",addr,ServerData.Type.OTHER),false,null);
        }
        if(client.player!=null && !logged){logged=true;WorldGitClient.LOG.info("WGPOC AUTO_JOINED name={}",client.player.getName().getString());}
        if(ticks%5!=0 || !Files.exists(CONTROL))return;
        try {
            String text=Files.readString(CONTROL);if(text.equals(last))return;last=text;
            String[] lines=text.split("\n",2);if(lines.length!=2)return;
            String action=lines[1].trim();
            if(action.startsWith("command ") && client.getConnection()!=null)client.getConnection().sendCommand(action.substring(8));
            else if(action.startsWith("screenshot "))Adapter.screenshot(ROOT.resolve("experiments/05-fabric-poc/screenshots").resolve(action.substring(11)));
            else if(action.equals("measure"))WorldGitClient.scene.measure();
            else if(action.equals("quit")){WorldGitClient.LOG.info("WGPOC AUTO_DONE");client.stop();}
            WorldGitClient.LOG.info("WGPOC CONTROL_ACK {} {}",lines[0],action);
        }catch(Exception e){WorldGitClient.LOG.error("WGPOC automation",e);}
    }
}
