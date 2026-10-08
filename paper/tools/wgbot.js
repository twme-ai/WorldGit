// WorldGit 驗收用 mineflayer 機器人：由 stdin 一行一個指令，輸出 "BOT {json}"。
// 用法：node wgbot.js <port> <version> <name> [mod]
//   mod：模擬安裝了 WorldGit 客戶端模組——註冊 worldgit:* channel、回覆 hello、統計收到的 status/diff 封包。
// mineflayer 安裝在 .work/bot/node_modules（26.2 靠 experiments/03 的版本 hack）。
const path = require('path')
const root = path.resolve(__dirname, '../../.work/bot/node_modules')
const mineflayer = require(path.join(root, 'mineflayer'))
const { Vec3 } = require(path.join(root, 'vec3'))
const readline = require('readline')
const crypto = require('crypto')

const [port, version, name, mode] = process.argv.slice(2)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: +port, username: name, version, auth: 'offline', viewDistance: 'tiny' })
const out = (o) => console.log('BOT ' + JSON.stringify(o))
const received = { hello: 0, status: 0, diff: 0, clear: 0, conflicts: 0, conflictPreview: 0, statusEntries: 0, diffEntries: 0, bytes: 0, previews: {} }
const axiom = process.env.WG_AXIOM_BOT ? require("./axiom-protocol.js")(bot, out, version) : null
let ready = false

function varint(buf, pos) { let n = 0, i = 0, b; do { b = buf[pos++]; n |= (b & 127) << (7 * i++); } while (b & 128); return [n, pos] }
function writeVarint(n) { const a = []; do { let b = n & 127; n >>>= 7; a.push(b | (n ? 128 : 0)) } while (n); return Buffer.from(a) }
function str(s) { const b = Buffer.from(s, 'utf8'); return Buffer.concat([writeVarint(b.length), b]) }

function encodeHello(version, nonce, caps) {
  const head = Buffer.alloc(3 + 8); head[0] = 2; head[1] = 0; head[2] = version; head.writeBigInt64BE(BigInt(nonce), 3)
  const palette = Buffer.alloc(16) // 色票內容對驗收無關
  return Buffer.concat([head, writeVarint(caps.length), ...caps.map(str), palette])
}

// WG_BOT_DUMP=<檔案>：把收到的 worldgit:* payload 以「channel hex」逐行附加，供離線以 protocol 解碼（四端一致性驗收）。
const fs = require('fs')
const dump = process.env.WG_BOT_DUMP
function onPayload(channel, data) {
  if (dump && (channel === 'worldgit:conflicts' || channel === 'worldgit:conflict_preview')) require('fs').appendFileSync(dump, channel + ' ' + Buffer.from(data).toString('hex') + '\n')
  received.bytes += data.length
  if (process.env.WG_BOT_DUMP && channel.startsWith('worldgit:') && channel !== 'worldgit:hello') fs.appendFileSync(process.env.WG_BOT_DUMP, channel + ' ' + Buffer.from(data).toString('hex') + '\n')
  if (channel === 'worldgit:hello' && mode === 'mod') {
    // [version][kind=0][version][nonce 8][caps...]
    const v = data[2]; const nonce = data.readBigInt64BE(3)
    received.hello++
    bot._client.write('custom_payload', { channel: 'worldgit:hello', data: encodeHello(v, nonce, ['ghost-render', 'outline', 'status-outline', 'section-palette-v2', 'merge-regions-v1']) })
    out({ ev: 'hello_replied', serverVersion: v })
    ready = true
  } else if (channel === 'worldgit:status' || channel === 'worldgit:diff') {
    const kind = data[1]; const preview = data.readBigInt64BE(2)
    let p = 10; let seq, parts, total
    ;[seq, p] = varint(data, p); [parts, p] = varint(data, p); [total, p] = varint(data, p)
    const key = String(preview)
    const pv = received.previews[key] || (received.previews[key] = { kind, parts, total, seen: new Set() })
    pv.seen.add(seq)
    if (kind === 3) received.status++; else received.diff++
  } else if (channel === 'worldgit:conflicts') { received.conflicts++; out({ev:'merge_payload',channel,bytes:data.length}) }
  else if (channel === 'worldgit:conflict_preview') { received.conflictPreview++; out({ev:'merge_payload',channel,bytes:data.length}) }
  else if (channel === 'worldgit:clear') {
    received.clear++
    // clear 是單調 floor：清除所有 <= id 的 preview，與真 Fabric ClientPreviews 相同。
    const floor=data.readBigInt64BE(2)
    for (const id of Object.keys(received.previews)) if (BigInt(id)<=floor) delete received.previews[id]
  }
}

bot.once('login', () => {
  // channel 要先註冊，Paper 才會把 plugin message 送給客戶端（experiments/05 的結論）。
  if (mode === 'mod') bot._client.write('custom_payload', { channel: 'minecraft:register', data: Buffer.from(['worldgit:hello', 'worldgit:diff', 'worldgit:status', 'worldgit:clear', 'worldgit:conflicts', 'worldgit:conflict_preview'].join('\0')) })
})
bot._client.on('custom_payload', (packet) => { try { onPayload(packet.channel, packet.data) } catch (e) { out({ ev: 'payload_error', channel: packet.channel, e: String(e) }) } })
bot._client.on('boss_bar', (packet) => out({ev:'bossbar',packet}))
bot.once('spawn', () => out({ ev: 'spawn', pos: bot.entity.position, version: bot.version }))
bot.on('kicked', (r) => out({ ev: 'kicked', r: JSON.stringify(r) }))
bot.on('error', (e) => out({ ev: 'error', e: String(e) }))
bot.on('end', (r) => { out({ ev: 'end', r }); process.exit(0) })
bot.on('health', () => out({ ev: 'health', health: bot.health, food: bot.food }))
bot.on('message', (m) => { const t = m.toString(); if (t.trim()) out({ ev: 'chat', t, json: m.json }) })

const rl = readline.createInterface({ input: process.stdin })
rl.on('line', async (line) => {
  const a = line.trim().split(/\s+/)
  const cmd = a[0]
  try {
    if (cmd === 'axiom' && axiom) axiom(a)
    else if (cmd === 'give') {
      const Item = require(path.join(root, 'prismarine-item'))(bot.version)
      const it = bot.registry.itemsByName[a[1]]
      await bot.creative.setInventorySlot(36, new Item(it.id, Math.min(64, it.stackSize))); out({ ev: 'give', item: a[1] })
    } else if (cmd === 'place') { // place rx ry rz dx dy dz
      await bot.equip(bot.inventory.slots[36].type, 'hand').catch(() => {})
      const ref = bot.blockAt(new Vec3(+a[1], +a[2], +a[3]))
      await bot.placeBlock(ref, new Vec3(+a[4], +a[5], +a[6])); out({ ev: 'placed', ref: a.slice(1, 4), face: a.slice(4, 7) })
    } else if (cmd === 'dig') {
      const b = bot.blockAt(new Vec3(+a[1], +a[2], +a[3])); await bot.dig(b, true); out({ ev: 'dug', name: b.name })
    } else if (cmd === 'block') {
      const b = bot.blockAt(new Vec3(+a[1], +a[2], +a[3])); out({ ev: 'block', pos: a.slice(1, 4).join(','), name: b ? b.name : null, stateId: b ? b.stateId : null, properties: b ? b.getProperties() : null })
    } else if (cmd === 'chat') { bot.chat(line.slice(5)); out({ ev: 'chat_sent' }) }
    else if (cmd === 'sample') {
      const cx=+a[1], cz=+a[2], sy=+a[3], hash=crypto.createHash('sha256'), counts={}; let missing=0
      for(let i=0;i<4096;i++) {
        const b=bot.blockAt(new Vec3(cx*16+(i&15),sy*16+(i>>8),cz*16+((i>>4)&15)))
        if(!b) { missing++; continue }
        counts[b.name]=(counts[b.name]||0)+1
        const props=b.getProperties(), keys=Object.keys(props).sort()
        const state='minecraft:'+b.name+(keys.length ? '['+keys.map(k=>k+'='+props[k]).join(',')+']' : '')
        hash.update(state+'\n')
      }
      out({ev:'sample',cx,cz,sy,hash:hash.digest('hex'),missing,counts})
    } else if (cmd === 'health') out({ev:'health_now', health:bot.health, food:bot.food})
    else if (cmd === 'pos') out({ ev: 'pos', pos: bot.entity.position, gm: bot.game.gameMode })
    else if (cmd === 'stats') out({ ev: 'stats', ready, received: { ...received, previews: Object.fromEntries(Object.entries(received.previews).map(([k, v]) => [k, { kind: v.kind, parts: v.parts, total: v.total, seen: v.seen.size }])) } })
    else if (cmd === 'entities') out({ ev: 'entities', displays: Object.values(bot.entities).filter((e) => /display/.test(e.name || e.displayName || '')).length, ids: Object.values(bot.entities).map((e) => e.id), names: [...new Set(Object.values(bot.entities).map((e) => e.name))] })
    else if (cmd === 'interact_uuid') {
      const entity = Object.values(bot.entities).find((e) => e.uuid === a[1])
      if (!entity) throw new Error('entity UUID not visible: ' + a[1])
      await bot.activateEntity(entity); out({ev:'entity_interacted', uuid:entity.uuid, id:entity.id, player:bot.entity.position, entity:entity.position, held:bot.heldItem && {name:bot.heldItem.name, components:bot.heldItem.components}})
    }
    else if (cmd === 'use') { await bot.equip(bot.inventory.slots[36], 'hand'); await bot.activateBlock(bot.blockAt(new Vec3(+a[1], +a[2], +a[3])), new Vec3(0, 1, 0)); out({ev:'used'}) }
    else if (cmd === 'place_entity') {
      const block = bot.blockAt(new Vec3(+a[1], +a[2], +a[3]))
      if (version === '26.2') { await bot._genericPlace(block, new Vec3(0, 1, 0), {forceLook:true}); out({ev:'entity_place_sent'}) }
      else { const entity = await bot.placeEntity(block, new Vec3(0, 1, 0)); out({ev:'entity_placed', id:entity.id, name:entity.name}) }
    }
    else if (cmd === 'window') { const w = bot.currentWindow; out({ ev: 'window', opened: !!w, title: w && String(w.title), slots: w ? w.slots.filter((x) => x).length : 0, size: w ? w.slots.length : 0 }) }
    else if (cmd === 'click') { const w = bot.currentWindow; if (!w) throw new Error('no window'); await bot.clickWindow(+a[1], +(a[2] || 0), +(a[3] || 0)); out({ ev: 'clicked', slot: +a[1] }) }
    else if (cmd === 'quit') { bot.quit(); setTimeout(() => process.exit(0), 300) }
    else out({ ev: 'unknown', cmd })
  } catch (e) { out({ ev: 'cmd_error', cmd, e: String(e) }) }
})
