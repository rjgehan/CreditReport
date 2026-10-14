// Flow answers one question: where did the money go in this period?
// A period switcher and one Sankey that fits the screen. Nothing else.

import { get } from '../api.js';
import { h, onMount, segmented } from '../ui.js';
import { money, pct, monthLabel, shiftMonth, thisMonth } from '../fmt.js';
import { sankey } from '../charts.js';

const RANGES = [
  ['month', '1M'],
  ['3', '3M'],
  ['6', '6M'],
  ['12', '12M'],
];
const RANGE_NAMES = { month: 'One month', 3: 'Last 3 months', 6: 'Last 6 months', 12: 'Last 12 months' };
const MODES = [['total', 'Total'], ['avg', 'Avg']];

export async function render(params, app) {
  const range = params.get('range') || 'month';
  const month = params.get('month') || thisMonth();
  const mode = params.get('mode') === 'avg' ? 'avg' : 'total';
  const go = (r, m, md) => app.go(`#/flow?range=${r}&month=${m}&mode=${md || mode}`);

  let url;
  let left;
  let caption = null;
  if (range === 'month') {
    url = `/api/flow?from=${month}&to=${month}`;
    left = h('div', { class: 'month-nav' },
      h('button', { 'aria-label': 'Previous month', onclick: () => go('month', shiftMonth(month, -1)) }, '‹'),
      h('span', null, monthLabel(month, true)),
      h('button', { 'aria-label': 'Next month', disabled: month >= thisMonth(), onclick: () => go('month', shiftMonth(month, 1)) }, '›'));
  } else {
    // Multi-month ranges end at the last complete month, so a half-finished one doesn't skew them.
    const end = shiftMonth(thisMonth(), -1);
    const start = shiftMonth(end, -(Number(range) - 1));
    url = `/api/flow?from=${start}&to=${end}&average=${mode === 'avg'}`;
    left = segmented(MODES, mode, (md) => go(range, month, md));
    left.querySelectorAll('button').forEach((b, i) => b.setAttribute('aria-label', i ? 'Average per month' : 'Total for the period'));
    caption = `${monthLabel(start, true)} – ${monthLabel(end, true)}${mode === 'avg' ? ', per month' : ', total'}`;
  }
  const switcher = segmented(RANGES, range, (r) => go(r, month));
  switcher.querySelectorAll('button').forEach((b, i) => b.setAttribute('aria-label', RANGE_NAMES[RANGES[i][0]]));

  const d = await get(url);
  const flow = compactFlow(d);
  const chart = h('div');
  onMount(() => sankey(chart, flow, {
    total: flow.income,
    onNode: (n) => {
      const suffix = range === 'month' ? `&month=${month}` : '';
      if (n.categoryId) app.go(`#/activity?category=${n.categoryId}${suffix}`);
      else if (n.id === 'exp:other' && range === 'month') app.go(`#/activity?month=${month}`);
      else if (n.id === 'in' || n.id === 'taxes') app.go('#/paychecks');
    },
  }));

  return h('div', null,
    h('div', { class: 'toolbar period' }, left, switcher),
    h('section', { class: 'card flow-card' }, caption ? h('div', { class: 'flow-caption' }, caption) : null, chart),
    screenReaderList(d, flow.income));
}

/**
 * Folds the server's detailed graph (per-employer gross and take-home, an
 * Income hub, five tax lines) into three columns that fit a phone:
 * where it came from, what it did, and where exactly it went.
 */
export function compactFlow(d) {
  const node = (id) => d.nodes.find((n) => n.id === id);
  const value = (id) => node(id)?.valueCents || 0;
  const taxes = d.nodes.filter((n) => n.id.startsWith('tax:') || n.id.startsWith('ded:')).reduce((s, n) => s + n.valueCents, 0);
  const expLinks = d.links.filter((l) => l.source === 'spending' && l.target.startsWith('exp:')).sort((a, b) => b.valueCents - a.valueCents);
  const savLinks = d.links.filter((l) => l.source === 'saved' && l.target.startsWith('sav:')).sort((a, b) => b.valueCents - a.valueCents);
  const spending = expLinks.reduce((s, l) => s + l.valueCents, 0);
  const saved = savLinks.reduce((s, l) => s + l.valueCents, 0);
  const leftover = value('leftover');
  const fromSavings = value('from-savings');
  // Derived from the parts rather than read off a node, so averaged months still balance to the cent.
  const income = taxes + spending + saved + leftover - fromSavings;

  const nodes = [];
  const links = [];
  if (fromSavings > 0) nodes.push({ id: 'from', label: 'From savings', kind: 'savings' });
  nodes.push({ id: 'in', label: d.hasPaystub ? 'Gross income' : 'Income', kind: 'income' });
  nodes.push({ id: 'spend', label: 'Spending', kind: 'expense' });
  nodes.push({ id: 'save', label: 'Saved', kind: 'savings' });
  if (leftover > 0) nodes.push({ id: 'left', label: 'Left over', kind: 'income' });
  if (taxes > 0) nodes.push({ id: 'taxes', label: 'Taxes', kind: 'tax' });

  // Money out of savings pays for spending first, so its ribbon enters the top of Spending without crossing.
  const fsToSpend = Math.min(fromSavings, spending);
  const fsToSave = fromSavings - fsToSpend;
  links.push({ source: 'from', target: 'spend', valueCents: fsToSpend });
  links.push({ source: 'from', target: 'save', valueCents: fsToSave });
  links.push({ source: 'in', target: 'spend', valueCents: spending - fsToSpend });
  links.push({ source: 'in', target: 'save', valueCents: Math.max(0, saved - fsToSave) });
  links.push({ source: 'in', target: 'left', valueCents: leftover });
  links.push({ source: 'in', target: 'taxes', valueCents: taxes });

  for (const l of expLinks) {
    const n = node(l.target);
    nodes.push({ id: l.target, label: n?.label || 'Other', kind: 'expense', categoryId: n?.categoryId ?? null });
    links.push({ source: 'spend', target: l.target, valueCents: l.valueCents });
  }
  for (const l of savLinks) {
    const n = node(l.target);
    nodes.push({ id: l.target, label: n?.label || 'Saved', kind: 'savings', categoryId: n?.categoryId ?? null });
    links.push({ source: 'save', target: l.target, valueCents: l.valueCents });
  }
  return { nodes, links, income };
}

/** The full breakdown for VoiceOver, including the per-employer and per-tax lines the graph folds together. */
function screenReaderList(d, income) {
  const groups = [
    ['Income', (n) => n.id.startsWith('inc:') || n.id.startsWith('gross:') || n.id.startsWith('match:') || n.id === 'from-savings'],
    ['Spending', (n) => n.id.startsWith('exp:')],
    ['Saving and investing', (n) => n.id.startsWith('sav:')],
    ['Taxes and deductions', (n) => n.id.startsWith('tax:') || n.id.startsWith('ded:')],
  ];
  const list = h('ul', { class: 'sr-only', 'aria-label': 'Cash flow breakdown' });
  for (const [name, test] of groups) {
    const items = d.nodes.filter(test).sort((a, b) => b.valueCents - a.valueCents);
    if (!items.length) continue;
    list.append(h('li', null, name, h('ul', null, items.map((n) =>
      h('li', null, `${n.label}: ${money(n.valueCents)}${income > 0 ? ', ' + pct(n.valueCents / income) : ''}`)))));
  }
  return list;
}
