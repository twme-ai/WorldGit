// Keep normal regression bots free of Axiom handshake traffic.
process.env.WG_AXIOM_BOT = '1'
require('./wgbot.js')
