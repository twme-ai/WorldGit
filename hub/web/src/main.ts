import './style.css'
import { setToken } from './api.ts'
import { refreshUser, session } from './session.ts'
import { commitPage } from './pages/commit.ts'
import { branchesPage } from './pages/branches.ts'
import { comparePage } from './pages/compare.ts'
import { commitsPage } from './pages/commits.ts'
import { homePage, loginPage, settingsPage } from './pages/home.ts'
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
    nav.append(link('/settings', me.username, 'pill'), h('button', { class: 'link', onClick: () => { setToken(null); void refreshUser(); navigate('/') } }, '登出'))
  } else nav.append(link('/login', '登入', 'btn small'))
}
session.listeners.push(renderUser)

addRoute(/^\/$/, (_p, root) => homePage(root))
addRoute(/^\/login$/, (_p, root) => loginPage(root))
addRoute(/^\/settings$/, (_p, root) => settingsPage(root))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/branches$/, ([o, w], root) => branchesPage(root, o, w))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/compare\/(.+)$/, ([o, w, spec], root) => comparePage(root, o, w, spec))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/commits$/, ([o, w], root) => commitsPage(root, o, w))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)\/commit\/([^/]+)\/([0-9a-fA-F]{4,40}|HEAD)$/, ([o, w, d, r], root) => commitPage(root, o, w, d, r))
addRoute(/^\/([a-z0-9_-]+)\/([a-z0-9_-]+)$/, ([o, w], root) => worldPage(root, o, w))

void refreshUser().then(() => startRouter(main))
