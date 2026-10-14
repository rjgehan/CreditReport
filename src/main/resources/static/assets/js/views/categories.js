import { post, patch, del } from '../api.js';
import { h, sheet, toast, field, busy, confirmSheet, icon } from '../ui.js';
import { categories } from './activity.js';

// Your categories: make new ones, rename them, say what kind they are, delete them.

const KINDS = [['EXPENSE', 'Spending'], ['INCOME', 'Income'], ['SAVINGS', 'Saving & investing'], ['TRANSFER', 'Not counted']];

export async function render(params, app) {
  const cats = await categories(true);
  const refresh = () => app.rerender();
  const root = h('div', { class: 'grid' });
  root.append(h('div', null,
    h('button', { class: 'btn primary', onclick: () => categorySheet(null, refresh) }, icon('plus', 18), 'New category')));

  for (const [kind, label] of KINDS) {
    const these = cats.filter((c) => c.kind === kind);
    if (!these.length) continue;
    const list = h('div', { class: 'list' });
    for (const c of these) {
      const meta = [];
      if (c.fixedCost) meta.push('Monthly bill');
      if (c.earnedIncome) meta.push('Counts as income');
      if (c.required) meta.push('Used by the app');
      list.append(h('button', { class: 'row', onclick: () => categorySheet(c, refresh) },
        h('div', { class: 'avatar' }, c.icon || '•'),
        h('div', { class: 'main' }, h('div', { class: 'title' }, c.name),
          meta.length ? h('div', { class: 'meta' }, meta.join(' · ')) : null),
        icon('chevron', 18)));
    }
    root.append(h('div', { class: 'section-title' }, label), h('section', { class: 'card flush' }, list));
  }
  root.append(h('section', { class: 'card flush' },
    h('a', { class: 'row tap', href: '#/rules' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, 'Rules'),
        h('div', { class: 'meta' }, 'Which words file a transaction where')),
      icon('chevron', 18))));
  return root;
}

/**
 * Make (c null) or change a category. {@code done} gets the saved category, or
 * nothing after a delete.
 */
export function categorySheet(c, done) {
  sheet(c ? c.name : 'New category', (body, s) => {
    const name = h('input', { class: 'field', value: c?.name || '', placeholder: 'e.g. Student loans', disabled: c?.required });
    const glyph = h('input', { class: 'field', value: c?.icon || '', maxlength: 4, placeholder: 'Optional' });
    const kind = h('select', { class: 'field', disabled: c?.required },
      KINDS.map(([k, label]) => h('option', { value: k, selected: (c?.kind || 'EXPENSE') === k }, label)));
    const fixed = h('input', { type: 'checkbox', checked: c ? c.fixedCost : false });
    const earned = h('input', { type: 'checkbox', checked: c ? c.earnedIncome : true });
    const fixedRow = h('label', { class: 'check' }, fixed, 'A bill: about the same every month, like rent or a loan');
    const earnedRow = h('label', { class: 'check' }, earned, 'Counts as income: pay or side work, not refunds or gifts');
    const showFlags = () => {
      fixedRow.hidden = kind.value !== 'EXPENSE';
      earnedRow.hidden = kind.value !== 'INCOME';
    };
    kind.addEventListener('change', showFlags);
    showFlags();

    const save = h('button', { class: 'btn primary' }, c ? 'Save' : 'Add category');
    save.addEventListener('click', busy(save, async () => {
      const payload = {
        name: name.value.trim(), kind: kind.value, icon: glyph.value.trim(),
        fixedCost: kind.value === 'EXPENSE' ? fixed.checked : false,
        earnedIncome: kind.value === 'INCOME' ? earned.checked : false,
      };
      if (!payload.name) { toast('Give it a name'); return; }
      const saved = c ? await patch('/api/categories/' + c.id, payload) : await post('/api/categories', payload);
      await categories(true);
      s.close();
      toast(c ? 'Saved' : `${saved.name} added`);
      done?.(saved);
    }));

    const remove = c && !c.required ? h('button', { class: 'btn danger' }, 'Delete') : null;
    remove?.addEventListener('click', busy(remove, async () => {
      if (!(await confirmSheet(`Delete ${c.name}?`,
        'Its transactions go back to being sorted by the rules and show up in To review again. Its rules and spending target go too.',
        'Delete'))) return;
      await del('/api/categories/' + c.id);
      await categories(true);
      s.close();
      toast(`${c.name} deleted`);
      done?.();
    }));

    body.append(h('div', { class: 'form' },
      c?.required ? h('p', { class: 'hint', style: { margin: 0 } }, 'The app files things here by name, so it keeps its name and kind. You can still change its icon.') : null,
      field('Name', name),
      h('div', { class: 'two' }, field('Kind', kind), field('Icon', glyph)),
      fixedRow, earnedRow,
      h('div', { class: 'btn-row' }, save, remove)));
  });
}
