package org.worldgit.paper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;

/** 每位玩家在自己的 EntityScheduler 安裝／更新 bossbar 與預覽清理。 */
final class ApplyUi {
  private final WorldGitPlugin plugin;
  private final Map<UUID,BossBar> bars=new ConcurrentHashMap<>();
  volatile ApplyQueue queue;
  volatile int total;
  volatile String target;
  ApplyUi(WorldGitPlugin plugin) { this.plugin=plugin; }
  void start(ApplyQueue queue,String target) { this.queue=queue; this.target=target; total=0; tick(); }
  void tick() {
    var current=queue; if(current==null || !plugin.isEnabled() || plugin.repo().stopping()) return;
    for(Player p:plugin.getServer().getOnlinePlayers()) plugin.platform().entity(p,()->{
      if(queue!=current) return;
      var bar=bars.computeIfAbsent(p.getUniqueId(),k->BossBar.bossBar(Messages.inLocale(p,()->Messages.line("paper.apply.progress","target",target,"done",0,"total",total)),0,BossBar.Color.BLUE,BossBar.Overlay.PROGRESS));
      bar.name(Messages.inLocale(p,()->Messages.line("paper.apply.progress","target",target,"done",current.sections.get(),"total",total)));
      bar.progress(total==0 ? 0 : Math.min(1f,(float)current.sections.get()/total));
      p.showBossBar(bar);
    },()->bars.remove(p.getUniqueId()));
  }
  void stop() {
    queue=null;
    for(Player p:plugin.getServer().getOnlinePlayers()) plugin.platform().entity(p,()->{ var bar=bars.remove(p.getUniqueId()); if(bar!=null) p.hideBossBar(bar); },()->bars.remove(p.getUniqueId()));
  }
  void shutdown() { queue=null; bars.clear(); }
}
