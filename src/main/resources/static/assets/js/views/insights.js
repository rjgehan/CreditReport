// Claude's notes: the card on Home, and the page behind it with the Ask box (anything
// about your money, "Can I afford it?" included), the week's recap, charges to check and
// the changes it suggests for you to approve: rules, targets, and once-in-a-while
// purchases to leave out of your monthly averages.

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
    h('a', { class: 'claude-more', href: '#/insights' }, counts.length ? counts.join(' · ') : 'Ask Claude'));
}

export async function render(params, app) {
  const s = await get('/api/insights');
  if (!s.configured) {
    return h('div', { class: 'card empty' }, h('p', null, 'Claude isn\'t set up yet.'),
      h('a', { class: 'btn primary', href: '#/claude' }, 'Set up Claude'));
  }
  return h('div', { class: 'grid' }, askCard(s.ask, app), week(s), charges(s, app), suggestions(s, app),
    s.lastError ? h('p', { class: 'hint', style: { textAlign: 'center' } }, s.lastError) : null);
}

// --- Ask --------------------------------------------------------------------------------

const VERDICT = { yes: ['good', 'Yes'], careful: ['warn', 'Careful'], no: ['bad', 'No'] };
const PRESETS = [
  ['Can I afford…', 'Can I afford ', false],
  ['What did the move cost?', 'How much did the move cost all in?', true],
  ['Food per week', 'What do I spend on food in a typical week?', true],
];

// What you've typed and not sent yet survives the screen being rebuilt (coming back to the
// app, amounts re-hiding, approving a suggestion).
let draft = '';

/** The latest question stays here, answered or still on its way, until the next one. */
function askCard(last, app) {
  const input = h('input', { class: 'field', placeholder: 'Ask about your money', maxlength: 300,
    enterkeyhint: 'send', 'aria-label': 'Ask Claude', value: draft });
  input.addEventListener('input', () => { draft = input.value; });
  const out = h('div');
  const send = h('button', { class: 'btn primary', type: 'submit' }, 'Ask');
  const go = busy(send, async () => {
    const q = input.value.trim();
    if (!q) return input.focus();
    try {
      const started = await post('/api/insights/ask', { question: q });
      input.value = '';
      draft = '';
      show(out, started, app);
    } catch (e) {
      toast(e.message);
    }
  });
  const form = h('form', { class: 'claude-ask', onsubmit: (e) => { e.preventDefault(); go(); } }, input, send);
  const presets = h('div', { class: 'claude-presets' }, PRESETS.map(([label, text, now]) => h('button', {
    class: 'chip', type: 'button', onclick: () => {
      input.value = text;
      draft = text;
      if (now) go(); else input.focus();
    } }, label)));
  if (last) show(out, last, app);
  return h('section', { class: 'card' }, h('div', { class: 'card-head' }, h('h2', null, 'Ask Claude')), form, presets, out);
}

function show(out, q, app) {
  // Each card checks back on its own question; a newer question or a rebuilt screen ends it.
  clearTimeout(out.poll);
  out.qid = q.id;
  const asked = h('div', { class: 'claude-asked' }, q.question);
  if (q.status === 'RUNNING') {
    out.replaceChildren(asked, h('p', { class: 'hint' }, 'Claude is looking through your history. About a minute; you can leave this screen.'));
    out.poll = setTimeout(async () => {
      if (!out.isConnected) return;
      let next = q;
      try {
        // Coming back from a locked phone: wait for the one sign-in check before asking.
        await app.ready();
        next = await get(`/api/insights/questions/${q.id}`);
      } catch { /* check again in a moment */ }
      if (out.isConnected && out.qid === q.id) show(out, next, app);
    }, 3000);
    return;
  }
  if (q.status === 'FAILED') {
    out.replaceChildren(asked, h('p', { class: 'error-text' }, q.error || 'Claude couldn\'t answer. Try again.'));
    return;
  }
  const [cls, word] = VERDICT[q.verdict] || [];
  const rows = q.rows.length ? h('div', { class: 'claude-rows' },
    q.rows.map((r) => h('div', { class: 'claude-row' },
      h('span', { class: 'when' }, shortDate(r.day)), h('span', { class: 'what' }, r.description),
      h('span', { class: 'amt' }, money(r.amountCents, { cents: true })))),
    q.totalCents !== null && q.totalCents !== undefined
      ? h('div', { class: 'claude-row total' }, h('span', { class: 'when' }), h('span', { class: 'what' }, `These ${q.rows.length} add up to`),
        h('span', { class: 'amt' }, money(q.totalCents, { cents: true }))) : null) : null;
  out.replaceChildren(asked,
    h('div', { class: 'claude-answer' }, word ? h('span', { class: 'pill ' + cls }, word) : null,
      h('ul', { class: 'claude-lines' }, q.lines.map((l) => h('li', null, l)))),
    rows);
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
