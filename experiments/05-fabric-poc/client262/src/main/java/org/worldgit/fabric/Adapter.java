package org.worldgit.fabric;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.brigadier.StringReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.fabricmc.fabric.api.client.rendering.v1.level.*;
import org.joml.*;
import java.nio.file.*;
import java.util.*;

/** 26.2 薄轉接：模型搬家、BindGroup/DepthStencil、GpuBufferSlice、新事件。 */
public final class Adapter {
    public static final String VERSION="26.2";
    public static String rendererInfo(){return RenderSystem.getDevice().getDeviceInfo().toString();}
    public static net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> s2c(){return net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay();}
    public static net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> c2s(){return net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay();}
    public static boolean readyToConnect(){var mc=Minecraft.getInstance();return mc.gui.overlay()==null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen;}
    public static void hideHud(){Minecraft.getInstance().gui.hud.toggle();}
    private static final RenderPipeline LINES=pipeline(false),GHOST=pipeline(true);
    private static RenderPipeline pipeline(boolean texture) {
        var layout=BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER);
        if(texture)layout.withSampler("Sampler0");
        return RenderPipelines.register(RenderPipeline.builder().withLocation(Identifier.parse("worldgit:pipeline/"+(texture?"ghost":"lines")))
            .withVertexShader(Identifier.parse("worldgit:core/"+(texture?"poc_model":"poc_lines")))
            .withFragmentShader(Identifier.parse("worldgit:core/"+(texture?"poc_texture":"poc_color")))
            .withBindGroupLayout(layout.build())
            .withVertexBinding(0,texture?DefaultVertexFormat.POSITION_TEX_COLOR:DefaultVertexFormat.POSITION_COLOR)
            // LINES 是由 quad 展開的粗線（GL_TRIANGLES）；兩頂點細線用 DEBUG_LINES。
            .withPrimitiveTopology(texture?PrimitiveTopology.QUADS:PrimitiveTopology.DEBUG_LINES)
            // 26.2 使用 reversed Z（近=1、遠=0），不能沿用 1.21.11 的 LEQUAL。
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,false))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).withCull(false).build());
    }
    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(ctx->WorldGitClient.extract());
        LevelRenderEvents.END_MAIN.register(ctx->{var cam=ctx.levelState().cameraRenderState.pos;WorldGitClient.scene.draw(RenderSystem.getModelViewMatrixCopy(),cam.x,cam.y,cam.z);});
    }
    public static String dimension(){var level=Minecraft.getInstance().level;return level==null?null:level.dimension().identifier().toString();}
    public static List<GhostScene.Quad> model(String serialized) {
        try {
            var state=BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK,new StringReader(serialized),false).blockState();
            var model=Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            var parts=new ArrayList<BlockStateModelPart>();model.collectParts(RandomSource.create(42),parts);
            var result=new ArrayList<GhostScene.Quad>();
            var directions=new ArrayList<Direction>(Arrays.asList(Direction.values()));directions.add(null);
            for(var part:parts)for(var direction:directions)for(var quad:part.getQuads(direction)){
                float[] a=new float[20];for(int v=0;v<4;v++){var p=quad.position(v);long uv=quad.packedUV(v);int k=v*5;a[k]=p.x();a[k+1]=p.y();a[k+2]=p.z();a[k+3]=UVPair.unpackU(uv);a[k+4]=UVPair.unpackV(uv);}result.add(new GhostScene.Quad(a));
            }
            WorldGitClient.LOG.info("WGPOC MODEL state={} quads={}",serialized,result.size());return List.copyOf(result);
        }catch(Exception e){throw new IllegalArgumentException("model state "+serialized,e);}
    }
    public static void draw(GhostScene.Mesh mesh,Matrix4f transform,float alpha) {
        var client=Minecraft.getInstance();var target=client.gameRenderer.mainRenderTarget();
        var uniforms=RenderSystem.getDynamicUniforms().writeTransform(transform,new Vector4f(1,1,1,alpha),new Vector3f(),new Matrix4f());
        var mode=mesh.textured()?PrimitiveTopology.QUADS:PrimitiveTopology.DEBUG_LINES;
        int indices=mesh.textured()?mesh.vertices()/4*6:mesh.vertices();var sequential=RenderSystem.getSequentialBuffer(mode);var index=sequential.getBuffer(indices);
        try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"WorldGit cached diff",target.getColorTextureView(),Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
            pass.setPipeline(mesh.textured()?GHOST:LINES);RenderSystem.bindDefaultUniforms(pass);pass.setUniform("DynamicTransforms",uniforms);
            if(mesh.textured()){var atlas=client.getTextureManager().getTexture(Identifier.withDefaultNamespace("textures/atlas/blocks.png"));pass.bindTexture("Sampler0",atlas.getTextureView(),atlas.getSampler());}
            pass.setVertexBuffer(0,mesh.buffer().slice());pass.setIndexBuffer(index,sequential.type());pass.drawIndexed(indices,1,0,0,0);
        }
    }
    public static void screenshot(Path path){Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(),image->{try{Files.createDirectories(path.getParent());image.writeToFile(path);WorldGitClient.LOG.info("WGPOC SCREENSHOT {}",path);}catch(Exception e){WorldGitClient.LOG.error("screenshot",e);}finally{image.close();}});}
}
