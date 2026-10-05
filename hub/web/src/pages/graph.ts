import { api, dimLabel, dimRepo, getJson, sendJson } from '../api.ts'
import { type Graph, segments } from '../graph.ts'
import { navigate } from '../router.ts'
import { h, link, errorBanner, showError } from '../ui.ts'
import { dimensionPicker } from './dimensions.ts'

interface GraphPage {dimension:string;graph:Graph;merges:{commit:string;id:string;number:number}[];defaultBranch:string}
export async function graphPage(root:HTMLElement,owner:string,world:string,repo?:string) {
  const q=new URLSearchParams(location.search),info=await api.world(owner,world)
  const dimension=info.dimensions?.find(d=>d.repo===repo)?.id ?? q.get('dim') ?? 'minecraft:overworld'
  const endpoint=`/api/v1/worlds/${owner}/${world}/dims/${dimRepo(dimension)}`
  root.append(h('div',{class:'crumbs'},link(`/${owner}/${world}`,`${owner}/${world}`),' / 分支圖'),h('h1',{},`${dimLabel(dimension)} · 分支圖`),dimensionPicker(info.dimensions ?? [],dimension,d=>navigate(`/${owner}/${world}/graph/${dimRepo(d)}?all=${q.get('all') ?? 'true'}`)))
  const all=q.get('all')!=='false'
  root.append(h('label',{},h('input',{type:'checkbox',checked:all,'aria-label':'所有 refs（--all）',onChange:(e:Event)=>navigate(`/${owner}/${world}/graph/${dimRepo(dimension)}?all=${(e.target as HTMLInputElement).checked}`)}),'所有分支、tag 與 remote tracking（--all）'),h('button',{onClick:()=>{document.documentElement.dataset.theme=document.documentElement.dataset.theme==='light'?'dark':'light'}},'切換亮／暗色'))
  try {
    const page=await getJson<GraphPage>(`${endpoint}/graph?all=${all}&limit=${q.get('limit') ?? 200}`)
    root.append(h('p',{class:'muted'},`預設分支：${page.defaultBranch} · ${page.graph.nodes.length} commits · ${page.graph.lanes} lanes`))
    if(page.graph.truncated)root.append(h('p',{class:'notice',role:'status'},'歷史已截斷；本頁最多 10,000 列，遍歷最多 20,000 commits。'))
    const host=h('div',{class:'graph-scroll',tabindex:0,role:'region','aria-label':'分支圖，可水平捲動'})
    const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg')
    const rowHeight=64,graphWidth=Math.max(64,page.graph.lanes*26+20)
    svg.setAttribute('width',String(graphWidth));svg.setAttribute('height',String(Math.max(1,page.graph.nodes.length)*rowHeight));svg.setAttribute('aria-hidden','true')
    const list=h('ol',{class:'graph-list'})
    page.graph.nodes.forEach((node,index)=> {
      const x=(lane:number)=>16+lane*26,y=(row:number)=>index*rowHeight+26+row*rowHeight
      for(const [from,y0,to,y1] of segments(node,page.graph.nodes[index+1])) {
        const path=document.createElementNS(ns,'path');path.setAttribute('d',`M ${x(from)} ${y(y0)} L ${x(to)} ${y(y1)}`);path.setAttribute('class',`graph-edge lane-${from%6}`);svg.append(path)
      }
      const circle=document.createElementNS(ns,'circle');circle.setAttribute('cx',String(x(node.lane)));circle.setAttribute('cy',String(y(0)));circle.setAttribute('r','5');circle.setAttribute('class',`graph-node lane-${node.lane%6}`);svg.append(circle)
      const merge=page.merges.find(p=>p.commit===node.id)
      list.append(h('li',{'data-commit':node.id},h('div',{class:'row'},link(`/${owner}/${world}/commit/${dimRepo(dimension)}/${node.id}`,[h('code',{},node.id.slice(0,8)),' ',node.message.split('\n')[0] || '無訊息']),...node.labels.map(l=>h('span',{class:`badge graph-label ${l.kind}`},`${l.kind}: ${l.name}`)),merge?link(`/${owner}/${world}/pulls/${merge.id}`,`PR #${merge.number}`,'badge'):null),h('div',{class:'small muted'},`${node.author} · ${new Date(node.time).toLocaleString('zh-TW')}`)))
    })
    host.append(svg,list);root.append(host)
    if(['admin','owner'].includes(info.role ?? '')) {
      const branches=await api.branches(owner,world,null,dimension),select=h('select',{'aria-label':'預設分支'},...branches.branches.map(b=>h('option',{value:b.name,selected:b.name===page.defaultBranch},b.name)))
      root.append(h('div',{class:'row'},select,h('button',{onClick:async()=>{try{await sendJson(`${endpoint}/default-branch`,'PUT',{branch:select.value});navigate(location.pathname+location.search,true)}catch(e){showError(e)}}},'設定此維度預設分支')))
    }
  }catch(e){root.append(errorBanner(e))}
}
