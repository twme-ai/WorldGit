package org.worldgit.fabric.gametest;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;

/** 舊版沒有 Fabric client GameTest API；以真正 client executor／tick 執行同一組控制與斷言。 */
final class LegacyClientContext {
  private static volatile long ticks;
  static void tick() { ticks++; }
  @FunctionalInterface interface FunctionWithError<T,R,E extends Throwable> { R apply(T value) throws E; }
  <T,E extends Throwable> T computeOnClient(FunctionWithError<Minecraft,T,E> action) throws E {
    var future=new CompletableFuture<T>();var client=Minecraft.getInstance();
    client.execute(()->{try {future.complete(action.apply(client));}catch(Throwable error){future.completeExceptionally(error);}});
    try {return future.get(120,TimeUnit.SECONDS);}
    catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
    catch(TimeoutException error){throw new AssertionError("client executor timeout",error);}
    catch(ExecutionException error){if(error.getCause() instanceof RuntimeException runtime)throw runtime;if(error.getCause() instanceof Error fatal)throw fatal;throw new AssertionError(error.getCause());}
  }
  void runOnClient(Consumer<Minecraft> action) { computeOnClient(client->{action.accept(client);return null;}); }
  void waitFor(Predicate<Minecraft> test,int budget) {
    long deadline=System.nanoTime()+budget*50_000_000L;
    while(System.nanoTime()<deadline) {if(computeOnClient(test::test))return;pause();}
    throw new AssertionError("client condition timeout after "+budget+" ticks");
  }
  void waitTicks(int count) {
    long target=ticks+count,deadline=System.nanoTime()+Math.max(120,count/10)*1_000_000_000L;
    while(ticks<target) {if(System.nanoTime()>deadline)throw new AssertionError("client ticks stopped");pause();}
  }
  private void pause() {try {Thread.sleep(10);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
  Path takeScreenshot(String name) {
    Path file=computeOnClient(client->{var path=client.gameDirectory.toPath().resolve("screenshots").resolve(name+".png");
      net.minecraft.client.Screenshot.grab(client.gameDirectory,name+".png",client.getMainRenderTarget(),message->{});return path;});
    waitFor(client->Files.isRegularFile(file),1200);return file;
  }
  Input getInput() { return new Input(); }
  final class Input {
    void setCursorPos(double x,double y) { runOnClient(client->org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().getWindow(),x,y)); }
  }
  WorldBuilder worldBuilder() { return new WorldBuilder(); }
  final class WorldBuilder {
    WorldBuilder adjustSettings(Consumer<Settings> action) {action.accept(new Settings());return this;}
    World create() {
      // 1.20.4 doWorldLoad 等 overlay 消失，但載入期間不畫 overlay；先等原版資源重載畫完。
      waitFor(client->client.getOverlay()==null,3600);
      runOnClient(client->LegacyTestPlatform.loadWorld(client,"WorldGitPhase5"));
      waitFor(client->client.getSingleplayerServer()!=null && client.player!=null && client.level!=null,3600);
      return new World();
    }
  }
  static final class Settings {void setAllowCommands(boolean value){if(value)throw new AssertionError("fixture must have cheats disabled");}}
  final class World {
    Server getServer() {return new Server();}
    void close() {runOnClient(client->LegacyTestPlatform.disconnect(client));}
  }
  final class Server {
    void runCommand(String command) {
      var done=computeOnClient(client->client.getSingleplayerServer().submit(()->client.getSingleplayerServer().getCommands()
          .performPrefixedCommand(client.getSingleplayerServer().createCommandSourceStack(),command)));
      done.join();waitTicks(5);
    }
  }
}
