/** 共用 core operation 的網頁 DTO；dimension 在中性事件中是 DimensionId。 */
export interface ErrorReport { code: string; operationId: string; operation: string; dimension: string; worldGitVersion: string; platformVersion: string; utc: string; message: string; text: string }
export interface Result { operationId: string; operation: string; status: 'SUCCESS'|'NO_OP'|'PARTIAL'|'FAILED'|'CANCELLED'; dimension: { value: string } | null; summary: { message?: string }; elapsedMillis: number; nextSteps: string[]; error: ErrorReport | null }
export interface Progress { operationId: string; operation: string; dimension: { value: string } | null; phase: string; completed: number; total: number | null; unit: string; remainingMillis: number | null; cancellable: boolean }
export interface Operation<T = unknown> { id: string; sequence: number; operation: string; events: Progress[]; result: Result | null; data: T; cancellable: boolean; download: boolean }
export function resultMessage(r: Result): string {
  const labels = { SUCCESS: '成功', NO_OP: '沒有變更', PARTIAL: '部分完成', FAILED: '失敗', CANCELLED: '已取消' }
  return `${labels[r.status]}：${r.summary.message ?? r.operation} · ${(r.elapsedMillis/1000).toFixed(1)} 秒`
}
export function progressText(p: Progress): string {
  const pct=p.total===null?'總量未知':`${p.total===0?100:Math.round(p.completed/p.total*100)}%`
  const eta=p.remainingMillis===null?'':` · 剩餘約 ${Math.ceil(p.remainingMillis/1000)} 秒`
  const phase=({queued:'等候工作','merge-preview':'計算合併預覽',merge:'計算合併',publish:'發布提交',index:'更新索引',webhook:'排程 Webhook',assemble:'組裝世界',zip:'壓縮 ZIP',complete:'完成','apply-terrain':'組裝地形','apply-entities':'組裝實體'} as Record<string,string>)[p.phase] ?? p.phase
  const unit=({CHUNK:'區塊',SECTION:'區段',OBJECT:'資料項',BYTES:'位元組',COMMIT:'提交'} as Record<string,string>)[p.unit] ?? p.unit
  return `${phase} · ${p.dimension?.value ?? '世界'} · ${pct} · ${p.completed}${p.total===null?'':`/${p.total}`} ${unit}${eta}`
}
export function browserReport(message: string): string {
  const safe=message.replace(/([a-z][\w+.-]*:\/\/)[^\s/@]+@/gi,'$1[REDACTED]@').replace(/\b(?:github_pat_|gh[pousr]_|wgt_)[\w-]+/gi,'[REDACTED]').replace(/((?:token|secret|password|authorization)\s*[:=]\s*)[^\n,;]+/gi,'$1[REDACTED]')
  return `code=browser operation=web id=${crypto.randomUUID()} dimension=all WorldGit=0.1.0-SNAPSHOT platform=Hub 0.1.0-SNAPSHOT UTC=${new Date().toISOString()}\n${safe.slice(0,7800)}`
}
