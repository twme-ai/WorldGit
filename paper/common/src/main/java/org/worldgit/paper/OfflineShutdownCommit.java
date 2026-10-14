package org.worldgit.paper;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.operation.OperationProgress;
import org.worldgit.core.operation.OperationResult;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;

/**
 * Folia 的「關閉前 commit」：onDisable 時 region scheduler 已經不能用，沒有單一擁有執行緒可以安全複製 chunk。
 * 改走離線路徑：在 JVM 關閉鉤子裡等伺服器把世界存檔完成，再用 core 的離線掃描（與 CLI 同一條路徑）commit。
 *
 * <p>流程：onEnable 註冊鉤子執行緒；onDisable 擷取版面、設定與作者歸屬並預載插件 jar 的所有類別（PluginClassLoader 隨後會被關閉，
 * 之後才第一次用到的類別會 NoClassDefFoundError）；鉤子等到 log4j appender 看到 adapter 已核對的世界存檔／I/O 完成訊號（舊兩版為 RegionFileIOThread.close(true) 返回後的「Closing Thread Pool」；
 * Folia 的 session.lock 要到 JVM 結束才釋放，不能當訊號）才掃描與寫 commit，逾時就放棄並寫明原因（不動世界）。結果寫到 plugins/WorldGit/shutdown-commit.log，因為此時 log4j 可能已關閉。
 */
final class OfflineShutdownCommit {
  static final String MESSAGE = "伺服器關閉前自動存檔點";
  static final long WAIT_FOR_DISABLE_SECONDS = 120, WAIT_FOR_LOCK_SECONDS = 180;

  private record Prepared(
      WorldLayout layout,
      Attribution.Drained drained,
      CommitMetadata.Identity committer,
      double tolerance,
      SortedMap<DimensionId, org.worldgit.core.anvil.OfflineSnapshotSource> sources) {}

  private final WorldGitPlugin plugin;
  private final CountDownLatch disabled = new CountDownLatch(1);
  private final Path logFile;
  // Paper redirects System.err through its plugin logger. During JVM shutdown
  // that turns normal lifecycle messages into ERROR and races logger teardown.
  // Keep the durable file and write directly to the process console instead.
  // This stream must never be closed: the descriptor belongs to the JVM.
  private final PrintStream shutdownConsole =
      new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
  private final java.util.concurrent.atomic.AtomicBoolean ioDone = new java.util.concurrent.atomic.AtomicBoolean();
  private volatile List<Prepared> prepared=List.of();
  private volatile Thread hook;
  private String pluginVersion, minecraftVersion, platformVersion;

  OfflineShutdownCommit(WorldGitPlugin plugin) {
    this.plugin = plugin;
    this.logFile = plugin.getDataFolder().toPath().resolve("shutdown-commit.log");
  }

  private static final class Watch extends org.apache.logging.log4j.core.appender.AbstractAppender {
    private final java.util.concurrent.atomic.AtomicBoolean flag;
    private final NmsBridge bridge;
    private final WorldGitPlugin plugin;

    Watch(java.util.concurrent.atomic.AtomicBoolean flag, WorldGitPlugin plugin) {
      super("WorldGitShutdownWatch", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
      this.flag = flag;
      this.plugin = plugin;
      this.bridge = plugin.bridge();
    }

    @Override
    public void append(org.apache.logging.log4j.core.LogEvent event) {
      if(event.getMessage()==null)return;
      String message=event.getMessage().getFormattedMessage();
      if(plugin.platform().isShuttingDown() && bridge.shutdownRegionSave(event.getLoggerName(),message)) {
        if(!Thread.currentThread().getClass().getName().equals("io.papermc.paper.threadedregions.RegionShutdownThread"))
          throw new IllegalStateException("Folia save callback must run synchronously on native shutdown owner");
        for(var world:org.bukkit.Bukkit.getWorlds())bridge.restoreShutdownGuards(world);
      }
      if(bridge.shutdownIoComplete(event.getLoggerName(),message)) {
        if(bridge.pendingShutdownGuards()!=0)
          throw new IllegalStateException("Folia I/O completed with unrestored chunk guards: "+bridge.pendingShutdownGuards());
        flag.set(true);
      }
    }
  }

  void register() {
    try {
      var watch = new Watch(ioDone, plugin);
      watch.start();
      ((org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger()).addAppender(watch);
    } catch (Throwable t) {
      plugin.getLogger().warning("無法監看伺服器關閉進度（log4j appender），Folia 關閉前 commit 停用：" + t);
      return;
    }
    var thread = new Thread(this::run, "WorldGit-Offline-Shutdown-Commit");
    try {
      Runtime.getRuntime().addShutdownHook(thread);
      hook = thread;
    } catch (IllegalStateException e) {
      plugin.getLogger().warning("無法註冊關閉鉤子，Folia 關閉前 commit 停用：" + e.getMessage());
    }
  }

  /** onDisable 呼叫（Folia）：repo 背景執行緒已閒置。 */
  void prepare(boolean enabled) {
    try {
      if (enabled && hook != null) {
        pluginVersion=plugin.getPluginMeta().getVersion();
        minecraftVersion=plugin.bridge().minecraftVersion();
        platformVersion=plugin.getServer().getVersion();
        // 在 PluginClassLoader 關閉前載入 locale 與 Adventure formatter 的資源。
        plain(plugin.operations().completion(new OperationResult(UUID.randomUUID(),"shutdown.commit",OperationResult.Status.NO_OP,null,Map.of(),0,List.of(),null)));
        plain(Messages.text("paper.phase5.copy"));plain(Messages.text("paper.phase5.copy-hover"));plain(Messages.text("paper.phase5.recovery"));
        var all=new ArrayList<Prepared>();
        for(var mapping:plugin.mappings()) {
        var local = org.worldgit.core.config.WorldGitConfig.readLocal(mapping.layout().repositoryRoot().resolve("worldgit.yml"));
        // 離線來源在這裡先建立並載入 entity tag registry：它從 jar 讀資源，PluginClassLoader 關閉後就讀不到了。
        var sources = new TreeMap<DimensionId, org.worldgit.core.anvil.OfflineSnapshotSource>();
        for (var id : new WorldRepositories(mapping.layout()).tracked().keySet()) {
          var source = new org.worldgit.core.anvil.OfflineSnapshotSource(mapping.layout(), mapping.layout().dimensions().get(id));
          source.warnings();
          sources.put(id, source);
        }
        all.add(new Prepared(mapping.layout(), plugin.attribution(mapping).drain(), plugin.serverIdentity(), local.entityTolerance(), sources));
        }
        prepared = List.copyOf(all);
      }
      // Active cancellation and native owner cleanup also outlive disable,
      // even when no automatic shutdown commit was requested.
      if(hook!=null)preloadClasses();
    } catch (IOException | RuntimeException e) {
      plugin.getLogger().warning("準備 Folia 關閉前離線 commit 失敗：" + e);
    } finally {
      disabled.countDown();
    }
  }

  private void preloadClasses() {
    int loaded = 0;
    try {
      var source = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
      var loader = plugin.getClass().getClassLoader();
      try (var jar = new JarFile(source.toFile())) {
        for (var entries = jar.entries(); entries.hasMoreElements(); ) {
          String name = entries.nextElement().getName();
          if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class")) continue;
          try {
            Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, loader);
            loaded++;
          } catch (Throwable ignored) {
            // 其他 Minecraft 版本的轉接層（Java 版本不符）等載入不了的類別，不需要。
          }
        }
      }
    } catch (Exception e) {
      plugin.getLogger().warning("預載插件類別失敗：" + e);
    }
    plugin.getLogger().info("Folia 關閉前離線 commit 已預載 " + loaded + " 個類別，將在世界存檔完成後提交。");
  }

  private void log(String line) {
    String text = Instant.now() + " " + line;
    shutdownConsole.println("[WorldGit] " + line);
    try {
      Files.createDirectories(logFile.getParent());
      Files.writeString(logFile, text + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // 沒有其他可回報的地方
    }
  }

  private void run() {
    try {
      log("JVM 關閉鉤子啟動，等待插件 disable 完成");
      if (!disabled.await(WAIT_FOR_DISABLE_SECONDS, TimeUnit.SECONDS)) return; // 不是正常 disable 流程（例如崩潰），不動世界
      var p = prepared;
      if (p.isEmpty()) {
        log("插件 disable 時沒有準備資料，略過");
        return;
      }
      log("插件 disable 完成，等待世界存檔完成");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_FOR_LOCK_SECONDS);
      while (!ioDone.get()) {
        if (System.nanoTime() > deadline) {
          log("等不到伺服器「所有 RegionFile I/O 完成」的訊號，放棄關閉前 commit（不動世界）");
          return;
        }
        Thread.sleep(250);
      }
      log("看到世界存檔完成訊號，開始離線掃描與 commit");
      for(var world:p)commit(world);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Throwable t) {
      log("關閉前離線 commit 失敗：" + t);
    }
  }

  private static String plain(net.kyori.adventure.text.Component component) {
    return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
  }

  private OperationResult.ErrorReport error(UUID id,DimensionId dimension,Throwable failure) {
    String message=String.valueOf(failure.getMessage());
    if(plugin.remote()!=null)message=plugin.remote().mask(message);
    return OperationResult.ErrorReport.create("PAPER_SHUTDOWN",id,"shutdown.commit",dimension,pluginVersion,minecraftVersion,platformVersion,message,List.of());
  }

  private void commit(Prepared p) {
    var last=new java.util.concurrent.atomic.AtomicLong();
    var finished=new java.util.concurrent.atomic.AtomicBoolean();
    try(var progress=new OperationProgress("shutdown.commit",event->{
      long now=System.currentTimeMillis();
      if(!finished.get() && now-last.get()>=1000) {last.set(now);log(plain(OperationUi.progressText(event)));}
    })) {
      var summary=new TreeMap<String,Object>();var snapshotId=UUID.randomUUID();var rows=new TreeMap<DimensionId,WorldRepositories.Outcome<DimensionRepository.CommitResult>>();
      OperationResult.ErrorReport error=null;
      var status=OperationResult.Status.NO_OP;
      int changed=0,failed=0;
      try {
        var repos=new WorldRepositories(p.layout());var tracked=repos.tracked();var manifest=repos.manifest();
        UUID snapshot=UUID.randomUUID();
        for(var e:tracked.entrySet()) {
          DimensionId dimension=e.getKey();
          try(var repo=new DimensionRepository(e.getValue(),dimension,false);var source=p.sources().get(dimension)) {
            var contributors=p.drained().of(dimension);
            var author=p.drained().primary(dimension).map(Attribution.Contributor::identity).orElse(p.committer());
            var metadata=new CommitMetadata(author,p.committer(),MESSAGE,Instant.now(),p.layout().dataVersion(),dimension,CommitMetadata.Source.PLUGIN,true,snapshot,
                contributors.stream().map(Attribution.Contributor::toContribution).toList());
            var r=repo.commit(source,manifest,metadata,p.tolerance());
            if(r.changed())changed++;
            rows.put(dimension,new WorldRepositories.Outcome<>(r,null));
            log("關閉前離線 commit "+dimension+" → "+(r.changed()?Messages.shortId(r.commit()):"沒有變動"));
          } catch(Exception failure) {
            failed++;error=error(progress.id(),dimension,failure);rows.put(dimension,new WorldRepositories.Outcome<>(null,error.message()));
            log("關閉前離線 commit "+dimension+" 失敗："+error.message());
            if(failure instanceof java.io.InterruptedIOException) {status=OperationResult.Status.CANCELLED;break;}
          }
        }
        if(status!=OperationResult.Status.CANCELLED)status=failed>0?(failed==tracked.size()?OperationResult.Status.FAILED:OperationResult.Status.PARTIAL):changed>0?OperationResult.Status.SUCCESS:OperationResult.Status.NO_OP;
      } catch(Exception failure) {status=OperationResult.Status.FAILED;error=error(progress.id(),null,failure);}
      if(!rows.isEmpty())summary.put("batch",new WorldRepositories.Batch<>(snapshotId,rows));
      var result=progress.result(status,null,summary,List.of(),error);
      finished.set(true);
      log(plain(plugin.operations().completion(result)));
      if(error!=null)log(error.text().replace("\n"," | "));
    }
  }
}
