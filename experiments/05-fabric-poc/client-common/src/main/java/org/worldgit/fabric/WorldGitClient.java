package org.worldgit.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;
import net.minecraft.resources.Identifier;
import org.worldgit.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

public final class WorldGitClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("WorldGitPoc");
    public static GhostScene scene = new GhostScene();
    private static Assembler assembler = new Assembler();
    public static List<Protocol.Entry> pending;
    public static String dimension;
    public static int currentCount;
    public static boolean handshaken;
    public record Payload(Type<Payload> type, byte[] bytes) implements CustomPacketPayload {}
    private static TypeAndCodec<RegistryFriendlyByteBuf,Payload> channel(String name) {
        var type = new CustomPacketPayload.Type<Payload>(Identifier.parse(name));
        var codec = new StreamCodec<RegistryFriendlyByteBuf,Payload>() {
            public Payload decode(RegistryFriendlyByteBuf buf) {
                int n=buf.readableBytes(); if(n>Protocol.MAX_PAYLOAD) throw new IllegalArgumentException("oversize payload");
                byte[] bytes=new byte[n];buf.readBytes(bytes);return new Payload(type,bytes);
            }
            public void encode(RegistryFriendlyByteBuf buf,Payload payload) { if(payload.bytes.length>Protocol.MAX_PAYLOAD) throw new IllegalArgumentException("oversize payload");buf.writeBytes(payload.bytes); }
        };
        return new TypeAndCodec<>(type,codec);
    }
    @Override public void onInitializeClient() {
        var hello=channel(Protocol.HELLO);var diff=channel(Protocol.DIFF);var clear=channel(Protocol.CLEAR);
        Adapter.s2c().register(hello.type(),hello.codec());
        Adapter.c2s().register(hello.type(),hello.codec());
        Adapter.s2c().register(diff.type(),diff.codec());
        Adapter.s2c().register(clear.type(),clear.codec());
        ClientPlayNetworking.registerGlobalReceiver(hello.type(),(payload,ctx)->{
            try { var h=Protocol.hello(payload.bytes); if(h.version()!=Protocol.VERSION) { LOG.warn("WGPOC unsupported protocol {}",h.version());return; }
                ClientPlayNetworking.send(new Payload(hello.type(),Protocol.hello(new Protocol.Hello(Protocol.VERSION,h.nonce(),Protocol.CAPABILITIES))));
                handshaken=true;LOG.info("WGPOC HANDSHAKE_OK nonce={} capabilities={}",h.nonce(),Protocol.CAPABILITIES);
            } catch(Exception e){LOG.error("WGPOC hello rejected",e);}
        });
        ClientPlayNetworking.registerGlobalReceiver(diff.type(),(payload,ctx)->{
            try { var p=Protocol.part(payload.bytes); if(!handshaken) throw new IllegalArgumentException("not handshaken");
                if(!p.dimension().equals(Adapter.dimension())) { LOG.warn("WGPOC ignored other dimension {}",p.dimension());return; }
                var entries=assembler.accept(p,System.currentTimeMillis());
                if(entries!=null) {pending=entries;dimension=p.dimension();LOG.info("WGPOC BATCH_COMPLETE n={} parts={}",entries.size(),p.parts());}
            } catch(Exception e){assembler.reset();LOG.error("WGPOC diff rejected",e);}
        });
        ClientPlayNetworking.registerGlobalReceiver(clear.type(),(payload,ctx)->{
            try {assembler.clear(Protocol.clear(payload.bytes));pending=null;scene.close();currentCount=0;LOG.info("WGPOC CLEARED");}
            catch(Exception e){LOG.error("WGPOC clear rejected",e);}
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{scene.close();assembler=new Assembler();pending=null;currentCount=0;handshaken=false;});
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->scene.close());
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(currentCount!=0 && !Objects.equals(dimension,Adapter.dimension())){scene.close();currentCount=0;pending=null;assembler=new Assembler();}
            Automation.tick(client);
        });
        Adapter.register();LOG.info("WGPOC FABRIC_LOADED adapter={}",Adapter.VERSION);
    }
    /** 在 extraction 階段使用模型資源，完成後 drawing 僅讀 GPU buffer 與 render state。 */
    public static void extract() {
        if(pending==null)return;
        var entries=pending;pending=null;
        LOG.info("WGPOC RENDERER {}",Adapter.rendererInfo());
        scene.close();scene=new GhostScene();scene.build(entries);currentCount=entries.size();
    }
}
