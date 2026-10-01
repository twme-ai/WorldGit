package org.worldgit.fabric;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.brigadier.StringReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import org.joml.*;
import java.nio.file.*;
import java.util.*;

/** 1.21.11 薄轉接：原版模型、舊版 GPU API、世界事件與 framebuffer。 */
public final class Adapter {
    public static final String VERSION="1.21.11";
    public static String rendererInfo(){return RenderSystem.getDevice().getBackendName()+" / "+RenderSystem.getDevice().getRenderer()+" / "+RenderSystem.getDevice().getVersion();}
    public static net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> s2c(){return net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C();}
    public static net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> c2s(){return net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S();}
    public static boolean readyToConnect(){var mc=Minecraft.getInstance();return mc.getOverlay()==null && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen;}
    public static void hideHud(){Minecraft.getInstance().options.hideGui=true;}
    private static final RenderPipeline LINES=pipeline(false),GHOST=pipeline(true);
    private static RenderPipeline pipeline(boolean texture) {
        var b=RenderPipeline.builder().withLocation(Identifier.parse("worldgit:pipeline/"+(texture?"ghost":"lines")))
            .withVertexShader(Identifier.parse("worldgit:core/"+(texture?"poc_model":"poc_lines")))
            .withFragmentShader(Identifier.parse("worldgit:core/"+(texture?"poc_texture":"poc_color")))
            .withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER)
            .withVertexFormat(texture?DefaultVertexFormat.POSITION_TEX_COLOR:DefaultVertexFormat.POSITION_COLOR,texture?VertexFormat.Mode.QUADS:VertexFormat.Mode.DEBUG_LINES)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false).withCull(false).withBlend(BlendFunction.TRANSLUCENT);
        if(texture)b.withSampler("Sampler0");return RenderPipelines.register(b.build());
    }
    public static void register() {
        WorldRenderEvents.END_EXTRACTION.register(ctx->WorldGitClient.extract());
        WorldRenderEvents.END_MAIN.register(ctx->{var cam=ctx.worldState().cameraRenderState.pos;WorldGitClient.scene.draw(RenderSystem.getModelViewMatrix(),cam.x,cam.y,cam.z);});
    }
    public static String dimension(){var level=Minecraft.getInstance().level;return level==null?null:level.dimension().identifier().toString();}
    public static List<GhostScene.Quad> model(String serialized) {
        try {
            var state=BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK,new StringReader(serialized),false).blockState();
            var model=Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            var parts=new ArrayList<BlockModelPart>();model.collectParts(RandomSource.create(42),parts);
            var result=new ArrayList<GhostScene.Quad>();
            var directions=new ArrayList<Direction>(Arrays.asList(Direction.values()));directions.add(null);
            for(var part:parts)for(var direction:directions)for(var quad:part.getQuads(direction)){
                float[] a=new float[20];for(int v=0;v<4;v++){var p=quad.position(v);long uv=quad.packedUV(v);int k=v*5;a[k]=p.x();a[k+1]=p.y();a[k+2]=p.z();a[k+3]=UVPair.unpackU(uv);a[k+4]=UVPair.unpackV(uv);}result.add(new GhostScene.Quad(a));
            }
            WorldGitClient.LOG.info("WGPOC MODEL state={} quads={}",serialized,result.size());return List.copyOf(result);
        }catch(Exception e){throw new IllegalArgumentException("model state "+serialized,e);}
    }
    public static void draw(GhostScene.Mesh mesh,Matrix4f transform,float alpha) {
        var client=Minecraft.getInstance();var target=client.getMainRenderTarget();
        var uniforms=RenderSystem.getDynamicUniforms().writeTransform(transform,new Vector4f(1,1,1,alpha),new Vector3f(),new Matrix4f());
        var mode=mesh.textured()?VertexFormat.Mode.QUADS:VertexFormat.Mode.DEBUG_LINES;
        int indices=mesh.textured()?mesh.vertices()/4*6:mesh.vertices();var sequential=RenderSystem.getSequentialBuffer(mode);var index=sequential.getBuffer(indices);
        try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"WorldGit cached diff",target.getColorTextureView(),OptionalInt.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
            pass.setPipeline(mesh.textured()?GHOST:LINES);RenderSystem.bindDefaultUniforms(pass);pass.setUniform("DynamicTransforms",uniforms);
            if(mesh.textured()){var atlas=client.getTextureManager().getTexture(Identifier.withDefaultNamespace("textures/atlas/blocks.png"));pass.bindTexture("Sampler0",atlas.getTextureView(),atlas.getSampler());}
            pass.setVertexBuffer(0,mesh.buffer());pass.setIndexBuffer(index,sequential.type());pass.drawIndexed(0,0,indices,1);
        }
    }
    public static void screenshot(Path path){Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try{Files.createDirectories(path.getParent());image.writeToFile(path);WorldGitClient.LOG.info("WGPOC SCREENSHOT {}",path);}catch(Exception e){WorldGitClient.LOG.error("screenshot",e);}finally{image.close();}});}
}
