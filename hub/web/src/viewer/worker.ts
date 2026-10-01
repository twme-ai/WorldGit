// 網格生成 Worker：init（載入資源包）→ states/biomes（增量同步表）→ mesh（回傳頂點緩衝，Transferable）。
import { Mesher, type MeshRequest, type MeshResult } from './mesher.ts'
import { PackResources, fetchPack, patchBlockColors } from './resources.ts'

export type WorkerIn =
  | { type: 'init'; base: string }
  | { type: 'states'; from: number; list: string[] }
  | { type: 'biomes'; from: number; list: string[] }
  | { type: 'mesh'; req: MeshRequest }
export type WorkerOut =
  | { type: 'ready'; rects: Float32Array; ms: number }
  | { type: 'mesh'; result: MeshResult }
  | { type: 'error'; message: string; id?: number }

let mesher: Mesher | null = null
const pending: WorkerIn[] = []
let initializing = false

async function handle(m: WorkerIn) {
  if (m.type === 'init') {
    initializing = true
    try {
      const t0 = performance.now()
      patchBlockColors()
      const pack = await fetchPack(m.base)
      mesher = new Mesher(new PackResources(pack))
      const rects = Mesher.rectTable(pack)
      ;(self as unknown as Worker).postMessage({ type: 'ready', rects, ms: performance.now() - t0 } satisfies WorkerOut, [rects.buffer])
    } catch (e) {
      ;(self as unknown as Worker).postMessage({ type: 'error', message: String(e) } satisfies WorkerOut)
    }
    initializing = false
    for (const p of pending.splice(0)) await handle(p)
    return
  }
  if (!mesher) { pending.push(m); return }
  if (initializing) { pending.push(m); return }
  try {
    if (m.type === 'states') mesher.setStates(m.from, m.list)
    else if (m.type === 'biomes') mesher.setBiomes(m.from, m.list)
    else if (m.type === 'mesh') {
      const result = mesher.mesh(m.req)
      const transfer = Object.values(result.layers) as ArrayBuffer[]
      ;(self as unknown as Worker).postMessage({ type: 'mesh', result } satisfies WorkerOut, transfer)
    }
  } catch (e) {
    ;(self as unknown as Worker).postMessage({ type: 'error', message: String(e), id: m.type === 'mesh' ? m.req.id : undefined } satisfies WorkerOut)
  }
}

self.onmessage = (e: MessageEvent<WorkerIn>) => { void handle(e.data) }
