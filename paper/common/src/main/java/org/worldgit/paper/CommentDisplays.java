package org.worldgit.paper;

import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.worldgit.platform.remote.*;

/** 每玩家獨立的非持久留言；region spawn/remove、player owner visibility/particles。 */
final class CommentDisplays implements Listener {
  static final String TAG="worldgit_comment";
  private static final class Session {
    final UUID world; final List<HubClient.Comment> comments;
    final Set<Entity> entities=ConcurrentHashMap.newKeySet();
    Session(UUID world,List<HubClient.Comment> comments) {this.world=world;this.comments=comments;}
  }
  private final WorldGitPlugin plugin;
  private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
  private volatile boolean stopped;
  private final DisplayRequests requests=new DisplayRequests();
  long request(Player p) {clear(p);return requests.reserve(p.getUniqueId());}
  CommentDisplays(WorldGitPlugin plugin) {
    this.plugin=plugin;plugin.getServer().getPluginManager().registerEvents(this,plugin);
    plugin.platform().asyncRepeating(1,1000,()->{
      if(stopped)return;
      for(var p:plugin.getServer().getOnlinePlayers()) plugin.platform().entity(p,()->particles(p),()->clear(p));
    });
  }
  boolean show(Player p,World world,List<HubClient.Comment> comments,long request) {
    if(stopped || !requests.current(p.getUniqueId(),request) || !p.isOnline() || p.getWorld()!=world)return false;
    var s=new Session(world.getUID(),List.copyOf(comments)); sessions.put(p.getUniqueId(),s);
    for(var c:comments.stream().limit(64).toList()) {
      var pin=c.pin();
      if(!plugin.dimensionOf(world).map(d->d.value().equals(pin.dimension())).orElse(false))continue;
      plugin.platform().region(world,pin.x()>>4,pin.z()>>4,()->{
        if(stopped || sessions.get(p.getUniqueId())!=s || !world.isChunkLoaded(pin.x()>>4,pin.z()>>4))return;
        // 不為不可信遠端座標載入/生成 chunk；每次 show 只顯示已載入視窗。
        var display=world.spawn(new Location(world,pin.x()+.5,pin.y()+1.5,pin.z()+.5),TextDisplay.class,e->{
          e.setVisibleByDefault(false);e.setPersistent(false);e.addScoreboardTag(TAG);
          e.text(Component.text(CommentText.display(c)));e.setBillboard(Display.Billboard.CENTER);
          e.setLineWidth(180);e.setViewRange(.5f);e.setBrightness(new Display.Brightness(15,15));
          e.setInvulnerable(true);e.setGravity(false);
        });
        synchronized(s) { if(stopped || sessions.get(p.getUniqueId())!=s) {display.remove();return;} s.entities.add(display); }
        plugin.platform().entity(p,()->{
          if(!stopped && sessions.get(p.getUniqueId())==s && p.getWorld()==world && permitted(p)) p.showEntity(plugin,display);
        },()->clear(p));
      });
    }
    return true;
  }
  private static boolean permitted(Player p) {return p.hasPermission("worldgit.command.comment") || p.hasPermission("worldgit.admin");}
  private void particles(Player p) {
    var s=sessions.get(p.getUniqueId()); if(s==null)return;
    if(!permitted(p) || !p.getWorld().getUID().equals(s.world)) {clear(p);return;}
    var loc=p.getLocation(); int budget=384;
    for(var c:s.comments.stream().limit(64).toList()) {
      var b=c.pin(); if(!b.range())continue;
      double[] low={b.x(),b.y(),b.z()},high={b.maxX()+1d,b.maxY()+1d,b.maxZ()+1d};
      // 固定每邊 8 點；range 再大也不增加執行成本，距玩家超過 64 格的點省略。
      for(int axis=0;axis<3;axis++) for(int edge=0;edge<4;edge++) for(int i=0;i<=8;i++) {
        double[] xyz=low.clone(); int bit=0;
        for(int j=0;j<3;j++) if(j!=axis) xyz[j]=((edge>>(bit++))&1)==0?low[j]:high[j];
        xyz[axis]=low[axis]+(high[axis]-low[axis])*i/8;
        if(budget==0)return;
        if(loc.distanceSquared(new Location(p.getWorld(),xyz[0],xyz[1],xyz[2]))>4096)continue;
        p.spawnParticle(Particle.DUST,xyz[0],xyz[1],xyz[2],1,0,0,0,0,new Particle.DustOptions(Color.AQUA,1));budget--;
      }
    }
  }
  void clear(Player p) {requests.cancel(p.getUniqueId());var s=sessions.remove(p.getUniqueId());if(s!=null)remove(s);}
  int count(Player p) {var s=sessions.get(p.getUniqueId());return s==null?0:s.entities.size();}
  private void remove(Session s) {
    synchronized(s) {
      for(var e:s.entities) {
        if(Bukkit.isOwnedByCurrentRegion(e)) e.remove();
        else plugin.platform().entity(e,e::remove,()->{});
      }
      s.entities.clear();
    }
  }
  @EventHandler public void quit(PlayerQuitEvent e) {clear(e.getPlayer());}
  @EventHandler public void dimension(PlayerChangedWorldEvent e) {clear(e.getPlayer());}
  void shutdown() {
    stopped=true;
    // Paper 同步清除；Folia 若 owner 已停止則非持久實體隨世界卸載消失。
    for(var s:sessions.values()) synchronized(s) { for(var e:s.entities) if(Bukkit.isOwnedByCurrentRegion(e)) e.remove(); s.entities.clear(); }
    sessions.clear();requests.clear();
  }
}
