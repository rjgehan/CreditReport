// Home answers one question at a glance: am I OK right now?
// Net worth and its direction, what's left to spend this month, which targets
// are tight, then goals. Tapping a card opens what manages it.

import { get, post } from '../api.js';
import { h, toast, busy, icon } from '../ui.js';
import { money, pct, monthLabel, ago, shortDate, privacy, parseDay, depositLine, filedLine } from '../fmt.js';
import { meter } from '../charts.js';

const MAX_TARGETS = 6;

export async function render(params, app) {
  const d = await get('/api/summary');

  if (!d.hasAccounts) {
    return h('div', { class: 'card empty' },
      h('p', null, 'Nothing here yet. Connect a bank, add an account by hand, or import a statement.'),
      h('a', { class: 'btn primary', href: '#/accounts' }, 'Add accounts'));
  }

  // With no income on record the month card can't say much, so help find the paycheck first.
  const setup = d.plan.incomeCents === 0 ? await incomeSetup(app) : null;
  const left = h('div', { class: 'home-col' }, netWorth(d), setup, thisMonth(d), dueCards(d));
  const right = h('div', { class: 'home-col' }, targets(d), goals(d));
  return h('div', { class: 'grid two' }, left, right);
}

// --- Net worth ------------------------------------------------------------------

function netWorthChange(d) {
  if (d.netWorthChangePercent === null || d.netWorthChangePercent === undefined) return '—';
  const p = d.netWorthChangePercent;
  return (p >= 0 ? '+' : '−') + Math.abs(p).toFixed(1) + '%';
}

function netWorth(d) {
  let change = null;
  if (d.netWorthChangeCents !== null) {
    const up = d.netWorthChangeCents >= 0;
    const percent = d.netWorthChangePercent === null ? null
      : (up ? '+' : '−') + Math.abs(d.netWorthChangePercent).toFixed(1) + '%';
    const amount = privacy.on ? null : money(d.netWorthChangeCents, { sign: true });
    // Hidden, the big figure already is the change, so this line just says over what.
    change = h('div', { class: 'nw-change ' + (up ? 'up' : 'down') },
      privacy.on ? '' : [amount, percent].filter(Boolean).join(' · '),
      h('span', { class: 'quiet' }, privacy.on ? `change over the past ${d.netWorthChangeDays ?? 30} days` : ` past ${d.netWorthChangeDays ?? 30} days`));
  }
  return h('a', { class: 'card nw', href: '#/accounts', 'aria-label': 'Net worth, open accounts' },
    h('div', { class: 'nw-top' }, h('span', { class: 'label' }, 'Net worth'), syncStatus(d)),
    h('div', { class: 'nw-main' },
      h('span', { class: 'nw-figure' }, privacy.on ? netWorthChange(d) : money(d.netWorthCents)),
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

function thisMonth(d) {
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
  const flowLink = `#/flow?range=month&month=${p.month}`;

  return h('section', { class: 'card month' },
    h('div', { class: 'month-head' },
      h('span', { class: 'name' }, monthLabel(p.month)),
      h('span', { class: 'day' }, `Day ${p.dayOfMonth} of ${p.daysInMonth}`)),
    h('div', { class: 'month-figure' }, big, h('span', { class: 'pill ' + PILL[p.status] }, p.verdict)),
    meter(p.spentFraction, p.status, p.current ? p.expectedFraction : null, 'lg'),
    h('hr', { class: 'month-rule' }),
    h('div', { class: 'strip two' },
      stat('Income', 'var(--income)', (p.incomeEstimated ? '~' : '') + money(p.incomeCents), flowLink),
      stat('Spent', 'var(--expense)', money(p.spentCents), flowLink)));
}

function stat(label, color, value, href) {
  return h(href ? 'a' : 'div', { class: 'stat', href },
    h('div', { class: 'label' }, color ? h('span', { class: 'key', style: { background: color } }) : null, label),
    h('div', { class: 'value' }, value));
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

// --- Income setup (only while income is $0) ---------------------------------------

/**
 * Income counts deposits filed as Paycheck or Side Income. A paycheck whose
 * description no built-in rule recognises lands in Other Income and doesn't
 * count, so offer the regular deposits the app has spotted, one tap each.
 */
async function incomeSetup(app) {
  const { detected } = (await get('/api/paychecks')).overview;
  // Pay already recognised: income is just waiting for this month's payday.
  if (detected.some((p) => p.tracked && !p.accountHidden)) return null;
  const card = h('section', { class: 'card flush setup' },
    h('div', { class: 'tcard-head' }, h('h2', null, 'Set up your income')));
  // Recognised, but it lands in an account you hid, and hidden accounts count nowhere.
  const hiddenPay = detected.find((p) => p.tracked && p.accountHidden);
  if (hiddenPay) {
    card.append(h('div', { class: 'row' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, `Pay from ${hiddenPay.pattern} lands in ${hiddenPay.accountName}`),
        h('div', { class: 'meta wrap' }, 'That account is hidden, so it doesn\'t count. Show it again from Hidden at the bottom of Accounts.')),
      h('a', { class: 'btn small', href: '#/accounts' }, 'Accounts')));
  }
  const candidates = detected.filter((p) => !p.tracked).slice(0, 3);
  if (!candidates.length && hiddenPay) return card;
  if (!candidates.length) {
    card.append(h('div', { class: 'row' }, h('div', { class: 'main' },
      h('div', { class: 'meta wrap' }, 'No regular deposits spotted yet. Open a paycheck in Activity, file it as Paycheck and tick "always file like this".')),
    h('a', { class: 'btn small', href: '#/activity' }, 'Activity')));
    card.append(paystubLink());
    return card;
  }
  for (const p of candidates) {
    const yes = h('button', { class: 'btn small primary' }, 'This is my pay');
    yes.addEventListener('click', busy(yes, async () => {
      const r = await post('/api/paychecks/track', { pattern: p.pattern });
      toast(filedLine(r.filed));
      app.rerender();
    }));
    card.append(h('div', { class: 'row' },
      h('div', { class: 'main' },
        h('div', { class: 'title' }, p.pattern),
        h('div', { class: 'meta' }, depositLine(p))),
      yes));
  }
  card.append(paystubLink());
  return card;
}

// Taxes and 401(k) come out before pay lands; a paystub puts them in the cash flow.
function paystubLink() {
  return h('a', { class: 'row tap', href: '#/paychecks' },
    h('div', { class: 'main' }, h('div', { class: 'title' }, 'Paystub'),
      h('div', { class: 'meta' }, 'Taxes and 401(k) taken out before payday')),
    icon('chevron', 18));
}
