// Hand-rolled SVG charts: a Sankey, a net-worth line and meters.
// No library: a few small charts don't justify one, and this keeps the CSP strict.

import { h, clear } from './ui.js';
import { money, pct, KIND_COLOR, monthLabel, shortDate, privacy, change } from './fmt.js';

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
 * Draws at the container's own width; nothing scrolls sideways.
 *
 * @param data {nodes:[{id,label,kind,valueCents,categoryId}], links:[{source,target,valueCents}]}
 *        Nodes are drawn in the order given within each column.
 * @param opts.total what shares are a percentage of (income)
 * @param opts.onNode called with a node when it is tapped
 */
export function sankey(container, data, opts = {}) {
  clear(container);
  container.classList.add('chart');
  if (!data.links.length) {
    container.append(h('div', { class: 'flow-empty' }, 'No money moved in this period yet.'));
    return;
  }

  const nodes = data.nodes.map((n) => ({ ...n, in: [], out: [] }));
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const links = data.links
    .filter((l) => l.valueCents > 0 && byId.has(l.source) && byId.has(l.target))
    .map((l) => ({ ...l, s: byId.get(l.source), t: byId.get(l.target) }));
  for (const l of links) { l.s.out.push(l); l.t.in.push(l); }
  for (const n of nodes) {
    n.value = Math.max(n.in.reduce((s, l) => s + l.valueCents, 0), n.out.reduce((s, l) => s + l.valueCents, 0));
  }
  const live = nodes.filter((n) => n.value > 0);

  // Columns by longest path from a source.
  const depth = new Map();
  const visit = (n, d) => {
    if ((depth.get(n) ?? -1) >= d) return;
    depth.set(n, d);
    n.out.forEach((l) => visit(l.t, d + 1));
  };
  live.filter((n) => !n.in.length).forEach((n) => visit(n, 0));
  const maxDepth = Math.max(...depth.values());
  const columns = Array.from({ length: maxDepth + 1 }, () => []);
  live.forEach((n) => { n.col = depth.get(n) ?? 0; columns[n.col].push(n); });

  const width = Math.max(240, container.clientWidth);
  const wide = width >= 560;
  const labelSpace = wide ? 190 : 120;
  const nodeW = wide ? 10 : 8;
  const pad = wide ? 22 : 18;
  const last = columns[maxDepth];
  const height = Math.max(300, 26 * last.length + 40);
  const top = 8;
  const usable = height - top * 2;

  const ky = Math.min(...columns.map((c) => (usable - (c.length - 1) * pad) / c.reduce((s, n) => s + n.value, 0)));
  const colX = (c) => (maxDepth === 0 ? 0 : (c * (width - labelSpace - nodeW)) / maxDepth);

  for (const col of columns) {
    const total = col.reduce((s, n) => s + Math.max(2, n.value * ky), 0) + (col.length - 1) * pad;
    let y = top + (usable - total) / 2;
    for (const n of col) {
      n.x = colX(n.col);
      n.h = Math.max(2, n.value * ky);
      n.y = y;
      y += n.h + pad;
    }
  }
  // Ribbons leave and arrive in the vertical order of what they connect to, so they don't cross needlessly.
  for (const n of live) {
    n.out.sort((a, b) => a.t.y - b.t.y);
    n.in.sort((a, b) => a.s.y - b.s.y);
    let sy = n.y;
    for (const l of n.out) { l.w = Math.max(1, l.valueCents * ky); l.sy = sy; sy += l.w; }
    let ty = n.y;
    for (const l of n.in) { l.ty = ty; ty += Math.max(1, l.valueCents * ky); }
  }

  const svg = h('svg', { class: 'sankey', viewBox: `0 0 ${width} ${height}`, height, role: 'img',
    'aria-label': 'Where the money went' });
  svg.style.width = '100%';
  const linkLayer = h('g');
  const nodeLayer = h('g');
  svg.append(linkLayer, nodeLayer);
  const tip = tooltip(container);
  const total = opts.total || 0;
  const share = (cents) => (total > 0 ? pct(cents / total) : '');

  for (const l of links) {
    const x0 = l.s.x + nodeW;
    const x1 = l.t.x;
    const mx = (x0 + x1) / 2;
    const d = `M${x0},${l.sy} C${mx},${l.sy} ${mx},${l.ty} ${x1},${l.ty}`
      + ` L${x1},${l.ty + l.w} C${mx},${l.ty + l.w} ${mx},${l.sy + l.w} ${x0},${l.sy + l.w} Z`;
    const path = h('path', { class: 'link', d, fill: KIND_COLOR[l.t.kind] || 'var(--tax)' });
    l.el = path;
    path.addEventListener('pointermove', (e) => {
      const r = container.getBoundingClientRect();
      const value = privacy.on ? share(l.valueCents) : `${money(l.valueCents)}${total ? ' · ' + share(l.valueCents) : ''}`;
      tip.show(e.clientX - r.left, e.clientY - r.top, `${l.s.label} → ${l.t.label}`, [{ value, color: KIND_COLOR[l.t.kind] }]);
    });
    path.addEventListener('pointerleave', () => tip.hide());
    linkLayer.append(path);
  }

  const lastColLabels = [];
  for (const n of live) {
    const g = h('g', { class: 'node', tabindex: 0, role: 'button', 'aria-label': `${n.label} ${money(n.value)}` });
    g.append(h('rect', { x: n.x, y: n.y, width: nodeW, height: n.h, rx: 2, fill: KIND_COLOR[n.kind] || 'var(--tax)' }));
    const lx = n.x + nodeW + 6;
    const cy = n.y + n.h / 2;
    if (n.col === maxDepth) {
      // The label column: one line each, so a tall stack of small categories doesn't collide.
      const name = h('tspan', null, n.label);
      const value = privacy.on ? share(n.value) : money(n.value);
      const text = h('text', { class: 'node-label', x: lx, y: cy + 4 }, name, ' ', h('tspan', { class: 'node-value' }, value));
      g.append(text);
      lastColLabels.push({ text, name, full: n.label });
    } else {
      g.append(h('text', { class: 'node-label', x: lx, y: cy - 1 }, n.label));
      // The source column shows amounts only; the middle column adds its share of income.
      let value = null;
      if (privacy.on) value = n.col === 0 ? null : share(n.value);
      else value = n.col === 0 ? money(n.value) : `${money(n.value)}${total ? ' · ' + share(n.value) : ''}`;
      if (value) g.append(h('text', { class: 'node-value', x: lx, y: cy + 13 }, value));
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

  // Long category names are shortened to fit the label column; the full name stays in the aria-label.
  const maxText = labelSpace - nodeW - 6;
  for (const { text, name, full } of lastColLabels) {
    let label = full;
    while (label.length > 4 && text.getComputedTextLength() > maxText) {
      label = label.slice(0, -1);
      name.textContent = label.trimEnd() + '…';
    }
  }
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
  // Three recessive gridlines with compact labels; the figure above carries the exact value.
  for (let i = 0; i < 3; i++) {
    const c = lo + ((hi - lo) * (i + 0.5)) / 3;
    svg.append(h('line', { class: 'grid-line', x1: 0, x2: width, y1: y(c), y2: y(c) }));
    // Hidden amounts: the line's shape still shows, the scale doesn't.
    if (!privacy.on) svg.append(h('text', { x: width - 2, y: y(c) - 4, 'text-anchor': 'end' }, money(Math.round(c), { compact: true })));
  }
  const seen = new Set();
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
  const lastPoint = points[points.length - 1];
  svg.append(h('circle', { cx: x(lastPoint), cy: y(lastPoint.cents), r: 4, fill: color, stroke: 'var(--surface)', 'stroke-width': 2 }));

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
      [{ value: privacy.on ? change(best.cents, points[0].cents) + ' since ' + shortDate(points[0].day) : money(best.cents), name: opts.label || 'Net worth', color }]);
  });
  hit.addEventListener('pointerleave', () => { cross.setAttribute('visibility', 'hidden'); dot.setAttribute('visibility', 'hidden'); tip.hide(); });
  svg.append(hit);
  container.append(svg);
}

// --- Meters ------------------------------------------------------------------------

/**
 * A progress bar. status: ok | watch | over | done | savings | behind.
 * expected: 0..1, where an even pace would be today. size: lg | sm | xs.
 */
export function meter(fraction, status = 'ok', expected = null, size = '') {
  const el = h('div', { class: `meter ${status} ${size}`.trim(), role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': 100,
    'aria-valuenow': Math.round(Math.min(1, Math.max(0, fraction)) * 100) });
  el.append(h('div', { class: 'fill', style: { width: Math.min(100, Math.max(0, fraction * 100)) + '%' } }));
  if (expected !== null && expected !== undefined && expected > 0 && expected < 1) {
    el.append(h('div', { class: 'pace', style: { left: `calc(${expected * 100}% - 1px)` }, title: 'Where an even pace would be today' }));
  }
  return el;
}
