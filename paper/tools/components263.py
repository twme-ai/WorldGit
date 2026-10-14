"""26.3 真互動與元件 codec fixture；由 multiversion runner 在持鎖期間呼叫。"""
import json, os, re, shutil, subprocess, time, zipfile
from pathlib import Path
import harness
from setup_bot_263 import component_codecs
ROOT=Path(harness.ROOT)
COMPONENTS={
 'attack_animation':'{type:"stab",duration:12}',
 'interact_animation':'{type:"whack",duration:9}',
 'block_transformer':'"minecraft:axe"',
 'brewing_fuel':'{uses:7,speed_multiplier:1.25f}',
 'compostable':'{layers:1}',
 'cooking_fuel':'{burn_time:321,speed_multiplier:1.5f}',
 'cushion/color':'"red"',
 'mob_visibility':'{targeting_entity_types:["minecraft:zombie"],visibility:0.5f}',
 'provides_pottery_pattern':'"minecraft:angler"',
 'sign_text_front':'{messages:["WG 26.3","front","",""],color:"red",has_glowing_text:true}',
 'sign_text_back':'{messages:["WG back","","",""],filtered_messages:["filtered","","",""],color:"blue",has_glowing_text:false}',
 'villager_food':'{nutrition:5}',
 'waxed':'{}',
}

def install(server):
    component_codecs(ROOT/'.work/bot')
    base=ROOT/'.work/gradle-home/caches/modules-2/files-2.1'
    jars=[ROOT/'paper/v26_3/.gradle/caches/paperweight/taskCache/mappedServerJar.jar']
    for group in ['io.papermc.paper/paper-api/26.3.build.177-stable','com.google.guava/guava','com.mojang/brigadier','com.mojang/datafixerupper','com.mojang/authlib','com.mojang/logging','io.netty/netty-buffer','io.netty/netty-common','net.kyori/adventure-api','net.kyori/adventure-key','org.jetbrains/annotations','net.md-5/bungeecord-chat','net.kyori/examination-api','net.kyori/examination-string','it.unimi.dsi/fastutil']:
        jars+=list((base/group).rglob('*.jar'))
    classes=Path(server.dir)/'components263-classes';classes.mkdir()
    subprocess.run([str(Path(harness.JAVA['26.3']).with_name('javac')),'--release','25','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(ROOT/'paper/tools/components-fixture/Components263Fixture.java')],check=True)
    with zipfile.ZipFile(Path(server.dir)/'plugins/components263-fixture.jar','w') as jar:
        for path in classes.rglob('*.class'):jar.write(path,str(path.relative_to(classes)))
        jar.writestr('plugin.yml','name: Components263Fixture\nversion: 1\nmain: org.worldgit.fixture.Components263Fixture\napi-version: "26.3"\ncommands:\n  wg263fixture:\n    description: Native item component acceptance\n')
    shutil.rmtree(classes)

def interact(server,out,check):
    bot=server.bot('WgBot')
    server.cmd('gamemode creative WgBot',r'(?i)game ?mode')
    server.cmd('tp WgBot 13.5 65 6.5',r'(?i)teleported')
    # A real player places new registry blocks and uses the new transformer component.
    server.cmd('setblock 14 64 6 poplar_log',r'(?i)changed')
    # A native axe already supplies block_transformer=axe, so its patch drops
    # that default. Diamond keeps all thirteen values explicit on the wire.
    carrier='diamond['+','.join(key+'='+value for key,value in COMPONENTS.items())+']'
    server.cmd('item replace entity WgBot hotbar.0 with '+carrier,r'(?i)replaced')
    wire=server.cmd('wg263fixture WgBot',r'WG263WIRE (complete|failed)')
    samples=[{'name':name,'hex':hex} for name,hex in re.findall(r'WG263WIRE (minecraft:\S+) ([0-9a-f]+)',wire)]
    check('13 new components emitted by native STREAM_CODEC',len(samples)==13 and {s['name'] for s in samples}=={'minecraft:'+key for key in COMPONENTS},console=wire)
    path=out/'native-component-wire.json';path.write_text(json.dumps(samples,indent=2)+'\n')
    decoded=subprocess.check_output(['node',str(ROOT/'paper/tools/components263-wire.js'),str(path)],text=True)
    data=json.loads(decoded);(out/'component-wire-roundtrip.json').write_text(decoded)
    check('13 native component bytes decode and encode identically',data['success'] and len(data['checks'])==13,wire=data)
    used=bot.ask('use 14 64 6','used')
    deadline=time.monotonic()+10
    while True:
        transformed=bot.ask('block 14 64 6','block')
        if transformed['name']=='stripped_poplar_log' or time.monotonic()>=deadline:break
        time.sleep(.1)
    check('player uses block_transformer on a new poplar log',transformed['name']=='stripped_poplar_log',interaction=used,block=transformed)
    server.cmd('setblock 14 64 7 chest',r'(?i)changed')
    server.cmd('item replace block 14 64 7 container.0 from entity WgBot weapon.mainhand',r'(?i)replaced')
    saved=server.cmd('data get block 14 64 7 Items',r'(?i)block data')
    check('native chest persists all 13 explicit new components',all('minecraft:'+key in saved for key in COMPONENTS),console=saved)
    server.cmd('item replace entity WgBot hotbar.0 with poplar_planks',r'(?i)replaced')
    bot.ask('place 13 63 7 0 1 0','placed')
    check('player places a new poplar plank block',bot.ask('block 13 64 7','block')['name']=='poplar_planks')
    # Existing sign ID with new item components must materialize native BE fields.
    sign='oak_sign['+','.join(key+'='+COMPONENTS[key] for key in ['sign_text_front','sign_text_back','waxed'])+']'
    server.cmd('item replace entity WgBot hotbar.0 with '+sign,r'(?i)replaced')
    bot.ask('place 13 63 5 0 1 0','placed')
    saved=server.cmd('data get block 13 64 5',r'(?i)block data')
    plain=re.sub(r'\x1b\[[0-9;]*m','',saved)
    check('player places sign with both native texts and wax',all(x in plain for x in ['WG 26.3','WG back','filtered','is_waxed: 1b']),console=saved)
    server.cmd('item replace block 14 64 7 container.1 from entity WgBot weapon.mainhand',r'(?i)replaced')
    # Reference branch, independent of the constants used above.
    reference='diamond[cooking_fuel={burn_time:"minecraft:cooking/time_coal",speed_multiplier:"minecraft:cooking/speed_default"},brewing_fuel={uses:"minecraft:brewing/uses_default",speed_multiplier:"minecraft:brewing/speed_default"},compostable={layers:"minecraft:compostable/low"}]'
    server.cmd('item replace entity WgBot hotbar.0 with '+reference,r'(?i)replaced')
    wire=server.cmd('wg263fixture WgBot',r'WG263WIRE (complete|failed)')
    samples=[{'name':name,'hex':hex} for name,hex in re.findall(r'WG263WIRE (minecraft:\S+) ([0-9a-f]+)',wire)]
    path=out/'native-component-reference-wire.json';path.write_text(json.dumps(samples,indent=2)+'\n')
    data=json.loads(subprocess.check_output(['node',str(ROOT/'paper/tools/components263-wire.js'),str(path)],text=True))
    check('resolvable number references match native wire bytes',data['success'] and len(data['checks'])==3,wire=data)
    server.cmd('item replace block 14 64 7 container.2 from entity WgBot weapon.mainhand',r'(?i)replaced')
    server.cmd('gamemode survival WgBot',r'(?i)game ?mode')
    bot.stop()
