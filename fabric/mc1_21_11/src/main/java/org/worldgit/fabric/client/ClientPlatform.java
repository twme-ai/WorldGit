package org.worldgit.fabric.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.*;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** 1.21.11 客戶端轉接層：世界渲染事件、RenderPipeline／RenderPass（舊版 GPU API）、原版方塊模型。 */
final class ClientPlatform {
  private ClientPlatform() {}

  static final String VERSION = "1.21.11";

  private static RenderPipeline pipeline(boolean texture, boolean seeThrough) {
    var builder =
        RenderPipeline.builder()
            .withLocation(Identifier.parse("worldgit:pipeline/" + (texture ? "ghost" : "lines") + (seeThrough ? "_see" : "")))
            .withVertexShader(Identifier.parse("worldgit:core/" + (texture ? "wg_model" : "wg_lines")))
            .withFragmentShader(Identifier.parse("worldgit:core/" + (texture ? "wg_texture" : "wg_color")))
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(
                texture ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR,
                texture ? VertexFormat.Mode.QUADS : VertexFormat.Mode.DEBUG_LINES)
            .withDepthTestFunction(seeThrough ? DepthTestFunction.NO_DEPTH_TEST : DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT);
    if (texture) builder.withSampler("Sampler0");
    return RenderPipelines.register(builder.build());
  }

  private static final RenderPipeline LINES = pipeline(false, false), LINES_SEE = pipeline(false, true), GHOST = pipeline(true, false), GHOST_SEE = pipeline(true, true);

  /** 客戶端指令建構器（Fabric API 在 26.2 把 ClientCommandManager 改名為 ClientCommands）。 */
  static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
    return ClientCommandManager.literal(name);
  }

  static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
    return ClientCommandManager.argument(name, type);
  }

  static void registerRender(RenderCallback callback) {
    WorldRenderEvents.END_MAIN.register(
        ctx -> {
          var cam = ctx.worldState().cameraRenderState.pos;
          callback.render(RenderSystem.getModelViewMatrix(), cam.x, cam.y, cam.z);
        });
  }

  /** 原版（含資源包）的方塊模型 quads；失敗丟出 IllegalArgumentException。 */
  static List<Geometry.Quad> model(String serialized) {
    try {
      var state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, new StringReader(serialized), false).blockState();
      var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
      var parts = new ArrayList<BlockModelPart>();
      model.collectParts(RandomSource.create(42), parts);
      var result = new ArrayList<Geometry.Quad>();
      var directions = new ArrayList<Direction>(Arrays.asList(Direction.values()));
      directions.add(null);
      for (var part : parts)
        for (var direction : directions)
          for (var quad : part.getQuads(direction)) {
            float[] a = new float[20];
            for (int v = 0; v < 4; v++) {
              var p = quad.position(v);
              long uv = quad.packedUV(v);
              int k = v * 5;
              a[k] = p.x();
              a[k + 1] = p.y();
              a[k + 2] = p.z();
              a[k + 3] = UVPair.unpackU(uv);
              a[k + 4] = UVPair.unpackV(uv);
            }
            result.add(new Geometry.Quad(a));
          }
      return List.copyOf(result);
    } catch (Exception e) {
      throw new IllegalArgumentException("model state " + serialized, e);
    }
  }

  static void draw(GpuBuffer buffer, int vertices, boolean textured, boolean seeThrough, Matrix4f transform, float alpha) {
    var client = Minecraft.getInstance();
    var target = client.getMainRenderTarget();
    var uniforms = RenderSystem.getDynamicUniforms().writeTransform(transform, new Vector4f(1, 1, 1, alpha), new Vector3f(), new Matrix4f());
    var mode = textured ? VertexFormat.Mode.QUADS : VertexFormat.Mode.DEBUG_LINES;
    int indices = textured ? vertices / 4 * 6 : vertices;
    var sequential = RenderSystem.getSequentialBuffer(mode);
    var index = sequential.getBuffer(indices);
    try (var pass =
        RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(() -> "WorldGit preview", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
      pass.setPipeline(textured ? (seeThrough ? GHOST_SEE : GHOST) : (seeThrough ? LINES_SEE : LINES));
      RenderSystem.bindDefaultUniforms(pass);
      pass.setUniform("DynamicTransforms", uniforms);
      if (textured) {
        var atlas = client.getTextureManager().getTexture(Identifier.withDefaultNamespace("textures/atlas/blocks.png"));
        pass.bindTexture("Sampler0", atlas.getTextureView(), atlas.getSampler());
      }
      pass.setVertexBuffer(0, buffer);
      pass.setIndexBuffer(index, sequential.type());
      pass.drawIndexed(0, 0, indices, 1);
    }
  }

  static void setScreen(net.minecraft.client.gui.screens.Screen screen) { Minecraft.getInstance().setScreen(screen); }

  static net.minecraft.client.KeyMapping registerKey(String name, int key) {
    return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(new net.minecraft.client.KeyMapping(
        name, key, net.minecraft.client.KeyMapping.Category.register(net.minecraft.resources.Identifier.parse("worldgit:controls"))));
  }
  static void registerCommentHud() {
    net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,Identifier.parse("worldgit:comments"),(g,delta)->{
      var mc=Minecraft.getInstance();if(mc.level==null)return;
      int y=12,width=Math.min(600,g.guiWidth()-24);
      for(var line:ClientRuntime.get().commentHud()) {
        for(var part:mc.font.split(line,width)) {
          if(y>g.guiHeight()-70)return;
          g.fill(8,y-2,12+width,y+10,0xCC101820);
          g.drawString(mc.font,part,12,y,0xFFFFFFFF,true);y+=11;
        }
        y+=3;
      }
    });
  }

}
