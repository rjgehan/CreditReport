// Claude's notes: the card on Home, and the page behind it with "Can I afford it?",
// the week's recap, charges to check and the changes it suggests for you to approve:
// rules, targets, and once-in-a-while purchases to leave out of your monthly averages.

import { get, post, put, patch } from '../api.js';
import { h, toast, busy } from '../ui.js';
import { money, ago, shortDate } from '../fmt.js';

const STATUS = { on_track: ['good', 'On track'], watch: ['warn', 'Watch'], over: ['bad', 'Over'] };

/** For Home: today's take, or nothing at all until Claude is set up. */
export function insightCard(s, app) {
  if (!s || !s.configured) return null;
  const t = s.take;
  const refresh = h('button', { class: 'link', type: 'button' }, s.busy ? 'Looking…' : 'Refresh');
  refresh.disabled = s.busy;
  refresh.addEventListener('click', busy(refresh, async () => {
    refresh.textContent = 'Looking…';
    try {
      await post('/api/insights/refresh');
      app.rerender();
    } finally {
      refresh.textContent = 'Refresh';
    }
  }));
  const [cls, word] = STATUS[t?.status] || ['', ''];
  const counts = [];
  const charges = s.items.filter((i) => i.kind === 'CHARGE').length;
  const ideas = s.items.length - charges;
  if (charges) counts.push(`${charges} charge${charges === 1 ? '' : 's'} to check`);
  if (ideas) counts.push(`${ideas} suggestion${ideas === 1 ? '' : 's'}`);
  return h('section', { class: 'card claude' },
    h('div', { class: 'card-head' }, h('h2', null, 'Claude'),
      h('span', { class: 'hint' }, t ? ago(t.at) + ' · ' : '', refresh)),
    t ? h('div', { class: 'claude-head' }, word ? h('span', { class: 'pill ' + cls }, word) : null,
      h('span', { class: 'claude-headline' }, t.headline))
      : h('div', { class: 'hint' }, s.lastError || 'No notes yet. Tap Refresh, or they arrive each morning.'),
    t && t.lines.length ? h('ul', { class: 'claude-lines' }, t.lines.map((l) => h('li', null, l))) : null,
    h('a', { class: 'claude-more', href: '#/insights' }, counts.length ? counts.join(' · ') : 'Can I afford it?'));
}

export async function render(params, app) {
  const s = await get('/api/insights');
  if (!s.configured) {
    return h('div', { class: 'card empty' }, h('p', null, 'Claude isn\'t set up yet.'),
      h('a', { class: 'btn primary', href: '#/claude' }, 'Set up Claude'));
  }
  return h('div', { class: 'grid' }, afford(), week(s), charges(s, app), suggestions(s, app),
    s.lastError ? h('p', { class: 'hint', style: { textAlign: 'center' } }, s.lastError) : null);
}

function afford() {
  const input = h('input', { class: 'field', placeholder: 'A $200 concert this weekend?', maxlength: 300,
    enterkeyhint: 'send', 'aria-label': 'What do you want to buy?' });
  const out = h('div');
  const ask = h('button', { class: 'btn primary', type: 'submit' }, 'Ask');
  const form = h('form', { class: 'claude-ask', onsubmit: (e) => { e.preventDefault(); go(); } }, input, ask);
  const go = busy(ask, async () => {
    const q = input.value.trim();
    if (!q) return input.focus();
    out.replaceChildren(h('p', { class: 'hint' }, 'Claude is looking…'));
    try {
      const a = await post('/api/insights/afford', { question: q });
      const [cls, word] = { yes: ['good', 'Yes'], careful: ['warn', 'Careful'], no: ['bad', 'No'] }[a.answer] || ['', ''];
      out.replaceChildren(h('div', { class: 'claude-answer' }, h('span', { class: 'pill ' + cls }, word), h('span', null, a.text)));
    } catch (e) {
      out.replaceChildren(h('p', { class: 'error-text' }, e.message));
    }
  });
  return h('section', { class: 'card' }, h('div', { class: 'card-head' }, h('h2', null, 'Can I afford it?')), form, out);
}

function week(s) {
  if (!s.week || !s.week.lines.length) return null;
  return h('section', { class: 'card' },
    h('div', { class: 'card-head' }, h('h2', null, 'This week'), h('span', { class: 'hint' }, 'From ' + shortDate(s.week.period))),
    h('ul', { class: 'claude-lines' }, s.week.lines.map((l) => h('li', null, l))));
}

function charges(s, app) {
  const list = s.items.filter((i) => i.kind === 'CHARGE');
  if (!list.length) return null;
  return h('section', { class: 'card flush' },
    h('div', { class: 'card-head', style: { padding: '16px 16px 0' } }, h('h2', null, 'Charges to check')),
    list.map((i) => h('div', { class: 'row' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, i.description),
        h('div', { class: 'meta' }, `${shortDate(i.day)} · ${money(i.amountCents)} · ${i.reason}`)),
      close(i, 'dismissed', 'Fine', app))));
}

function suggestions(s, app) {
  const list = s.items.filter((i) => i.kind !== 'CHARGE');
  if (!list.length) return null;
  return h('section', { class: 'card flush' },
    h('div', { class: 'card-head', style: { padding: '16px 16px 0' } }, h('h2', null, 'Suggestions')),
    list.map((i) => {
      const what = i.kind === 'RULE' ? `File "${i.pattern}" as ${i.categoryName}`
        : i.kind === 'ONE_OFF' ? `One-time: ${i.description}`
          : `${i.categoryName}: ${money(i.limitCents)} a month`;
      const meta = i.kind === 'ONE_OFF' ? `${shortDate(i.day)} · ${money(-i.amountCents)} · ${i.reason}` : i.reason;
      const label = { RULE: 'Add rule', TARGET: 'Set target', ONE_OFF: 'Leave out' }[i.kind];
      const apply = h('button', { class: 'btn small primary' }, label);
      apply.addEventListener('click', busy(apply, async () => {
        if (i.kind === 'RULE') await post('/api/rules', { pattern: i.pattern, categoryId: i.categoryId });
        else if (i.kind === 'ONE_OFF') await patch(`/api/transactions/${i.transactionId}`, { oneOff: true });
        else await put(`/api/targets/${i.categoryId}`, { monthlyLimitCents: i.limitCents });
        await post(`/api/insights/items/${i.id}/done`);
        toast({ RULE: 'Rule added', TARGET: 'Target set', ONE_OFF: 'Left out of your averages' }[i.kind]);
        app.rerender();
      }));
      return h('div', { class: 'row' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, what), h('div', { class: 'meta' }, meta)),
        h('div', { class: 'btn-row', style: { flexWrap: 'nowrap' } }, apply, close(i, 'dismissed', 'Skip', app)));
    }));
}

function close(i, state, label, app) {
  const b = h('button', { class: 'btn small' }, label);
  b.addEventListener('click', busy(b, async () => {
    await post(`/api/insights/items/${i.id}/${state}`);
    app.rerender();
  }));
  return b;
}
