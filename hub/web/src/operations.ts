import { ApiError, getJson, sendJson } from './api.ts'
import { type Operation, type Result, progressText } from './outcome.ts'
import { h, notifyResult, showError } from './ui.ts'

/** SSE 失敗／30 秒逾時改為有界輪詢；讀取也會重新驗權限。 */
export async function runOperation<T>(worldEndpoint: string, body: unknown): Promise<Operation<T>> {
  const job=await sendJson<{id:string}>(`${worldEndpoint}/operations`,'POST',body)
  return observeOperation<T>(worldEndpoint,job.id)
}
export async function observeOperation<T>(worldEndpoint:string,id:string):Promise<Operation<T>> {
  const endpoint=`${worldEndpoint}/operations/${id}`
  const bar=h('progress',{'aria-label':'操作進度'}), label=h('p',{'aria-live':'polite'},'已開始，等待工作…')
  const cancel=h('button',{onClick:async()=>{try{await sendJson(`${endpoint}/cancel`,'POST');cancel.disabled=true}catch(e){showError(e)}}},'取消操作')
  const panel=h('div',{class:'operation-panel',role:'region','aria-label':'操作進度'},label,bar,cancel)
  document.body.append(panel)
  return new Promise((resolve,reject)=> {
    let source:EventSource|undefined,timer:ReturnType<typeof setTimeout>|undefined,done=false,polls=0,fallback=false
    const cleanup=()=>{done=true;source?.close();if(timer)clearTimeout(timer);cancel.hidden=true;panel.append(h('button',{onClick:()=>panel.remove()},'關閉進度'));setTimeout(()=>panel.remove(),10000)}
    const update=(op:Operation<T>)=> {
      const event=op.events.at(-1)
      if(event){label.textContent=progressText(event);if(event.total===null)bar.removeAttribute('value');else {bar.max=Math.max(1,event.total);bar.value=event.completed}}
      cancel.hidden=!op.cancellable
      if(op.result && !done){cleanup();notifyResult(op.result);if(['FAILED','PARTIAL','CANCELLED'].includes(op.result.status)){const error=new ApiError(422,op.result.error?.message ?? op.result.status,op.result.error ?? undefined);reject(error)}else resolve(op)}
    }
    const poll=async()=> {
      if(done)return
      if(++polls>1800){cleanup();reject(new Error('操作觀察逾時，請重新載入查看結果'));return}
      try{update(await getJson<Operation<T>>(endpoint));if(!done)timer=setTimeout(()=>void poll(),500)}catch(e){cleanup();showError(e);reject(e)}
    }
    if(typeof EventSource!=='undefined') {
      source=new EventSource(`${endpoint}/events`)
      source.addEventListener('operation',e=>{try{update(JSON.parse((e as MessageEvent).data) as Operation<T>)}catch(ex){cleanup();reject(ex)}})
      source.onerror=()=>{source?.close();if(!done && !fallback){fallback=true;void poll()}}
    }else void poll()
  })
}
export function downloadPrepared(endpoint: string, operation: Operation) {
  const a=h('a',{href:`${endpoint}/operations/${operation.id}/download`,download:`world-${operation.id}.zip`},'下載世界 ZIP')
  document.body.append(a);a.click();a.remove()
}
export function finishedResult(operation: Operation): Result | null {return operation.result}
