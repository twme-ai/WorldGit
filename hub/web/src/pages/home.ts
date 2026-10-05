import { errorBanner } from '../ui.ts'
import { showError } from '../ui.ts'
import { ApiError, api, getJson, sendJson, type WorldInfo } from '../api.ts'
import { navigate } from '../router.ts'
import { refreshUser, session } from '../session.ts'
import { copyText, fmtTime, h, link, toast } from '../ui.ts'

export async function homePage(root: HTMLElement) {
  const list = h('div', { class: 'grid' }, h('p', { class: 'empty' }, '載入中…'))
  root.append(h('div', { class: 'row' }, h('h1', {}, '世界'), h('span', { class: 'spacer' }), session.me.username ? h('button', { class: 'primary', onClick: () => newWorldDialog() }, '新增世界') : null), list)
  try {
    const worlds = await api.worlds()
    list.replaceChildren()
    if (!worlds.length) list.replaceWith(h('div', { class: 'card' },
      h('p', {}, session.me.username ? '還沒有世界。建立一個，或直接用 git 推送（Hub 會自動建立私人世界）：' : '沒有公開的世界。登入後可以建立或推送。'),
      session.me.username ? h('pre', { class: 'mono' }, `cd <伺服器>/.worldgit/<世界>/minecraft.overworld\ngit push ${location.origin}/git/${session.me.username}/<世界>/minecraft.overworld.git main`) : null))
    for (const w of worlds) list.append(worldCard(w))
  } catch (e) { list.replaceChildren(errorBanner(e)) }
}

function worldCard(w: WorldInfo) {
  return h('div', { class: 'card world-card' },
    h('h2', {}, link(`/${w.owner}/${w.name}`, `${w.owner} / ${w.name}`), h('span', { class: `badge ${w.public ? 'public' : ''}` }, w.public ? '公開' : '私人')),
    h('div', { class: 'muted' }, w.displayName !== w.name ? w.displayName : ''),
    h('div', { class: 'small muted' }, `建立於 ${fmtTime(w.createdAt)}`))
}

async function newWorldDialog() {
  const name = prompt('世界名稱（小寫英數、-、_）')
  if (!name) return
  const isPublic = confirm('設為公開世界？（取消＝私人）')
  try {
    const w = await api.createWorld(name, name, isPublic)
    navigate(`/${w.owner}/${w.name}`)
  } catch (e) { showError(e) }
}

export function loginPage(root: HTMLElement) {
  const err = h('div', { class: 'err' })
  const user = h('input', { name: 'username', autocomplete: 'username', required: true })
  const pass = h('input', { name: 'password', type: 'password', autocomplete: 'current-password', required: true })
  const form = h('form', { class: 'stack card', onSubmit: async (e: Event) => {
    e.preventDefault()
    try {
      await api.login(user.value, pass.value)
      await refreshUser()
      navigate('/')
    } catch (ex) { err.replaceChildren(errorBanner(ex)) }
  } },
  h('h1', {}, '登入'), h('label', {}, '帳號'), user, h('label', {}, '密碼'), pass,
  h('p', {}, h('button', { class: 'primary', type: 'submit' }, '登入')), err,
  h('p', { class: 'small muted' }, '本機帳號登入，或使用已連結的 OAuth 帳號。'))
  void getJson<{ oauth: string[]; registration: boolean }>('/api/v1/auth/options').then(options => {
    for (const provider of options.oauth) form.append(h('a', { href: `/oauth2/authorization/${provider}`, class: 'btn' }, `以 ${provider} 登入`))
    if (options.registration) {
      const email = h('input', { type: 'email', 'aria-label': '註冊信箱' })
      form.append(h('label', {}, '註冊信箱', email), h('button', { type: 'button', onClick: async () => { try { await sendJson('/api/v1/auth/register', 'POST', { username: user.value, password: pass.value, email: email.value }); err.textContent = '若資料可用，驗證信已寄出' } catch (e) { err.replaceChildren(errorBanner(e)) } } }, '申請註冊'))
    }
  }).catch(e => { err.replaceChildren(errorBanner(e)) })
  root.append(form)
}

export async function settingsPage(root: HTMLElement) {
  if (!session.me.username) { navigate('/login', true); return }
  const box = h('div', { class: 'card' })
  const render = async () => {
    const tokens = await api.tokens()
    box.replaceChildren(
      h('h2', {}, '存取 token'),
      h('p', { class: 'muted' }, 'git push 時以 token 當密碼（帳號欄任意）。token 只會在建立時顯示一次。'),
      h('table', {}, h('tbody', {}, ...tokens.map((t) => h('tr', {}, h('td', {}, `${t.name} · ${t.scope}`), h('td', { class: 'muted' }, t.kind === 'SESSION' ? '網頁登入' : '個人 token'), h('td', { class: 'muted' }, fmtTime(t.createdAt)),
        h('td', { class: 'muted' }, t.expiresAt ? `到期：${new Date(t.expiresAt).toLocaleString('zh-TW')}` : '無到期日'),
        h('td', { class: 'muted' }, t.lastUsedAt ? `最後使用：${fmtTime(t.lastUsedAt)}` : '尚未使用'),
        h('td', {}, h('button', { class: 'link', onClick: async () => { try{await api.deleteToken(t.id); await render()}catch(e){showError(e)} } }, '刪除')))))),
      h('p', {}, h('button', { onClick: async () => {
        const name = prompt('token 名稱', 'laptop') ?? ''
        const date = prompt('到期日（YYYY-MM-DD；留空使用伺服器預設期限）', '')
        if (date === null) return
        const parsed = date.trim() ? new Date(`${date.trim()}T23:59:59`) : null
        if (parsed && (!Number.isFinite(parsed.getTime()) || parsed.getTime() <= Date.now())) { toast('請輸入有效的未來日期', 'error'); return }
        const scope = prompt('scope：read／write／admin', 'read')
        if (!scope || !['read', 'write', 'admin'].includes(scope)) return
        try {const r = await api.createToken(name, parsed?.toISOString(), scope)
        const cmd = r.token
        box.append(h('div', { class: 'code' }, h('code', {}, cmd), h('button', { class: 'small', onClick: () => copyText(cmd) }, '複製')))
        toast('token 只顯示一次，請立即複製') }catch(e){showError(e)}
      } }, '建立 token')))
  }
  root.append(h('h1', {}, '設定'), box)
  await render()
  await oauthSettings(root)
  accountActions(root)
}

export async function oauthSettings(root: HTMLElement) {
  const options = await getJson<{ oauth: string[] }>('/api/v1/auth/options')
  const identities = await getJson<{ provider: string }[]>('/api/v1/auth/identities')
  const card = h('div', { class: 'card' }, h('h2', {}, 'OAuth 帳號連結'))
  for (const provider of options.oauth) {
    const linked = identities.some(i => i.provider === provider)
    card.append(h('button', { onClick: async () => { try {
      if (linked) { await sendJson(`/api/v1/auth/identities/${provider}`, 'DELETE'); navigate('/settings', true) }
      else { const r = await sendJson<{ url: string }>(`/api/v1/auth/oauth/${provider}/link`, 'POST'); location.assign(r.url) }
    } catch (e) { showError(e) } } }, `${linked ? '解除' : '連結'} ${provider}`))
  }
  root.append(card)
}

function accountActions(root:HTMLElement) {
  const action=(title:string,fields:[string,string,string?][],submit:(values:string[])=>Promise<unknown>)=> {
    const inputs=fields.map(([label,value,type])=>h('input',{'aria-label':label,value,type:type ?? 'text',required:true}))
    root.append(h('form',{class:'card stack',onSubmit:async(e:Event)=>{e.preventDefault();try{await submit(inputs.map(i=>i.value))}catch(e){showError(e)}}},h('h2',{},title),...inputs.map((input,i)=>h('label',{},fields[i][0],input)),h('button',{type:'submit'},title)))
  }
  action('更新密碼',[['新密碼','','password']],([password])=>sendJson('/api/v1/auth/password','PUT',{password}))
  action('建立組織',[['組織名稱',''],['組織顯示名稱','']],([slug,displayName])=>sendJson('/api/v1/orgs','POST',{slug,displayName}))
  action('設定組織成員',[['組織',''],['成員帳號',''],['組織角色 owner/admin/write/read/none','read']],([org,user,role])=>sendJson(`/api/v1/orgs/${encodeURIComponent(org)}/members/${encodeURIComponent(user)}`,'PUT',{role}))
  action('建立團隊',[['團隊所屬組織',''],['團隊名稱','']],([org,slug])=>sendJson(`/api/v1/orgs/${encodeURIComponent(org)}/teams`,'POST',{slug}))
  action('設定團隊成員',[['成員所屬組織',''],['成員所屬團隊',''],['團隊成員帳號',''],['成員動作 add/remove','add']],([org,team,user,command])=>sendJson(`/api/v1/orgs/${encodeURIComponent(org)}/teams/${encodeURIComponent(team)}/members/${encodeURIComponent(user)}`,command==='remove'?'DELETE':'PUT'))
  if(session.me.admin)action('建立帳號',[['新帳號',''],['初始密碼','','password']],([username,password])=>sendJson('/api/v1/users','POST',{username,password,admin:false}))
}
