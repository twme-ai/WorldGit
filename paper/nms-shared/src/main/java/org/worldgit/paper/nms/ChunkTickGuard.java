package org.worldgit.paper.nms;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.worldgit.core.anvil.Nbt;

/** Ordinary NMS data access on the owner thread. No tick-rate changes or class rewriting. */
public final class ChunkTickGuard {
  private record Key(ServerLevel level, long chunk) {}
  private static final Map<Key, Hold> HOLDS = new ConcurrentHashMap<>();
  private static final Map<ServerLevel,Set<org.worldgit.core.model.ChunkPos>> BOUNDARIES=new ConcurrentHashMap<>();
  private static final Map<Entity,BoundaryCallback> CALLBACKS=new ConcurrentHashMap<>();
  private static final Field LEVEL_CALLBACK=field(Entity.class,"levelCallback");
  public static void entityBoundary(ServerLevel level,Set<org.worldgit.core.model.ChunkPos> scope) {BOUNDARIES.put(level,Set.copyOf(scope));}
  public static void guardEntity(org.bukkit.entity.Entity entity) {
    var nativeEntity=((org.bukkit.craftbukkit.entity.CraftEntity)entity).getHandle();
    if(!(nativeEntity.level() instanceof ServerLevel level) || !BOUNDARIES.containsKey(level))return;
    try {
      var callback=(net.minecraft.world.level.entity.EntityInLevelCallback)LEVEL_CALLBACK.get(nativeEntity);
      if(callback instanceof BoundaryCallback)return;
      var wrapper=new BoundaryCallback(level,nativeEntity,callback);
      CALLBACKS.put(nativeEntity,wrapper);nativeEntity.setLevelCallback(wrapper);
    }catch(IllegalAccessException error){throw new IllegalStateException(error);}
  }
  public static void guardEntities(ServerLevel level,int x,int z) {
    if(!BOUNDARIES.containsKey(level))return;
    var box=new net.minecraft.world.phys.AABB(x*16,level.getMinY()-1,z*16,x*16+16,level.getMaxY()+2,z*16+16);
    for(var entity:level.getEntities((Entity)null,box,e->e.chunkPosition().getMinBlockX()>>4==x && e.chunkPosition().getMinBlockZ()>>4==z))guardEntity(entity.getBukkitEntity());
  }
  public static Collection<org.bukkit.entity.Entity> clearEntityBoundary(ServerLevel level) {
    BOUNDARIES.remove(level);
    return CALLBACKS.values().stream().filter(c->c.level==level).map(c->(org.bukkit.entity.Entity)c.entity.getBukkitEntity()).toList();
  }
  public static void restoreEntity(org.bukkit.entity.Entity entity) {
    var nativeEntity=((org.bukkit.craftbukkit.entity.CraftEntity)entity).getHandle();var wrapper=CALLBACKS.remove(nativeEntity);
    if(wrapper==null)return;
    try {if(LEVEL_CALLBACK.get(nativeEntity)==wrapper)nativeEntity.setLevelCallback(wrapper.delegate);}
    catch(IllegalAccessException error){throw new IllegalStateException(error);}
  }
  /** Decorate the existing NMS movement callback, before EntityLookup moves its indexes.
   * This is an ordinary data object, installed and restored on each entity's owner. */
  private static final class BoundaryCallback implements net.minecraft.world.level.entity.EntityInLevelCallback {
    final ServerLevel level;final Entity entity;final net.minecraft.world.level.entity.EntityInLevelCallback delegate;
    net.minecraft.world.phys.Vec3 last;boolean restoring;
    BoundaryCallback(ServerLevel level,Entity entity,net.minecraft.world.level.entity.EntityInLevelCallback delegate) {this.level=level;this.entity=entity;this.delegate=delegate;last=entity.position();}
    private boolean blocked(Set<org.worldgit.core.model.ChunkPos> scope,net.minecraft.world.phys.Vec3 pos) {
      return scope.contains(new org.worldgit.core.model.ChunkPos(net.minecraft.util.Mth.floor(pos.x)>>4,net.minecraft.util.Mth.floor(pos.z)>>4));
    }
    public void onMove() {
      if(restoring)return;
      var scope=BOUNDARIES.get(level);
      if(entity.level()==level && scope!=null && (blocked(scope,last) || blocked(scope,entity.position()))) {
        restoring=true;try {entity.setPos(last.x,last.y,last.z);}finally {restoring=false;}return;
      }
      last=entity.position();delegate.onMove();
    }
    public void onRemove(Entity.RemovalReason reason) {CALLBACKS.remove(entity,this);delegate.onRemove(reason);}
  }
  private static final net.minecraft.server.level.TicketType<?> PIN = new net.minecraft.server.level.TicketType<>(0, net.minecraft.server.level.TicketType.FLAG_LOADING);
  private static Field field(Class<?> type, String name) {
    for(var c=type;c!=null;c=c.getSuperclass()) try {
      var f=c.getDeclaredField(name); f.setAccessible(true); return f;
    } catch(NoSuchFieldException ignored) {}
    throw new IllegalStateException("Unsupported NMS field: "+type.getName()+"."+name);
  }
  private static Object get(Object object,String name) {
    try {return field(object.getClass(),name).get(object);}
    catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
  }
  private static Object call(Object object,String name,Class<?>[] types,Object... args) {
    try {return object.getClass().getMethod(name,types).invoke(object,args);}
    catch(ReflectiveOperationException e) {throw new IllegalStateException("Unsupported NMS owner method: "+name,e);}
  }
  private static Object owner(ServerLevel level) {
    try {return level.getClass().getMethod("getCurrentWorldData").invoke(level);}
    catch(NoSuchMethodException paper) {return level;}
    catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
  }
  @SuppressWarnings("unchecked")
  private static ca.spottedleaf.moonrise.common.list.ReferenceList<LevelChunk> chunks(ServerLevel level,boolean entities) {
    var data=owner(level);
    return data==level ? (entities ? level.moonrise$getEntityTickingChunks() : level.moonrise$getTickingChunks())
        : (ca.spottedleaf.moonrise.common.list.ReferenceList<LevelChunk>)call(data,entities ? "getEntityTickingChunks" : "getTickingChunks",new Class<?>[]{});
  }
  public static long scheduleTime(ServerLevel level) {return clock(level);}
  private static long clock(ServerLevel level) {
    try {return ((Number)level.getClass().getMethod("getRedstoneGameTime").invoke(level)).longValue();}
    catch(NoSuchMethodException paper) {return level.getGameTime();}
    catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
  }
  private static long key(int x,int z) {return Integer.toUnsignedLong(x)|((long)z<<32);}
  public static boolean atomicLightingSafe(ServerLevel level,org.worldgit.core.apply.ApplyPlan plan,java.util.function.ToIntFunction<net.minecraft.world.level.block.state.BlockState> dampening) throws java.io.IOException {
    var states=new HashMap<org.worldgit.core.model.BlockState,net.minecraft.world.level.block.state.BlockState>();
    for(var pos:org.worldgit.core.apply.LiveApplyVerification.affected(plan)) {
      if(!level.getWorld().isChunkLoaded(pos.x(),pos.z()) || !level.getWorld().getChunkAt(pos.x(),pos.z()).isEntitiesLoaded())return false;
      var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());if(chunk==null || chunk.getBlockEntities().size()>64)return false;
      var box=new net.minecraft.world.phys.AABB(pos.x()*16,level.getMinY()-1,pos.z()*16,pos.x()*16+16,level.getMaxY()+2,pos.z()*16+16);
      if(level.getEntities((Entity)null,box,e->!(e instanceof Player)).size()>32)return false;
    }
    for(var op:plan.chunks().values()) {
      var chunk=level.getChunkSource().getChunkNow(op.pos().x(),op.pos().z());if(chunk==null)return false;
      for(var patch:op.sections().values()) {
        var old=chunk.getSection(chunk.getSectionIndexFromSectionY(patch.y()));
        for(int i=0;i<4096;i++)if(patch.covers(i)) {
          var before=old.getBlockState(i&15,i>>8,(i>>4)&15);
          var after=states.computeIfAbsent(patch.section().block(i),b->((org.bukkit.craftbukkit.block.data.CraftBlockData)org.bukkit.Bukkit.createBlockData(b.canonical())).getState());
          if(before!=after && (dampening.applyAsInt(before)!=dampening.applyAsInt(after) || before.getLightEmission()!=after.getLightEmission() || before.useShapeForLightOcclusion() || after.useShapeForLightOcclusion()))return false;
        }
      }
    }
    return true;
  }
  public static AutoCloseable lock(ServerLevel level,int x,int z) {
    var chunk=level.getChunkSource().getChunkNow(x,z);
    if(chunk==null) throw new IllegalStateException("Chunk must be loaded on owner before locking");
    var id=new Key(level,key(x,z)); var hold=new Hold(level,chunk);
    var existing=HOLDS.putIfAbsent(id,hold);
    if(existing!=null) {existing.users++;return ()->{if(--existing.users==0)existing.close();};}
    try {hold.suspend();}
    catch(Throwable error) {try {hold.close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}throw error;}
    return ()->{if(--hold.users==0)hold.close();};
  }
  public static void refresh(ServerLevel level,int x,int z) {
    var hold=HOLDS.get(new Key(level,key(x,z))); if(hold!=null) hold.suspend();
  }
  public static void beforeSave(ServerLevel level,int x,int z) {var hold=HOLDS.get(new Key(level,key(x,z)));if(hold!=null)hold.rebase();}
  public static void replacedTicks(ServerLevel level,int x,int z) {
    var hold=HOLDS.get(new Key(level,key(x,z))); if(hold!=null) hold.since=clock(level);
  }
  public static long elapsed(ServerLevel level,int x,int z) {
    var hold=HOLDS.get(new Key(level,key(x,z)));return hold==null ? 0 : clock(level)-hold.since;
  }
  public static void correctCapturedTicks(Nbt.Compound raw,long elapsed) {
    for(var key:List.of("block_ticks","fluid_ticks")) for(var value:raw.list(key).values()) {
      var tick=(Nbt.Compound)value;
      tick.put("t",Math.toIntExact(tick.integer("t",0)+elapsed));
    }
  }
  @SuppressWarnings("unchecked")
  private static List<TickingBlockEntity> tickers(Object owner,String name) {
    return (List<TickingBlockEntity>)get(owner,name);
  }
  private static final class Hold implements AutoCloseable {
    final ServerLevel level; final LevelChunk chunk;
    final Set<Entity> entities=Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<TickingBlockEntity> blockEntities=Collections.newSetFromMap(new IdentityHashMap<>());
    final List<net.minecraft.world.level.BlockEventData> events=new ArrayList<>();
    boolean wasTicking,wasEntityTicking,closed,pinned;
    boolean entityStatusHeld;
    int users=1;
    long since;
    Hold(ServerLevel level,LevelChunk chunk) {this.level=level;this.chunk=chunk;since=clock(level);}
    void suspend() {
      if(!pinned) {level.getChunkSource().addTicketAtLevel(PIN,chunk.getPos(),33);pinned=true;}
      // Detached containers also stay detached when Folia splits or merges regions.
      level.getBlockTicks().removeContainer(chunk.getPos());level.getFluidTicks().removeContainer(chunk.getPos());
      var owner=owner(level);
      // EntityLookup applies this visibility to entities arriving in the boundary buffer too.
      var lookup=level.moonrise$getEntityLookup();var slices=lookup.getChunk(chunk.getPos().getMinBlockX()>>4,chunk.getPos().getMinBlockZ()>>4);
      if(slices!=null && slices.status==net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING) {
        lookup.chunkStatusChange(chunk.getPos().getMinBlockX()>>4,chunk.getPos().getMinBlockZ()>>4,net.minecraft.server.level.FullChunkStatus.FULL);entityStatusHeld=true;
      }
      var chunks=chunks(level,false); var entityChunks=chunks(level,true);
      wasTicking|=chunks.remove(chunk);wasEntityTicking|=entityChunks.remove(chunk);
      // Moonrise's player tick list can also enumerate a chunk: remove its random candidates.
      for(var section:chunk.getSections()) {
        if(!section.isRandomlyTicking())continue;
        section.moonrise$getTickingBlockList().clear();
        try {field(section.getClass(),"tickingBlockCount").setShort(section,(short)0);field(section.getClass(),"tickingFluidCount").setShort(section,(short)0);}
        catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
      }
      for(String list:List.of("blockEntityTickers","pendingBlockEntityTickers"))
        tickers(owner,list).removeIf(ticker->{if(inChunk(ticker.getPos())) {blockEntities.add(ticker);return true;}return false;});
      var box=new net.minecraft.world.phys.AABB(chunk.getPos().getMinBlockX(),level.getMinY()-1,chunk.getPos().getMinBlockZ(),chunk.getPos().getMinBlockX()+16,level.getMaxY()+2,chunk.getPos().getMinBlockZ()+16);
      for(var entity:level.getEntities((Entity)null,box,e->true)) {
        if(entity instanceof Player || entity.chunkPosition().getMinBlockX()!=chunk.getPos().getMinBlockX() || entity.chunkPosition().getMinBlockZ()!=chunk.getPos().getMinBlockZ()) continue;
        boolean ticking;
        if(owner==level) {
          var list=get(level,"entityTickList");ticking=(boolean)call(list,"contains",new Class<?>[]{Entity.class},entity);
          if(ticking) call(list,"remove",new Class<?>[]{Entity.class},entity);
        } else {
          ticking=(boolean)call(owner,"hasEntityTickingEntity",new Class<?>[]{Entity.class},entity);
          if(ticking) call(owner,"removeEntityTickingEntity",new Class<?>[]{Entity.class},entity);
        }
        if(ticking) entities.add(entity);
      }
      @SuppressWarnings("unchecked") var queue=(Collection<net.minecraft.world.level.BlockEventData>)get(owner,"blockEvents");
      queue.removeIf(event->{if(inChunk(event.pos())) {events.add(event);return true;}return false;});
    }
    boolean inChunk(net.minecraft.core.BlockPos pos) {return pos.getX()>>4==(chunk.getPos().getMinBlockX()>>4) && pos.getZ()>>4==(chunk.getPos().getMinBlockZ()>>4);}
    private <T> void shift(LevelTicks<T> levelTicks,LevelChunkTicks<T> chunkTicks,long elapsed) {
      var saved=chunkTicks.getAll().toList();
      chunkTicks.removeIf(t->true);
      for(var tick:saved) chunkTicks.schedule(new ScheduledTick<>(tick.type(),tick.pos(),tick.triggerTick()+elapsed,tick.priority(),tick.subTickOrder()));
      // Rebind nextTickForContainer even when a now-empty container had a stale head.
      levelTicks.removeContainer(chunk.getPos());levelTicks.addContainer(chunk.getPos(),chunkTicks);
    }
    void rebase() {
      long elapsed=clock(level)-since;
      shift(level.getBlockTicks(),(LevelChunkTicks<net.minecraft.world.level.block.Block>)chunk.getBlockTicks(),elapsed);
      shift(level.getFluidTicks(),(LevelChunkTicks<net.minecraft.world.level.material.Fluid>)chunk.getFluidTicks(),elapsed);
      level.getBlockTicks().removeContainer(chunk.getPos());level.getFluidTicks().removeContainer(chunk.getPos());
      since=clock(level);chunk.markUnsaved();
    }
    public void close() {
      if(closed)return;
      var owner=owner(level);long elapsed=clock(level)-since;
      shift(level.getBlockTicks(),(LevelChunkTicks<net.minecraft.world.level.block.Block>)chunk.getBlockTicks(),elapsed);shift(level.getFluidTicks(),(LevelChunkTicks<net.minecraft.world.level.material.Fluid>)chunk.getFluidTicks(),elapsed);
      for(var section:chunk.getSections()) section.recalcBlockCounts();
      for(var ticker:blockEntities) if(!ticker.isRemoved()) tickers(owner,"blockEntityTickers").add(ticker);
      for(var entity:entities) if(!entity.isRemoved()) {
        if(owner==level) call(get(level,"entityTickList"),"add",new Class<?>[]{Entity.class},entity);
        else call(owner,"addEntityTickingEntity",new Class<?>[]{Entity.class},entity);
      }
      @SuppressWarnings("unchecked") var queue=(Collection<net.minecraft.world.level.BlockEventData>)get(owner,"blockEvents");
      // Old piston events apply only if the target still has their original block.
      for(var event:events) if(level.getBlockState(event.pos()).is(event.block())) queue.add(event);
      if(wasTicking) chunks(level,false).add(chunk);
      if(wasEntityTicking) chunks(level,true).add(chunk);
      if(entityStatusHeld) {
        var holder=level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunk.getPos().getMinBlockX()>>4,chunk.getPos().getMinBlockZ()>>4);
        if(holder!=null)level.moonrise$getEntityLookup().chunkStatusChange(chunk.getPos().getMinBlockX()>>4,chunk.getPos().getMinBlockZ()>>4,holder.getChunkStatus());
      }
      chunk.markUnsaved();
      if(pinned)level.getChunkSource().removeTicketAtLevel(PIN,chunk.getPos(),33);
      HOLDS.remove(new Key(level,key((chunk.getPos().getMinBlockX()>>4),(chunk.getPos().getMinBlockZ()>>4))),this);closed=true;
    }
  }
}
