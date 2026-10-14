// Shell: tabs, hash routing and the sync button.

import { get, post } from './api.js';
import { h, clear, icon, toast, flushMount, discardMount } from './ui.js';
import { privacy } from './fmt.js';
import * as home from './views/home.js';
import * as flow from './views/flow.js';
import * as activity from './views/activity.js';
import * as accounts from './views/accounts.js';
import * as history from './views/history.js';
import * as plan from './views/plan.js';
import * as investment from './views/investment.js';
import * as shortcut from './views/shortcut.js';

// Tabs first; the rest are screens reached from a tab, with a back button to it.
const ROUTES = [
  { path: 'home', title: 'Home', icon: 'home', view: home },
  { path: 'flow', title: 'Cash flow', tab: 'Flow', icon: 'flow', view: flow },
  { path: 'activity', title: 'Activity', icon: 'list', view: activity },
  { path: 'accounts', title: 'Accounts', icon: 'bank', view: accounts },
  { path: 'history', title: 'History', icon: 'calendar', view: history },
  { path: 'targets', title: 'Spending targets', parent: 'home', view: { render: plan.targetsPage } },
  { path: 'goals', title: 'Goals', parent: 'home', view: { render: plan.goalsPage } },
  { path: 'paychecks', title: 'Paychecks', parent: 'flow', view: { render: plan.paychecksPage } },
  { path: 'rules', title: 'Rules', parent: 'activity', view: { render: plan.rulesPage } },
  { path: 'investment', title: 'Investments', parent: 'accounts', view: investment },
  { path: 'shortcut', title: 'iPhone shortcut', parent: 'accounts', view: shortcut },
];

export const app = { me: null, rerender: () => {} };

const tabbar = document.querySelector('.tabbar');
const view = document.getElementById('view');
const title = document.getElementById('title');
const back = document.getElementById('back');
const syncBtn = document.getElementById('sync');

for (const r of ROUTES.filter((r) => r.icon)) {
  tabbar.append(h('a', { href: '#/' + r.path, dataset: { path: r.path } }, icon(r.icon), r.tab || r.title));
}
back.append(icon('back'));

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, query = ''] = raw.split('?');
  return { path: path || 'home', params: new URLSearchParams(query) };
}

let current = null;
async function render() {
  const { path, params } = parseHash();
  const route = ROUTES.find((r) => r.path === path) || ROUTES[0];
  title.textContent = route.title;
  document.title = route.title === 'Home' ? 'Finance' : `${route.title} · Finance`;
  const tab = route.parent || route.path;
  tabbar.querySelectorAll('a').forEach((a) => a.classList.toggle('active', a.dataset.path === tab));
  back.hidden = !route.parent;
  back.setAttribute('href', '#/' + (route.parent || 'home'));
  const token = {};
  current = token;
  view.classList.add('loading');
  try {
    discardMount();
    const el = await route.view.render(params, app);
    if (current !== token) return;
    clear(view).append(el);
    if (el.dataset?.title) document.title = el.dataset.title;
    flushMount();
  } catch (e) {
    if (current !== token) return;
    clear(view).append(h('div', { class: 'card empty' }, e.message));
  } finally {
    if (current === token) view.classList.remove('loading', 'masking');
  }
}
app.rerender = render;
app.go = (hash) => { if (location.hash === hash) render(); else location.hash = hash; };

privacy.onChange((hidden) => {
  if (hidden) {
    // Re-hiding (on a timer, or by hand) must not leave amounts showing anywhere:
    // close sheets and toasts, and blank the view until it re-renders masked.
    document.querySelectorAll('.sheet-backdrop, .toast').forEach((n) => n.remove());
    document.body.style.overflow = '';
    view.classList.add('masking');
  }
  render();
});

syncBtn.append(icon('sync'));
syncBtn.addEventListener('click', async () => {
  if (syncBtn.classList.contains('spinning')) return;
  syncBtn.classList.add('spinning');
  try {
    const [banks, prices] = await Promise.allSettled([post('/api/sync'), post('/api/investments/refresh')]);
    const errors = banks.status === 'fulfilled' ? banks.value.errors : [banks.reason.message];
    if (prices.status === 'fulfilled' && prices.value.failed?.length) errors.push('No price for ' + prices.value.failed.join(', '));
    toast(errors.length ? errors[0] : 'Up to date');
    render();
  } finally {
    syncBtn.classList.remove('spinning');
  }
});

window.addEventListener('scroll', () => {
  document.querySelector('.topbar').classList.toggle('scrolled', window.scrollY > 4);
}, { passive: true });
window.addEventListener('hashchange', () => {
  // A sheet belongs to the screen that opened it.
  document.querySelectorAll('.sheet-backdrop').forEach((s) => s.remove());
  document.body.style.overflow = '';
  window.scrollTo(0, 0);
  render();
});

let resizeTimer;
let lastWidth = window.innerWidth;
window.addEventListener('resize', () => {
  if (Math.abs(window.innerWidth - lastWidth) < 40) return;
  lastWidth = window.innerWidth;
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(render, 200);
});

(async () => {
  try {
    app.me = await get('/api/me');
  } catch {
    return;
  }
  render();
})();
