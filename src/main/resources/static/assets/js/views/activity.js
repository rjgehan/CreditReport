import { get, post, patch, del } from '../api.js';
import { h, clear, sheet, toast, field, busy, confirmSheet, logoAvatar, icon, segmented } from '../ui.js';
import { money, dayLabel, monthLabel, shiftMonth, thisMonth, parseMoney, centsToInput, privacy } from '../fmt.js';
import { categorySheet } from './categories.js';

let categoriesCache = null;
export async function categories(force = false) {
  if (!categoriesCache || force) categoriesCache = await get('/api/categories');
  return categoriesCache;
}

export async function render(params, app) {
  const [cats, accounts] = await Promise.all([categories(), get('/api/accounts')]);
  const state = {
    q: params.get('q') || '',
    category: params.get('category') || '',
    account: params.get('account') || '',
    month: params.get('month') || '',
    review: params.get('review') || '',
  };
  // Links from Flow and History name Uncategorized by id; the list calls it No category.
  const uncategorized = cats.find((c) => c.roles.includes('UNCATEGORIZED'));
  if (uncategorized && state.category === String(uncategorized.id)) state.category = 'none';
  const root = h('div');

  const search = h('input', { class: 'field', type: 'search', placeholder: 'Search', value: state.q, 'aria-label': 'Search transactions' });
  const catSelect = h('select', { class: 'field', 'aria-label': 'Category' },
    h('option', { value: '' }, 'All categories'),
    h('option', { value: 'none', selected: state.category === 'none' }, 'No category'),
    cats.filter((c) => c !== uncategorized)
      .map((c) => h('option', { value: c.id, selected: String(c.id) === state.category }, `${c.icon || ''} ${c.name}`)));
  const acctSelect = h('select', { class: 'field', 'aria-label': 'Account' },
    h('option', { value: '' }, 'All accounts'),
    accounts.map((a) => h('option', { value: a.id, selected: String(a.id) === state.account }, `${a.institution ? a.institution + ' · ' : ''}${a.name}`)));
  const monthSelect = h('select', { class: 'field', 'aria-label': 'Month' }, h('option', { value: '' }, 'Any time'));
  for (let i = 0; i < 18; i++) {
    const m = shiftMonth(thisMonth(), -i);
    monthSelect.append(h('option', { value: m, selected: m === state.month }, monthLabel(m)));
  }
  // An older month from a link (Bills, say) still shows, and "Any time" still clears it.
  if (state.month && ![...monthSelect.options].some((o) => o.value === state.month)) {
    monthSelect.append(h('option', { value: state.month, selected: true }, monthLabel(state.month)));
  }
  const summary = h('div', { class: 'hint', style: { margin: '0 4px 8px' } });
  const listCard = h('section', { class: 'card flush' });
  const more = h('button', { class: 'btn', style: { width: '100%', marginTop: '12px' }, hidden: true }, 'Load more');

  const filters = h('div', { class: 'toolbar' },
    h('div', { class: 'search' }, searchIcon(), search),
    h('a', { class: 'btn small', href: '#/categories' }, 'Categories'));
  const reviewTabs = h('div', { class: 'toolbar' });
  const selects = h('div', { class: 'toolbar', style: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))' } },
    catSelect, acctSelect, monthSelect);
  root.append(filters, reviewTabs, selects, summary, listCard, more);

  let last = null;
  let lastDay = null;
  let list = null;
  let toReview = 0;
  const filterQuery = () => {
    const q = new URLSearchParams();
    if (state.q) q.set('q', state.q);
    if (state.category) q.set('category', state.category);
    if (state.account) q.set('account', state.account);
    if (state.month) {
      const [y, m] = state.month.split('-').map(Number);
      q.set('from', `${state.month}-01`);
      q.set('to', `${state.month}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`);
    }
    return q;
  };
  // All, or only what you haven't checked off yet; the count follows your checks.
  const drawTabs = () => {
    clear(reviewTabs).append(segmented([['', 'All'], ['todo', `To review${toReview ? ' (' + toReview.toLocaleString() + ')' : ''}`]],
      state.review, (v) => { state.review = v; syncHash(); load(true); }));
    if (state.review === 'todo' && toReview > 1) {
      const all = h('button', { class: 'btn small' }, 'Check all off');
      all.addEventListener('click', busy(all, async () => {
        if (!(await confirmSheet(`Check off all ${toReview.toLocaleString()}?`,
          'Every transaction these filters show is marked reviewed, whatever its category.', 'Check all off', false))) return;
        const r = await post('/api/transactions/review?' + filterQuery(), { reviewed: true });
        toast(`${r.changed.toLocaleString()} checked off`); load(true);
      }));
      reviewTabs.append(all);
    }
  };
  const onReviewed = (now) => { toReview = Math.max(0, toReview + (now ? -1 : 1)); drawTabs(); };

  async function load(reset) {
    if (reset) { last = null; lastDay = null; clear(listCard); list = h('div', { class: 'list' }); listCard.append(list); }
    const q = filterQuery();
    q.set('size', 60);
    // More picks up after the last row shown, so checking rows off can't make it skip any.
    if (last) { q.set('beforeDay', last.day); q.set('beforeId', last.id); }
    if (state.review) q.set('review', state.review);
    const res = await get('/api/transactions?' + q);
    toReview = res.toReview;
    drawTabs();
    clear(summary).append(`${res.total.toLocaleString()} transactions · in ${money(res.inCents)} · out ${money(res.outCents)}`);
    if (!res.items.length && !last) {
      list.append(h('div', { class: 'empty' }, state.review ? 'All checked off.' : 'No transactions match.'));
    }
    for (const t of res.items) {
      if (t.day !== lastDay) { list.append(h('div', { class: 'date-head' }, dayLabel(t.day))); lastDay = t.day; }
      list.append(txnRow(t, () => load(true), onReviewed));
    }
    if (res.items.length) last = res.items[res.items.length - 1];
    more.hidden = res.items.length < 60;
  }
  more.addEventListener('click', busy(more, async () => { await load(false); }));

  const syncHash = () => {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(state)) if (v) p.set(k, v);
    history.replaceState(null, '', '#/activity' + (p.toString() ? '?' + p : ''));
  };
  let timer;
  search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { state.q = search.value.trim(); syncHash(); load(true); }, 250); });
  catSelect.addEventListener('change', () => { state.category = catSelect.value; syncHash(); load(true); });
  acctSelect.addEventListener('change', () => { state.account = acctSelect.value; syncHash(); load(true); });
  monthSelect.addEventListener('change', () => { state.month = monthSelect.value; syncHash(); load(true); });

  await load(true);
  return root;
}

function searchIcon() {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor'); s.setAttribute('stroke-width', '2');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zm9 3-4.5-4.5');
  s.append(p);
  return s;
}

/**
 * One transaction: a check to tick it off once its category is right, and the rest
 * of the row opens it.
 */
export function txnRow(t, onChange, onReviewed) {
  const meta = [t.accountName];
  if (t.pending) meta.push('pending');
  if (t.paired) meta.push('transfer');
  if (t.categorySource === 'MANUAL') meta.push('set by you');
  const named = !t.needsCategory;
  const row = h('div', { class: 'row txn' + (t.reviewed ? ' done' : '') });
  const check = h('button', { class: 'check-btn', type: 'button' }, h('span', { class: 'ring' }, icon('check', 14)));
  const paint = () => {
    row.classList.toggle('done', t.reviewed);
    check.setAttribute('aria-pressed', String(t.reviewed));
    check.setAttribute('aria-label', t.reviewed ? 'Reviewed. Tap to undo' : 'Mark reviewed');
  };
  let saving = false;
  check.addEventListener('click', async () => {
    if (saving) return;
    saving = true;
    const next = !t.reviewed;
    t.reviewed = next;
    paint();
    try {
      await patch('/api/transactions/' + t.id, { reviewed: next });
      onReviewed?.(next);
    } catch (e) {
      t.reviewed = !next;
      paint();
      toast(e.message || 'Something went wrong');
    } finally {
      saving = false;
    }
  });
  paint();
  row.append(check, h('button', { class: 'txn-open', type: 'button', onclick: () => editTxn(t, onChange) },
    logoAvatar(t.logo, t.categoryIcon || '❔'),
    h('div', { class: 'main' },
      h('div', { class: 'title' }, t.merchant || t.description),
      h('div', { class: 'meta' }, h('span', { class: named ? null : 'needs' }, named ? t.categoryName : 'No category'),
        ` · ${meta.join(' · ')}`)),
    h('div', { class: 'amount' + (t.amountCents > 0 ? ' in' : '') }, money(t.amountCents, { cents: true, sign: true }))));
  return row;
}

export async function editTxn(t, onChange, preselect) {
  const cats = await categories();
  let chosen = preselect ?? t.categoryId;
  sheet(t.merchant || t.description, (body, s) => {
    const groups = [['INCOME', 'Income'], ['EXPENSE', 'Spending'], ['SAVINGS', 'Saving & investing'], ['TRANSFER', 'Not counted']];
    const grid = h('div');
    const buttons = [];
    for (const [kind, label] of groups) {
      const these = cats.filter((c) => c.kind === kind);
      if (!these.length) continue;
      grid.append(h('div', { class: 'cat-group' }, label));
      const g = h('div', { class: 'cat-grid' });
      for (const c of these) {
        const b = h('button', { class: c.id === chosen ? 'on' : '', type: 'button', onclick: () => {
          chosen = c.id; buttons.forEach((x) => x.classList.toggle('on', x === b));
        } }, h('span', null, c.icon || '•'), c.name);
        buttons.push(b);
        g.append(b);
      }
      grid.append(g);
    }
    // A category that doesn't exist yet: make it here and come straight back with it picked.
    grid.append(h('button', { class: 'btn small', type: 'button', style: { marginTop: '10px' }, onclick: () => {
      categorySheet(null, async (made) => { s.close(); if (made) editTxn(t, onChange, made.id); });
    } }, icon('plus', 16), 'New category'));
    const rule = h('input', { type: 'checkbox' });
    const pattern = h('input', { class: 'field', value: '', placeholder: 'Words to match, e.g. STARBUCKS' });
    const notes = h('textarea', { class: 'field', rows: 2, value: t.notes || '' });
    // Typed in by you (an old rent payment, say), so it can be corrected.
    const amount = t.manual ? h('input', { class: 'field', inputmode: 'decimal',
      value: privacy.on ? '' : centsToInput(Math.abs(t.amountCents)), placeholder: privacy.on ? 'Hidden. Type a new amount' : '' }) : null;
    const day = t.manual ? h('input', { class: 'field', type: 'date', value: t.day }) : null;
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      const typed = amount && amount.value.trim() ? parseMoney(amount.value) : null;
      if (typed !== null && !(Math.abs(typed) > 0)) { toast('Type an amount, or delete it instead'); return; }
      const res = await patch('/api/transactions/' + t.id, {
        categoryId: chosen !== t.categoryId ? chosen : null,
        notes: notes.value,
        ruleFromThis: rule.checked && chosen ? true : null,
        rulePattern: rule.checked ? pattern.value.trim() || null : null,
        // Saving means you've looked at it.
        reviewed: true,
        amountCents: typed !== null ? (t.amountCents < 0 ? -Math.abs(typed) : Math.abs(typed)) : null,
        day: day && day.value && day.value !== t.day ? day.value : null,
      });
      s.close();
      toast(res.refiled ? `Saved · ${res.refiled} other transactions re-filed` : 'Saved');
      onChange?.();
    }));
    const reason = h('span', { class: 'meta' }, '…');
    const facts = h('div', { class: 'list', style: { marginBottom: '14px', border: '1px solid var(--border)', borderRadius: '12px' } },
      factRow('Amount', money(t.amountCents, { cents: true, sign: true })),
      factRow('Date', dayLabel(t.day)),
      factRow('Account', t.accountName),
      factRow('Bank description', t.description),
      factRow('Filed by', reason));
    get('/api/transactions/' + t.id + '/reason').then((r) => {
      clear(reason).append(r.text);
      if (r.ruleId) reason.append(' ', h('a', { href: '#/rules', onclick: () => s.close() }, 'Rules'));
    }).catch(() => clear(reason).append('—'));
    body.append(facts, grid,
      h('div', { class: 'form', style: { marginTop: '16px' } },
        h('label', { class: 'check' }, rule, 'Always file transactions like this here'),
        field('Match on', pattern, 'Leave blank to use the merchant name. Applies to past and future transactions you haven\'t set by hand.'),
        amount ? h('div', { class: 'two' }, field('Amount', amount), field('Date', day)) : null,
        field('Notes', notes),
        h('div', { class: 'btn-row' }, save,
          t.categorySource === 'MANUAL' ? action('btn', 'Use rules again', async () => {
            await patch('/api/transactions/' + t.id, { clearCategory: true });
            s.close(); toast('Back to automatic'); onChange?.();
          }) : null,
          t.manual ? action('btn danger', 'Delete', async () => {
            if (!(await confirmSheet('Delete transaction?', 'This removes it for good.', 'Delete'))) return;
            await del('/api/transactions/' + t.id); s.close(); toast('Deleted'); onChange?.();
          }) : null)));
  });
}

/** A button that shows a failure instead of swallowing it. */
function action(cls, label, fn) {
  const b = h('button', { class: cls }, label);
  b.addEventListener('click', busy(b, fn));
  return b;
}

function factRow(label, value) {
  return h('div', { class: 'row', style: { minHeight: '40px', padding: '8px 12px' } },
    h('div', { class: 'main meta', style: { color: 'var(--ink-3)' } }, label),
    h('div', { style: { textAlign: 'right', maxWidth: '65%', overflowWrap: 'anywhere' } }, value));
}

export { parseMoney, centsToInput };
