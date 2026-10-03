import { api, dimLabel, getJson, sendJson, type CommitInfo } from '../api.ts'
import { type Choice, type MergeReport } from '../merge.ts'
import { navigate } from '../router.ts'
import { session } from '../session.ts'
import { h, link, toast } from '../ui.ts'
import { type Viewer } from '../viewer/viewer.ts'
import { mergePage } from './merge.ts'

interface Page<T> { items: T[]; offset: number; limit: number; hasMore: boolean }
interface Pull { id: string; number: number; authorId: string; author: string; source: string; target: string; title: string; description: string; status: string; fingerprint: string; selectionsInvalidated: boolean }
interface Detail { pr: Pull; preview: MergeReport | null; choices: Record<number, Choice>; reviews: { username: string; decision: string }[]; mergeability: string; approvals: number; requiredReviews: number; commits: CommitInfo[]; commitsTruncated: boolean }
interface Pin { dimension: string; x: number; y: number; z: number; maxX?: number; maxY?: number; maxZ?: number }
interface Comment { id: string; prId: string; userId: string; username: string; parentId: string | null; body: string; pin: Pin | null; deleted: boolean }
interface Release { id: string; tag: string; title: string; body: string; commits: Record<string, string> }
const base = (o: string, w: string) => `/api/v1/worlds/${o}/${w}`
const field = (label: string, value = '', type = 'text') => h('label', {}, label, h('input', { 'aria-label': label, value, type, required: true }))
const val = (el: HTMLElement) => el.querySelector('input')!.value
function crumbs(root: HTMLElement, o: string, w: string, title: string) { root.append(h('div', { class: 'crumbs' }, link('/', '世界'), ' / ', link(`/${o}/${w}`, `${o}/${w}`)), h('h1', {}, title)) }
const error = (e: unknown) => toast(e instanceof Error ? e.message : String(e), 'error')
function paging<T>(root: HTMLElement, page: Page<T>, path: string) {
  const join = path.includes('?') ? '&' : '?'
  root.append(h('nav', { class: 'row' }, page.offset > 0 ? link(`${path}${join}offset=${Math.max(0, page.offset-page.limit)}`, '上一頁', 'btn') : null, page.hasMore ? link(`${path}${join}offset=${page.offset+page.limit}`, '下一頁', 'btn') : null))
}
export async function pullsPage(root: HTMLElement, o: string, w: string) {
  crumbs(root, o, w, 'Pull Requests')
  try {
    const q = new URLSearchParams(location.search), status = q.get('status') ?? 'open'
    const [page, branches] = await Promise.all([getJson<Page<Pull>>(`${base(o,w)}/pulls?status=${status}&offset=${q.get('offset') ?? 0}`), api.branches(o,w)])
    root.append(h('nav', { class: 'row' }, ...['open','merged','closed'].map(s => link(`/${o}/${w}/pulls?status=${s}`, s, 'btn'))))
    for (const p of page.items) root.append(h('article', { class: 'card' }, link(`/${o}/${w}/pulls/${p.id}`, `#${p.number} ${p.title}`), h('p', {}, `${p.source} → ${p.target} · ${p.author} · ${p.status}`)))
    paging(root,page,`/${o}/${w}/pulls?status=${status}`)
    if (session.me.username) {
      const select = (label: string, initial: string) => h('label', {}, label, h('select', { 'aria-label': label }, ...branches.branches.map(b => h('option', { value: b.name, selected: b.name===initial }, b.name))))
      const source = select('來源分支', q.get('source') ?? branches.branches.find(b=>b.name!==branches.defaultBranch)?.name ?? ''), target = select('目標分支', branches.defaultBranch ?? 'main'), title = field('PR 標題'), description = h('textarea', { 'aria-label': 'PR 描述', maxlength: 16000 })
      root.append(h('form', { class: 'card stack', onSubmit: async (e: Event) => { e.preventDefault(); try {
        const p = await sendJson<Pull>(`${base(o,w)}/pulls`, 'POST', { source: source.querySelector('select')!.value, target: target.querySelector('select')!.value, title: val(title), description: description.value }); navigate(`/${o}/${w}/pulls/${p.id}`)
      } catch (ex) { error(ex) } } }, h('h2', {}, '建立 Pull Request'), source,target,title,h('label', {}, '描述',description),h('button', { type:'submit',class:'primary' }, '建立 PR')))
    }
  } catch (e) { root.append(h('p', { class: 'err' }, String(e))) }
}
export async function pullPage(root: HTMLElement, o: string, w: string, id: string): Promise<() => void> {
  let dispose: (() => void) | undefined, disposed = false, viewer: Viewer | null = null, dimension = '', picked: Pin | null = null
  const cleanup = () => { disposed = true; dispose?.() }
  const endpoint = `${base(o,w)}/pulls/${id}`
  try {
    let detail = await getJson<Detail>(endpoint)
    if (disposed) return cleanup
    crumbs(root,o,w,`#${detail.pr.number} ${detail.pr.title}`)
    root.append(h('p', {}, `${detail.pr.author} · ${detail.pr.source} → ${detail.pr.target}`), h('p', { class: 'pr-description' }, detail.pr.description))
    const state = h('div', { class: 'card' }), previews = h('div'), discussion = h('div', { class: 'card' }), commits = h('details', { class: 'card' }, h('summary', {}, `來源提交（${detail.commits.length}${detail.commitsTruncated ? '+' : ''}）`))
    for (const c of detail.commits) commits.append(h('p', {}, `${c.dimension} · ${c.id.slice(0,8)} · ${c.message.split('\n')[0]} · ${c.author.name}`))
    root.append(state,commits,previews,discussion)
    const statusText: Record<string,string> = { ff:'可快轉（將建立整合提交）',clean:'可合併',conflicts:'衝突需選擇', 'needs-review':'需審核','changes-requested':'要求修改',unmergeable:'無法合併',merged:'已合併',closed:'已關閉' }
    const renderState = () => {
      state.replaceChildren(h('div', {}, h('p', { role: 'status' }, `${statusText[detail.mergeability] ?? detail.mergeability} · 審核 ${detail.approvals}/${detail.requiredReviews}`), detail.pr.selectionsInvalidated ? h('p', { class: 'notice' }, '分支 tip 已變動，舊選擇與審核已作廢；請重新檢查。') : null,
        ...detail.reviews.map(r=>h('p', {}, `${r.username}：${r.decision}`))))
      if (detail.pr.status==='open' && session.me.username) {
        for (const [label,decision] of [['核准','approve'],['要求修改','request-changes']]) state.append(h('button', { onClick: async () => { try { detail=await sendJson<Detail>(`${endpoint}/reviews`,'POST',{fingerprint:detail.pr.fingerprint,decision});renderState() }catch(e){error(e)} } }, label))
        state.append(h('button', { class:'primary',disabled:!['ff','clean'].includes(detail.mergeability),onClick:async()=>{try{await sendJson(`${endpoint}/merge`,'POST',{fingerprint:detail.pr.fingerprint});navigate(`/${o}/${w}/pulls/${id}`,true)}catch(e){error(e)}} },'合併 PR'), h('button',{onClick:async()=>{try{await sendJson(endpoint,'PATCH',{status:'closed'});navigate(`/${o}/${w}/pulls/${id}`,true)}catch(e){error(e)}}},'關閉 PR'),
          h('button',{onClick:async()=>{const title=prompt('PR 標題',detail.pr.title),description=prompt('PR 描述',detail.pr.description);if(title===null||description===null)return;try{await sendJson(endpoint,'PATCH',{title,description});navigate(`/${o}/${w}/pulls/${id}`,true)}catch(e){error(e)}}},'編輯 PR'))
      }
    }
    let comments: Comment[] = [], commentOffset = 0
    const bounds = (p: Pin): [number,number,number,number,number,number] => [p.x,p.y,p.z,p.maxX??p.x,p.maxY??p.y,p.maxZ??p.z]
    const focus = (c: Comment) => { if (!c.pin) return; if (c.pin.dimension!==dimension) { navigate(`/${o}/${w}/pulls/${id}?dim=${encodeURIComponent(c.pin.dimension)}&pin=${c.id}`,true);return } viewer?.focusBox(bounds(c.pin)); document.getElementById(`comment-${c.id}`)?.scrollIntoView({block:'nearest'}) }
    const pins = () => { viewer?.setCommentPins(comments.filter(c=>c.pin && c.pin.dimension===dimension && !c.deleted).map(c=>({id:c.id,bounds:bounds(c.pin!)}))); if(viewer)viewer.onPin=id=>{const c=comments.find(c=>c.id===id);if(c)focus(c)} }
    const pinStatus = h('p',{class:'small',role:'status'},'可點選 3D 方塊釘選座標，或手動輸入座標。')
    const loadComments = async () => {
      const page=await getJson<Page<Comment>>(`${base(o,w)}/comments?pr=${id}&offset=${commentOffset}`)
      if(disposed)return
      if(commentOffset===0)comments=[];comments.push(...page.items)
      discussion.replaceChildren(h('h2',{},'留言與座標釘選'))
      for(const c of comments) discussion.append(h('article',{class:'comment',id:`comment-${c.id}`},h('b',{},c.username),c.parentId?h('small',{},` 回覆 ${c.parentId.slice(0,8)}`):null,h('p',{class:'comment-body'},c.deleted?'留言已刪除':c.body), c.pin?h('button',{class:'link',onClick:()=>focus(c)},`📍 ${dimLabel(c.pin.dimension)} (${c.pin.x}, ${c.pin.y}, ${c.pin.z})`):null,
        !c.deleted&&session.me.username?h('div',{class:'row'},h('button',{onClick:()=>postComment(c.id)},'回覆'),h('button',{onClick:async()=>{const body=prompt('編輯留言',c.body);if(body===null)return;try{await sendJson(`${base(o,w)}/comments/${c.id}`,'PATCH',{body});commentOffset=0;await loadComments()}catch(e){error(e)}}},'編輯'),h('button',{onClick:async()=>{try{await sendJson(`${base(o,w)}/comments/${c.id}`,'DELETE');commentOffset=0;await loadComments()}catch(e){error(e)}}},'刪除')):null))
      if(page.hasMore)discussion.append(h('button',{onClick:async()=>{commentOffset+=page.limit;await loadComments()}},'載入更多留言'))
      if(session.me.username)discussion.append(pinStatus,commentForm)
      pins();const focusId=new URLSearchParams(location.search).get('pin'),c=comments.find(c=>c.id===focusId);if(c)focus(c)
    }
    const body=h('textarea',{'aria-label':'留言內容',required:true,maxlength:8000}),dim=field('釘選維度','minecraft:overworld'),x=field('釘選 X','','number'),y=field('釘選 Y','','number'),z=field('釘選 Z','','number'),range=field('範圍上限 X,Y,Z（選填）')
    for(const f of [dim,x,y,z,range])f.querySelector('input')!.required=false
    const postComment = async (parentId?: string) => { const text=parentId?prompt('回覆內容'):body.value;if(!text)return;try{
      let pin: Pin | null = null
      if(!parentId && val(x)!=='' && val(y)!=='' && val(z)!=='') { pin={dimension:val(dim),x:Number(val(x)),y:Number(val(y)),z:Number(val(z))};if(val(range)){const max=val(range).split(',').map(Number);if(max.length!==3||!max.every(Number.isInteger))throw new Error('範圍格式為 X,Y,Z');[pin.maxX,pin.maxY,pin.maxZ]=max} }
      await sendJson(`${endpoint}/comments`,'POST',{body:text,parentId,pin});body.value='';commentOffset=0;await loadComments()
    }catch(e){error(e)} }
    const commentForm=h('form',{class:'stack',onSubmit:(e:Event)=>{e.preventDefault();void postComment()}},h('label',{},'留言內容',body),h('div',{class:'row'},dim,x,y,z,range),h('button',{type:'button',onClick:()=>{picked=null;for(const f of [x,y,z,range])f.querySelector('input')!.value='';pinStatus.textContent='一般留言'}},'清除釘選'),h('button',{type:'submit'},'送出留言'))
    renderState()
    if(detail.preview) dispose=await mergePage(previews,o,w,`${detail.pr.target}...${detail.pr.source}`,{
      report:detail.preview,choices:detail.choices,
      onChoices:async choices=>{detail=await sendJson<Detail>(`${endpoint}/choices`,'PUT',{fingerprint:detail.pr.fingerprint,choices});renderState()},
      onPick:(p,d)=>{picked=p?{dimension:d,...p}:null;if(picked){dim.querySelector('input')!.value=d;x.querySelector('input')!.value=String(picked.x);y.querySelector('input')!.value=String(picked.y);z.querySelector('input')!.value=String(picked.z);pinStatus.textContent=`釘選 ${dimLabel(d)} (${p!.x}, ${p!.y}, ${p!.z})`}},
      onViewer:(v,d)=>{viewer=v;dimension=d;pins()}
    })
    if(disposed){dispose?.();return cleanup}
    await loadComments()
    if(!detail.preview && detail.pr.status==='merged')root.append(link(`/${o}/${w}/compare/${detail.pr.source}...${detail.pr.target}`,'檢視合併後 3D diff','btn'))
  } catch(e){root.append(h('p',{class:'err'},String(e)))}
  return cleanup
}
export async function releasesPage(root: HTMLElement,o:string,w:string){
  crumbs(root,o,w,'Releases')
  try{const page=await getJson<Page<Release>>(`${base(o,w)}/releases?offset=${new URLSearchParams(location.search).get('offset')??0}`)
    for(const r of page.items)root.append(h('article',{class:'card'},link(`/${o}/${w}/releases/${r.id}`,`${r.tag} · ${r.title}`)))
    paging(root,page,`/${o}/${w}/releases`)
    if(session.me.username){const tag=field('Release tag'),title=field('Release 標題'),body=h('textarea',{'aria-label':'Release 說明'})
      root.append(h('form',{class:'card stack',onSubmit:async(e:Event)=>{e.preventDefault();try{const r=await sendJson<Release>(`${base(o,w)}/releases`,'POST',{tag:val(tag),title:val(title),body:body.value});navigate(`/${o}/${w}/releases/${r.id}`)}catch(e){error(e)}}},h('h2',{},'建立 Release'),tag,title,body,h('button',{type:'submit'},'發布 Release')))}
  }catch(e){root.append(h('p',{class:'err'},String(e)))}
}
export async function releasePage(root:HTMLElement,o:string,w:string,id:string){try{const r=await getJson<Release>(`${base(o,w)}/releases/${id}`);crumbs(root,o,w,r.title);root.append(h('p',{},r.tag),h('p',{},r.body),h('a',{href:`${base(o,w)}/releases/${id}/zip`,class:'btn primary'},'下載世界 ZIP'),h('p',{class:'small'},'ZIP 可解壓直接開啟世界；不含玩家資料與 repo 歷史。'))}catch(e){root.append(h('p',{class:'err'},String(e)))}}
export async function permissionsPage(root:HTMLElement,o:string,w:string){
  crumbs(root,o,w,'權限、分支保護與 Webhook')
  try{
    const endpoint=base(o,w),permissions=await getJson<{users:unknown[];teams:unknown[];public:boolean}>(`${endpoint}/permissions`)
    const submit=(title:string,fields:HTMLElement[],label:string,action:()=>Promise<unknown>)=>h('form',{class:'card stack',onSubmit:async(e:Event)=>{e.preventDefault();try{await action();toast('已儲存');navigate(`/${o}/${w}/settings`,true)}catch(e){error(e)}}},h('h2',{},title),...fields,h('button',{type:'submit'},label))
    root.append(h('pre',{},JSON.stringify(permissions,null,2)),h('button',{onClick:async()=>{try{await sendJson(`${endpoint}/visibility`,'PUT',{isPublic:!permissions.public});navigate(`/${o}/${w}/settings`,true)}catch(e){error(e)}}},permissions.public?'改為私人世界':'改為公開世界'))
    const username=field('使用者'),role=field('角色 read/write/admin/none','read')
    root.append(submit('個人授權',[username,role],'授權',()=>sendJson(`${endpoint}/permissions/users/${encodeURIComponent(val(username))}`,'PUT',{role:val(role)})))
    const team=field('團隊名稱'),teamRole=field('團隊角色','read')
    root.append(submit('團隊授權',[team,teamRole],'授予團隊',()=>sendJson(`${endpoint}/permissions/teams/${encodeURIComponent(val(team))}`,'PUT',{role:val(teamRole)})))
    const rules=await getJson<{branch:string;prOnly:boolean;reviews:number}[]>(`${endpoint}/protected-branches`)
    for(const rule of rules)root.append(h('p',{},`${rule.branch} · PR only ${rule.prOnly} · 審核 ${rule.reviews}`,h('button',{onClick:async()=>{try{await sendJson(`${endpoint}/protected-branches?branch=${encodeURIComponent(rule.branch)}`,'DELETE');navigate(`/${o}/${w}/settings`,true)}catch(e){error(e)}}},'移除保護')))
    const branch=field('保護分支','main'),reviews=field('需要審核數','0','number'),prOnly=h('input',{type:'checkbox',checked:true,'aria-label':'只能經 PR 合併'})
    root.append(submit('受保護分支',[branch,reviews,h('label',{},prOnly,'只能經 PR 合併')],'設定保護',()=>sendJson(`${endpoint}/protected-branches`,'PUT',{branch:val(branch),prOnly:prOnly.checked,reviews:Number(val(reviews))})))
    const hooks=await getJson<Page<{id:string;url:string;events:string[];enabled:boolean}>>(`${endpoint}/webhooks`)
    for(const hook of hooks.items){const log=h('pre');root.append(h('article',{class:'card'},h('p',{},`${hook.url} · ${hook.events.join(',')}`),h('button',{onClick:async()=>{try{log.textContent=JSON.stringify(await getJson(`${endpoint}/webhooks/${hook.id}/deliveries`),null,2)}catch(e){error(e)}}},'投遞紀錄'),h('button',{onClick:async()=>{try{await sendJson(`${endpoint}/webhooks/${hook.id}`,'PUT',{...hook,secret:null,enabled:!hook.enabled});navigate(`/${o}/${w}/settings`,true)}catch(e){error(e)}}},hook.enabled?'停用':'啟用'),h('button',{onClick:async()=>{try{await sendJson(`${endpoint}/webhooks/${hook.id}`,'DELETE');navigate(`/${o}/${w}/settings`,true)}catch(e){error(e)}}},'刪除 Webhook'),log))}
    const url=field('Webhook URL','','url'),secret=field('Webhook secret（至少32字）','','password'),events=field('Webhook 事件','push,pr.opened,pr.merged,release')
    root.append(submit('新增 Webhook',[url,secret,events],'建立 Webhook',()=>sendJson(`${endpoint}/webhooks`,'POST',{url:val(url),secret:val(secret),events:val(events).split(',').map(s=>s.trim()),enabled:true})))
  }catch(e){root.append(h('p',{class:'err'},String(e)))}
}
export async function notificationsPage(root:HTMLElement){root.append(h('h1',{},'通知'));try{const page=await getJson<Page<{id:string;seen:boolean;event:{event:string;world:{owner:string;name:string};data:{pr?:string;release?:string}}}>>(`/api/v1/notifications?offset=${new URLSearchParams(location.search).get('offset')??0}`);for(const n of page.items){const e=n.event;root.append(h('article',{class:'card'},link(`/${e.world.owner}/${e.world.name}${e.data.pr?`/pulls/${e.data.pr}`:e.data.release?`/releases/${e.data.release}`:''}`,e.event),h('button',{disabled:n.seen,onClick:async()=>{try{await sendJson(`/api/v1/notifications/${n.id}/seen`,'PUT');navigate('/notifications',true)}catch(e){error(e)}}},n.seen?'已讀':'標為已讀')))}paging(root,page,'/notifications')}catch(e){root.append(h('p',{class:'err'},String(e)))}}
export function verifyPage(root:HTMLElement){const token=location.hash.slice(1);history.replaceState(null,'','/verify');root.append(h('h1',{},'信箱驗證'),h('button',{onClick:async()=>{try{await sendJson('/api/v1/auth/verify','POST',{token});toast('驗證成功，請登入');navigate('/login')}catch(e){error(e)}}},'確認驗證信箱'))}
