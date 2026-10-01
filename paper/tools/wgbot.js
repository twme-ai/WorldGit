// WorldGit 驗收用 mineflayer 機器人：由 stdin 一行一個指令，輸出 "BOT {json}"。
// 用法：node wgbot.js <port> <version> <name> [mod]
//   mod：模擬安裝了 WorldGit 客戶端模組——註冊 worldgit:* channel、回覆 hello、統計收到的 status/diff 封包。
// mineflayer 安裝在 .work/bot/node_modules（26.2 靠 experiments/03 的版本 hack）。
const path = require('path')
const root = path.resolve(__dirname, '../../.work/bot/node_modules')
const mineflayer = require(path.join(root, 'mineflayer'))
const { Vec3 } = require(path.join(root, 'vec3'))
const readline = require('readline')

const [port, version, name, mode] = process.argv.slice(2)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: +port, username: name, version, auth: 'offline', viewDistance: 'tiny' })
const out = (o) => console.log('BOT ' + JSON.stringify(o))
const received = { hello: 0, status: 0, diff: 0, clear: 0, statusEntries: 0, diffEntries: 0, bytes: 0, previews: {} }
let ready = false

function varint(buf, pos) { let n = 0, i = 0, b; do { b = buf[pos++]; n |= (b & 127) << (7 * i++); } while (b & 128); return [n, pos] }
function writeVarint(n) { const a = []; do { let b = n & 127; n >>>= 7; a.push(b | (n ? 128 : 0)) } while (n); return Buffer.from(a) }
function str(s) { const b = Buffer.from(s, 'utf8'); return Buffer.concat([writeVarint(b.length), b]) }

function encodeHello(version, nonce, caps) {
  const head = Buffer.alloc(3 + 8); head[0] = 2; head[1] = 0; head[2] = version; head.writeBigInt64BE(BigInt(nonce), 3)
  const palette = Buffer.alloc(16) // 色票內容對驗收無關
  return Buffer.concat([head, writeVarint(caps.length), ...caps.map(str), palette])
}

function onPayload(channel, data) {
  received.bytes += data.length
  if (channel === 'worldgit:hello' && mode === 'mod') {
    // [version][kind=0][version][nonce 8][caps...]
    const v = data[2]; const nonce = data.readBigInt64BE(3)
    received.hello++
    bot._client.write('custom_payload', { channel: 'worldgit:hello', data: encodeHello(v, nonce, ['ghost-render', 'outline', 'status-outline', 'section-palette-v2']) })
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
  } else if (channel === 'worldgit:clear') received.clear++
}

bot.once('login', () => {
  // channel 要先註冊，Paper 才會把 plugin message 送給客戶端（experiments/05 的結論）。
  if (mode === 'mod') bot._client.write('custom_payload', { channel: 'minecraft:register', data: Buffer.from(['worldgit:hello', 'worldgit:diff', 'worldgit:status', 'worldgit:clear'].join('\0')) })
})
bot._client.on('custom_payload', (packet) => { try { onPayload(packet.channel, packet.data) } catch (e) { out({ ev: 'payload_error', channel: packet.channel, e: String(e) }) } })
bot.once('spawn', () => out({ ev: 'spawn', pos: bot.entity.position, version: bot.version }))
bot.on('kicked', (r) => out({ ev: 'kicked', r: JSON.stringify(r) }))
bot.on('error', (e) => out({ ev: 'error', e: String(e) }))
bot.on('end', (r) => { out({ ev: 'end', r }); process.exit(0) })
bot.on('message', (m) => { const t = m.toString(); if (t.trim()) out({ ev: 'chat', t }) })

const rl = readline.createInterface({ input: process.stdin })
rl.on('line', async (line) => {
  const a = line.trim().split(/\s+/)
  const cmd = a[0]
  try {
    if (cmd === 'give') {
      const Item = require(path.join(root, 'prismarine-item'))(bot.version)
      const it = bot.registry.itemsByName[a[1]]
      await bot.creative.setInventorySlot(36, new Item(it.id, 64)); out({ ev: 'give', item: a[1] })
    } else if (cmd === 'place') { // place rx ry rz dx dy dz
      await bot.equip(bot.inventory.slots[36].type, 'hand').catch(() => {})
      const ref = bot.blockAt(new Vec3(+a[1], +a[2], +a[3]))
      await bot.placeBlock(ref, new Vec3(+a[4], +a[5], +a[6])); out({ ev: 'placed', ref: a.slice(1, 4), face: a.slice(4, 7) })
    } else if (cmd === 'dig') {
      const b = bot.blockAt(new Vec3(+a[1], +a[2], +a[3])); await bot.dig(b, true); out({ ev: 'dug', name: b.name })
    } else if (cmd === 'block') {
      const b = bot.blockAt(new Vec3(+a[1], +a[2], +a[3])); out({ ev: 'block', pos: a.slice(1, 4).join(','), name: b ? b.name : null })
    } else if (cmd === 'chat') { bot.chat(line.slice(5)); out({ ev: 'chat_sent' }) }
    else if (cmd === 'pos') out({ ev: 'pos', pos: bot.entity.position, gm: bot.game.gameMode })
    else if (cmd === 'stats') out({ ev: 'stats', ready, received: { ...received, previews: Object.fromEntries(Object.entries(received.previews).map(([k, v]) => [k, { kind: v.kind, parts: v.parts, total: v.total, seen: v.seen.size }])) } })
    else if (cmd === 'quit') { bot.quit(); setTimeout(() => process.exit(0), 300) }
    else out({ ev: 'unknown', cmd })
  } catch (e) { out({ ev: 'cmd_error', cmd, e: String(e) }) }
})
