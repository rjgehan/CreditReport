// History looks back: how last month went (with a score), a calendar of how
// net worth moved day by day or month by month, and every past month.

import { get } from '../api.js';
import { h, clear, segmented } from '../ui.js';
import { money, pct, monthLabel, shiftMonth, thisMonth, privacy, parseDay } from '../fmt.js';
import { meter } from '../charts.js';

export async function render(params, app) {
  const calMonth = params.get('cal') || thisMonth();
  const calView = params.get('view') === 'months' ? 'months' : 'days';
  const [months, calendar] = await Promise.all([
    get('/api/history?count=12'),
    calView === 'days' ? get('/api/history/days?month=' + calMonth) : Promise.resolve(null),
  ]);
  // The recap defaults to the latest finished month that had any activity.
  const recapMonth = params.get('month') || months.find((m) => !m.current)?.month || shiftMonth(thisMonth(), -1);
  const recap = await get('/api/history/recap?month=' + recapMonth);
  const go = (changes) => {
    const p = new URLSearchParams({ month: recapMonth, cal: calMonth, view: calView, ...changes });
    app.go('#/history?' + p);
  };

  return h('div', { class: 'grid two' },
    h('div', { class: 'home-col' }, recapCard(recap), calendarCard(months, calendar, calMonth, calView, go)),
    h('div', { class: 'home-col' }, monthList(months, recapMonth, go)));
}

// --- Recap ---------------------------------------------------------------------------

function recapCard(r) {
  const s = r.score;
  const head = h('div', { class: 'recap-head' },
    h('span', { class: 'name' }, monthLabel(s.month) + (s.current ? ' so far' : '')),
    r.empty ? null : h('span', { class: 'score' }, h('span', { class: 'num' }, String(s.score)),
      h('span', { class: 'grade grade-' + s.grade }, s.grade)));
  if (r.empty) {
    return h('section', { class: 'card recap' }, head, h('div', { class: 'empty' }, 'No activity this month.'));
  }
  const facts = [];
  if (privacy.on) {
    if (s.savingsRate !== null) facts.push(`Kept ${pct(s.savingsRate)}`);
  } else {
    facts.push(`Kept ${money(s.keptCents)}`);
  }
  if (s.netWorthChangePercent !== null) facts.push(`Net worth ${signed(s.netWorthChangePercent)}`);

  // The four parts of the score as bars; the sentence behind each is in its tooltip.
  const parts = h('div', { class: 'parts' });
  for (const p of s.parts) {
    const f = p.points / p.outOf;
    parts.append(h('div', { class: 'part', title: p.detail },
      h('div', { class: 'part-line' }, h('span', { class: 'name' }, p.name), h('span', { class: 'pts' }, `${p.points}/${p.outOf}`)),
      meter(f, f >= 0.75 ? 'ok' : f >= 0.4 ? 'watch' : 'over', null, 'xs')));
  }

  const card = h('section', { class: 'card recap' }, head, h('div', { class: 'recap-facts' }, facts.join(' · ')), parts);
  if (r.categories.length) {
    const list = h('div', { class: 'recap-cats' });
    for (const c of r.categories) {
      const delta = c.cents - c.previousCents;
      const dir = c.previousCents === 0 || delta === 0 ? '' : delta > 0 ? 'more' : 'less';
      const change = c.previousCents === 0 ? 'new'
        : delta === 0 ? 'same'
          : privacy.on ? `${Math.abs(100 * delta / c.previousCents).toFixed(0)}%` : money(Math.abs(delta));
      list.append(h('a', { class: 'cat-row', href: `#/activity?category=${c.categoryId ?? 'none'}&month=${s.month}`,
        'aria-label': `${c.name} ${money(c.cents)}, ${dir ? (dir === 'more' ? 'up ' : 'down ') + change : change} from the month before` },
      h('span', { class: 'name' }, c.name),
      h('span', { class: 'amt' }, money(c.cents)),
      h('span', { class: 'delta ' + dir }, dir ? h('span', { class: 'arrow', 'aria-hidden': 'true' }, dir === 'more' ? '▲' : '▼') : null, change)));
    }
    card.append(list);
  }
  return card;
}

function signed(percent) {
  return (percent >= 0 ? '+' : '−') + Math.abs(percent).toFixed(1) + '%';
}

// --- Calendar ---------------------------------------------------------------------------

function calendarCard(months, calendar, calMonth, view, go) {
  const card = h('section', { class: 'card cal' });
  const toggle = segmented([['days', 'Days'], ['months', 'Months']], view, (v) => go({ view: v }));
  if (view === 'months') {
    card.append(h('div', { class: 'cal-head' }, h('span', { class: 'cal-title' }, 'Net worth by month'), toggle));
    card.append(monthGrid(months, go));
    return card;
  }

  const nav = h('div', { class: 'month-nav' },
    h('button', { 'aria-label': 'Previous month', onclick: () => go({ cal: shiftMonth(calMonth, -1) }) }, '‹'),
    h('span', null, monthLabel(calMonth, true)),
    h('button', { 'aria-label': 'Next month', disabled: calMonth >= thisMonth(), onclick: () => go({ cal: shiftMonth(calMonth, 1) }) }, '›'));
  const days = calendar.days;
  const known = days.filter((d) => d.changePercent !== null);
  const monthChange = calendar.changePercent;
  const max = Math.max(0.05, ...known.map((d) => Math.abs(d.changePercent)));
  card.append(h('div', { class: 'cal-head' }, nav, toggle));

  // The month's change until a day is tapped, then that day's.
  const monthLine = () => (monthChange === null ? []
    : [h('span', { class: 'cal-total ' + (monthChange >= 0 ? 'up' : 'down') }, signed(monthChange)), ' this month']);
  const detail = h('div', { class: 'cal-detail' }, monthLine());
  const grid = h('div', { class: 'cal-grid', role: 'grid', 'aria-label': 'Daily net worth change' });
  for (const w of ['S', 'M', 'T', 'W', 'T', 'F', 'S']) grid.append(h('div', { class: 'cal-dow' }, w));
  const offset = parseDay(days[0].day).getDay();
  for (let i = 0; i < offset; i++) grid.append(h('div'));
  for (const d of days) {
    const n = parseDay(d.day).getDate();
    const cell = h('button', { class: 'cal-day', type: 'button', disabled: d.netWorthCents === null,
      'aria-label': `${monthLabel(calMonth)} ${n}${d.changePercent === null ? '' : ', ' + signed(d.changePercent)}` },
    h('span', { class: 'dn' }, String(n)),
    h('span', { class: 'dp' }, d.changePercent === null ? '' : signed(d.changePercent)));
    if (d.changePercent !== null && Math.abs(d.changePercent) >= 0.005) {
      const strength = Math.round(12 + 43 * Math.min(1, Math.abs(d.changePercent) / max));
      cell.style.background = `color-mix(in srgb, ${d.changePercent >= 0 ? 'var(--income)' : 'var(--critical)'} ${strength}%, transparent)`;
    }
    cell.addEventListener('click', () => {
      grid.querySelectorAll('.cal-day.on').forEach((c) => c.classList.remove('on'));
      cell.classList.add('on');
      const parts = [`${monthLabel(calMonth, true)} ${n}`];
      if (d.changePercent !== null) parts.push(`net worth ${signed(d.changePercent)}`);
      if (d.spentCents) parts.push(`spent ${money(d.spentCents)}`);
      if (d.incomeCents) parts.push(`income ${money(d.incomeCents)}`);
      clear(detail).append(parts.join(' · '));
    });
    grid.append(cell);
  }
  card.append(grid, detail);
  return card;
}

function monthGrid(months, go) {
  const grid = h('div', { class: 'mon-grid' });
  const byMonth = new Map(months.map((m) => [m.month, m]));
  const now = thisMonth();
  for (let i = 11; i >= 0; i--) {
    const key = shiftMonth(now, -i);
    const m = byMonth.get(key);
    const change = m?.netWorthChangePercent ?? null;
    const cell = h('button', { class: 'mon-cell', type: 'button', disabled: !m, onclick: () => go({ month: key }) },
      h('span', { class: 'mn' }, monthLabel(key, true)),
      h('span', { class: 'mp' }, change === null ? '—' : signed(change)),
      h('span', { class: 'ms' }, m ? `${m.score} ${m.grade}` : ''));
    if (change !== null && Math.abs(change) >= 0.05) {
      const strength = Math.round(12 + 43 * Math.min(1, Math.abs(change) / 5));
      cell.style.background = `color-mix(in srgb, ${change >= 0 ? 'var(--income)' : 'var(--critical)'} ${strength}%, transparent)`;
    }
    grid.append(cell);
  }
  return grid;
}

// --- Past months ------------------------------------------------------------------------

function monthList(months, selected, go) {
  const card = h('section', { class: 'card flush' }, h('div', { class: 'tcard-head' }, h('h2', null, 'Past months')));
  if (!months.length) {
    card.append(h('div', { class: 'empty' }, 'Months appear here once there is activity.'));
    return card;
  }
  for (const m of months) {
    const worth = m.netWorthChangePercent === null ? '' : signed(m.netWorthChangePercent);
    card.append(h('button', { class: 'month-row' + (m.month === selected ? ' on' : ''), type: 'button',
      onclick: () => { go({ month: m.month }); } },
    h('span', { class: 'name' }, monthLabel(m.month) + (m.current ? ' so far' : '')),
    h('span', { class: 'meta ' + (m.netWorthChangePercent >= 0 ? 'up' : 'down') }, worth),
    h('span', { class: 'score-sm' }, String(m.score), h('span', { class: 'grade grade-' + m.grade }, m.grade))));
  }
  return card;
}
