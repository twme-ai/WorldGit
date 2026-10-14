// Compare each native STREAM_CODEC sample with the client's full decoder/encoder.
const fs = require('fs')
const path = require('path')
const root = path.resolve(__dirname, '../../.work/bot/node_modules')
const serializer = require(path.join(root, 'minecraft-protocol/src/transforms/serializer'))
const proto = serializer.createDeserializer({state:'play', version:'26.3'}).proto
const samples = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
const result = samples.map(sample => {
  const bytes = Buffer.from(sample.hex, 'hex')
  const decoded = proto.parsePacketBuffer('SlotComponent', bytes)
  if (decoded.metadata.size !== bytes.length) throw new Error('Unread native bytes: ' + sample.name)
  const encoded = proto.createPacketBuffer('SlotComponent', decoded.data)
  if (!encoded.equals(bytes)) throw new Error('Native component wire mismatch: ' + sample.name)
  if ('minecraft:' + decoded.data.type !== sample.name) throw new Error('Component registry mismatch: ' + sample.name)
  return {name:sample.name, bytes:bytes.length, decoded:decoded.data, ok:true}
})
console.log(JSON.stringify({success:true, checks:result}, null, 2))
