import { get, patch, del } from '../api.js';
import { h, clear, sheet, toast, field, busy, confirmSheet } from '../ui.js';
import { money, dayLabel, monthLabel, shiftMonth, thisMonth, parseMoney, centsToInput } from '../fmt.js';

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
  };
  const root = h('div');

  const search = h('input', { class: 'field', type: 'search', placeholder: 'Search', value: state.q, 'aria-label': 'Search transactions' });
  const catSelect = h('select', { class: 'field', 'aria-label': 'Category' },
    h('option', { value: '' }, 'All categories'),
    h('option', { value: 'none', selected: state.category === 'none' }, 'No category'),
    cats.map((c) => h('option', { value: c.id, selected: String(c.id) === state.category }, `${c.icon || ''} ${c.name}`)));
  const acctSelect = h('select', { class: 'field', 'aria-label': 'Account' },
    h('option', { value: '' }, 'All accounts'),
    accounts.map((a) => h('option', { value: a.id, selected: String(a.id) === state.account }, `${a.institution ? a.institution + ' · ' : ''}${a.name}`)));
  const monthSelect = h('select', { class: 'field', 'aria-label': 'Month' }, h('option', { value: '' }, 'Any time'));
  for (let i = 0; i < 18; i++) {
    const m = shiftMonth(thisMonth(), -i);
    monthSelect.append(h('option', { value: m, selected: m === state.month }, monthLabel(m)));
  }
  const summary = h('div', { class: 'hint', style: { margin: '0 4px 8px' } });
  const listCard = h('section', { class: 'card flush' });
  const more = h('button', { class: 'btn', style: { width: '100%', marginTop: '12px' }, hidden: true }, 'Load more');

  const filters = h('div', { class: 'toolbar' },
    h('div', { class: 'search' }, searchIcon(), search));
  const selects = h('div', { class: 'toolbar', style: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))' } },
    catSelect, acctSelect, monthSelect);
  root.append(filters, selects, summary, listCard, more);

  let page = 0;
  let lastDay = null;
  let list = null;
  async function load(reset) {
    if (reset) { page = 0; lastDay = null; clear(listCard); list = h('div', { class: 'list' }); listCard.append(list); }
    const q = new URLSearchParams({ page, size: 60 });
    if (state.q) q.set('q', state.q);
    if (state.category) q.set('category', state.category);
    if (state.account) q.set('account', state.account);
    if (state.month) {
      const [y, m] = state.month.split('-').map(Number);
      q.set('from', `${state.month}-01`);
      q.set('to', `${state.month}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`);
    }
    const res = await get('/api/transactions?' + q);
    clear(summary).append(`${res.total.toLocaleString()} transactions · in ${money(res.inCents)} · out ${money(res.outCents)}`);
    if (!res.items.length && page === 0) list.append(h('div', { class: 'empty' }, 'No transactions match.'));
    for (const t of res.items) {
      if (t.day !== lastDay) { list.append(h('div', { class: 'date-head' }, dayLabel(t.day))); lastDay = t.day; }
      list.append(txnRow(t, app, () => load(true)));
    }
    more.hidden = res.page + 1 >= res.pages;
  }
  more.addEventListener('click', busy(more, async () => { page++; await load(false); }));

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

export function txnRow(t, app, onChange) {
  const meta = [t.accountName];
  if (t.pending) meta.push('pending');
  if (t.paired) meta.push('transfer');
  if (t.categorySource === 'MANUAL') meta.push('set by you');
  return h('button', { class: 'row', onclick: () => editTxn(t, onChange) },
    h('div', { class: 'avatar' }, t.categoryIcon || '❔'),
    h('div', { class: 'main' },
      h('div', { class: 'title' }, t.merchant || t.description),
      h('div', { class: 'meta' }, `${t.categoryName || 'No category'} · ${meta.join(' · ')}`)),
    h('div', { class: 'amount' + (t.amountCents > 0 ? ' in' : '') }, money(t.amountCents, { cents: true, sign: true })));
}

export async function editTxn(t, onChange) {
  const cats = await categories();
  let chosen = t.categoryId;
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
    const rule = h('input', { type: 'checkbox' });
    const pattern = h('input', { class: 'field', value: '', placeholder: 'Words to match, e.g. STARBUCKS' });
    const notes = h('textarea', { class: 'field', rows: 2, value: t.notes || '' });
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      const res = await patch('/api/transactions/' + t.id, {
        categoryId: chosen !== t.categoryId ? chosen : null,
        notes: notes.value,
        ruleFromThis: rule.checked && chosen ? true : null,
        rulePattern: rule.checked ? pattern.value.trim() || null : null,
      });
      s.close();
      toast(res.refiled ? `Saved · ${res.refiled} other transactions re-filed` : 'Saved');
      onChange?.();
    }));
    const facts = h('div', { class: 'list', style: { marginBottom: '14px', border: '1px solid var(--border)', borderRadius: '12px' } },
      factRow('Amount', money(t.amountCents, { cents: true, sign: true })),
      factRow('Date', dayLabel(t.day)),
      factRow('Account', t.accountName),
      factRow('Bank description', t.description));
    body.append(facts, grid,
      h('div', { class: 'form', style: { marginTop: '16px' } },
        h('label', { class: 'check' }, rule, 'Always file transactions like this here'),
        field('Match on', pattern, 'Leave blank to use the merchant name. Applies to past and future transactions you haven\'t set by hand.'),
        field('Notes', notes),
        h('div', { class: 'btn-row' }, save,
          t.categorySource === 'MANUAL' ? h('button', { class: 'btn', onclick: async () => {
            await patch('/api/transactions/' + t.id, { clearCategory: true });
            s.close(); toast('Back to automatic'); onChange?.();
          } }, 'Use rules again') : null,
          t.manual ? h('button', { class: 'btn danger', onclick: async () => {
            if (!(await confirmSheet('Delete transaction?', 'This removes it for good.', 'Delete'))) return;
            await del('/api/transactions/' + t.id); s.close(); onChange?.();
          } }, 'Delete') : null)));
  });
}

function factRow(label, value) {
  return h('div', { class: 'row', style: { minHeight: '40px', padding: '8px 12px' } },
    h('div', { class: 'main meta', style: { color: 'var(--ink-3)' } }, label),
    h('div', { style: { textAlign: 'right', maxWidth: '65%', overflowWrap: 'anywhere' } }, value));
}

export { parseMoney, centsToInput };
