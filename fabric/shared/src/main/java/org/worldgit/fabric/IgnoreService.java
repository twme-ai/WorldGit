package org.worldgit.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.config.EntityTagRegistry;
import org.worldgit.core.config.IgnoreEditor;
import org.worldgit.core.merge.MergeState;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.fabric.logic.MessageKeys;
import org.worldgit.fabric.logic.Msg;

/**
 * .wgignore 編輯：指令、聊天與客戶端畫面共用同一個入口。修改一律先對 HEAD 預覽，120 秒內以綁定
 * 執行者、維度、原規則與 HEAD 的代碼確認；確認時重新驗證權限（呼叫端）、MERGING、規則與 HEAD 是否過期。
 * 所有方法在 repo 執行緒執行（目標準星在伺服器執行緒另行擷取）。
 */
final class IgnoreService {
  static final long TTL_MILLIS = 120_000;

  /** 可翻譯的拒絕。 */
  static final class Refused extends IOException {
    final transient Msg msg;
    Refused(Msg msg) { super(msg.key()); this.msg = msg; }
  }

  record View(IgnoreEditor.Document document, String token, boolean merging) {}

  record Proposal(String code, String change, IgnoreEditor.Preview preview, int expiresSeconds) {}

  record Test(IgnoreEditor.TestResult result, String selector) {}

  /** 準星目標：selector 文字，或實際實體 NBT（含 tag registry 語意）。 */
  record Aim(String selector, Nbt.Compound entity) {}

  private record Pending(String code, DimensionId dimension, String before, String head, IgnoreEditor.Document proposed, String change, long expires) {}

  private final ServerRuntime rt;
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  IgnoreService(ServerRuntime rt) {
    this.rt = rt;
  }

  static String owner(ServerPlayer player) { return player == null ? "console" : player.getUUID().toString(); }

  static String token(String text) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  void forget(String owner) { pending.remove(owner); }

  private DimensionRepository open(DimensionId dimension) throws IOException {
    var path = new WorldRepositories(WorldLayout.discover(rt.worldRoot())).tracked().get(dimension);
    if (path == null) throw new IOException("維度尚未 init：" + dimension);
    return new DimensionRepository(path, dimension, false);
  }

  private static boolean merging(DimensionRepository repo) throws IOException {
    return MergeState.read(repo.directory().resolve("merge-state.bin")) != null
        || java.nio.file.Files.exists(repo.directory().resolve("MERGE_HEAD"));
  }

  private EntityTagRegistry semantics() throws IOException {
    var layout = WorldLayout.discover(rt.worldRoot());
    return EntityTagRegistry.load(layout.world(), layout.dataVersion(), ModPacks.INSTANCE);
  }

  View read(DimensionId dimension) throws IOException {
    try (var repo = open(dimension)) {
      var document = IgnoreEditor.read(repo.ignorePath());
      return new View(document, token(document.text()), merging(repo));
    }
  }

  /** 解析修改請求並預覽；回傳確認代碼。MERGING 禁止。 */
  Proposal propose(String owner, DimensionId dimension, String mode, int line, int destination, String text, String expectedToken) throws IOException {
    try (var repo = open(dimension)) {
      var document = IgnoreEditor.read(repo.ignorePath());
      if (expectedToken != null && !expectedToken.isBlank() && !expectedToken.equals(token(document.text()))) throw new Refused(Msg.prefixed(MessageKeys.IGNORE_STALE));
      if (!mode.equals("preview") && merging(repo)) throw new Refused(Msg.prefixed(MessageKeys.IGNORE_MERGING));
      IgnoreEditor.Document proposed;
      String change;
      try {
        proposed = switch (mode) {
          case "add" -> { change = "add " + text; yield document.add(text); }
          case "remove" -> { change = "remove " + line + ": " + lineText(document, line); yield document.remove(line); }
          case "move" -> { change = "move " + line + " -> " + destination + ": " + lineText(document, line); yield document.move(line, destination); }
          case "enable" -> { change = "enable " + line + ": " + lineText(document, line); yield document.enabled(line, true); }
          case "disable" -> { change = "disable " + line + ": " + lineText(document, line); yield document.enabled(line, false); }
          case "preview" -> { change = "preview"; yield document; }
          default -> throw new Refused(Msg.prefixed(MessageKeys.IGNORE_USAGE));
        };
      } catch (IOException invalid) {
        if (invalid instanceof Refused) throw invalid;
        throw new Refused(Msg.prefixed(MessageKeys.IGNORE_INVALID, "message", String.valueOf(invalid.getMessage())));
      }
      var preview = IgnoreEditor.preview(repo, proposed, semantics());
      String code = UUID.randomUUID().toString().substring(0, 8);
      if (!mode.equals("preview"))
        pending.put(owner, new Pending(code, dimension, document.text(), repo.refs().head(), proposed, change, System.currentTimeMillis() + TTL_MILLIS));
      return new Proposal(code, change, preview, (int) (TTL_MILLIS / 1000));
    }
  }

  private static String lineText(IgnoreEditor.Document document, int number) {
    return number >= 1 && number <= document.lines().size() ? document.lines().get(number - 1) : "";
  }

  /** 確認寫入。呼叫端已重新驗證權限；這裡驗證代碼、維度、期限、MERGING、規則文字與 HEAD。 */
  void confirm(String owner, DimensionId dimension, String code) throws IOException {
    var proposal = pending.get(owner);
    if (proposal == null) throw new Refused(Msg.prefixed(MessageKeys.IGNORE_NO_PENDING));
    try (var repo = open(dimension)) {
      var document = IgnoreEditor.read(repo.ignorePath());
      if (!proposal.code().equals(code) || proposal.expires() < System.currentTimeMillis() || !proposal.dimension().equals(dimension)
          || !proposal.before().equals(document.text()) || !Objects.equals(proposal.head(), repo.refs().head()))
        throw new Refused(Msg.prefixed(MessageKeys.IGNORE_STALE));
      if (merging(repo)) throw new Refused(Msg.prefixed(MessageKeys.IGNORE_MERGING));
      IgnoreEditor.write(repo, proposal.proposed());
      pending.remove(owner, proposal);
    }
  }

  boolean cancel(String owner) { return pending.remove(owner) != null; }

  Test test(DimensionId dimension, String selector, Nbt.Compound entity) throws IOException {
    try (var repo = open(dimension)) {
      var document = IgnoreEditor.read(repo.ignorePath());
      var registry = semantics();
      var result = entity != null ? IgnoreEditor.testEntity(document, entity, registry) : IgnoreEditor.test(document, selector, registry);
      return new Test(result, selector);
    }
  }

  /** 伺服器執行緒：玩家準星的方塊或實體（6 格內）。 */
  static Aim aim(ServerPlayer player) throws IOException {
    var level = (ServerLevel) player.level();
    var eye = player.getEyePosition();
    var end = eye.add(player.getLookAngle().scale(6));
    var block = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    double blockDistance = block.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : eye.distanceToSqr(block.getLocation());
    Entity best = null;
    double bestDistance = Double.MAX_VALUE;
    for (Entity candidate : level.getEntities(player, new AABB(eye, end).inflate(1.0), e -> !e.isSpectator() && e.isPickable())) {
      var hit = candidate.getBoundingBox().inflate(candidate.getPickRadius()).clip(eye, end);
      if (hit.isEmpty()) continue;
      double distance = eye.distanceToSqr(hit.get());
      if (distance < bestDistance) { best = candidate; bestDistance = distance; }
    }
    if (best != null && bestDistance <= blockDistance) {
      var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
      if (!best.save(out)) throw new IOException("實體不可儲存，無法測試");
      var entity = ChunkCapture.toCore(out.buildResult());
      return new Aim("entity " + entity.string("id") + " " + best.getX() + "," + best.getY() + "," + best.getZ(), entity);
    }
    if (block.getType() == HitResult.Type.MISS) return null;
    var pos = block.getBlockPos();
    return new Aim("block " + pos.getX() + "," + pos.getY() + "," + pos.getZ(), null);
  }
}
