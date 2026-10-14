import { get, post, patch, del } from '../api.js';
import { h, sheet, toast, field, busy, confirmSheet, icon } from '../ui.js';
import { categories } from './activity.js';

// Your categories, all of them yours: add, rename, change, delete. A few have a job
// (the app puts transfers, pay, card refunds and so on there on its own); deleting one
// of those moves the job to the category you pick.

const KINDS = [['EXPENSE', 'Spending'], ['INCOME', 'Income'], ['SAVINGS', 'Saving & investing'], ['TRANSFER', 'Not counted']];
const KIND_WORDS = { EXPENSE: 'spending', INCOME: 'income', SAVINGS: 'saving & investing', TRANSFER: 'not counted' };

export async function render(params, app) {
  const cats = await categories(true);
  const refresh = () => app.rerender();
  const root = h('div', { class: 'grid' });
  root.append(h('div', null,
    h('button', { class: 'btn primary', onclick: () => categorySheet(null, refresh) }, icon('plus', 18), 'New category')));

  for (const [kind, title] of KINDS) {
    const these = cats.filter((c) => c.kind === kind);
    if (!these.length) continue;
    const list = h('div', { class: 'list' });
    for (const c of these) {
      const meta = [];
      if (c.fixedCost) meta.push('Monthly bill');
      if (c.earnedIncome) meta.push('Counts as income');
      if (c.jobs.length) meta.push('Gets ' + listWords(c.jobs));
      list.append(h('button', { class: 'row', onclick: () => categorySheet(c, refresh) },
        h('div', { class: 'avatar' }, c.icon || c.name.slice(0, 1).toUpperCase()),
        h('div', { class: 'main' }, h('div', { class: 'title' }, c.name),
          meta.length ? h('div', { class: 'meta' }, meta.join(' · ')) : null),
        icon('chevron', 18)));
    }
    root.append(h('div', { class: 'section-title' }, title), h('section', { class: 'card flush' }, list));
  }
  root.append(h('section', { class: 'card flush' },
    h('a', { class: 'row tap', href: '#/rules' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, 'Rules'),
        h('div', { class: 'meta' }, 'Which words file a transaction where')),
      icon('chevron', 18))));
  return root;
}

function listWords(words) {
  return words.length < 2 ? words.join('') : `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`;
}

/**
 * Make (c null) or change a category. {@code done} gets the saved category, or
 * nothing after a delete. {@code preset} starts a new one off, e.g. { kind: 'INCOME' }.
 */
export function categorySheet(c, done, preset = {}) {
  sheet(c ? c.name : 'New category', (body, s) => {
    const hasJobs = !!c?.jobs.length;
    const name = h('input', { class: 'field', value: c?.name || '', placeholder: 'e.g. Student loans' });
    const glyph = h('input', { class: 'field', value: c?.icon || '', maxlength: 4, placeholder: 'Optional' });
    // A category with a job keeps its kind: transfers filed as spending would count twice.
    const kind = h('select', { class: 'field', disabled: hasJobs },
      KINDS.map(([k, label]) => h('option', { value: k, selected: (c?.kind || preset.kind || 'EXPENSE') === k }, label)));
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

    const remove = c ? h('button', { class: 'btn danger' }, 'Delete') : null;
    remove?.addEventListener('click', () => { s.close(); deleteSheet(c, done); });

    body.append(h('div', { class: 'form' },
      hasJobs ? h('p', { class: 'hint', style: { margin: 0 } },
        `The app puts ${listWords(c.jobs)} here on its own. Rename it however you like; if you delete it, you pick where those go instead.`) : null,
      field('Name', name),
      h('div', { class: 'two' }, field('Kind', kind, hasJobs ? 'Stays as it is while it has a job.' : null), field('Icon', glyph)),
      fixedRow, earnedRow,
      h('div', { class: 'btn-row' }, save, remove)));
  });
}

/** Delete, moving its transactions (and any job it has) somewhere, or back to the rules. */
async function deleteSheet(c, done) {
  const [all, found] = await Promise.all([categories(), get(`/api/transactions?category=${c.id}&size=1`)]);
  const others = all.filter((o) => o.id !== c.id);
  // Subscriptions is the one job the app can do without; the rest need a new home.
  const hasJobs = c.roles.some((r) => r !== 'SUBSCRIPTIONS');
  const catchAll = c.roles.some((r) => ['UNCATEGORIZED', 'OTHER_INCOME', 'REFUNDS'].includes(r));
  if (catchAll) {
    // Leftovers need a category of their own, or every purchase in it would look unsorted.
    sheet(`Delete ${c.name}`, (body, s) => {
      // Make the new one here, then everything moves into it in one go.
      const replace = () => {
        s.close();
        categorySheet(null, async (made) => {
          if (made && await confirmSheet(`Move everything into ${made.name}?`,
            `${c.name} is deleted, and ${made.name} gets ${listWords(c.jobs)} from now on.`, 'Move and delete', false)) {
            await del(`/api/categories/${c.id}?moveTo=${made.id}`);
            await categories(true);
            toast(`${c.name} deleted · ${made.name} takes over`);
          }
          done?.();
        }, { kind: c.kind });
      };
      body.append(h('div', { class: 'form' },
        h('p', { class: 'hint', style: { margin: 0 } },
          `The app puts ${listWords(c.jobs)} in ${c.name}, so it needs a category of its own. To call it something else, just rename it. To start over, make a new one and everything moves into it.`),
        h('div', { class: 'btn-row' },
          h('button', { class: 'btn primary', onclick: () => { s.close(); categorySheet(c, done); } }, 'Rename it'),
          h('button', { class: 'btn', onclick: replace }, icon('plus', 16), 'Replace with a new one'))));
    });
    return;
  }
  // A job can only move to the same kind of category; plain transactions can go anywhere.
  const targets = hasJobs ? others.filter((o) => o.kind === c.kind) : others;
  sheet(`Delete ${c.name}`, (body, s) => {
    const count = found.total;
    if (hasJobs && !targets.length) {
      body.append(h('div', { class: 'form' },
        h('p', { class: 'hint', style: { margin: 0 } },
          `The app puts ${listWords(c.jobs)} in ${c.name}, and there's no other ${KIND_WORDS[c.kind]} category to take that over. Make one first, then delete this.`),
        h('div', { class: 'btn-row' }, h('button', { class: 'btn primary', onclick: () => { s.close(); categorySheet(null, done); } },
          icon('plus', 16), 'New category'))));
      return;
    }
    const sameKind = targets.filter((o) => o.kind === c.kind);
    const otherKind = targets.filter((o) => o.kind !== c.kind);
    const moveTo = h('select', { class: 'field' },
      hasJobs ? null : h('option', { value: '' }, 'Nowhere: sort them again with the rules'),
      sameKind.map((o) => h('option', { value: o.id }, o.name)),
      otherKind.length ? h('optgroup', { label: 'Other kinds' }, otherKind.map((o) => h('option', { value: o.id }, o.name))) : null);
    const go = h('button', { class: 'btn danger' }, 'Delete');
    go.addEventListener('click', busy(go, async () => {
      if (!(await confirmSheet(`Delete ${c.name}?`, 'This can\'t be undone.', 'Delete'))) return;
      await del(`/api/categories/${c.id}${moveTo.value ? '?moveTo=' + moveTo.value : ''}`);
      await categories(true);
      s.close();
      const into = all.find((o) => String(o.id) === moveTo.value);
      toast(into ? `${c.name} deleted · moved into ${into.name}` : `${c.name} deleted`);
      done?.();
    }));
    body.append(h('div', { class: 'form' },
      h('p', { class: 'hint', style: { margin: 0 } }, hasJobs
        ? `The app puts ${listWords(c.jobs)} in ${c.name}. Pick the category that takes that over; its ${count.toLocaleString()} transaction${count === 1 ? '' : 's'}, rules and target move there too.`
        : `It has ${count.toLocaleString()} transaction${count === 1 ? '' : 's'}. Move them, with its rules and target, into another category, or let the rules sort them again (they'll show up in To review).`),
      field('Move them to', moveTo),
      h('div', { class: 'btn-row' }, go)));
  });
}
