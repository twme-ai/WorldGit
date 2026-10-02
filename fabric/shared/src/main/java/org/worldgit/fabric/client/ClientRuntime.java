package org.worldgit.fabric.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.kyori.adventure.platform.modcommon.MinecraftClientAudiences;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.worldgit.core.model.DimensionId;
import org.worldgit.fabric.Mini;
import org.worldgit.fabric.Net;
import org.worldgit.fabric.logic.*;
import org.worldgit.i18n.MessageCatalog;
import org.worldgit.protocol.DiffPalette;
import org.worldgit.protocol.Protocol;

/**
 * 客戶端狀態：設定、握手、各維度的預覽場景。網路 receiver 與 render 事件都在 client thread，所以不需要額外同步。
 * 單人世界與連到 Paper／Fabric 伺服器走同一條路徑（兩者都是 worldgit:* plugin channel）。
 */
public final class ClientRuntime {
  static final Logger LOG = LoggerFactory.getLogger("WorldGit");
  private static ClientRuntime instance;

  public static ClientRuntime get() {
    return instance;
  }

  static void init(Path configDir) {
    instance = new ClientRuntime(configDir);
  }

  private final Path configDir;
  private final MessageCatalog catalog;
  private final ClientPreviews previews = new ClientPreviews();
  private final Map<DimensionId, PreviewScene> scenes = new HashMap<>();
  private ClientConfig config;
  private DiffPalette serverPalette;
  private boolean handshaken;

  private ClientRuntime(Path configDir) {
    this.configDir = configDir;
    this.catalog = MessageCatalog.withOverrides(configDir.resolve("worldgit").resolve("lang"));
    try {
      this.config = ClientConfig.load(configDir);
    } catch (IOException e) {
      LOG.error("WorldGit 客戶端設定無效，改用預設值：{}", e.getMessage());
      this.config = ClientConfig.defaults();
    }
  }

  public ClientConfig config() {
    return config;
  }

  public boolean handshaken() {
    return handshaken;
  }

  public DiffPalette palette() {
    return config.resolve(serverPalette);
  }

  // ---- 設定 ----------------------------------------------------------------

  void setConfig(ClientConfig next) {
    config = next;
    try {
      ClientConfig.save(configDir, next);
    } catch (IOException e) {
      LOG.warn("WorldGit 無法寫回客戶端設定：{}", e.getMessage());
    }
    restyle();
  }

  /** 重新讀取設定檔；失敗時保留目前設定並丟出例外。 */
  void reload() throws IOException {
    config = ClientConfig.load(configDir);
    catalog.reload();
    restyle();
  }

  private void restyle() {
    var palette = palette();
    scenes.values().forEach(s -> s.configure(config, palette));
  }

  // ---- 網路 ----------------------------------------------------------------

  public void onHello(byte[] bytes) {
    try {
      if (!(Protocol.decode(bytes) instanceof Protocol.Hello hello)) throw new IOException("不是 hello");
      serverPalette = hello.palette();
      var reply = ClientHandshake.reply(hello, config);
      if (reply.isEmpty()) {
        LOG.warn("WORLDGIT 伺服器 protocol v{} 與本模組 v{} 不相容，不顯示預覽", hello.version(), Protocol.VERSION);
        return;
      }
      ClientPlayNetworking.send(Net.HELLO.of(Protocol.encode(reply.get())));
      handshaken = true;
      LOG.info("WORLDGIT CLIENT_HANDSHAKE_OK nonce={} capabilities={} palette={}", hello.nonce(), Protocol.CAPABILITIES, config.palette());
      restyle();
    } catch (IOException | RuntimeException e) {
      LOG.warn("WORLDGIT hello 被拒絕：{}", e.toString());
    }
  }

  public void onPreview(byte[] bytes) {
    try {
      if (!handshaken) throw new IOException("尚未握手就收到預覽");
      var result = previews.accept(Protocol.decode(bytes), System.currentTimeMillis());
      if (result.clearedUpTo() >= 0) {
        scenes.values().removeIf(s -> {
          boolean gone = s.published().previewId() <= result.clearedUpTo();
          if (gone) s.close();
          return gone;
        });
        LOG.info("WORLDGIT PREVIEW_CLEARED upTo={}", result.clearedUpTo());
      }
      var p = result.published();
      if (p != null) {
        var old = scenes.put(p.dimension(), new PreviewScene(p, config, palette(), ClientPlatform::model));
        if (old != null) old.close();
        LOG.info("WORLDGIT PREVIEW_PUBLISHED dimension={} id={} {}={}", p.dimension(), p.previewId(), p.ghost() ? "cells" : "outlines", p.ghost() ? p.cells().size() : p.outlines().size());
      }
    } catch (IOException | RuntimeException e) {
      LOG.warn("WORLDGIT 預覽封包被拒絕：{}", e.toString());
    }
  }

  void clear() {
    scenes.values().forEach(PreviewScene::close);
    scenes.clear();
    previews.clear();
  }

  void onDisconnect() {
    clear();
    previews.reset();
    handshaken = false;
    serverPalette = null;
  }

  // ---- 繪製 ----------------------------------------------------------------

  private String currentDimension() {
    var level = Minecraft.getInstance().level;
    return level == null ? null : level.dimension().identifier().toString();
  }

  PreviewScene currentScene() {
    String dim = currentDimension();
    if (dim == null) return null;
    try {
      return scenes.get(new DimensionId(dim));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  Map<DimensionId, PreviewScene> scenes() {
    return scenes;
  }

  /** 目前維度預覽的摘要（測試與 /wgc status 用）：沒有預覽時為 null。 */
  public Summary summary() {
    var scene = currentScene();
    return scene == null ? null : new Summary(scene.published().previewId(), scene.sectionCount(), scene.cellCount(), scene.detailCount(), scene.published().ghost());
  }

  /** 已完整接收且正在顯示的格子；提供唯讀診斷，需在 client thread 呼叫。 */
  public List<Protocol.Cell> previewCells() {
    var scene = currentScene();
    return scene == null ? List.of() : scene.published().cells();
  }

  /** previewId、區塊數、格數（或 outline 數）、目前以明細繪製的區塊數、是否為 diff（鬼影）模式。 */
  public record Summary(long previewId, int sections, int cells, int detailSections, boolean ghost) {}

  /** END_MAIN：view 是只含視角旋轉的 modelview，(cx,cy,cz) 是相機世界座標。 */
  void render(Matrix4f view, double cx, double cy, double cz) {
    var scene = currentScene();
    if (scene == null) return;
    var mc = Minecraft.getInstance();
    var rotation = new Matrix4f(view);
    rotation.m30(0).m31(0).m32(0);
    float[] m = new float[16];
    rotation.get(m);
    var window = mc.getWindow();
    double aspect = window.getHeight() == 0 ? 1.8 : (double) window.getWidth() / window.getHeight();
    // 視錐只拿來略過整個區塊：FOV 放寬一些，寧可多畫不可少畫。
    var frustum = Frustum.perspective(m, Math.min(170, mc.options.fov().get() + 20), aspect * 1.15, 0.05, config.maxDistance() + 64.0);
    float pulse = (float) (.25 + .75 * (.5 + .5 * Math.sin(System.nanoTime() / 1e9 * Math.PI))); // 週期 2 秒
    for (var mesh : scene.frame(cx, cy, cz, frustum)) {
      var transform = new Matrix4f(view).translate((float) (mesh.ox() - cx), (float) (mesh.oy() - cy), (float) (mesh.oz() - cz));
      ClientPlatform.draw(mesh.buffer(), mesh.vertices(), mesh.textured(), config.seeThrough(), transform, mesh.pulse() ? pulse : 1f);
    }
  }

  // ---- 訊息 ----------------------------------------------------------------

  String locale() {
    var code = Minecraft.getInstance().options.languageCode;
    return code == null || code.isBlank() ? "en_us" : code;
  }

  Component text(Msg msg) {
    return MinecraftClientAudiences.of().asNative(Mini.render(catalog, locale(), msg, palette()));
  }

  String value(String key) {
    return Mini.plain(catalog, locale(), Msg.of(key));
  }

  String booleanText(boolean enabled) {
    return value(enabled ? MessageKeys.CLIENT_ON : MessageKeys.CLIENT_OFF);
  }

  String paletteText(String name) {
    return value(switch (name) {
      case "default" -> MessageKeys.CLIENT_PALETTE_DEFAULT;
      case "colorblind" -> MessageKeys.CLIENT_PALETTE_COLORBLIND;
      default -> MessageKeys.CLIENT_PALETTE_AUTO;
    });
  }

  void close() {
    clear();
  }
}
