import { get } from '../api.js';
import { h, onMount } from '../ui.js';
import { money, pct, monthLabel, shiftMonth, thisMonth, ago, dayLabel, shortDate } from '../fmt.js';
import { lineChart, barChart, meter } from '../charts.js';
import { txnRow } from './activity.js';

export async function render(params, app) {
  const month = params.get('month') || thisMonth();
  const d = await get('/api/summary?month=' + month);
  const root = h('div', { class: 'grid' });

  if (!d.hasAccounts) {
    root.append(h('div', { class: 'card empty' },
      h('div', { class: 'big' }, '👋'),
      h('p', null, 'Nothing here yet. Connect a bank, add an account by hand, or import a statement.'),
      h('a', { class: 'btn primary', href: '#/accounts' }, 'Add accounts')));
    return root;
  }

  for (const p of d.problems) {
    root.append(h('a', { class: 'banner', href: '#/accounts' }, '⚠️',
      h('div', null, h('strong', null, p.name + ': '), p.status === 'NEEDS_REAUTH' ? 'sign in again to keep it syncing.' : (p.error || 'last sync failed.'))));
  }
  if (d.demo) {
    root.append(h('div', { class: 'banner info' }, 'ℹ️', h('div', null, 'Demo data. Set APP_DEMO_DATA=false and connect your own accounts when you\'re ready.')));
  }

  // Net worth: the one number this screen leads with.
  const history = d.netWorthHistory;
  const monthAgo = history.length > 4 ? history[history.length - 5].cents : null;
  const change = monthAgo === null ? null : d.netWorthCents - monthAgo;
  const chart = h('div', { style: { marginTop: '12px' } });
  root.append(h('section', { class: 'card hero' },
    h('div', { class: 'label' }, 'Net worth'),
    h('div', { class: 'figure' }, money(d.netWorthCents)),
    h('div', { class: 'sub' },
      change === null ? `Assets ${money(d.assetsCents)} · Debts ${money(d.liabilitiesCents)}`
        : [h('span', { style: { color: change >= 0 ? 'var(--good-ink)' : 'var(--critical)', fontWeight: 600 } },
          (change >= 0 ? '▲ ' : '▼ ') + money(Math.abs(change))), ' in the last month'],
      d.lastSyncedAt ? ` · synced ${ago(d.lastSyncedAt)}` : ''),
    chart,
    h('div', { class: 'tiles', style: { marginTop: '14px' } },
      d.groups.map((g) => h('a', { class: 'tile', href: '#/accounts' },
        h('div', { class: 'label' }, g.name),
        h('div', { class: 'value' }, money(g.cents)))))));
  onMount(() => lineChart(chart, history));

  // This month.
  const t = d.totals;
  const monthNav = h('div', { class: 'month-nav' },
    h('button', { 'aria-label': 'Previous month', onclick: () => app.go('#/home?month=' + shiftMonth(month, -1)) }, '‹'),
    h('span', null, monthLabel(month)),
    h('button', { 'aria-label': 'Next month', disabled: d.currentMonth, onclick: () => app.go('#/home?month=' + shiftMonth(month, 1)) }, '›'));
  const progress = d.currentMonth ? `Day ${d.dayOfMonth} of ${d.daysInMonth}` : '';
  root.append(h('section', null,
    h('div', { class: 'toolbar' }, monthNav, h('span', { class: 'hint' }, progress)),
    h('div', { class: 'tiles' },
      tile('Income', t.incomeCents, 'var(--income)', t.grossIncomeCents !== t.incomeCents ? `${money(t.grossIncomeCents)} gross` : 'take-home'),
      tile('Spent', t.spentCents, 'var(--expense)', null),
      tile('Saved & invested', t.savedCents, 'var(--savings)', t.savingsRate === null ? null : pct(t.savingsRate) + ' of gross'),
      tile(t.leftoverCents >= 0 ? 'Left over' : 'Overspent', Math.abs(t.leftoverCents), null, null))));

  const grid = h('div', { class: 'grid two' });
  root.append(grid);

  // Spending targets.
  const targets = d.targets.spending;
  const targetCard = h('section', { class: 'card flush' },
    h('div', { class: 'card-head', style: { padding: '16px 16px 0' } }, h('h2', null, 'Spending targets'), h('a', { href: '#/plan' }, 'Edit')));
  if (!targets.length) {
    targetCard.append(h('div', { class: 'empty' }, 'Set monthly caps on the Plan tab.'));
  } else {
    const list = h('div', { class: 'list' });
    for (const s of targets.slice(0, 6)) list.append(targetRow(s));
    targetCard.append(list);
  }
  grid.append(targetCard);

  // Where it went.
  const spendCard = h('section', { class: 'card flush' },
    h('div', { class: 'card-head', style: { padding: '16px 16px 0' } }, h('h2', null, 'Top spending'), h('a', { href: '#/flow?month=' + month }, 'Cash flow')));
  const spendList = h('div', { class: 'list' });
  const topMax = Math.max(1, ...d.topSpending.map((c) => c.cents));
  for (const c of d.topSpending) {
    spendList.append(h('a', { class: 'meter-row tap', style: { display: 'block' },
      href: `#/activity?category=${c.categoryId ?? 'none'}&month=${month}` },
      h('div', { class: 'top' }, h('span', null, c.icon || '•'), h('span', { class: 'name' }, c.name), h('span', { class: 'nums' }, money(c.cents))),
      meterBar(c.cents / topMax)));
  }
  if (!d.topSpending.length) spendList.append(h('div', { class: 'empty' }, 'No spending yet this month.'));
  spendCard.append(spendList);
  grid.append(spendCard);

  // Goals.
  if (d.targets.goals.length) {
    const goalCard = h('section', { class: 'card flush' },
      h('div', { class: 'card-head', style: { padding: '16px 16px 0' } }, h('h2', null, 'Goals'), h('a', { href: '#/plan' }, 'Edit')));
    const list = h('div', { class: 'list' });
    for (const g of d.targets.goals) list.append(goalRow(g));
    goalCard.append(list);
    grid.append(goalCard);
  }

  // Credit cards.
  if (d.cards.length) {
    const cardCard = h('section', { class: 'card flush' },
      h('div', { class: 'card-head', style: { padding: '16px 16px 0' } }, h('h2', null, 'Credit cards')));
    const list = h('div', { class: 'list' });
    for (const c of d.cards) {
      const util = c.utilization;
      list.append(h('div', { class: 'meter-row' },
        h('div', { class: 'top' }, h('span', { class: 'name' }, `${c.institution ? c.institution + ' ' : ''}${c.name}`),
          h('span', { class: 'nums' }, money(-c.balanceCents, { cents: true }))),
        util === null ? null : meter(util, util > 0.5 ? 'over' : util > 0.3 ? 'watch' : 'ok'),
        h('div', { class: 'foot' },
          h('span', null, util === null ? 'No limit set' : `${pct(util)} of ${money(c.limitCents)} limit`),
          h('span', null, c.dueDate ? `Due ${shortDate(c.dueDate)}` : ''))));
    }
    cardCard.append(list);
    grid.append(cardCard);
  }

  // Six months side by side.
  const trend = h('div');
  grid.append(h('section', { class: 'card' + (d.cards.length && d.targets.goals.length ? ' span-2' : '') },
    h('div', { class: 'card-head' }, h('h2', null, 'Last six months')), trend));
  onMount(() => barChart(trend, d.trend.map((m) => ({ month: m.month, values: { income: m.incomeCents, spent: m.spentCents, saved: m.savedCents } })),
    [{ key: 'income', name: 'Income', color: 'var(--income)' }, { key: 'spent', name: 'Spent', color: 'var(--expense)' },
      { key: 'saved', name: 'Saved', color: 'var(--savings)' }]));

  // Recent.
  const recent = h('div', { class: 'list' });
  for (const tx of d.recent) recent.append(txnRow(tx, app, () => app.rerender()));
  root.append(h('section', { class: 'card flush' },
    h('div', { class: 'card-head', style: { padding: '16px 16px 4px' } }, h('h2', null, 'Recent'), h('a', { href: '#/activity' }, 'All activity')),
    recent));
  return root;
}

function tile(label, cents, color, delta) {
  return h('div', { class: 'tile' },
    h('div', { class: 'label' }, color ? h('span', { class: 'key', style: { background: color } }) : null, label),
    h('div', { class: 'value' }, money(cents)),
    delta ? h('div', { class: 'delta' }, delta) : null);
}

function meterBar(fraction) {
  const m = meter(fraction, 'ok');
  m.style.background = 'var(--sunken)';
  m.querySelector('.fill').style.background = 'var(--expense)';
  return m;
}

export function targetRow(s) {
  const status = s.status;
  const badge = status === 'over' ? h('span', { class: 'pill bad' }, '⚠ Over')
    : status === 'watch' ? h('span', { class: 'pill warn' }, '● Watch') : null;
  const left = s.limitCents - s.spentCents;
  return h('a', { class: 'meter-row tap', style: { display: 'block' }, href: `#/activity?category=${s.categoryId}` },
    h('div', { class: 'top' }, h('span', null, s.icon || '•'), h('span', { class: 'name' }, s.name), badge,
      h('span', { class: 'nums' }, `${money(s.spentCents)} / ${money(s.limitCents)}`)),
    meter(s.fraction, status, s.expectedFraction),
    h('div', { class: 'foot' }, h('span', null, left >= 0 ? `${money(left)} left` : `${money(-left)} over`),
      h('span', null, pct(s.fraction) + ' used')));
}

export function goalRow(g) {
  let nums;
  let foot;
  if (g.type === 'SAVINGS_RATE') {
    nums = `${g.currentPercent === null ? '—' : g.currentPercent.toFixed(0) + '%'} / ${g.targetPercent}%`;
    foot = g.detail;
  } else {
    nums = `${money(g.currentCents)} / ${money(g.targetCents)}`;
    foot = g.neededPerMonthCents ? `${g.detail} · ${money(g.neededPerMonthCents)}/mo to hit it by ${shortDate(g.targetDate)}` : g.detail;
  }
  return h('div', { class: 'meter-row' },
    h('div', { class: 'top' }, h('span', { class: 'name' }, g.name),
      g.status === 'done' ? h('span', { class: 'pill good' }, '✓ Done') : g.status === 'behind' ? h('span', { class: 'pill warn' }, '● Behind') : null,
      h('span', { class: 'nums' }, nums)),
    meter(g.fraction, g.status === 'done' ? 'done' : 'savings'),
    h('div', { class: 'foot' }, h('span', null, foot), h('span', null, pct(Math.min(g.fraction, 9.99)))));
}

export { dayLabel };
