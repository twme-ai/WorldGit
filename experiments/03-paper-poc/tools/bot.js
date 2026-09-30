// 最小 mineflayer 機器人：由 stdin 一行一個指令，輸出 "BOT {json}"。
// 用法：node bot.js <port> <version> [name]    （mineflayer 安裝在 .work/bot/node_modules）
const path = require('path')
const root = path.resolve(__dirname, '../../../.work/bot/node_modules')
const mineflayer = require(path.join(root, 'mineflayer'))
const { Vec3 } = require(path.join(root, 'vec3'))
const crypto = require('crypto')
const readline = require('readline')

const [port, version, name = 'WgBot'] = [process.argv[2], process.argv[3], process.argv[4]]
const bot = mineflayer.createBot({ host: '127.0.0.1', port: +port, username: name, version, auth: 'offline', viewDistance: 'normal' })
const out = (o) => console.log('BOT ' + JSON.stringify(o))
const counts = {}
const perChunk = {}
let recvChunkLoads = 0

bot.once('spawn', () => {
  out({ ev: 'spawn', pos: bot.entity.position, version: bot.version })
  bot._client.on('packet', (data, meta) => {
    if (meta.state !== 'play') return
    const n = meta.name
    if (['block_change', 'multi_block_change', 'map_chunk', 'update_light', 'tile_entity_data', 'unload_chunk', 'chunks_biomes', 'level_chunk_with_light'].includes(n)) {
      counts[n] = (counts[n] || 0) + 1
      let key = null
      if (n === 'block_change' && data.location) key = `${data.location.x >> 4},${data.location.z >> 4}`
      else if (n === 'multi_block_change') { const c = data.chunkCoordinates || data.chunkCoords; if (c) key = `${c.x},${c.z}` }
      else if (n === 'map_chunk' || n === 'update_light') key = `${data.x},${data.z}`
      if (key) { const k = n + '@' + key; perChunk[k] = (perChunk[k] || 0) + 1 }
    }
  })
})
bot.on('kicked', (r) => out({ ev: 'kicked', r: JSON.stringify(r) }))
bot.on('error', (e) => out({ ev: 'error', e: String(e) }))
bot.on('end', (r) => { out({ ev: 'end', r }); process.exit(0) })
bot.on('health', () => out({ ev: 'health', health: bot.health }))
bot.on('death', () => out({ ev: 'death' }))

function names(cx, sy, cz) {
  const h = crypto.createHash('sha256')
  let nulls = 0
  for (let y = 0; y < 16; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
    const b = bot.blockAt(new Vec3(cx * 16 + x, sy * 16 + y, cz * 16 + z))
    if (!b) nulls++
    h.update((b ? b.name : 'null') + ';')
  }
  return { hash: h.digest('hex').slice(0, 16), nulls }
}

const rl = readline.createInterface({ input: process.stdin })
rl.on('line', async (line) => {
  const a = line.trim().split(/\s+/)
  const cmd = a[0]
  try {
    if (cmd === 'hash') out({ ev: 'hash', tag: a[4] || '', ...names(+a[1], +a[2], +a[3]) })
    else if (cmd === 'names') { const arr = []; for (let y = 0; y < 16; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) { const b = bot.blockAt(new Vec3(+a[1] * 16 + x, +a[2] * 16 + y, +a[3] * 16 + z)); arr.push(b ? b.name : 'null') } out({ ev: 'names', tag: a[4] || '', names: arr.join(';') + ';' }) }
    else if (cmd === 'light') out({ ev: 'light', pos: a.slice(1, 4).join(','), light: bot.blockAt(new Vec3(+a[1], +a[2], +a[3])).light, sky: bot.blockAt(new Vec3(+a[1], +a[2], +a[3])).skyLight, name: bot.blockAt(new Vec3(+a[1], +a[2], +a[3])).name })
    else if (cmd === 'block') { const b = bot.blockAt(new Vec3(+a[1], +a[2], +a[3])); out({ ev: 'block', pos: a.slice(1, 4).join(','), name: b ? b.name : null }) }
    else if (cmd === 'give') { // creative：把物品放進第一個快捷欄
      const Item = require(path.join(root, 'prismarine-item'))(bot.version)
      const it = bot.registry.itemsByName[a[1]]
      await bot.creative.setInventorySlot(36, new Item(it.id, 64)); out({ ev: 'give', item: a[1] })
    }
    else if (cmd === 'place') { // place rx ry rz dx dy dz : 對參考方塊的某面放置
      await bot.equip(bot.inventory.slots[36].type, 'hand').catch(() => {})
      const ref = bot.blockAt(new Vec3(+a[1], +a[2], +a[3]))
      await bot.placeBlock(ref, new Vec3(+a[4], +a[5], +a[6])); out({ ev: 'placed' })
    }
    else if (cmd === 'dig') { const b = bot.blockAt(new Vec3(+a[1], +a[2], +a[3])); await bot.dig(b, true); out({ ev: 'dug', name: b.name }) }
    else if (cmd === 'chestput') { // chestput x y z item count : 開箱子並放入物品
      const b = bot.blockAt(new Vec3(+a[1], +a[2], +a[3]))
      const w = await bot.openContainer(b)
      await w.deposit(bot.registry.itemsByName[a[4]].id, null, +a[5])
      w.close(); out({ ev: 'chestput', ok: true })
    }
    else if (cmd === 'chat') { bot.chat(line.slice(5)); out({ ev: 'chat' }) }
    else if (cmd === 'pktstats') out({ ev: 'pktstats', counts, perChunk })
    else if (cmd === 'pktreset') { for (const k of Object.keys(counts)) delete counts[k]; for (const k of Object.keys(perChunk)) delete perChunk[k]; out({ ev: 'pktreset' }) }
    else if (cmd === 'pos') out({ ev: 'pos', pos: bot.entity.position, health: bot.health, gm: bot.game.gameMode })
    else if (cmd === 'quit') { bot.quit(); setTimeout(() => process.exit(0), 300) }
    else out({ ev: 'unknown', cmd })
  } catch (e) { out({ ev: 'cmd_error', cmd, e: String(e) }) }
})
