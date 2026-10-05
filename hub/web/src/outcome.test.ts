import { describe, it, expect } from 'vitest'
import { progressText, resultMessage, browserReport, type Result } from './outcome.ts'
import { segments, type GraphNode } from './graph.ts'
describe('Phase 5 共用呈現',()=> {
  it('所有完成狀態都有明確文字',()=> {
    for(const status of ['SUCCESS','NO_OP','PARTIAL','FAILED','CANCELLED'] as const) {
      const text=resultMessage({status,summary:{message:'測試'},elapsedMillis:1200} as Result)
      expect(text).toContain('測試');expect(text).toContain('1.2 秒');expect(text).not.toContain('undefined')
    }
  })
  it('未知總量、維度、計數及 ETA',()=> {
    const event={operationId:'id',operation:'merge',dimension:{value:'minecraft:the_nether'},phase:'assemble',completed:5,total:null,unit:'REGION',remainingMillis:null,cancellable:true}
    expect(progressText(event)).toContain('總量未知');expect(progressText(event)).toContain('the_nether')
    expect(progressText({...event,total:10,remainingMillis:2500})).toContain('50% · 5/10 REGION · 剩餘約 3 秒')
  })
  it('瀏覽器後備報告遮罩 URL、PAT 與秘密',()=> {
    const text=browserReport('https://name:password@example.org token=secret ghp_somepat')
    expect(text).toContain('[REDACTED]');expect(text).not.toContain('name:password');expect(text).not.toContain('somepat');expect(text).toContain('UTC=')
  })
  it('core lane 在跨列時保持連接',()=> {
    const a={id:'a',before:['a','c'],after:['b','c'],edges:[{parent:'b',fromLane:0,toLane:0}]} as GraphNode
    const b={before:['b','c']} as GraphNode
    expect(segments(a,b)).toEqual([[1,0,1,.5],[0,0,0,.5],[0,.5,0,1],[1,.5,1,1]])
    expect(segments(a)).toEqual([])
  })
})
