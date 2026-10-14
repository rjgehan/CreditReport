import { get, post, put, del } from '../api.js';
import { h, sheet, toast, field, busy, confirmSheet, icon, logoAvatar } from '../ui.js';
import { money, shortDate, parseMoney, centsToInput, privacy } from '../fmt.js';

// Monthly expenses: what comes out on its own. Subscriptions, bills and the rest,
// found in the bank history, plus any you add because the history can't show them.

const EVERY = { WEEKLY: 'Weekly', BIWEEKLY: 'Every other week', MONTHLY: 'Monthly', QUARTERLY: 'Every 3 months', YEARLY: 'Yearly' };
const SECTIONS = [['SUBSCRIPTION', 'Subscriptions'], ['BILL', 'Bills'], ['OTHER', 'Other regular charges']];

export async function render(params, app) {
  const o = await get('/api/recurring');
  const refresh = () => app.rerender();
  const root = h('div', { class: 'grid' });

  root.append(h('section', { class: 'card' },
    h('div', { class: 'nw-top' }, h('span', { class: 'label' }, 'Comes out every month')),
    h('div', { class: 'nw-figure' }, money(o.monthlyCents)),
    h('div', { class: 'meta', style: { color: 'var(--ink-3)' } }, `${money(o.monthlyCents * 12)} a year · ${o.items.length} regular charge${o.items.length === 1 ? '' : 's'}`)));

  for (const [kind, title] of SECTIONS) {
    const these = o.items.filter((i) => i.kind === kind);
    if (!these.length) continue;
    const total = these.reduce((s, i) => s + i.monthlyCents, 0);
    const list = h('div', { class: 'list' }, these.map((i) => itemRow(i, refresh)));
    if (kind === 'BILL') {
      list.append(h('a', { class: 'row tap', href: '#/bills?from=recurring' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, 'Rent and loan history'),
          h('div', { class: 'meta' }, 'Month by month, and payments from before your bank history')),
        icon('chevron', 18)));
    }
    root.append(h('section', { class: 'card flush' },
      h('div', { class: 'card-head', style: { padding: '14px 16px 4px' } }, h('h2', null, title),
        h('strong', null, `${money(total)}/mo`)),
      list));
  }
  if (!o.items.length) {
    root.append(h('section', { class: 'card empty' },
      h('p', null, 'Nothing regular found yet. Charges show up here once they\'ve come out twice at a steady rhythm.')));
  }

  root.append(h('div', null, h('button', { class: 'btn', onclick: () => chargeSheet(null, refresh) }, icon('plus', 18), 'Add one it missed')));

  if (o.stopped.length) {
    const details = h('details', null, h('summary', { style: { cursor: 'pointer', margin: '4px', fontWeight: 600 } },
      `Not seen lately (${o.stopped.length})`));
    details.append(h('section', { class: 'card flush', style: { marginTop: '8px' } },
      h('div', { class: 'list' }, o.stopped.map((i) => itemRow(i, refresh, true)))));
    root.append(details);
  }
  root.append(h('p', { class: 'hint', style: { margin: '0 4px' } },
    'Found from charges that repeat at a steady rhythm. Yearly ones only show once they\'ve come round twice, so add those yourself.',
    o.hidden ? ' ' : null,
    o.hidden ? h('a', { href: '#', onclick: async (e) => {
      e.preventDefault(); await del('/api/recurring/hide'); toast('Showing them again'); refresh();
    } }, `Show the ${o.hidden} you hid`) : null));
  return root;
}

function itemRow(i, refresh, stopped = false) {
  const meta = [EVERY[i.cadence] || i.cadence];
  if (i.cadence !== 'MONTHLY') meta.push(`${money(i.monthlyCents)}/mo`);
  if (stopped && i.lastDate) meta.push('last ' + shortDate(i.lastDate));
  else if (i.nextDate) meta.push('next ' + shortDate(i.nextDate));
  if (i.accountName) meta.push(i.accountName);
  return h('button', { class: 'row', onclick: () => (i.chargeId ? chargeSheet(i, refresh) : detailSheet(i, refresh)) },
    logoAvatar(i.logo, i.name.slice(0, 1).toUpperCase()),
    h('div', { class: 'main' }, h('div', { class: 'title' }, i.name), h('div', { class: 'meta' }, meta.join(' · '))),
    h('div', { class: 'amount' }, money(i.amountCents, { cents: true })));
}

/** One found in the history: its charges, or "this isn't recurring". */
function detailSheet(i, refresh) {
  sheet(i.name, (body, s) => {
    const facts = h('div', { class: 'list', style: { border: '1px solid var(--border)', borderRadius: '12px', marginBottom: '14px' } },
      fact('Each time', money(i.amountCents, { cents: true })),
      fact('How often', EVERY[i.cadence] || i.cadence),
      fact('A month', money(i.monthlyCents)),
      fact('Last charged', i.lastDate ? shortDate(i.lastDate) : '—'),
      fact('Next', i.nextDate ? shortDate(i.nextDate) : 'Not seen lately'),
      fact('Category', i.categoryName || 'None'),
      fact('Seen', `${i.count} times`));
    const hide = h('button', { class: 'btn danger' }, 'Not recurring');
    hide.addEventListener('click', busy(hide, async () => {
      if (!(await confirmSheet(`Take ${i.name} off the list?`, 'It stays in Activity; it just won\'t count as a regular charge.', 'Take it off'))) return;
      await post('/api/recurring/hide', { key: i.key });
      s.close(); toast('Taken off'); refresh();
    }));
    body.append(facts, h('div', { class: 'btn-row' },
      h('a', { class: 'btn', href: '#/activity?q=' + encodeURIComponent(i.search), onclick: () => s.close() }, 'See charges'),
      hide));
  });
}

/** Add (c null) or change one the history can't show. */
function chargeSheet(c, refresh) {
  sheet(c ? c.name : 'Add a regular charge', (body, s) => {
    const name = h('input', { class: 'field', value: c?.name || '', placeholder: 'e.g. Amazon Prime' });
    const amount = h('input', { class: 'field', inputmode: 'decimal',
      value: c && !privacy.on ? centsToInput(c.amountCents) : '', placeholder: c && privacy.on ? 'Hidden. Type a new amount' : 'e.g. 139' });
    const every = h('select', { class: 'field' }, Object.entries(EVERY).map(([k, label]) =>
      h('option', { value: k, selected: (c?.cadence || 'MONTHLY') === k }, label)));
    const kind = h('select', { class: 'field' }, SECTIONS.map(([k, label]) =>
      h('option', { value: k, selected: (c?.kind || 'SUBSCRIPTION') === k }, label)));
    const next = h('input', { class: 'field', type: 'date', value: c?.nextDate || '' });
    const save = h('button', { class: 'btn primary' }, c ? 'Save' : 'Add');
    save.addEventListener('click', busy(save, async () => {
      let cents = parseMoney(amount.value);
      if ((!cents || cents <= 0) && c && privacy.on) cents = c.amountCents;
      if (!name.value.trim()) { toast('Give it a name'); return; }
      if (!cents || cents <= 0) { toast('Type what each charge is'); return; }
      const payload = { name: name.value.trim(), amountCents: Math.abs(cents), cadence: every.value, kind: kind.value,
        nextDate: next.value || null };
      if (c) await put('/api/recurring/charges/' + c.chargeId, payload); else await post('/api/recurring/charges', payload);
      s.close(); toast(c ? 'Saved' : 'Added'); refresh();
    }));
    const remove = c ? h('button', { class: 'btn danger' }, 'Delete') : null;
    remove?.addEventListener('click', busy(remove, async () => {
      await del('/api/recurring/charges/' + c.chargeId);
      s.close(); toast('Deleted'); refresh();
    }));
    body.append(h('div', { class: 'form' },
      c ? null : h('p', { class: 'hint', style: { margin: 0 } }, 'For ones your bank history can\'t show, like a yearly subscription or one paid from an account you haven\'t linked.'),
      field('Name', name),
      h('div', { class: 'two' }, field('Each charge', amount), field('How often', every)),
      h('div', { class: 'two' }, field('List under', kind), field('Next charge', next)),
      h('div', { class: 'btn-row' }, save, remove)));
  });
}

function fact(label, value) {
  return h('div', { class: 'row', style: { minHeight: '40px', padding: '8px 12px' } },
    h('div', { class: 'main meta', style: { color: 'var(--ink-3)' } }, label),
    h('div', { style: { textAlign: 'right' } }, value));
}
