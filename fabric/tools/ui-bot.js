// 真 dedicated 的 UI 協定／撤權驗收；不建立 Minecraft client 實例。
const path = require('path')
const mineflayer = require(path.resolve(__dirname, '../../.work/bot/node_modules/mineflayer'))
const [port, version, username] = process.argv.slice(2)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: +port, username, version, auth: 'offline', viewDistance: 'tiny' })
const out = value => console.log('BOT ' + JSON.stringify(value))
const vi = n => { const a = []; do { const b = n & 127; n >>>= 7; a.push(b | (n ? 128 : 0)) } while (n); return Buffer.from(a) }
const str = text => { const bytes = Buffer.from(text); return Buffer.concat([vi(bytes.length), bytes]) }
function hello(data) {
  const head = Buffer.from(data.subarray(0, 11)), caps = ['fabric-ui-v1', 'outline', 'ghost-render', 'merge-regions-v1']
  bot._client.write('custom_payload', { channel: 'worldgit:hello', data: Buffer.concat([head, vi(caps.length), ...caps.map(str), Buffer.alloc(16)]) })
  out({ ev: 'hello_replied' })
}
bot.once('login', () => bot._client.write('custom_payload', { channel: 'minecraft:register', data: Buffer.from('worldgit:hello\0worldgit:ui') }))
bot.once('spawn', () => out({ ev: 'spawn', pos: bot.entity.position }))
bot._client.on('custom_payload', packet => {
  if (packet.channel === 'worldgit:hello') hello(packet.data)
  else if (packet.channel === 'worldgit:ui') out({ ev: 'ui', hex: packet.data.toString('hex') })
})
bot.on('message', message => { const text = message.toString(); if (text.trim()) out({ ev: 'chat', t: text, json: message.json }) })
bot.on('kicked', reason => out({ ev: 'kicked', r: String(reason) }))
bot.on('error', error => out({ ev: 'error', e: String(error) }))
bot.on('end', reason => { out({ ev: 'end', r: reason }); process.exit(0) })
require('readline').createInterface({ input: process.stdin }).on('line', line => {
  try {
    if (line.startsWith('chat ')) { bot.chat(line.slice(5)); out({ ev: 'chat_sent' }) }
    else if (line.startsWith('ui ')) { bot._client.write('custom_payload', { channel: 'worldgit:ui', data: Buffer.from(line.slice(3), 'hex') }); out({ ev: 'ui_sent' }) }
    else if (line === 'quit') bot.quit()
    else throw Error('unsupported ui-bot command')
  } catch (error) { out({ ev: 'cmd_error', e: String(error) }) }
})
