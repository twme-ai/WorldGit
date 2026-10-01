// 使用 03 原有的 mineflayer 26.2 協定補丁；此檔不修改共享 node_modules。
const path = require('path'), readline = require('readline');
const modules = path.resolve(__dirname, '../../../.work/bot/node_modules');
const mineflayer = require(path.join(modules,'mineflayer'));
const {Vec3} = require(path.join(modules,'vec3'));
const [port,version,name,role] = process.argv.slice(2);
const bot=mineflayer.createBot({host:'127.0.0.1',port:+port,username:name,version,auth:'offline'});
const log=o=>console.log(JSON.stringify({time:Date.now(),name,role,...o}));
let mode='idle', stopped=false, busy=false, cycle=0, stats={}, posOld=null, traveled=0;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function equip(item) {const it=bot.inventory.items().find(x=>x.name===item);if(!it)throw Error('missing '+item);await bot.equip(it,'hand');}
async function action(label,fn) {
  try {await fn(); stats[label]=(stats[label]||0)+1;log({event:'success',action:label});}
  catch(e){stats[label+'_failed']=(stats[label+'_failed']||0)+1;log({event:'failure',action:label,error:String(e)});bot.stopDigging();bot.clearControlStates();if(bot.currentWindow)bot.closeWindow(bot.currentWindow);}
}
async function near(x,y,z) {
  const target=new Vec3(x,y,z);let start=Date.now();
  while(bot.entity.position.horizontalDistanceTo(target)>2.5 && Date.now()-start<8000 && mode==='active') {
    await bot.lookAt(target.offset(0,1,0),true);bot.setControlState('forward',true);bot.setControlState('jump',true);await sleep(100);
  }
  bot.clearControlStates();
  if(bot.entity.position.distanceTo(target)>5.5)throw Error('out of reach '+target);
}
async function place(item,x,y,z) {
  await near(x,y,z);await equip(item);
  let target=bot.blockAt(new Vec3(x,y,z));if(!target || target.name!=='air')throw Error('occupied target');
  for(const d of [new Vec3(0,-1,0),new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,0,1),new Vec3(0,0,-1)]){
    const ref=bot.blockAt(target.position.plus(d));if(ref && ref.boundingBox==='block'){await bot.placeBlock(ref,d.scaled(-1));return;}
  }throw Error('no support');
}
async function dig(x,y,z,tool,label) {await near(x,y,z);await equip(tool);const b=bot.blockAt(new Vec3(x,y,z));if(!b || b.name==='air')throw Error('air');await bot.dig(b,true);}
async function work() {
 if(bot.isSleeping) {await sleep(2000);return;}
 if(!bot.time.isDay) {
   const i=+name.replace('Scale','');
   if(bot.entity.position.distanceTo(new Vec3(8+i*13,190,26))<18) {
     await action('sleep',async()=>{await near(8+i*13,190,26);const bed=bot.blockAt(new Vec3(8+i*13,190,26));await bot.sleep(bed);await sleep(1200);});
     if(bot.isSleeping)return;
   }
 }
 if(role==='explorer') {
   const heading= (Math.floor(cycle/15)%4)*Math.PI/2;
   await bot.look(heading,0,true);bot.setControlState('forward',true);bot.setControlState('sprint',true);bot.setControlState('jump',true);await sleep(5000);bot.clearControlStates();
   stats.walk_intervals=(stats.walk_intervals||0)+1;
 } else if(role==='miner') {
   const j=cycle%12;
   await action('mine',()=>dig(9+j%4,190,10+Math.floor(j/4),'iron_pickaxe'));
   await action('chop',()=>dig(15,190+j%3,10,'iron_axe'));
   if(cycle%3===0) await action('drop',async()=>{const it=bot.inventory.items().find(x=>x.name==='cobblestone'||x.name==='oak_log');if(!it)throw Error('no loot');await bot.toss(it.type,null,1);});
 } else if(role==='builder') {
   const j=cycle%124;
   let x,y,z;
   if(j<40 || j>=84) {const k=j<40?j:j-84;x=27+k%8;z=8+Math.floor(k/8);y=j<40?190:193;}
   else {const k=(j-40)%22;const perimeter=[];for(let xx=27;xx<=34;xx++){perimeter.push([xx,8]);perimeter.push([xx,12]);}for(let zz=9;zz<=11;zz++){perimeter.push([27,zz]);perimeter.push([34,zz]);}[x,z]=perimeter[k];y=191+Math.floor((j-40)/22);}
   if(cycle<124) await action('build',()=>place('oak_planks',x,y,z));
   else await action('renovate',async()=>{await dig(x,y,z,'iron_axe');await place('oak_planks',x,y,z);});
   if(cycle%5===0)await action('farm',async()=>{
     const ripe=bot.findBlock({matching:b=>b.name==='wheat' && b.getProperties().age===7,maxDistance:20});
     if(ripe){await near(ripe.position.x,ripe.position.y,ripe.position.z);await bot.dig(ripe,true);stats.harvest=(stats.harvest||0)+1;}
     const f=bot.findBlock({matching:b=>b.name==='farmland' && bot.blockAt(b.position.offset(0,1,0))?.name==='air',maxDistance:20});
     if(!f)throw Error('no empty farmland');await near(f.position.x,f.position.y,f.position.z);await equip('wheat_seeds');await bot.placeBlock(f,new Vec3(0,1,0));
   });
   if(cycle%7===0)await action('breed',async()=>{
     await near(31,190,19);await equip('wheat');const cows=Object.values(bot.entities).filter(e=>e.name==='cow'&&e.position.distanceTo(bot.entity.position)<5);
     if(cows.length<2)throw Error('not two cows nearby');for(const cow of cows.slice(0,2)){await bot.lookAt(cow.position.offset(0,1,0),true);await bot.activateEntity(cow);await sleep(600);}
   });
 } else {
   if(cycle===0)await action('chest_place',()=>place('chest',47,190,10));
   await action('chest_store',async()=>{await near(47,190,10);const c=await bot.openContainer(bot.blockAt(new Vec3(47,190,10)));await c.deposit(bot.registry.itemsByName.oak_planks.id,null,1);c.close();});
   await action('combat',async()=>{
     const e=Object.values(bot.entities).find(e=>['zombie','husk','skeleton'].includes(e.name)&&e.position.distanceTo(bot.entity.position)<12);
     if(!e)throw Error('no monster');await near(e.position.x,e.position.y,e.position.z);await equip('iron_sword');
     for(let i=0;i<18&&bot.entities[e.id];i++){await bot.lookAt(e.position.offset(0,1,0),true);bot.attack(e);await sleep(650);}if(bot.entities[e.id])throw Error('monster survived');
   });
   if(!bot.time.isDay)await action('sleep',async()=>{const bed=bot.findBlock({matching:b=>b.name.endsWith('_bed'),maxDistance:20});if(!bed)throw Error('no bed');await near(bed.position.x,bed.position.y,bed.position.z);await bot.sleep(bed);await sleep(1200);});
 }
 if(bot.food<16) await action('eat',async()=>{await equip('cooked_beef');await bot.consume();});
 cycle++;
}
async function loop() {
 while(!stopped){if(mode==='active'&&!busy&&bot.entity){busy=true;try{await work();}catch(e){log({event:'loop_error',error:String(e)});}finally{busy=false;}}await sleep(2500);}
}
bot.once('spawn',()=>{log({event:'spawn',pos:bot.entity.position,version:bot.version});loop();});
bot.on('death',()=>{stats.deaths=(stats.deaths||0)+1;log({event:'death'});});
bot.on('error',e=>log({event:'error',error:String(e)}));bot.on('kicked',r=>log({event:'kicked',reason:r}));bot.on('end',r=>{log({event:'end',reason:r,stats,traveled});process.exit(0);});
setInterval(()=>{if(!bot.entity)return;const p=bot.entity.position;if(posOld&&p.distanceTo(posOld)<20)traveled+=p.distanceTo(posOld);posOld=p.clone();log({event:'heartbeat',mode,pos:p,health:bot.health,food:bot.food,stats,traveled,gameTime:bot.time?.time});},30000);
readline.createInterface({input:process.stdin}).on('line',s=>{
 if(s==='active'||s==='idle'){mode=s;bot.clearControlStates();log({event:'mode',mode});}
 if(s==='quit'){stopped=true;bot.quit();setTimeout(()=>process.exit(0),1500);}
});
