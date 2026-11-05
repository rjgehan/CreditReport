// Small DOM helpers. Everything user- or bank-supplied goes in as text, never HTML.

export function h(tag, attrs, ...children) {
  const svgTags = new Set(['svg', 'path', 'g', 'rect', 'circle', 'line', 'text', 'tspan', 'polyline', 'polygon', 'defs', 'linearGradient', 'stop', 'clipPath', 'title']);
  const el = svgTags.has(tag)
    ? document.createElementNS('http://www.w3.org/2000/svg', tag)
    : document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.setAttribute('class', v);
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k === 'value' && 'value' in el) el.value = v;
      else if (k === 'checked' && 'checked' in el) el.checked = !!v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
  return el;
}

const ICONS = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z',
  flow: 'M3 6h5c4 0 4 12 8 12h5M3 18h5c2 0 3-3 4-6M3 12h18',
  list: 'M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01',
  bank: 'M3 10h18M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 21h18M12 3l9 5H3z',
  target: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zm0-4a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0-4a1 1 0 1 0 0-2 1 1 0 0 0 0 2z',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zm10 3a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  eyeOff: 'M3 3l18 18M10.6 5.1A10.9 10.9 0 0 1 12 5c6.5 0 10 7 10 7a17.6 17.6 0 0 1-3.2 4.2M6.6 6.6C3.8 8.4 2 12 2 12s3.5 7 10 7a9.7 9.7 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2',
  sync: 'M20 12a8 8 0 0 1-14.3 4.9M4 12A8 8 0 0 1 18.3 7.1M18 3v4.5h-4.5M6 21v-4.5h4.5',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zm9 3-4.5-4.5',
  plus: 'M12 5v14M5 12h14',
  close: 'M6 6l12 12M18 6 6 18',
  chevron: 'M9 6l6 6-6 6',
  logout: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l5-5-5-5M15 12H3',
  upload: 'M12 16V4M7 9l5-5 5 5M4 20h16',
  warn: 'M12 3 2 20h20zM12 10v4M12 17h.01',
  calendar: 'M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM4 10h16M8 2v4M16 2v4',
  back: 'M15 6l-6 6 6 6',
  check: 'M5 12.5l4.5 4.5L19 7.5',
};

export function icon(name, size) {
  const svg = h('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 1.8,
    'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' },
  h('path', { d: ICONS[name] || '' }));
  if (size) { svg.style.width = size + 'px'; svg.style.height = size + 'px'; }
  return svg;
}

let toastTimer;
export function toast(message) {
  document.querySelector('.toast')?.remove();
  const el = h('div', { class: 'toast', role: 'status' }, message);
  document.body.append(el);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.remove(), 3200);
}

/** A bottom sheet on phones, a centred dialog on wider screens. Returns { close, body }. */
export function sheet(title, build) {
  const body = h('div');
  const backdrop = h('div', { class: 'sheet-backdrop', onclick: (e) => { if (e.target === backdrop) close(); } },
    h('div', { class: 'sheet', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      h('div', { class: 'grabber' }),
      h('div', { class: 'sheet-head' }, h('h3', null, title),
        h('button', { class: 'icon-btn', 'aria-label': 'Close', onclick: () => close() }, icon('close'))),
      body));
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  function close() {
    backdrop.remove();
    document.removeEventListener('keydown', onKey);
    document.body.style.overflow = '';
  }
  document.addEventListener('keydown', onKey);
  document.body.style.overflow = 'hidden';
  document.body.append(backdrop);
  const api = { close, body };
  build?.(body, api);
  return api;
}

export function confirmSheet(title, message, actionLabel, danger = true) {
  return new Promise((resolve) => {
    let decided = false;
    const s = sheet(title, (body) => {
      body.append(
        h('p', { style: { color: 'var(--ink-2)', marginTop: 0 } }, message),
        h('div', { class: 'btn-row' },
          h('button', { class: 'btn ' + (danger ? 'danger' : 'primary'), onclick: () => { decided = true; s.close(); resolve(true); } }, actionLabel),
          h('button', { class: 'btn', onclick: () => { s.close(); resolve(false); } }, 'Cancel')));
    });
    const origClose = s.close;
    s.close = () => { origClose(); if (!decided) resolve(false); };
  });
}

/** Wraps an async click handler: disables the button while it runs and toasts failures. */
export function busy(button, fn) {
  return async (...args) => {
    if (button.disabled) return;
    button.disabled = true;
    try {
      return await fn(...args);
    } catch (e) {
      toast(e.message || 'Something went wrong');
    } finally {
      button.disabled = false;
    }
  };
}

export function field(label, input, hint) {
  return h('label', { class: 'stack' }, label, input, hint ? h('span', { class: 'hint' }, hint) : null);
}

export function segmented(options, value, onChange) {
  const el = h('div', { class: 'segmented', role: 'tablist' });
  for (const [key, label] of options) {
    el.append(h('button', { class: key === value ? 'on' : '', role: 'tab', 'aria-selected': key === value ? 'true' : 'false',
      onclick: () => onChange(key) }, label));
  }
  return el;
}

// Charts need their container's real width, which only exists once the view is
// in the document. Views queue drawing here; the shell runs it after mounting.
const mountQueue = [];
export function onMount(fn) { mountQueue.push(fn); }
export function flushMount() {
  while (mountQueue.length) {
    try { mountQueue.shift()(); } catch (e) { console.error(e); }
  }
}
export function discardMount() { mountQueue.length = 0; }

/**
 * A rounded avatar showing a merchant or bank logo when this server has one for
 * the domain, falling back to the given glyph (a category icon) when it doesn't.
 */
export function logoAvatar(domain, fallback) {
  const box = h('div', { class: 'avatar' + (domain ? ' logo' : '') });
  if (!domain) {
    box.append(fallback || '•');
    return box;
  }
  const img = h('img', { src: '/api/logos/' + encodeURIComponent(domain), alt: '', loading: 'lazy', decoding: 'async' });
  img.addEventListener('error', () => {
    box.classList.remove('logo');
    clear(box).append(fallback || '•');
  });
  box.append(img);
  return box;
}

/**
 * Swipe a row left to reveal one action behind it, like Mail. Taps still go to the
 * row; a drag never counts as a tap, and tapping an open row just closes it.
 * One row is open at a time.
 */
let closeOpenSwipe = null;
export function swipeable(row, label, onAction, kind = '') {
  const WIDTH = 88;
  const action = h('button', { class: 'swipe-action ' + kind, type: 'button', tabindex: '-1' }, label);
  const wrap = h('div', { class: 'swipe' }, action, row);
  let x = 0, base = 0, startX = 0, startY = 0, pointer = null, dragging = false, dragged = false;

  const outside = (e) => { if (!wrap.contains(e.target)) close(); };
  function move(to, animate) {
    x = to;
    row.style.transition = animate ? 'transform .22s ease' : 'none';
    row.style.transform = to ? `translateX(${to}px)` : '';
    wrap.classList.toggle('open', to < 0);
  }
  function open() {
    move(-WIDTH, true);
    closeOpenSwipe = close;
    document.addEventListener('pointerdown', outside, true);
  }
  function close() {
    move(0, true);
    if (closeOpenSwipe === close) closeOpenSwipe = null;
    document.removeEventListener('pointerdown', outside, true);
  }

  row.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    if (closeOpenSwipe && closeOpenSwipe !== close) closeOpenSwipe();
    pointer = e.pointerId; startX = e.clientX; startY = e.clientY; base = x;
    dragging = false; dragged = false;
  });
  row.addEventListener('pointermove', (e) => {
    if (e.pointerId !== pointer) return;
    const dx = e.clientX - startX;
    if (!dragging) {
      // Mostly sideways and past a small slop, or it's a scroll or a tap.
      if (Math.abs(dx) < 8 || Math.abs(dx) < Math.abs(e.clientY - startY)) return;
      dragging = dragged = true;
      try { row.setPointerCapture(pointer); } catch { /* pointer already gone */ }
    }
    move(Math.max(-WIDTH * 1.4, Math.min(0, base + dx)), false);
  });
  const end = (e) => {
    if (e.pointerId !== pointer) return;
    pointer = null;
    if (!dragging) return;
    dragging = false;
    if (x < -WIDTH / 2) open(); else close();
  };
  row.addEventListener('pointerup', end);
  row.addEventListener('pointercancel', end);
  // Captured on the wrapper so it runs before the row's own click handler.
  wrap.addEventListener('click', (e) => {
    if (action.contains(e.target)) return;
    if (!dragged && x === 0) return;
    e.stopPropagation();
    e.preventDefault();
    // The click that ends a drag is swallowed; a later tap on an open row closes it.
    if (dragged) dragged = false; else close();
  }, true);
  action.addEventListener('click', () => { close(); onAction(); });
  return wrap;
}

/**
 * Copies text from a tap. The Clipboard API only exists on HTTPS; on plain http
 * (a LAN address) this falls back to selecting a hidden field and copying that.
 */
export async function copyText(text) {
  if (window.isSecureContext && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch { /* fall through */ }
  }
  const area = h('textarea', { readonly: true, style: { position: 'fixed', top: '0', left: '0', opacity: '0' } });
  area.value = text;
  document.body.append(area);
  area.select();
  area.setSelectionRange(0, text.length);
  let ok = false;
  try { ok = document.execCommand('copy'); } catch { ok = false; }
  area.remove();
  return ok;
}
