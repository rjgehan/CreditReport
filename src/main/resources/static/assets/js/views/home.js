// Home answers one question at a glance: am I OK right now?
// Net worth and its direction, what's left to spend this month, which targets
// are tight, then goals. Tapping a card opens what manages it.

import { get, put } from '../api.js';
import { h, sheet, field, busy, toast } from '../ui.js';
import { money, pct, monthLabel, ago, shortDate, privacy, parseDay, parseMoney, centsToInput } from '../fmt.js';
import { meter } from '../charts.js';

const MAX_TARGETS = 6;

export async function render(params, app) {
  const d = await get('/api/summary');

  if (!d.hasAccounts) {
    return h('div', { class: 'card empty' },
      h('p', null, 'Nothing here yet. Connect a bank, add an account by hand, or import a statement.'),
      h('a', { class: 'btn primary', href: '#/accounts' }, 'Add accounts'));
  }

  const left = h('div', { class: 'home-col' }, netWorth(d), thisMonth(d, app), dueCards(d));
  const right = h('div', { class: 'home-col' }, targets(d), goals(d));
  return h('div', { class: 'grid two' }, left, right);
}

// --- Net worth ------------------------------------------------------------------

function netWorth(d) {
  let change = null;
  if (d.netWorthChangeCents !== null) {
    const up = d.netWorthChangeCents >= 0;
    const percent = d.netWorthChangePercent === null ? null
      : (up ? '+' : '−') + Math.abs(d.netWorthChangePercent).toFixed(1) + '%';
    const amount = privacy.on ? null : money(d.netWorthChangeCents, { sign: true });
    change = h('div', { class: 'nw-change ' + (up ? 'up' : 'down') },
      [amount, percent].filter(Boolean).join(' · '), h('span', { class: 'quiet' }, ` past ${d.netWorthChangeDays ?? 30} days`));
  }
  return h('a', { class: 'card nw', href: '#/accounts', 'aria-label': 'Net worth, open accounts' },
    h('div', { class: 'nw-top' }, h('span', { class: 'label' }, 'Net worth'), syncStatus(d)),
    h('div', { class: 'nw-main' },
      h('span', { class: 'nw-figure' }, money(d.netWorthCents)),
      sparkline(d.netWorthSpark, (d.netWorthChangeCents ?? 0) >= 0)),
    change);
}

/** Up or down at a glance: no axes, no labels, just the shape of the last month. */
function sparkline(points, up) {
  if (!points || points.length < 2) return null;
  const w = 96;
  const ht = 30;
  const values = points.map((p) => p.cents);
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = hi - lo || 1;
  const x = (i) => 2 + (i / (points.length - 1)) * (w - 6);
  const y = (v) => 3 + (1 - (v - lo) / span) * (ht - 6);
  const line = values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const color = up ? 'var(--good-ink)' : 'var(--critical)';
  const last = values.length - 1;
  return h('svg', { class: 'spark', viewBox: `0 0 ${w} ${ht}`, width: w, height: ht, 'aria-hidden': 'true' },
    h('path', { d: `${line} L${x(last)},${ht} L${x(0)},${ht} Z`, fill: color, 'fill-opacity': 0.1 }),
    h('path', { d: line, fill: 'none', stroke: color, 'stroke-width': 1.75, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }),
    h('circle', { cx: x(last), cy: y(values[last]), r: 2.5, fill: color }));
}

function syncStatus(d) {
  if (d.problems.length === 1) {
    const p = d.problems[0];
    return h('span', { class: 'status bad' }, `${p.name}: ${p.status === 'NEEDS_REAUTH' ? 'sign in again' : 'sync failed'}`);
  }
  if (d.problems.length > 1) {
    return h('span', { class: 'status bad' }, `${d.problems.length} connections need attention`);
  }
  if (!d.lastSyncedAt) return h('span', { class: 'status' });
  const hours = (Date.now() - new Date(d.lastSyncedAt).getTime()) / 3600000;
  return h('span', { class: 'status' + (hours > 36 ? ' stale' : '') }, `Synced ${ago(d.lastSyncedAt)}`);
}

// --- This month: income, minus what you keep, minus what you spend ----------------

const PILL = { ok: 'good', watch: 'warn', over: 'bad' };

function thisMonth(d, app) {
  const p = d.plan;
  let big;
  if (privacy.on) {
    big = p.leftToSpendCents < 0
      ? h('span', { class: 'big down' }, 'Over', h('span', { class: 'unit' }, ' budget'))
      : h('span', { class: 'big' }, pct(Math.max(0, p.spentFraction)), h('span', { class: 'unit' }, ' used'));
  } else if (p.leftToSpendCents >= 0) {
    big = h('span', { class: 'big' }, money(p.leftToSpendCents), h('span', { class: 'unit' }, ' left to spend'));
  } else {
    big = h('span', { class: 'big down' }, money(-p.leftToSpendCents), h('span', { class: 'unit' }, ' over'));
  }
  const perDay = p.perDayLeftCents === null ? null : Math.max(0, p.perDayLeftCents);
  const flowLink = `#/flow?range=month&month=${p.month}`;

  return h('section', { class: 'card month' },
    h('div', { class: 'month-head' },
      h('span', { class: 'name' }, monthLabel(p.month)),
      h('span', { class: 'day' }, `Day ${p.dayOfMonth} of ${p.daysInMonth}`)),
    h('div', { class: 'month-figure' }, big, h('span', { class: 'pill ' + PILL[p.status] }, p.verdict)),
    meter(p.spentFraction, p.status, p.current ? p.expectedFraction : null, 'lg'),
    h('hr', { class: 'month-rule' }),
    h('div', { class: 'strip' },
      stat('Income', 'var(--income)', (p.incomeEstimated ? '~' : '') + money(p.incomeCents), flowLink),
      stat('To save', 'var(--savings)', p.savingsTargetCents === null ? 'Set' : money(p.savingsTargetCents),
        null, () => savingsTargetSheet(p.savingsTargetCents, app)),
      stat('Spent', 'var(--expense)', money(p.spentCents), flowLink),
      stat('Per day', null, perDay === null ? '—' : money(perDay), null)));
}

function stat(label, color, value, href, onclick) {
  const tag = href ? 'a' : onclick ? 'button' : 'div';
  return h(tag, { class: 'stat' + (onclick ? ' tap' : ''), href, onclick, type: onclick ? 'button' : null },
    h('div', { class: 'label' }, color ? h('span', { class: 'key', style: { background: color } }) : null, label),
    h('div', { class: 'value' + (value === 'Set' ? ' set' : '') }, value));
}

function savingsTargetSheet(current, app) {
  sheet('Savings target', (body, s) => {
    // With amounts hidden the current target isn't shown, even here; typing a new
    // one still works, and a blank field leaves it as it was.
    const hidden = privacy.on;
    const amount = h('input', { class: 'field', inputmode: 'decimal', value: hidden ? '' : centsToInput(current),
      placeholder: hidden && current !== null ? 'Hidden. Type a new amount' : 'e.g. 1000' });
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      const cents = parseMoney(amount.value);
      if (!cents || cents <= 0) {
        if (hidden || current === null) { s.close(); return; }
        throw new Error('Enter an amount, or use Remove target');
      }
      await put('/api/settings', { savingsTargetCents: cents });
      s.close(); toast('Saved'); app.rerender();
    }));
    const remove = current === null ? null : h('button', { class: 'btn danger', onclick: async () => {
      await put('/api/settings', { savingsTargetCents: null });
      s.close(); toast('Target removed'); app.rerender();
    } }, 'Remove target');
    body.append(h('div', { class: 'form' },
      field('Keep this much each month', amount,
        'Left to spend is income minus this minus what you\'ve spent so far.'),
      h('div', { class: 'btn-row' }, save, remove)));
  });
}

// --- Cards due soon (only when one is) ------------------------------------------

function dueCards(d) {
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const soon = d.cards.filter((c) => {
    if (!c.dueDate) return false;
    const days = Math.round((parseDay(c.dueDate) - today) / 86400000);
    const owed = c.statementBalanceCents ?? -c.balanceCents;
    return days >= 0 && days <= 7 && owed > 0;
  }).slice(0, 2);
  if (!soon.length) return null;
  return h('section', { class: 'card flush' }, soon.map((c) =>
    h('a', { class: 'due-row', href: '#/accounts' },
      h('span', { class: 'text' }, `${c.institution ? c.institution + ' ' : ''}${c.name} due ${shortDate(c.dueDate)}`),
      h('span', { class: 'amt' }, money(c.statementBalanceCents ?? -c.balanceCents)))));
}

// --- Spending targets -----------------------------------------------------------

const RANK = { over: 0, watch: 1, ok: 2 };

function targets(d) {
  const all = [...d.targets.spending].sort((a, b) => (RANK[a.status] - RANK[b.status]) || (b.fraction - a.fraction));
  if (!all.length) {
    return h('a', { class: 'card tcard-empty', href: '#/targets' }, 'Set spending targets');
  }
  const shown = all.slice(0, MAX_TARGETS);
  const card = h('section', { class: 'card flush tcard' },
    h('a', { class: 'tcard-head', href: '#/targets' },
      h('h2', null, 'Spending targets', all.length > MAX_TARGETS ? h('span', { class: 'more' }, ` · ${shown.length} of ${all.length}`) : null),
      h('span', { class: 'col' }, privacy.on ? 'Used' : 'Left')));
  for (const s of shown) {
    const left = s.limitCents - s.spentCents;
    // The whole box opens the targets screen; each category's transactions are a tap away there.
    card.append(h('a', { class: 'trow', href: '#/targets' },
      h('span', { class: 'name' }, s.name),
      meter(s.fraction, s.status, s.expectedFraction, 'sm'),
      h('span', { class: 'amt' + (left < 0 && !privacy.on ? ' down' : '') }, privacy.on ? pct(s.fraction) : money(left))));
  }
  return card;
}

// --- Goals ------------------------------------------------------------------------

function goals(d) {
  const list = d.targets.goals;
  if (!list.length) return h('a', { class: 'card tcard-empty', href: '#/goals' }, 'Set a savings goal');
  const card = h('section', { class: 'card flush', style: { paddingBottom: '4px' } },
    h('a', { class: 'tcard-head', href: '#/goals' }, h('h2', null, 'Goals')));
  for (const g of list) {
    let value;
    if (g.type === 'SAVINGS_RATE') {
      value = `${g.currentPercent === null ? '—' : g.currentPercent.toFixed(0) + '%'} of ${g.targetPercent}%`;
    } else if (privacy.on) {
      value = pct(Math.min(g.fraction, 1));
    } else {
      value = `${money(g.currentCents)} of ${money(g.targetCents)}`;
    }
    const valEl = h('span', { class: 'val' });
    if (g.status === 'done') valEl.append(h('span', { class: 'done' }, 'Done'));
    else {
      valEl.append(value);
      if (g.status === 'behind') valEl.append(' · ', h('span', { class: 'behind' }, 'behind'));
    }
    card.append(h('a', { class: 'grow-row', href: '#/goals' },
      h('div', { class: 'line' }, h('span', { class: 'name' }, g.name), valEl),
      meter(g.fraction, g.status === 'behind' ? 'behind' : 'savings', null, 'xs')));
  }
  return card;
}
