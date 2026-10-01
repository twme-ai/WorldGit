// REST API 型別與 fetch 輔助。型別對應後端 org.worldgit.hub.history.Dto。
export interface Person { name: string; email: string }
export interface CommitInfo {
  id: string; parents: string[]; tree: string; time: number
  author: Person; committer: Person; message: string; auto: boolean; source: string
  dataVersion: number; dimension: string; snapshot: string; coAuthors: string[]
}
export interface DimensionState { id: string; repo: string; head: string | null; headTime: number | null; commits: number }
export interface SnapshotRow {
  snapshot: string; time: number; message: string; author: Person; auto: boolean; source: string
  commits: Record<string, CommitInfo>; partial: boolean; missingDimensions: string[]; partialReason: string | null
}
export interface SnapshotPage { snapshots: SnapshotRow[]; nextBefore: number | null; declaredDimensions: string[] }
export interface EntityChangeInfo { uuid: string; kind: string; type: string; before: number[] | null; after: number[] | null }
/** [chunkX, chunkZ, 新增, 移除, 修改, flags(bit0 實體, bit1 biome)] */
export type ChangedChunk = [number, number, number, number, number, number]
export interface CommitDetail {
  commit: CommitInfo; parent: string | null; initial: boolean
  added: number; removed: number; modified: number; chunkCount: number; sectionCount: number
  entitiesAdded: number; entitiesRemoved: number; entitiesModified: number
  entityChanges: EntityChangeInfo[]; changedChunks: ChangedChunk[]; bounds: number[]
  metadataChanges: string[]; mcVersion: string
}
export interface WorldInfo {
  owner: string; name: string; displayName: string; description: string; public: boolean; createdAt: number
  dimensions?: DimensionState[]; latest?: SnapshotRow | null; role?: string
}
export interface TileRef { rx: number; rz: number; chunks: number; id: string }
export interface Palette { added: string; removed: string; modified: string; conflict: string }
export interface Palettes {
  default: Palette; colorblind: Palette
  symbols: Record<string, string>; styles: Record<string, string>
}
export interface Me { id?: string; username?: string; admin?: boolean }

const TOKEN_KEY = 'worldgit.token'
export function getToken(): string | null {
  try { return localStorage.getItem(TOKEN_KEY) } catch { return null }
}
export function setToken(t: string | null) {
  try { if (t) localStorage.setItem(TOKEN_KEY, t); else localStorage.removeItem(TOKEN_KEY) } catch { /* 私密視窗等情況沒有 localStorage */ }
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string) { super(message) }
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  const token = getToken()
  if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)
  const res = await fetch(path, { ...init, headers })
  if (!res.ok) {
    let msg = `HTTP ${res.status}`
    try { const j = await res.json(); if (j?.error) msg = j.error } catch { /* 非 JSON 錯誤 */ }
    throw new ApiError(res.status, msg)
  }
  return res
}

export async function getJson<T>(path: string): Promise<T> { return (await request(path)).json() as Promise<T> }
export async function getBuffer(path: string): Promise<ArrayBuffer> { return (await request(path)).arrayBuffer() }
export async function getBlob(path: string): Promise<Blob> { return (await request(path)).blob() }
export async function sendJson<T>(path: string, method: string, body?: unknown): Promise<T> {
  return (await request(path, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })).json() as Promise<T>
}

export const api = {
  me: () => getJson<{ user: Me }>('/api/v1/me'),
  login: (username: string, password: string) => sendJson<{ token: string; user: Me }>('/api/v1/auth/login', 'POST', { username, password }),
  worlds: () => getJson<WorldInfo[]>('/api/v1/worlds'),
  world: (o: string, w: string) => getJson<WorldInfo>(`/api/v1/worlds/${o}/${w}`),
  snapshots: (o: string, w: string, limit = 50, before?: number | null, auto = true) =>
    getJson<SnapshotPage>(`/api/v1/worlds/${o}/${w}/snapshots?limit=${limit}${before ? `&before=${before}` : ''}&auto=${auto}`),
  commit: (o: string, w: string, dimRepo: string, rev: string) => getJson<CommitDetail>(`/api/v1/worlds/${o}/${w}/dims/${dimRepo}/commits/${rev}`),
  tiles: (o: string, w: string, dimRepo: string, rev: string) => getJson<TileRef[]>(`/api/v1/worlds/${o}/${w}/dims/${dimRepo}/commits/${rev}/tiles`),
  palettes: () => getJson<Palettes>('/api/v1/diff-palettes'),
  tokens: () => getJson<{ id: string; name: string; kind: string; createdAt: number; expiresAt: number | null; lastUsedAt: number | null }[]>('/api/v1/tokens'),
  createToken: (name: string, expiresAt?: string) => sendJson<{ token: string }>('/api/v1/tokens', 'POST', { name, expiresAt }),
  deleteToken: (id: string) => sendJson<{ deleted: boolean }>(`/api/v1/tokens/${id}`, 'DELETE'),
  createWorld: (name: string, displayName: string, isPublic: boolean) => sendJson<WorldInfo>('/api/v1/worlds', 'POST', { name, displayName, isPublic }),
}

/** 維度 id（minecraft:the_nether）→ repo 目錄名（minecraft.the_nether），與後端 DimensionId.directoryName 一致。 */
export function dimRepo(id: string): string {
  const i = id.indexOf(':')
  const ns = id.slice(0, i).replaceAll('.', '%2E')
  const path = id.slice(i + 1).replaceAll('.', '%2E').replaceAll('/', '%2F')
  return `${ns}.${path}`
}
export function dimLabel(id: string): string {
  return ({ 'minecraft:overworld': '主世界', 'minecraft:the_nether': '地獄', 'minecraft:the_end': '終界' } as Record<string, string>)[id] ?? id
}
