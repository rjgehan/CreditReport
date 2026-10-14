// Home answers one question at a glance: am I OK right now?
// Net worth, this month against its targets, which targets are tight, then goals.
// Everything else lives on the tab that owns it.

import { get } from '../api.js';
import { h } from '../ui.js';
import { money, pct, monthLabel, ago, shortDate, privacy, parseDay } from '../fmt.js';
import { meter } from '../charts.js';

const MAX_TARGETS = 6;

export async function render(params, app) {
  const d = await get('/api/summary');

  if (!d.hasAccounts) {
    return h('div', { class: 'card empty' },
      h('p', null, 'Nothing here yet. Connect a bank, add an account by hand, or import a statement.'),
      h('a', { class: 'btn primary', href: '#/accounts' }, 'Add accounts'));
  }

  const left = h('div', { class: 'home-col' }, netWorth(d), thisMonth(d), dueCards(d));
  const right = h('div', { class: 'home-col' }, targets(d), goals(d));
  return h('div', { class: 'grid two' }, left, right);
}

// --- Net worth ------------------------------------------------------------------

function netWorth(d) {
  const history = d.netWorthHistory;
  const before = history.length > 4 ? history[history.length - 5].cents : null;
  let change = null;
  if (before !== null) {
    const delta = d.netWorthCents - before;
    const text = privacy.on
      ? (before !== 0 ? signedPct(delta / Math.abs(before)) : null)
      : money(delta, { sign: true });
    if (text) {
      change = h('span', { class: 'nw-change ' + (delta >= 0 ? 'up' : 'down') }, text,
        h('span', { class: 'quiet' }, ' past month'));
    }
  }
  return h('a', { class: 'card nw', href: '#/accounts', 'aria-label': 'Net worth, open accounts' },
    h('div', { class: 'nw-top' }, h('span', { class: 'label' }, 'Net worth'), syncStatus(d)),
    h('div', { class: 'nw-main' }, h('span', { class: 'nw-figure' }, money(d.netWorthCents)), change));
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

function signedPct(fraction) {
  const n = fraction * 100;
  return (n >= 0 ? '+' : '−') + Math.abs(n).toFixed(1) + '%';
}

// --- This month -----------------------------------------------------------------

/** The same rule as TargetService, applied to the month as a whole. */
function verdict(d) {
  const t = d.targets;
  const f = t.spentInBudgetsCents / t.budgetedCents;
  const e = d.dayOfMonth / d.daysInMonth;
  const overCount = t.spending.filter((s) => s.status === 'over').length;
  if (f > 1) return { f, e, label: 'Over budget', pill: 'bad', meter: 'over' };
  if (overCount) return { f, e, label: `${overCount} over`, pill: 'warn', meter: 'watch' };
  if (f > e + 0.1 || f >= 0.9) return { f, e, label: 'Above pace', pill: 'warn', meter: 'watch' };
  return { f, e, label: 'On pace', pill: 'good', meter: 'ok' };
}

function thisMonth(d) {
  const t = d.totals;
  const card = h('section', { class: 'card month' },
    h('div', { class: 'month-head' },
      h('span', { class: 'name' }, monthLabel(d.month)),
      h('span', { class: 'day' }, `Day ${d.dayOfMonth} of ${d.daysInMonth}`)));

  if (d.targets.budgetedCents > 0) {
    const v = verdict(d);
    const rem = d.targets.budgetedCents - d.targets.spentInBudgetsCents;
    let big;
    if (privacy.on) big = h('span', { class: 'big' }, pct(v.f), h('span', { class: 'unit' }, ' used'));
    else if (rem >= 0) big = h('span', { class: 'big' }, money(rem), h('span', { class: 'unit' }, ' left'));
    else big = h('span', { class: 'big down' }, money(-rem), h('span', { class: 'unit' }, ' over'));
    card.append(
      h('div', { class: 'month-figure' }, big, h('span', { class: 'pill ' + v.pill }, v.label)),
      meter(v.f, v.meter, v.e, 'lg'),
      h('div', { class: 'month-foot' }, `${pct(v.f)} of ${money(d.targets.budgetedCents)} budget`));
  } else {
    card.append(
      h('div', { class: 'month-figure' }, h('span', { class: 'big' }, money(t.spentCents), h('span', { class: 'unit' }, ' spent'))),
      h('div', { class: 'month-foot' }, h('a', { href: '#/plan?tab=targets' }, 'Set monthly targets on Plan')));
  }

  card.append(h('hr', { class: 'month-rule' }),
    h('a', { class: 'strip', href: `#/flow?range=month&month=${d.month}`, 'aria-label': 'This month\'s cash flow' },
      stat('Income', 'var(--income)', t.incomeCents),
      stat('Spent', 'var(--expense)', t.spentCents),
      stat(t.savingsRate === null ? 'Saved' : `Saved ${pct(t.savingsRate)}`, 'var(--savings)', t.savedCents),
      stat('Left over', null, t.leftoverCents, true)));
  return card;
}

function stat(label, color, cents, signed = false) {
  return h('div', null,
    h('div', { class: 'label' }, color ? h('span', { class: 'key', style: { background: color } }) : null, label),
    h('div', { class: 'value' }, money(cents, { sign: signed })));
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
  if (!all.length) return null;
  const shown = all.slice(0, MAX_TARGETS);
  const card = h('section', { class: 'card flush tcard' },
    h('a', { class: 'tcard-head', href: '#/plan?tab=targets' },
      h('h2', null, 'Spending targets', all.length > MAX_TARGETS ? h('span', { class: 'more' }, ` · ${shown.length} of ${all.length}`) : null),
      h('span', { class: 'col' }, privacy.on ? 'Used' : 'Left')));
  for (const s of shown) {
    const left = s.limitCents - s.spentCents;
    const amount = privacy.on ? pct(s.fraction) : money(left);
    card.append(h('a', { class: 'trow', href: `#/activity?category=${s.categoryId}&month=${d.month}` },
      h('span', { class: 'name' }, s.name),
      meter(s.fraction, s.status, s.expectedFraction, 'sm'),
      h('span', { class: 'amt' + (left < 0 && !privacy.on ? ' down' : '') }, amount)));
  }
  return card;
}

// --- Goals ------------------------------------------------------------------------

function goals(d) {
  const list = d.targets.goals;
  if (!list.length) return null;
  const card = h('section', { class: 'card flush', style: { paddingBottom: '4px' } },
    h('a', { class: 'tcard-head', href: '#/plan?tab=goals' }, h('h2', null, 'Goals')));
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
    card.append(h('a', { class: 'grow-row', href: '#/plan?tab=goals' },
      h('div', { class: 'line' }, h('span', { class: 'name' }, g.name), valEl),
      meter(g.fraction, g.status === 'behind' ? 'behind' : 'savings', null, 'xs')));
  }
  return card;
}
