import { get } from '../api.js';
import { h, onMount, segmented } from '../ui.js';
import { money, pct, monthLabel, shiftMonth, thisMonth, KIND_COLOR } from '../fmt.js';
import { sankey } from '../charts.js';

const RANGES = [
  ['month', 'Month'],
  ['3', '3-mo avg'],
  ['6', '6-mo avg'],
  ['12', '12-mo avg'],
];

export async function render(params, app) {
  const range = params.get('range') || 'month';
  const month = params.get('month') || thisMonth();
  const go = (r, m) => app.go(`#/flow?range=${r}&month=${m}`);

  let url;
  let periodLabel;
  if (range === 'month') {
    url = `/api/flow?from=${month}&to=${month}`;
    periodLabel = monthLabel(month);
  } else {
    // Averages end at the last complete month, so a half-finished one doesn't drag them down.
    const end = shiftMonth(thisMonth(), -1);
    const start = shiftMonth(end, -(Number(range) - 1));
    url = `/api/flow?from=${start}&to=${end}&average=true`;
    periodLabel = `${monthLabel(start, true)} – ${monthLabel(end, true)}, per month`;
  }
  const d = await get(url);
  const root = h('div', { class: 'grid' });

  const controls = h('div', { class: 'toolbar' }, segmented(RANGES, range, (r) => go(r, month)));
  if (range === 'month') {
    controls.append(h('div', { class: 'month-nav' },
      h('button', { 'aria-label': 'Previous month', onclick: () => go('month', shiftMonth(month, -1)) }, '‹'),
      h('span', null, monthLabel(month)),
      h('button', { 'aria-label': 'Next month', disabled: month >= thisMonth(), onclick: () => go('month', shiftMonth(month, 1)) }, '›')));
  }
  root.append(controls);

  root.append(h('div', { class: 'tiles' },
    tile('Income', d.cashIncomeCents, 'var(--income)', d.hasPaystub ? `${money(d.grossIncomeCents)} gross` : 'take-home'),
    tile('Spent', d.spentCents, 'var(--expense)', d.cashIncomeCents > 0 ? pct(d.spentCents / d.cashIncomeCents) + ' of take-home' : null),
    tile('Saved & invested', d.savedCents, 'var(--savings)', d.savingsRate === null ? null : pct(d.savingsRate) + ' savings rate'),
    tile(d.leftoverCents >= 0 ? 'Left over' : 'Overspent', Math.abs(d.leftoverCents), null, null)));

  const chart = h('div', { class: 'sankey-scroll' });
  const card = h('section', { class: 'card' },
    h('div', { class: 'card-head' }, h('h2', null, 'Where the money went'), h('span', { class: 'hint' }, periodLabel)),
    chart,
    h('div', { class: 'legend' },
      legend('Income', 'income'), legend('Spending', 'expense'), legend('Saving & investing', 'savings'),
      d.hasPaystub ? legend('Taxes & deductions', 'tax') : null));
  if (!d.hasPaystub) {
    card.append(h('p', { class: 'hint', style: { marginBottom: 0 } },
      'Starts from take-home pay. Add a paystub on the Plan tab to start from gross and see taxes and your 401(k).'));
  }
  root.append(card);
  const income = d.cashIncomeCents || d.grossIncomeCents;
  onMount(() => sankey(chart, d, {
    total: d.hasPaystub ? d.grossIncomeCents : income,
    onNode: (n) => {
      if (n.categoryId) app.go(`#/activity?category=${n.categoryId}${range === 'month' ? '&month=' + month : ''}`);
    },
  }));

  // The same numbers as a table: readable without hovering, and with amounts hidden still shows shares.
  const table = h('div', { class: 'list' });
  const groups = [
    ['Income', d.nodes.filter((n) => n.id.startsWith('inc:') || n.id.startsWith('net:') || n.id === 'from-savings')],
    ['Spending', d.nodes.filter((n) => n.id.startsWith('exp:'))],
    ['Saving & investing', d.nodes.filter((n) => n.id.startsWith('sav:'))],
    ['Taxes & deductions', d.nodes.filter((n) => n.id.startsWith('tax:') || n.id.startsWith('ded:'))],
  ];
  for (const [name, list] of groups) {
    if (!list.length) continue;
    table.append(h('div', { class: 'date-head' }, name));
    for (const n of list.sort((a, b) => b.valueCents - a.valueCents)) {
      const row = h(n.categoryId ? 'a' : 'div', { class: 'row' + (n.categoryId ? ' tap' : ''),
        href: n.categoryId ? `#/activity?category=${n.categoryId}${range === 'month' ? '&month=' + month : ''}` : null },
      h('span', { class: 'key', style: { background: KIND_COLOR[n.kind] } }),
      h('div', { class: 'main' }, h('div', { class: 'title' }, `${n.icon ? n.icon + ' ' : ''}${n.label}`)),
      h('div', { class: 'amount' }, money(n.valueCents), h('div', { class: 'meta' }, income > 0 ? pct(n.valueCents / income) : '')));
      table.append(row);
    }
  }
  root.append(h('section', { class: 'card flush' }, table));
  return root;
}

function tile(label, cents, color, delta) {
  return h('div', { class: 'tile' },
    h('div', { class: 'label' }, color ? h('span', { class: 'key', style: { background: color } }) : null, label),
    h('div', { class: 'value' }, money(cents)),
    delta ? h('div', { class: 'delta' }, delta) : null);
}

function legend(name, kind) {
  return h('span', null, h('span', { class: 'key', style: { background: KIND_COLOR[kind] } }), name);
}
