// Hand-rolled SVG charts: a Sankey, a net-worth line and grouped monthly bars.
// No library: three small charts don't justify one, and this keeps the CSP strict.

import { h, clear } from './ui.js';
import { money, pct, KIND_COLOR, monthLabel, shortDate } from './fmt.js';

function tooltip(container) {
  let tip = container.querySelector(':scope > .tooltip');
  if (!tip) {
    tip = h('div', { class: 'tooltip', hidden: true });
    container.append(tip);
  }
  return {
    show(x, y, title, rows) {
      clear(tip);
      if (title) tip.append(h('div', { class: 't-title' }, title));
      for (const r of rows) {
        tip.append(h('div', { class: 't-row' },
          r.color ? h('span', { class: 'key line', style: { background: r.color } }) : null,
          h('strong', null, r.value), r.name ? h('span', { class: 't-name' }, r.name) : null));
      }
      tip.hidden = false;
      const w = container.clientWidth;
      const tw = tip.offsetWidth;
      const left = Math.max(tw / 2 + 4, Math.min(w - tw / 2 - 4, x));
      tip.style.left = left + 'px';
      tip.style.top = y + 'px';
    },
    hide() { tip.hidden = true; },
  };
}

// --- Sankey -----------------------------------------------------------------------

/**
 * @param data {nodes:[{id,label,kind,valueCents,icon,categoryId}], links:[{source,target,valueCents}]}
 * @param opts.total the amount percentages are taken of (income)
 * @param opts.onNode called with a node when it is tapped
 */
export function sankey(container, data, opts = {}) {
  clear(container);
  container.classList.add('chart');
  if (!data.links.length) {
    container.append(h('div', { class: 'empty' }, h('div', { class: 'big' }, '🌊'), 'No money moved in this period yet.'));
    return;
  }

  const nodes = data.nodes.map((n) => ({ ...n, in: [], out: [] }));
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const links = data.links
    .filter((l) => byId.has(l.source) && byId.has(l.target))
    .map((l) => ({ ...l, s: byId.get(l.source), t: byId.get(l.target) }));
  for (const l of links) { l.s.out.push(l); l.t.in.push(l); }

  // Columns by longest path from a source. Sinks stay where their inputs put
  // them, so taxes sit beside gross pay instead of being dragged to the far right.
  const depth = new Map();
  const visit = (n, d) => {
    if ((depth.get(n) ?? -1) >= d) return;
    depth.set(n, d);
    n.out.forEach((l) => visit(l.t, d + 1));
  };
  nodes.filter((n) => !n.in.length).forEach((n) => visit(n, 0));
  const maxDepth = Math.max(...depth.values());
  const columns = Array.from({ length: maxDepth + 1 }, () => []);
  nodes.forEach((n) => { n.col = depth.get(n) ?? 0; columns[n.col].push(n); });

  // The scroll wrapper pads 16px each side so the chart can bleed to the card edge on phones.
  const avail = container.clientWidth - 32;
  const narrow = avail < 560;
  const labelSpace = narrow ? 150 : 190;
  const width = Math.max(narrow ? 640 : 700, avail);
  const nodeW = 10;
  const pad = 22;
  const tallest = Math.max(...columns.map((c) => c.length));
  const height = Math.max(340, tallest * (pad + 8) + 40);
  const top = 8;
  const usable = height - top * 2;

  const ky = Math.min(...columns.map((c) => (usable - (c.length - 1) * pad) / c.reduce((s, n) => s + n.valueCents, 0)));
  const colX = (c) => (maxDepth === 0 ? 0 : (c * (width - labelSpace - nodeW)) / maxDepth);

  for (const col of columns) {
    const total = col.reduce((s, n) => s + Math.max(2, n.valueCents * ky), 0) + (col.length - 1) * pad;
    let y = top + (usable - total) / 2;
    for (const n of col) {
      n.x = colX(n.col);
      n.h = Math.max(2, n.valueCents * ky);
      n.y = y;
      y += n.h + pad;
    }
  }
  // Ribbons leave and arrive in the vertical order of what they connect to, so they don't cross needlessly.
  for (const n of nodes) {
    n.out.sort((a, b) => a.t.y - b.t.y);
    n.in.sort((a, b) => a.s.y - b.s.y);
    let sy = n.y;
    for (const l of n.out) { l.w = Math.max(1, l.valueCents * ky); l.sy = sy; sy += l.w; }
    let ty = n.y;
    for (const l of n.in) { l.ty = ty; ty += Math.max(1, l.valueCents * ky); }
  }

  const svg = h('svg', { class: 'sankey', viewBox: `0 0 ${width} ${height}`, width, height, role: 'img',
    'aria-label': 'Where the money went' });
  svg.style.width = width + 'px';
  svg.style.minWidth = width + 'px';
  const linkLayer = h('g');
  const nodeLayer = h('g');
  svg.append(linkLayer, nodeLayer);
  const tip = tooltip(container);
  const total = opts.total || 0;
  const share = (cents) => (total > 0 ? ' · ' + pct(cents / total) : '');

  for (const l of links) {
    const x0 = l.s.x + nodeW;
    const x1 = l.t.x;
    const mx = (x0 + x1) / 2;
    const d = `M${x0},${l.sy} C${mx},${l.sy} ${mx},${l.ty} ${x1},${l.ty}`
      + ` L${x1},${l.ty + l.w} C${mx},${l.ty + l.w} ${mx},${l.sy + l.w} ${x0},${l.sy + l.w} Z`;
    const path = h('path', { class: 'link', d, fill: KIND_COLOR[l.t.kind] || 'var(--tax)' });
    l.el = path;
    const show = (e) => {
      const r = container.getBoundingClientRect();
      tip.show(e.clientX - r.left + container.scrollLeft, e.clientY - r.top, `${l.s.label} → ${l.t.label}`,
        [{ value: money(l.valueCents) + share(l.valueCents), color: KIND_COLOR[l.t.kind] }]);
    };
    path.addEventListener('pointermove', show);
    path.addEventListener('pointerleave', () => tip.hide());
    linkLayer.append(path);
  }

  for (const n of nodes) {
    const g = h('g', { class: 'node', tabindex: 0, role: 'button', 'aria-label': `${n.label} ${money(n.valueCents)}` });
    g.append(h('rect', { x: n.x, y: n.y, width: nodeW, height: n.h, rx: 2, fill: KIND_COLOR[n.kind] || 'var(--tax)' }));
    const lx = n.x + nodeW + 6;
    const cy = n.y + n.h / 2;
    const name = (n.icon ? n.icon + ' ' : '') + n.label;
    if (!n.out.length) {
      // End points sit in their own label column with room to the right: one line each, so a
      // tall stack of small categories doesn't collide.
      g.append(h('text', { class: 'node-label', x: lx, y: cy + 4 }, name + '  ',
        h('tspan', { class: 'node-value' }, money(n.valueCents) + share(n.valueCents))));
    } else {
      g.append(h('text', { class: 'node-label', x: lx, y: cy - 1 }, name),
        h('text', { class: 'node-value', x: lx, y: cy + 13 }, money(n.valueCents) + share(n.valueCents)));
    }
    const highlight = (on) => {
      svg.classList.toggle('dim', on);
      [...n.in, ...n.out].forEach((l) => l.el.classList.toggle('hot', on));
    };
    g.addEventListener('pointerenter', () => highlight(true));
    g.addEventListener('pointerleave', () => highlight(false));
    g.addEventListener('focus', () => highlight(true));
    g.addEventListener('blur', () => highlight(false));
    if (opts.onNode) {
      g.addEventListener('click', () => opts.onNode(n));
      g.addEventListener('keydown', (e) => { if (e.key === 'Enter') opts.onNode(n); });
    }
    nodeLayer.append(g);
  }
  container.append(svg);
}

// --- Net worth line ------------------------------------------------------------

export function lineChart(container, points, opts = {}) {
  clear(container);
  container.classList.add('chart');
  if (points.length < 2) {
    container.append(h('div', { class: 'empty' }, 'History builds up as balances are recorded.'));
    return;
  }
  const width = Math.max(280, container.clientWidth);
  const height = opts.height || 170;
  const padL = 4, padR = 4, padT = 10, padB = 22;
  const values = points.map((p) => p.cents);
  let lo = Math.min(...values);
  let hi = Math.max(...values);
  if (hi === lo) { hi += 100; lo -= 100; }
  const span = hi - lo;
  lo -= span * 0.12;
  hi += span * 0.08;
  const t0 = new Date(points[0].day).getTime();
  const t1 = new Date(points[points.length - 1].day).getTime();
  const x = (p) => padL + ((new Date(p.day).getTime() - t0) / (t1 - t0 || 1)) * (width - padL - padR);
  const y = (c) => padT + (1 - (c - lo) / (hi - lo)) * (height - padT - padB);
  const color = opts.color || 'var(--income)';

  const svg = h('svg', { viewBox: `0 0 ${width} ${height}`, height, role: 'img', 'aria-label': opts.label || 'Net worth over time' });
  // Three recessive gridlines with compact labels; the hero figure carries the exact value.
  for (let i = 0; i < 3; i++) {
    const c = lo + ((hi - lo) * (i + 0.5)) / 3;
    svg.append(h('line', { class: 'grid-line', x1: 0, x2: width, y1: y(c), y2: y(c) }));
    svg.append(h('text', { x: width - 2, y: y(c) - 4, 'text-anchor': 'end' }, money(Math.round(c), { compact: true })));
  }
  let seen = new Set();
  for (const p of points) {
    const key = p.day.slice(0, 7);
    if (!seen.has(key) && p.day.slice(8) <= '07') {
      seen.add(key);
      svg.append(h('text', { x: x(p), y: height - 6, 'text-anchor': 'middle' }, monthLabel(key, true)));
    }
  }
  const line = points.map((p, i) => `${i ? 'L' : 'M'}${x(p).toFixed(1)},${y(p.cents).toFixed(1)}`).join(' ');
  svg.append(h('path', { d: `${line} L${x(points[points.length - 1])},${height - padB} L${x(points[0])},${height - padB} Z`,
    fill: color, 'fill-opacity': 0.1 }));
  svg.append(h('path', { d: line, fill: 'none', stroke: color, 'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
  const last = points[points.length - 1];
  svg.append(h('circle', { cx: x(last), cy: y(last.cents), r: 4, fill: color, stroke: 'var(--surface)', 'stroke-width': 2 }));

  const cross = h('line', { class: 'axis-line', y1: padT, y2: height - padB, visibility: 'hidden' });
  const dot = h('circle', { r: 4, fill: color, stroke: 'var(--surface)', 'stroke-width': 2, visibility: 'hidden' });
  svg.append(cross, dot);
  const tip = tooltip(container);
  const hit = h('rect', { x: 0, y: 0, width, height, fill: 'transparent' });
  hit.addEventListener('pointermove', (e) => {
    const r = svg.getBoundingClientRect();
    const px = ((e.clientX - r.left) / r.width) * width;
    let best = points[0];
    for (const p of points) if (Math.abs(x(p) - px) < Math.abs(x(best) - px)) best = p;
    cross.setAttribute('x1', x(best)); cross.setAttribute('x2', x(best)); cross.setAttribute('visibility', 'visible');
    dot.setAttribute('cx', x(best)); dot.setAttribute('cy', y(best.cents)); dot.setAttribute('visibility', 'visible');
    tip.show((x(best) / width) * r.width, (y(best.cents) / height) * r.height, shortDate(best.day),
      [{ value: money(best.cents), name: opts.label || 'Net worth', color }]);
  });
  hit.addEventListener('pointerleave', () => { cross.setAttribute('visibility', 'hidden'); dot.setAttribute('visibility', 'hidden'); tip.hide(); });
  svg.append(hit);
  container.append(svg);
}

// --- Grouped monthly bars --------------------------------------------------------

/**
 * @param months [{month, values: {key: cents}}]
 * @param series [{key, name, color}]
 */
export function barChart(container, months, series, opts = {}) {
  clear(container);
  container.classList.add('chart');
  const width = Math.max(280, container.clientWidth);
  const height = opts.height || 190;
  const padT = 8, padB = 22, padL = 40;
  const max = Math.max(1, ...months.flatMap((m) => series.map((s) => Math.max(0, m.values[s.key] || 0))));
  const nice = niceCeil(max);
  const y = (c) => padT + (1 - c / nice) * (height - padT - padB);
  const band = (width - padL) / months.length;
  const barW = Math.min(24, (band - 14) / series.length - 2);
  const svg = h('svg', { viewBox: `0 0 ${width} ${height}`, height, role: 'img', 'aria-label': opts.label || 'Monthly totals' });
  for (let i = 1; i <= 3; i++) {
    const c = (nice * i) / 3;
    svg.append(h('line', { class: 'grid-line', x1: 0, x2: width, y1: y(c), y2: y(c) }));
    svg.append(h('text', { x: 0, y: y(c) - 4 }, money(c, { compact: true })));
  }
  svg.append(h('line', { class: 'axis-line', x1: 0, x2: width, y1: y(0), y2: y(0) }));
  const tip = tooltip(container);
  months.forEach((m, i) => {
    const groupW = series.length * (barW + 2) - 2;
    const gx = padL + i * band + (band - groupW) / 2;
    series.forEach((s, j) => {
      const v = Math.max(0, m.values[s.key] || 0);
      const bx = gx + j * (barW + 2);
      const by = y(v);
      const bh = Math.max(0, y(0) - by);
      if (bh > 0) svg.append(h('path', { d: roundedTop(bx, by, barW, bh, Math.min(4, bh)), fill: s.color }));
    });
    svg.append(h('text', { x: padL + i * band + band / 2, y: height - 6, 'text-anchor': 'middle' }, monthLabel(m.month, true)));
    const hit = h('rect', { x: padL + i * band, y: 0, width: band, height, fill: 'transparent' });
    hit.addEventListener('pointermove', () => {
      const r = svg.getBoundingClientRect();
      tip.show(((padL + i * band + band / 2) / width) * r.width, (padT / height) * r.height + 10, monthLabel(m.month),
        series.map((s) => ({ value: money(m.values[s.key] || 0), name: s.name, color: s.color })));
    });
    hit.addEventListener('pointerleave', () => tip.hide());
    svg.append(hit);
  });
  container.append(svg);
  container.append(h('div', { class: 'legend' }, series.map((s) =>
    h('span', null, h('span', { class: 'key', style: { background: s.color } }), s.name))));
}

function roundedTop(x, y, w, hgt, r) {
  return `M${x},${y + hgt} L${x},${y + r} Q${x},${y} ${x + r},${y} L${x + w - r},${y} Q${x + w},${y} ${x + w},${y + r} L${x + w},${y + hgt} Z`;
}

function niceCeil(v) {
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 1.5, 2, 3, 4, 5, 6, 8, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}

// --- Meters ------------------------------------------------------------------------

/** A progress bar. status: ok | watch | over | done | savings. expected: 0..1 pace marker. */
export function meter(fraction, status = 'ok', expected = null) {
  const el = h('div', { class: 'meter ' + status, role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': 100,
    'aria-valuenow': Math.round(Math.min(1, fraction) * 100) });
  el.append(h('div', { class: 'fill', style: { width: Math.min(100, Math.max(0, fraction * 100)) + '%' } }));
  if (expected !== null && expected !== undefined && expected > 0 && expected < 1) {
    el.append(h('div', { class: 'pace', style: { left: `calc(${expected * 100}% - 1px)` }, title: 'Where an even pace would be today' }));
  }
  return el;
}
