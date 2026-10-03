package org.worldgit.paper;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.DimensionId;
import org.worldgit.protocol.DiffPalette;
import org.worldgit.protocol.Protocol;
import org.worldgit.protocol.MergeProtocol;
import org.worldgit.core.merge.MergeReport;

/**
 * 與玩家端 Fabric 模組的連線（docs/08 §6）：握手、傳送 status 描邊與 diff 鬼影。
 *
 * <p>握手沿用 Phase 0 的結論：玩家剛加入時 channel 可能尚未註冊，所以在第 0/20/60/100 tick 重送 hello，
 * 約 6 秒（120 ticks）沒回覆就判定沒有模組；之後仍接受遲到的回覆。回覆必須是同一個 nonce、同一個協定版本，
 * 並且宣告至少 {@value #REQUIRED_CAPABILITY} 能力，否則不送任何預覽。
 */
final class FabricLink implements PluginMessageListener, Listener {
  static final String REQUIRED_CAPABILITY = "outline";
  static final long[] RETRY_TICKS = {1, 20, 60, 100};
  static final int PACKETS_PER_TICK = 2;

  private record Session(long nonce, boolean ready, Set<String> capabilities) {}

  private final WorldGitPlugin plugin;
  private final SecureRandom random = new SecureRandom();
  private final AtomicLong previews = new AtomicLong(System.currentTimeMillis());
  private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, Long> active = new ConcurrentHashMap<>();
  private final ConcurrentMap<String,Long> mergeActive=new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID,String> mergeLists=new ConcurrentHashMap<>();
  private volatile DiffPalette palette = DiffPalette.DEFAULT;

  FabricLink(WorldGitPlugin plugin) {
    this.plugin = plugin;
  }

  void register() {
    var messenger = plugin.getServer().getMessenger();
    for (String channel : List.of(Protocol.HELLO, Protocol.DIFF, Protocol.STATUS, Protocol.CLEAR, MergeProtocol.REGIONS, MergeProtocol.PREVIEW))
      messenger.registerOutgoingPluginChannel(plugin, channel);
    messenger.registerIncomingPluginChannel(plugin, Protocol.HELLO, this);
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    // 已在線的玩家（/reload 或延後載入）也要握手。
    for (Player p : plugin.getServer().getOnlinePlayers()) begin(p);
  }

  void palette(DiffPalette palette) {
    this.palette = palette;
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent e) {
    begin(e.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    sessions.remove(e.getPlayer().getUniqueId());
    active.remove(e.getPlayer().getUniqueId());
    mergeLists.remove(e.getPlayer().getUniqueId());
    mergeActive.keySet().removeIf(k->k.startsWith(e.getPlayer().getUniqueId().toString()));
  }

  private void begin(Player player) {
    long nonce = random.nextLong() & Long.MAX_VALUE;
    UUID id = player.getUniqueId();
    sessions.put(id, new Session(nonce, false, Set.of()));
    for (long delay : RETRY_TICKS)
      plugin.platform().entityDelayed(player, delay, () -> {
        var s = sessions.get(id);
        if (s == null || s.ready || !player.isOnline()) return;
        try {
          player.sendPluginMessage(plugin, Protocol.HELLO, Protocol.encode(new Protocol.Hello(Protocol.VERSION, s.nonce, java.util.stream.Stream.concat(Protocol.CAPABILITIES.stream(),java.util.stream.Stream.of("revision-preview",MergeProtocol.CAPABILITY,MergeProtocol.SELECT_CAPABILITY)).distinct().toList(), palette)));
        } catch (IOException ex) {
          plugin.getLogger().log(Level.WARNING, "無法編碼 hello", ex);
        }
      }, () -> {});
  }

  @Override
  public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
    if (!Protocol.HELLO.equals(channel)) return;
    try {
      if (!(Protocol.decode(bytes) instanceof Protocol.Hello hello)) return;
      Session s = sessions.get(player.getUniqueId());
      if (s == null || hello.nonce() != s.nonce) return;
      if (hello.version() != Protocol.VERSION) {
        plugin.getLogger().warning("玩家 " + player.getName() + " 的 WorldGit 模組協定 v" + hello.version() + " 與伺服器 v" + Protocol.VERSION + " 不相容");
        return;
      }
      if (!hello.capabilities().contains(REQUIRED_CAPABILITY)) {
        plugin.getLogger().warning("玩家 " + player.getName() + " 的模組缺少必要能力 " + REQUIRED_CAPABILITY);
        return;
      }
      sessions.put(player.getUniqueId(), new Session(s.nonce, true, Set.copyOf(hello.capabilities())));
      plugin.getLogger().info("WorldGit 模組握手成功：" + player.getName() + " 協定 v" + hello.version() + " 能力 " + hello.capabilities());
    } catch (IOException | RuntimeException e) {
      plugin.getLogger().warning("玩家 " + player.getName() + " 傳來無效的 hello：" + e.getMessage());
    }
  }

  boolean ready(Player player) {
    var s = sessions.get(player.getUniqueId());
    return s != null && s.ready;
  }

  boolean supports(Player player, String capability) {
    var s = sessions.get(player.getUniqueId());
    return s != null && s.ready && s.capabilities.contains(capability);
  }

  /** 傳 status 描邊（section 包圍盒＋計數）。回傳封包數；沒有變動時改送 clear。 */
  int sendStatus(Player player, DimensionId dimension, WorldDiff diff) throws IOException {
    long id = previews.incrementAndGet();
    int minY = player.getWorld().getMinHeight(), maxY = player.getWorld().getMaxHeight() - 1;
    List<byte[]> packets = Protocol.status(id, diff, minY, maxY);
    send(player, Protocol.STATUS, packets, id);
    return packets.size();
  }

  /** 傳方塊級 diff（鬼影）。超過 {@link Protocol#MAX_ENTRIES} 格呼叫端應改傳 status。 */
  int sendDiff(Player player, WorldDiff blocksDiff) throws IOException {
    long id = previews.incrementAndGet();
    List<byte[]> packets = Protocol.diff(id, blocksDiff);
    send(player, Protocol.DIFF, packets, id);
    return packets.size();
  }

  void clear(Player player) {
    active.remove(player.getUniqueId());
    mergeLists.remove(player.getUniqueId());
    mergeActive.keySet().removeIf(k->k.startsWith(player.getUniqueId().toString()));
    long id=previews.incrementAndGet();
    if (!ready(player)) return;
    try {
      byte[] bytes = Protocol.encode(new Protocol.Clear(id));
      plugin.platform().entity(player, () -> player.sendPluginMessage(plugin, Protocol.CLEAR, bytes), () -> {});
    } catch (IOException e) {
      plugin.getLogger().log(Level.WARNING, "無法編碼 clear", e);
    }
  }

  void sendRegions(Player player,DimensionId dimension,List<MergeReport.Region> regions,String token) {
    if(!supports(player,MergeProtocol.CAPABILITY)) return;
    if(token.equals(mergeLists.put(player.getUniqueId(),token))) return;
    try { long id=previews.incrementAndGet(); sendMerge(player,MergeProtocol.REGIONS,MergeProtocol.regions(id,dimension,regions),id,dimension); }
    catch(IOException|RuntimeException e) { mergeLists.remove(player.getUniqueId()); plugin.getLogger().warning("傳送衝突區域失敗："+e.getMessage()); }
  }
  void sendConflictPreview(Player player,DimensionId dimension,int region,MergeReport.Choice choice,List<MergeReport.PreviewBlock> cells) throws IOException {
    if(!supports(player,MergeProtocol.CAPABILITY)) return;
    long id=previews.incrementAndGet();
    sendMerge(player,MergeProtocol.PREVIEW,MergeProtocol.preview(id,dimension,region,choice,cells.stream().map(c->new MergeProtocol.PreviewCell(c.position(),c.state().canonical(),c.blockEntity())).toList()),id,dimension);
  }
  private void sendMerge(Player player,String channel,List<byte[]> packets,long id,DimensionId dimension) {
    String key=player.getUniqueId()+":"+channel; mergeActive.put(key,id); sendMergeFrom(player,channel,packets,0,id,key,dimension);
  }
  private void sendMergeFrom(Player player,String channel,List<byte[]> packets,int start,long id,String key,DimensionId dimension) {
    plugin.platform().entity(player,()->{
      if(!player.isOnline() || !Objects.equals(mergeActive.get(key),id) || !supports(player,MergeProtocol.CAPABILITY)
          || !player.hasPermission("worldgit.command.conflicts") || !plugin.dimensionOf(player.getWorld()).filter(dimension::equals).isPresent()) return;
      int end=Math.min(packets.size(),start+PACKETS_PER_TICK);
      for(int i=start;i<end;i++) player.sendPluginMessage(plugin,channel,packets.get(i));
      if(end<packets.size()) plugin.platform().entityDelayed(player,1,()->sendMergeFrom(player,channel,packets,end,id,key,dimension),()->{});
    },()->{});
  }

  /** 每個 tick 送 {@value #PACKETS_PER_TICK} 包，避免單 tick 湧出大量 plugin message。 */
  private void send(Player player, String channel, List<byte[]> packets, long previewId) {
    active.put(player.getUniqueId(), previewId);
    sendFrom(player, channel, packets, 0, previewId);
  }

  private void sendFrom(Player player, String channel, List<byte[]> packets, int start, long previewId) {
    plugin.platform().entity(player, () -> {
      if (!player.isOnline() || !Objects.equals(active.get(player.getUniqueId()), previewId)) return;
      int end = Math.min(packets.size(), start + PACKETS_PER_TICK);
      for (int i = start; i < end; i++) player.sendPluginMessage(plugin, channel, packets.get(i));
      if (end < packets.size()) plugin.platform().entityDelayed(player, 1, () -> sendFrom(player, channel, packets, end, previewId), () -> {});
    }, () -> {});
  }
}
