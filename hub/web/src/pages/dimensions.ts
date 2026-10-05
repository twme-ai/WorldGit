import { dimLabel, type DimensionState } from '../api.ts'
import { h } from '../ui.ts'
export function dimensionPicker(dims:DimensionState[],selected:string,pick:(dimension:string)=>void) {
  return h('label',{},'維度',h('select',{'aria-label':'維度',onChange:(e:Event)=>pick((e.target as HTMLSelectElement).value)},...dims.filter(d=>d.head).map(d=>h('option',{value:d.id,selected:d.id===selected},`${dimLabel(d.id)} · ${d.id}`))))
}
