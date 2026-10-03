import './style.css'
import { sendJson } from './api.ts'
import { refreshUser, session } from './session.ts'
import { commitPage } from './pages/commit.ts'
import { branchesPage } from './pages/branches.ts'
import { mergePage } from './pages/merge.ts'
import { comparePage } from './pages/compare.ts'
import { commitsPage } from './pages/commits.ts'
import { homePage, loginPage, settingsPage } from './pages/home.ts'
import { pullsPage, pullPage, releasesPage, releasePage, permissionsPage, notificationsPage, verifyPage } from './pages/collaboration.ts'
import { worldPage } from './pages/world.ts'
import { addRoute, navigate, startRouter } from './router.ts'
import { h, link } from './ui.ts'

const app = document.getElementById('app')!
const nav = h('nav', { class: 'userbox' })
const main = h('main', { class: 'page' })
app.append(
  h('header', { class: 'top' },
    link('/', [h('span', { class: 'logo' }, '◆'), ' WorldGit Hub'], 'brand'),
    h('span', { class: 'spacer' }), nav),
  main)

function renderUser() {
  const me = session.me
  nav.replaceChildren()
  if (me.username) {
    nav.append(link('/notifications', '通知', 'pill'), link('/settings', me.username, 'pill'), h('button', { class: 'link', onClick: async () => { await sendJson('/api/v1/auth/logout', 'POST'); await refreshUser(); navigate('/') } }, '登出'))
  } else nav.append(link('/login', '登入', 'btn small'))
}
session.listeners.push(renderUser)

addRoute(/^\/$/, (_p, root) => homePage(root))
addRoute(/^\/login$/, (_p, root) => loginPage(root))
addRoute(/^\/verify$/, (_p, root) => verifyPage(root))
addRoute(/^\/notifications$/, (_p, root) => notificationsPage(root))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/pulls$/, ([o, w], root) => pullsPage(root, o, w))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/pulls\/([^/]+)$/, ([o, w, id], root) => pullPage(root, o, w, id))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/releases$/, ([o, w], root) => releasesPage(root, o, w))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/releases\/([^/]+)$/, ([o, w, id], root) => releasePage(root, o, w, id))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/settings$/, ([o, w], root) => permissionsPage(root, o, w))
addRoute(/^\/settings$/, (_p, root) => settingsPage(root))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/branches$/, ([o, w], root) => branchesPage(root, o, w))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/compare\/(.+)$/, ([o, w, spec], root) => comparePage(root, o, w, spec))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/merge-preview\/(.+)$/, ([o, w, spec], root) => mergePage(root, o, w, spec))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/commits$/, ([o, w], root) => commitsPage(root, o, w))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/commit\/([^/]+)\/([0-9a-fA-F]{4,40}|HEAD)$/, ([o, w, d, r], root) => commitPage(root, o, w, d, r))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)$/, ([o, w], root) => worldPage(root, o, w))

void refreshUser().then(() => startRouter(main))
