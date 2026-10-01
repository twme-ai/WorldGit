package wg.poc;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftEntitySnapshot;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import wgproto.Codec;
import wgproto.Nbt;
import wgproto.Offline;

import java.nio.file.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 從 03 的 Sect/Nms/Sched 擴充。並行安全的 coordinator 不直接讀寫 live world。 */
public final class Bench implements Listener {
    final World world=Bukkit.getWorlds().getFirst();
    final ServerLevel level=Nms.level(world);
    final boolean folia;
    final List<int[]> coords=Offline.coords(Integer.getInteger("wg.width",24),Integer.getInteger("wg.height",14));
    final ConcurrentHashMap<Long,Pair> snapshots=new ConcurrentHashMap<>();
    final ConcurrentHashMap<Long,List<CompoundTag>> entitiesA=new ConcurrentHashMap<>(),entitiesB=new ConcurrentHashMap<>();
    final Set<UUID> tracked=ConcurrentHashMap.newKeySet();
    final Map<String,net.minecraft.world.level.block.state.BlockState> states=new ConcurrentHashMap<>();
    volatile Job job;
    volatile String head="A";
    volatile long protectUntil;
    volatile boolean stopping;
    final AtomicLong paperTick=new AtomicLong();
    final ConcurrentLinkedQueue<Double> paperTimes=new ConcurrentLinkedQueue<>();
    final Path manifests=PocPlugin.get().getDataFolder().toPath();
    record Pair(Sect.Snap[] a,Sect.Snap[] b,boolean[] changed) {}
    static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    static int cx(long k){return (int)(k>>32);} static int cz(long k){return (int)k;}
    static long now(){return System.nanoTime();}
    static double ms(long n){return n/1e6;}
    public Bench(){boolean f;try{Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler");f=true;}catch(Exception e){f=false;}folia=f;
        if(!folia) {
            // Paper event has full tick duration; register reflectively so Folia doesn't need this event.
            try {
                Class<? extends Event> cls=(Class<? extends Event>)Class.forName("com.destroystokyo.paper.event.server.ServerTickEndEvent");
                Bukkit.getPluginManager().registerEvent(cls,this,EventPriority.MONITOR,(l,e)->{
                    paperTick.incrementAndGet(); if(job!=null)try{paperTimes.add(((Number)e.getClass().getMethod("getTickDuration").invoke(e)).doubleValue());}catch(Exception ex){throw new RuntimeException(ex);}
                },PocPlugin.get());
            }catch(Exception e){throw new RuntimeException(e);}
        }
    }
    void error(String where,Throwable e){Out.res("error","where",where,"err",e.toString(),"trace",Tests.trace(e));}
    public void command(CommandSender sender,String[] a) {
        if(a.length==0){sender.sendMessage("wgpoc init | switch A|B ms sections inflight | save | sample cx cz sy tag | cancel | status | park name island | protection-test name");return;}
        switch(a[0]) {
            case "init" -> { if(job!=null)throw new IllegalStateException("busy");start("init","A",5,4,24); }
            case "switch" -> {if(job!=null)throw new IllegalStateException("busy");if(snapshots.size()!=coords.size())throw new IllegalStateException("init first");
                if(!a[1].equals("A")&&!a[1].equals("B"))throw new IllegalArgumentException("A or B");
                start("switch",a[1],Double.parseDouble(a[2]),Integer.parseInt(a[3]),Integer.parseInt(a[4]));}
            case "cancel" -> {if(job!=null){job.cancelled.set(true);Out.res("cancel_requested","id",job.id);}}
            case "save" -> save(()->Out.res("saved","head",head));
            case "status" -> Out.res("status","head",head,"chunks",snapshots.size(),"active",job!=null,"done",job==null?0:job.sections.get());
            case "sample" -> {int x=Integer.parseInt(a[1]),z=Integer.parseInt(a[2]),sy=Integer.parseInt(a[3]);String tag=a[4];
                Sched.at(world,x,z,()->{LevelChunk c=Nms.chunkNow(level,x,z);Out.res("sample","tag",tag,"cx",x,"cz",z,"sy",sy,"hash",c==null?null:Sect.nameHash(c,sy));});}
            case "park" -> {Player p=Bukkit.getPlayerExact(a[1]);int island=Integer.parseInt(a[2]);if(p==null)throw new IllegalArgumentException("no player");
                Sched.entity(p,()->{p.setGameMode(GameMode.CREATIVE);p.setAllowFlight(true);p.setFlying(true);
                    p.teleportAsync(new Location(world,(island*128+16)*16+8,194,15*16+8)).thenAccept(ok->Out.res("parked","player",p.getName(),"ok",ok));});}
            case "protection-test" -> {Player p=Bukkit.getPlayerExact(a[1]);Sched.entity(p,()->{protectUntil=System.currentTimeMillis()+10000;
                for(EntityDamageEvent.DamageCause c:List.of(EntityDamageEvent.DamageCause.FALL,EntityDamageEvent.DamageCause.SUFFOCATION,EntityDamageEvent.DamageCause.DROWNING,EntityDamageEvent.DamageCause.ENTITY_ATTACK)) {
                    EntityDamageEvent ev=new EntityDamageEvent(p,c,4);Bukkit.getPluginManager().callEvent(ev);Out.res("protection_test","cause",c.name(),"cancelled",ev.isCancelled());}
            });}
            default -> throw new IllegalArgumentException(a[0]);
        }
    }
    @EventHandler public void join(PlayerJoinEvent ev){Player p=ev.getPlayer();int n;try{n=Integer.parseInt(p.getName().replace("WgBot",""))-1;}catch(Exception e){n=0;}
        final int island=Math.floorMod(n,3);Sched.entity(p,()->{p.setAllowFlight(true);p.setFlying(true);p.teleportAsync(new Location(world,(island*128+16)*16+8,194,15*16+8));});}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player&&e.getEntity().getWorld()==world&&(job!=null||System.currentTimeMillis()<protectUntil)){
        switch(e.getCause()){case FALL,SUFFOCATION,DROWNING -> {e.setCancelled(true);Out.res("damage_cancelled","cause",e.getCause().name());}default -> {}}}}
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e){if(job!=null&&e.getBlock().getWorld()==world)e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(job!=null&&e.getBlock().getWorld()==world)e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void physics(BlockPhysicsEvent e){if(job!=null&&e.getBlock().getWorld()==world)e.setCancelled(true);}

    void start(String mode,String target,double budget,int limit,int inflight){
        if(budget<=0||limit<1||inflight<1||inflight>256)throw new IllegalArgumentException("positive limits; inflight <=256");
        Job j=new Job(mode,target,budget,limit,inflight);job=j;paperTimes.clear();protectUntil=Long.MAX_VALUE;
        Out.res("job_start","id",j.id,"mode",mode,"from",head,"target",target,"chunks",coords.size(),"budgetMs",budget,"sectionLimit",limit,"inflight",inflight);
        // 所有計數與 HEAD 為 plugin 狀態；只有 owner callbacks 碰 live data。
        if(mode.equals("switch"))save(()->{j.started=now();j.paperStartTick=paperTick.get();j.pump();j.monitor();});else {j.started=now();j.paperStartTick=paperTick.get();j.pump();j.monitor();}
    }
    class Budget {long tick=Long.MIN_VALUE,used;int sections; synchronized boolean allow(long t,long n,int lim){if(t!=tick){tick=t;used=0;sections=0;}return used<n&&sections<lim;}
        synchronized void charge(long t,long ns){if(t!=tick){tick=t;used=0;sections=0;}used+=ns;sections++;}}
    record Context(long region,long tick,Object handle){}
    Context context(){if(!folia)return new Context(0,paperTick.get(),null);try{
        Object r=Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler").getMethod("getCurrentRegion").invoke(null);
        Object d=r.getClass().getMethod("getData").invoke(r);long id=((Number)r.getClass().getField("id").get(r)).longValue();
        long tick=((Number)d.getClass().getMethod("getCurrentTick").invoke(d)).longValue();Object h=d.getClass().getMethod("getRegionSchedulingHandle").invoke(d);
        return new Context(id,tick,h);
    }catch(Exception e){throw new RuntimeException(e);}}
    class Job {
        final String mode,target,id=Long.toString(System.currentTimeMillis()); final double budgetMs;final int limit,max;
        long started,paperStartTick;final AtomicInteger next=new AtomicInteger(),active=new AtomicInteger(),finished=new AtomicInteger(),sections=new AtomicInteger(),errors=new AtomicInteger(),ticketCount=new AtomicInteger(),ticketPeak=new AtomicInteger(),loaded=new AtomicInteger(),unloaded=new AtomicInteger();
        final AtomicBoolean cancelled=new AtomicBoolean(),ending=new AtomicBoolean();final ConcurrentHashMap<Long,Budget> budgets=new ConcurrentHashMap<>();
        final Set<Long> regions=ConcurrentHashMap.newKeySet();
        final ConcurrentLinkedQueue<Long> loadNs=new ConcurrentLinkedQueue<>(),batchNs=new ConcurrentLinkedQueue<>(),sectionNs=new ConcurrentLinkedQueue<>();
        final AtomicInteger entityWaits=new AtomicInteger(),notValid=new AtomicInteger();final LongAdder blocks=new LongAdder(),light=new LongAdder(),beRemoved=new LongAdder(),beCreated=new LongAdder(),removed=new LongAdder(),spawned=new LongAdder();
        final ConcurrentHashMap<Long,List<Map<String,Object>>> metrics=new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long,Long> metricTicks=new ConcurrentHashMap<>();
        final AtomicLong heapPeak=new AtomicLong();
        Job(String m,String t,double b,int l,int n){mode=m;target=t;budgetMs=b;limit=l;max=n;}
        void pump(){if(stopping)return;
            synchronized(this){while(!cancelled.get()&&active.get()<max){int n=next.getAndIncrement();if(n>=coords.size())break;active.incrementAndGet();new Task(this,coords.get(n)).load();}}
            if((finished.get()==coords.size() || cancelled.get()&&active.get()==0)&&ending.compareAndSet(false,true))doneBlocks();
        }
        void monitor(){if(job!=this||stopping)return;
            long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();heapPeak.accumulateAndGet(heap,Math::max);
            Out.res("progress","id",id,"mode",mode,"chunks",finished.get(),"sections",sections.get(),"active",active.get(),"tickets",ticketCount.get(),"heapBytes",heap);
            // 三個 bot 錨點各在自己的 owner thread 讀 tick report，不以 global TPS 代替 region TPS。
            for(int island=0;island<3;island++){int x=island*128+16;Sched.at(world,x,15,()->{if(job!=this)return;try{Context c=context();regions.add(c.region);
                capture(c);
            }catch(Throwable e){error("metrics",e);}});}
            Sched.globalDelayed(20,this::monitor);
        }
        void capture(Context c) throws Exception {
            if(c.handle==null)return;
            Long prior=metricTicks.get(c.region);if(prior!=null&&c.tick-prior<20)return;
            metricTicks.put(c.region,c.tick);
            Object r=c.handle.getClass().getMethod("getTickReport5s",long.class).invoke(c.handle,now());
            if(r!=null){Map<String,Object> v=report(r);v.put("atTick",c.tick);metrics.computeIfAbsent(c.region,k->Collections.synchronizedList(new ArrayList<>())).add(v);}
        }
        void doneBlocks(){
            if(mode.equals("init")){Sched.async(()->{try{export();save(this::finish);}catch(Throwable e){errors.incrementAndGet();error("export",e);finish();}});return;}
            if(cancelled.get()||errors.get()>0){save(this::finish);return;}
            Map<Long,List<CompoundTag>> es=target.equals("A")?entitiesA:entitiesB;
            if(es.isEmpty()){save(this::finish);return;}
            AtomicInteger todo=new AtomicInteger(es.size());
            for(var entry:es.entrySet()){long k=entry.getKey();int x=cx(k),z=cz(k);
                world.getChunkAtAsync(x,z,true).thenAccept(ch->Sched.at(world,x,z,()->spawnWhenReady(entry.getValue(),x,z,ch,0,todo)))
                    .exceptionally(e->{errors.incrementAndGet();error("entity load",e);if(todo.decrementAndGet()==0)save(this::finish);return null;});
            }
        }
        /** 08：chunk 重新載入後 entity section 可能尚未就緒，此時 addEntity 會靜默失敗（或 getEntities 回空），所以先等 isEntitiesLoaded。 */
        void spawnWhenReady(List<CompoundTag> tags,int x,int z,org.bukkit.Chunk ch,int tries,AtomicInteger todo){
            if(!ch.isEntitiesLoaded()&&tries<400){entityWaits.incrementAndGet();Sched.atDelayed(world,x,z,1,()->spawnWhenReady(tags,x,z,ch,tries+1,todo));return;}
            boolean ticket=false;try{
                world.addPluginChunkTicket(x,z,PocPlugin.get());ticket=true;
                for(CompoundTag tag:tags) {
                    org.bukkit.entity.Entity e=CraftEntitySnapshot.create(tag.copy()).createEntity(world);
                    UUID u=uuid(tag);((CraftEntity)e).getHandle().setUUID(u);
                    world.addEntity(e);
                    // 08：Folia 上位於非 ticking chunk（只有 plugin ticket）的實體 addEntity 後 isValid()==false，但並非加入失敗；
                    // 不以 isValid 判失敗，改記錄診斷，最終以存檔後離線驗證（UUID 次數／完整 NBT）為準。
                    org.bukkit.entity.Entity ent=e;var h=((CraftEntity)ent).getHandle();
                    if(!ent.isValid()){notValid.incrementAndGet();if(notValid.get()<=3)Out.res("spawn_not_valid","uuid",u.toString(),"removed",h.isRemoved(),"alive",h.isAlive(),"entitiesLoaded",ch.isEntitiesLoaded(),"chunkLoaded",world.isChunkLoaded(x,z),"inWorld",ent.isInWorld(),"tries",tries);}
                    spawned.increment();
                }
            }catch(Throwable e){errors.incrementAndGet();error("spawn",e);}finally{if(ticket)world.removePluginChunkTicket(x,z,PocPlugin.get());if(todo.decrementAndGet()==0)save(this::finish);}
        }
        void finish(){
            double seconds=(now()-started)/1e9;
            if(!cancelled.get()&&errors.get()==0)head=target;else head="PARTIAL";
            protectUntil=System.currentTimeMillis()+10000;
            Map<String,Object> regionReports=new TreeMap<>();for(var e:metrics.entrySet())regionReports.put(Long.toString(e.getKey()),e.getValue());
            Out.res("job_done","id",id,"mode",mode,"target",target,"head",head,"cancelled",cancelled.get(),"errors",errors.get(),"chunks",finished.get(),"sections",sections.get(),
                "seconds",seconds,"sectionsPerSecond",sections.get()/seconds,"budgetMs",budgetMs,"sectionLimit",limit,"inflight",max,"loadedAtDispatch",loaded.get(),"ticketLoads",unloaded.get(),
                "ticketPeak",ticketPeak.get(),"ticketRemaining",ticketCount.get(),"loadMs",distribution(loadNs),"batchMs",distribution(batchNs),"sectionMs",distribution(sectionNs),"heapPeakBytes",heapPeak.get(),
                "blocksChanged",blocks.sum(),"lightChecks",light.sum(),"beRemoved",beRemoved.sum(),"beCreated",beCreated.sum(),"entitiesRemoved",removed.sum(),"entitiesSpawned",spawned.sum(),"entityReadyWaits",entityWaits.get(),"spawnNotValid",notValid.get(),
                "regions",regions,"regionReports",regionReports,"paperTicks",distributionDouble(paperTimes),"paperTPS",folia?null:Math.min(20.0,(paperTick.get()-paperStartTick)/seconds));job=null;
        }
    }
    class Task {
        final Job j;final int x,z;final long k;int section;boolean ticket,complete;LevelChunk c;
        Task(Job j,int[] q){this.j=j;x=q[0];z=q[1];k=key(x,z);}
        void load(){long t=now();if(Nms.chunkNow(level,x,z)!=null)j.loaded.incrementAndGet();else j.unloaded.incrementAndGet();
            world.getChunkAtAsync(x,z,true).thenAccept(ch->Sched.at(world,x,z,()->{j.loadNs.add(now()-t);try{world.addPluginChunkTicket(x,z,PocPlugin.get());ticket=true;
                int n=j.ticketCount.incrementAndGet();j.ticketPeak.accumulateAndGet(n,Math::max);c=(LevelChunk)((org.bukkit.craftbukkit.CraftChunk)ch).getHandle(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
                if(j.cancelled.get()){end();return;}
                if(j.mode.equals("init")) {prepare();end();}else {
                    removeThenStep(ch,0);}
            }catch(Throwable e){j.errors.incrementAndGet();error("load/prepare "+x+","+z,e);end();}}))
                .exceptionally(e->{j.errors.incrementAndGet();error("chunk future",e);end();return null;});
        }
        void removeThenStep(org.bukkit.Chunk ch,int tries){
            if(!ch.isEntitiesLoaded()&&tries<400&&!j.cancelled.get()){j.entityWaits.incrementAndGet();Sched.atDelayed(world,x,z,1,()->{try{removeThenStep(ch,tries+1);}catch(Throwable e){j.errors.incrementAndGet();error("remove "+x+","+z,e);end();}});return;}
            for(org.bukkit.entity.Entity e:ch.getEntities())if(!(e instanceof Player)&&tracked.contains(e.getUniqueId())){e.remove();j.removed.increment();}
            step();
        }
        void prepare() throws Exception {
            Sect.Snap[] a=new Sect.Snap[4],b=new Sect.Snap[4];boolean[] changed=new boolean[4];
            for(int i=0;i<4;i++){
                int sy=i+8;a[i]=Sect.snapshot(level,c,sy);
                var sec=Offline.section(true,sy);Codec.Section decoded=Codec.decodeSection(Codec.encodeSection(sec,Offline.bes(true,x,z).stream().filter(t->(t.intv("y",0)>>4)==sy).toList(),sy));
                var pc=c.getSection(Sect.idx(c,sy)).getStates().recreate();
                List<net.minecraft.world.level.block.state.BlockState> pal=new ArrayList<>();for(Codec.State s:decoded.palette)pal.add(states.computeIfAbsent(s.canon(),v->Nms.state(Bukkit.createBlockData(v))));
                for(int n=0;n<4096;n++)pc.set(n&15,n>>8,(n>>4)&15,pal.get(decoded.idx[n]));
                Map<BlockPos,CompoundTag> be=new LinkedHashMap<>();for(var e:decoded.blockEntities){Nbt.NCompound tag=e.getValue();int p=e.getKey();BlockPos pos=new BlockPos(x*16+(p&15),sy*16+(p>>8),z*16+((p>>4)&15));
                    tag.put("x",pos.getX());tag.put("y",pos.getY());tag.put("z",pos.getZ());be.put(pos,toMc(tag));}
                Sect.replace(level,c,sy,pc,be,Sect.Notify.NONE);b[i]=Sect.snapshot(level,c,sy);
                Sect.replace(level,c,sy,a[i].states.copy(),a[i].be,Sect.Notify.NONE);
                changed[i]=!Objects.equals(sectionHash(a[i],sy),sectionHash(b[i],sy));
            }
            snapshots.put(k,new Pair(a,b,changed));
            if((x%128==16||x%128==20||x%128==24)&&(z==12||z==15||z==18||z==21))entityPair(x,z);
            world.refreshChunk(x,z);j.sections.addAndGet(4);
        }
        void step(){if(complete)return;if(j.cancelled.get()||stopping){end();return;}
            try {
                if(!Bukkit.isOwnedByCurrentRegion(world,x,z))throw new IllegalStateException("wrong owner");
                Context ctx=context();j.regions.add(ctx.region);j.capture(ctx);Budget budget=j.budgets.computeIfAbsent(ctx.region,k->new Budget());long batchStart=now();
                Pair pair=snapshots.get(k);Sect.Snap[] target=j.target.equals("A")?pair.a:pair.b;
                while(section<4){if(!pair.changed[section]){section++;continue;}
                    if(!budget.allow(ctx.tick,(long)(j.budgetMs*1e6),j.limit)){if(now()>batchStart)j.batchNs.add(now()-batchStart);Sched.atDelayed(world,x,z,1,this::step);return;}
                    long t=now();Map<String,Object> v=Sect.replace(level,c,section+8,target[section].states.copy(),target[section].be,Sect.Notify.NONE);
                    long duration=now()-t;j.sectionNs.add(duration);budget.charge(ctx.tick,duration);j.sections.incrementAndGet();section++;
                    j.blocks.add(((Number)v.get("blocksChanged")).longValue());j.light.add(((Number)v.get("lightChecks")).longValue());j.beRemoved.add(((Number)v.get("beRemoved")).longValue());j.beCreated.add(((Number)v.get("beNow")).longValue());
                }
                world.refreshChunk(x,z);j.batchNs.add(now()-batchStart);end();
            }catch(Throwable e){j.errors.incrementAndGet();error("step",e);end();}
        }
        void end(){synchronized(this){if(complete)return;complete=true;}
            if(ticket){world.removePluginChunkTicket(x,z,PocPlugin.get());j.ticketCount.decrementAndGet();ticket=false;}
            j.finished.incrementAndGet();j.active.decrementAndGet();Sched.global(j::pump);
        }
    }
    void entityPair(int x,int z) throws Exception {
        int island=x/128,local=x%128;
        List<CompoundTag> aa=new ArrayList<>(),bb=new ArrayList<>();
        for(int type=0;type<3;type++) {
            org.bukkit.entity.Entity a=recipe(x,z,type,"A",island);CompoundTag at=entityNbt(a);aa.add(at);tracked.add(a.getUniqueId());world.addEntity(a);
            org.bukkit.entity.Entity b=recipe(x,z,type,"B",(island+2)%3);CompoundTag bt=entityNbt(b);bb.add(bt);tracked.add(b.getUniqueId());
        }
        entitiesA.put(key(x,z),aa);entitiesB.put(key(x,z),bb);
    }
    org.bukkit.entity.Entity recipe(int x,int z,int type,String variant,int origin) {
        Class<? extends org.bukkit.entity.Entity> cls=type==0?ArmorStand.class:type==1?ItemFrame.class:Cow.class;
        org.bukkit.entity.Entity e=world.createEntity(new Location(world,x*16+(type==1?10:4+type*2)+.5,type==1?185:183,z*16+(type==1?9:5)+.5),cls);
        UUID id=UUID.nameUUIDFromBytes(("wg08:"+origin+":"+(x%128)+":"+z+":"+type).getBytes(java.nio.charset.StandardCharsets.UTF_8));((CraftEntity)e).getHandle().setUUID(id);
        e.customName(net.kyori.adventure.text.Component.text("WG08-"+variant+"-"+type));e.setGravity(false);e.setInvulnerable(true);e.setSilent(true);e.setPersistent(true);
        if(e instanceof ArmorStand as){as.setArms(true);as.setBasePlate(false);as.getEquipment().setHelmet(new ItemStack(variant.equals("A")?Material.DIAMOND_BLOCK:Material.EMERALD_BLOCK));}
        if(e instanceof ItemFrame f){f.setFixed(true);f.setFacingDirection(BlockFace.NORTH,true);f.setItem(new ItemStack(variant.equals("A")?Material.DIAMOND:Material.EMERALD));}
        if(e instanceof LivingEntity l){l.setAI(false);l.setRemoveWhenFarAway(false);l.setCollidable(false);}
        return e;
    }
    CompoundTag entityNbt(org.bukkit.entity.Entity e) {
        var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());
        if(!((CraftEntity)e).getHandle().save(out))throw new IllegalStateException("entity serialization failed "+e.getType());
        return out.buildResult();
    }
    static UUID uuid(CompoundTag t){int[] a=t.getIntArray("UUID").orElseThrow();return new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL));}
    static Nbt.NCompound fromMc(CompoundTag t)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();NbtIo.write(t,new DataOutputStream(b));return Nbt.readRoot(b.toByteArray());}
    static CompoundTag toMc(Nbt.NCompound t)throws Exception{return NbtIo.read(new DataInputStream(new ByteArrayInputStream(Nbt.toBytes(t,false))),NbtAccounter.unlimitedHeap());}
    String sectionHash(Sect.Snap s,int sy)throws Exception{
        Codec.Section sec=new Codec.Section();Map<net.minecraft.world.level.block.state.BlockState,Integer> ids=new IdentityHashMap<>();
        for(int n=0;n<4096;n++) {var state=s.states.get(n&15,n>>8,(n>>4)&15);Integer id=ids.get(state);
            if(id==null){id=ids.size();ids.put(state,id);String canon=Nms.stateString(state);int idx=canon.indexOf('[');String name=idx<0?canon:canon.substring(0,idx);TreeMap<String,String> props=new TreeMap<>();if(idx>=0)for(String v:canon.substring(idx+1,canon.length()-1).split(",")){String[] p=v.split("=");props.put(p[0],p[1]);}sec.palette.add(new Codec.State(name,props));}
            sec.idx[n]=id;
        }
        Nbt.NCompound raw=new Nbt.NCompound().put2("Y",(byte)sy).put2("block_states",Codec.toMcBlockStates(sec));List<Nbt.NCompound> be=new ArrayList<>();for(CompoundTag t:s.be.values())be.add(fromMc(t));
        return Offline.hash(Codec.encodeSection(raw,be,sy));
    }
    void export()throws Exception{
        for(String v:List.of("A","B")){StringBuilder ss=new StringBuilder(),es=new StringBuilder();
            for(int[] q:coords){Pair p=snapshots.get(key(q[0],q[1]));for(int sy=-4;sy<20;sy++)if(sy<8||sy>11)ss.append(q[0]).append('\t').append(q[1]).append('\t').append(sy).append("\tair\n");for(int i=0;i<4;i++)ss.append(q[0]).append('\t').append(q[1]).append('\t').append(i+8).append('\t').append(sectionHash(v.equals("A")?p.a[i]:p.b[i],i+8)).append('\n');}
            for(var tags:(v.equals("A")?entitiesA:entitiesB).values())for(CompoundTag tag:tags){var n=fromMc(tag);Files.write(manifests.resolve("entity-"+v+"-"+Codec.uuidKey(n)+".nbt"),Nbt.toBytes(n,false));es.append(Codec.uuidKey(n)).append('\t').append(Offline.hash(Nbt.toBytes(Codec.normEntity(n,true),true))).append('\n');}
            Files.writeString(manifests.resolve("sections-"+v+".tsv"),ss);Files.writeString(manifests.resolve("entities-"+v+".tsv"),es);
        }
        Out.res("manifest","sections",coords.size()*4,"entities",entitiesA.values().stream().mapToInt(List::size).sum());
    }
    void save(Runnable after){
        AtomicInteger left=new AtomicInteger(coords.size());Set<Long> seen=ConcurrentHashMap.newKeySet();long t=now();
        for(int[] q:coords)Sched.at(world,q[0],q[1],()->{try{Context c=context();if(seen.add(c.region))Nms.saveAllChunksNms(level,true);}catch(Throwable e){if(job!=null)job.errors.incrementAndGet();error("save",e);}finally{if(left.decrementAndGet()==0){Out.res("region_save","regions",seen,"ms",ms(now()-t));Sched.global(after);}}});
    }
    static Object call(Object o,String n)throws Exception{return o.getClass().getMethod(n).invoke(o);}
    static Map<String,Object> report(Object r)throws Exception {
        Map<String,Object> v=new LinkedHashMap<>();v.put("ticks",call(r,"collectedTicks"));
        for(String key:List.of("tpsData","timePerTickData")){Object a=call(call(r,key),"segmentAll");v.put(key,Out.m("mean",call(a,"average"),"median",call(a,"median"),"min",call(a,"least"),"max",call(a,"greatest")));}
        return v;
    }
    static Map<String,Object> distribution(Collection<Long> x){List<Double> a=new ArrayList<>();for(long n:x)a.add(ms(n));return distributionDouble(a);}
    static Map<String,Object> distributionDouble(Collection<Double> x){List<Double>a=new ArrayList<>(x);if(a.isEmpty())return Map.of();Collections.sort(a);return Out.m("count",a.size(),"mean",a.stream().mapToDouble(Double::doubleValue).average().orElse(0),"p50",a.get(a.size()/2),"p95",a.get(Math.min(a.size()-1,(int)(a.size()*.95))),"p99",a.get(Math.min(a.size()-1,(int)(a.size()*.99))),"max",a.getLast());}
    public void shutdown(){stopping=true;if(job!=null){job.cancelled.set(true);Out.res("shutdown_partial","id",job.id,"sections",job.sections.get(),"tickets",job.ticketCount.get(),"head",head);}}
}
