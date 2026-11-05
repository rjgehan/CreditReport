import { get } from '../api.js';
import { h } from '../ui.js';
import { money, monthLabel, shortDate } from '../fmt.js';

// This month, looking ahead: what's free to spend from here to the end of the month
// once the pay still coming, the bills still due and your savings are counted. It
// replaces the budget card: the point is to show what you can spend, calmly.

const PARTS = [
  ['spent', 'Spent so far', 'var(--expense)'],
  ['due', 'Bills still due', 'var(--tax)'],
  ['saving', 'Saving', 'var(--savings)'],
  ['free', 'Free to spend', 'var(--income)'],
];
const VERDICT = { ok: ['good', 'On track'], tight: ['warn', 'A little tight'], under: ['warn', 'Saving less this month'],
  short: ['warn', 'Short this month'], unknown: ['', 'Pay not known yet'] };

function parts(f) {
  // Saving is what fits this month: all of the target, or what's left when less is.
  return { spent: f.spentCents, due: f.billsComingCents, saving: Math.max(0, Math.min(f.savingsTargetCents, f.roomCents)),
    free: f.freeCents };
}

/** Short and covered by savings reads differently from short with nothing behind it. */
function verdict(f) {
  if (f.status === 'short' && f.savingsBalanceCents >= -f.roomCents) return ['warn', 'Covered by savings'];
  return VERDICT[f.status];
}

/** The sentence under the number: what it means, in plain words. */
function message(f) {
  if (f.status === 'unknown') {
    return `No pay is on record for this month yet. You've spent ${money(f.spentCents)} so far, with ${money(f.billsComingCents)} of bills still due.`;
  }
  if (f.status === 'short') {
    const gap = -f.roomCents;
    return f.savingsBalanceCents >= gap
      ? `Bills and spending this month come to ${money(gap)} more than its pay. Your savings cover that, and next month starts fresh.`
      : `Bills and spending this month come to ${money(gap)} more than its pay. Go easy until the next payday.`;
  }
  if (f.status === 'under') {
    return `After bills and what you've spent, ${money(f.roomCents)} is left this month, so that's what you'll save of your ${money(f.savingsTargetCents)} target. Nothing is overspent.`;
  }
  if (!f.history) {
    return `That's after ${money(f.billsComingCents)} of bills still due and saving ${money(f.savingsTargetCents)}.`;
  }
  if (f.status === 'tight') {
    return `Your usual pace from here is about ${money(f.usualRestCents)}, a bit more than is free. Around ${money(f.perDayCents)} a day keeps you on track.`;
  }
  return `At your usual pace you'll spend about ${money(f.usualRestCents)} more on everyday things and still end the month with about ${money(f.endCents)} spare, on top of saving ${money(f.savingsTargetCents)}.`;
}

/** Spent, still due, saving and free, as one bar: where this month's pay goes. */
function splitBar(f) {
  const p = parts(f);
  const total = Object.values(p).reduce((s, v) => s + Math.max(0, v), 0) || 1;
  const bar = h('div', { class: 'split', role: 'img', 'aria-label': 'Where this month\'s pay goes' });
  for (const [key, label, color] of PARTS) {
    if (p[key] > 0) bar.append(h('span', { style: { flexGrow: String(p[key] / total), background: color }, title: label }));
  }
  return bar;
}

function legend(f) {
  const p = parts(f);
  return h('div', { class: 'split-legend' }, PARTS.filter(([key]) => p[key] > 0).map(([key, label, color]) =>
    h('div', { class: 'item' }, h('span', { class: 'key', style: { background: color } }), h('span', { class: 'name' }, label),
      h('span', { class: 'val' }, money(p[key])))));
}

function figure(f) {
  return h('div', { class: 'month-figure' },
    h('span', { class: 'big' }, money(Math.max(0, f.freeCents)), h('span', { class: 'unit' }, ' free to spend')),
    h('span', { class: 'pill ' + verdict(f)[0] }, verdict(f)[1]));
}

function perDay(f) {
  return f.freeCents > 0
    ? h('div', { class: 'forecast-day' }, `About ${money(f.perDayCents)} a day for the next ${f.daysLeft} day${f.daysLeft === 1 ? '' : 's'}`)
    : null;
}

/** Home's card: the number, what it means, and the bar. Tap for the whole month. */
export function forecastCard(f) {
  return h('a', { class: 'card month', href: '#/month', 'aria-label': 'This month, open the forecast' },
    h('div', { class: 'month-head' },
      h('span', { class: 'name' }, monthLabel(f.month)),
      h('span', { class: 'day' }, `${f.daysLeft} day${f.daysLeft === 1 ? '' : 's'} left`)),
    figure(f),
    perDay(f),
    splitBar(f),
    legend(f),
    h('p', { class: 'forecast-note' }, message(f)));
}

export async function render() {
  const f = await get('/api/forecast');
  const root = h('div', { class: 'grid' });

  root.append(h('section', { class: 'card month' },
    h('div', { class: 'month-head' },
      h('span', { class: 'name' }, monthLabel(f.month)),
      h('span', { class: 'day' }, `Day ${f.dayOfMonth} of ${f.daysInMonth}`)),
    figure(f), perDay(f),
    h('p', { class: 'forecast-note' }, message(f)),
    donut(f), legend(f)));

  if (f.oneOffCents > 0) {
    root.append(h('section', { class: 'card' }, h('p', { class: 'forecast-note', style: { margin: 0 } },
      `${money(f.oneOffCents)} of one-off purchases this month (like the furniture) isn't counted here. It comes out of savings, not out of the month.`)));
  }

  const list = (title, rows, empty) => {
    const l = h('div', { class: 'list' }, rows.length ? rows : h('div', { class: 'empty' }, empty));
    return [h('div', { class: 'section-title' }, title), h('section', { class: 'card flush' }, l)];
  };
  const row = (name, meta, cents, href) => h(href ? 'a' : 'div', { class: 'row' + (href ? ' tap' : ''), href },
    h('div', { class: 'main' }, h('div', { class: 'title' }, name), meta ? h('div', { class: 'meta' }, meta) : null),
    h('div', { class: 'amount' }, money(cents)));

  root.append(...list('Still coming in',
    f.incomeComing.map((c) => row(c.name, c.day ? shortDate(c.day) : 'Estimate from past months', c.cents)),
    f.receivedCents > 0 ? `All of this month's pay is in: ${money(f.receivedCents)}.` : 'No paydays left this month.'));
  root.append(...list('Bills still due',
    f.billsComing.map((c) => row(c.name, shortDate(c.day), c.cents, '#/recurring')),
    'Nothing else due this month.'));
  root.append(...list('Spent so far',
    f.spentByCategory.map((s) => row(s.name, null, s.cents,
      s.rest ? `#/activity?month=${f.month}` : `#/activity?category=${s.categoryId ?? 'none'}&month=${f.month}`)),
    'Nothing yet.'));
  root.append(h('p', { class: 'hint', style: { margin: '0 4px' } },
    'Free to spend is this month\'s pay, minus what you\'ve spent, the bills still due and your savings target. One-offs aren\'t counted. ',
    h('a', { href: '#/targets' }, 'Savings target'), ' · ', h('a', { href: '#/recurring' }, 'Monthly expenses')));
  return root;
}

/** The same four parts as a ring, like a pie chart, with what's free in the middle. */
function donut(f) {
  const p = parts(f);
  const total = Object.values(p).reduce((s, v) => s + Math.max(0, v), 0);
  const size = 180;
  const r = 70;
  const stroke = 26;
  const c = 2 * Math.PI * r;
  const svg = h('svg', { viewBox: `0 0 ${size} ${size}`, width: size, height: size, class: 'donut', role: 'img',
    'aria-label': 'Where this month\'s pay goes' });
  let offset = 0;
  if (total > 0) {
    for (const [key, label, color] of PARTS) {
      if (p[key] <= 0) continue;
      const len = (p[key] / total) * c;
      // A 2px gap between parts, so neighbours read as separate.
      const shown = Math.max(0, len - 2);
      svg.append(h('circle', { cx: size / 2, cy: size / 2, r, fill: 'none', stroke: color, 'stroke-width': stroke,
        'stroke-dasharray': `${shown} ${c - shown}`, 'stroke-dashoffset': String(-offset),
        transform: `rotate(-90 ${size / 2} ${size / 2})` }, h('title', null, label)));
      offset += len;
    }
  }
  svg.append(h('text', { x: size / 2, y: size / 2 - 2, 'text-anchor': 'middle', class: 'donut-value' }, money(Math.max(0, f.freeCents))));
  svg.append(h('text', { x: size / 2, y: size / 2 + 18, 'text-anchor': 'middle', class: 'donut-label' }, 'free'));
  return h('div', { class: 'donut-wrap' }, svg);
}
