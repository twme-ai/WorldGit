// Interoperability fixture from the MIT AxiomPaper packet protocol. No Axiom client code.
const path = require('path')
const root = path.resolve(__dirname, '../../.work/bot/node_modules')
const nbt = require(path.join(root, 'prismarine-nbt'))
const crypto = require('crypto')
const vi = (n) => { const a=[]; do { const b=n&127; n>>>=7; a.push(b|(n?128:0)) } while(n); return Buffer.from(a) }
const str = (s) => { const b=Buffer.from(s); return Buffer.concat([vi(b.length),b]) }
const uuid = (s) => Buffer.from(s.replace(/-/g,''),'hex')
const pos = (x,y,z) => { const b=Buffer.alloc(8); b.writeBigUInt64BE((BigInt(x)&0x3ffffffn)<<38n | (BigInt(z)&0x3ffffffn)<<12n | BigInt(y)&0xfffn);return b }
const doubles=(...values)=>{const b=Buffer.alloc(values.length*8);values.forEach((v,i)=>b.writeDoubleBE(v,i*8));return b}
const floats=(...values)=>{const b=Buffer.alloc(values.length*4);values.forEach((v,i)=>b.writeFloatBE(v,i*4));return b}
const int=(value)=>{const b=Buffer.alloc(4);b.writeInt32BE(value);return b}
function tag(value) { const b=nbt.writeUncompressed({type:'compound',name:'',value});return Buffer.concat([b.subarray(0,1),b.subarray(3)]) }
module.exports=(bot,out,version)=>{
 const data=require(path.join(root,'minecraft-data'))(version)
 let sequence=1000,propertySequence=0
 bot._client.on('acknowledge_player_digging',packet=>out({ev:'axiom_block_ack',packet}))
 const payload=(channel,...bytes)=>bot._client.write('custom_payload',{channel:'axiom:'+channel,data:Buffer.concat(bytes)})
 bot.once('login',()=>bot._client.write('custom_payload',{channel:'minecraft:register',data:Buffer.from('axiom:hello\0axiom:enable\0axiom:restrictions\0axiom:response_chunk_data\0axiom:response_entity_data\0axiom:ack_world_properties')}))
 bot._client.on('custom_payload',(packet)=>{
  if(packet.channel==='axiom:hello') {
   payload('hello',vi(10),vi(data.version.dataVersion),vi(data.version.version),packet.data)
   out({ev:'axiom_hello',nonce:packet.data.toString('hex')})
  } else if(packet.channel==='axiom:ack_world_properties')out({ev:'axiom_property_ack',hex:packet.data.toString('hex')})
  else if(packet.channel==='axiom:response_entity_data')out({ev:'axiom_entity_data',bytes:packet.data.length})
  else if(packet.channel==='axiom:enable')out({ev:'axiom_enable',enabled:packet.data[0]===1,hex:packet.data.toString('hex')})
 })
 return (a)=>{
  const type=a[1];const x=+a[2],y=+a[3],z=+a[4]
  if(type==='locale') {
   bot.setSettings({locale:a[2]});out({ev:'axiom_sent',type,locale:a[2]})
  } else if(type==='block') {
   const state=bot.registry.blocksByName[a[5]||'diamond_block'].defaultState
   const sequenceId=++sequence
   payload('set_block',vi(1),pos(x,y,z),vi(state),Buffer.from([0]),vi(1),Buffer.from([0]),pos(x,y,z),Buffer.from([1]),floats(.5,.5,.5),Buffer.from([0,0]),vi(0),vi(sequenceId))
   out({ev:'axiom_sent',type,x,y,z,state,sequenceId})
  } else if(type==='buffer') {
   const state=bot.registry.blocksByName[a[5]||'gold_block'].defaultState,side=+(a[6]||1);const parts=[]
   for(let dx=0;dx<side;dx++)for(let dz=0;dz<side;dz++)parts.push(pos(x+dx,y,z+dz),Buffer.from([0]),vi(state),vi(0))
   payload('set_buffer',str('minecraft:overworld'),uuid(crypto.randomUUID()),Buffer.from([0]),...parts,pos(-33554432,-2048,-33554432),vi(2000))
   out({ev:'axiom_sent',type,x,y,z,state,side})
  } else if(type==='spawn') {
   const id=a[5]||crypto.randomUUID()
   payload('spawn_entity',vi(1),uuid(id),doubles(x,y,z),floats(0,0),Buffer.from([0]),tag({id:{type:'string',value:'minecraft:armor_stand'},NoGravity:{type:'byte',value:1},Invulnerable:{type:'byte',value:1}}))
   out({ev:'axiom_sent',type,id})
  } else if(type==='manipulate') {
   const id=a[2]
   payload('manipulate_entity',vi(1),uuid(id),Buffer.from([0]),doubles(+a[3],+a[4],+a[5]),floats(45,0),Buffer.from([0]),vi(0))
   out({ev:'axiom_sent',type,id})
  } else if(type==='delete') {
   payload('delete_entity',vi(1),uuid(a[2]));out({ev:'axiom_sent',type,id:a[2]})
  } else if(type==='read') {
   const request=Buffer.alloc(8);request.writeBigInt64BE(1n)
   payload('request_entity_data',request,vi(1),uuid(a[2]));out({ev:'axiom_sent',type,id:a[2]})
  } else if(type==='property') {
   const updateId=++propertySequence
   payload('set_world_property',str('axiom:pause_weather'),vi(0),vi(1),Buffer.from([+a[2]]),vi(updateId));out({ev:'axiom_sent',type,updateId})
  } else if(type==='tick'||type==='fix') {
   payload(type==='tick'?'tick_blocks':'fix_area',str('minecraft:overworld'),Buffer.from([1]),pos(x,y,z),pos(x,y,z));out({ev:'axiom_sent',type})
  } else if(type==='time') {
   payload('set_world_time',str('minecraft:overworld'),Buffer.from([1]),int(+a[2]),Buffer.from([0]));out({ev:'axiom_sent',type})
  } else throw new Error('unknown Axiom fixture: '+type)
 }
}
