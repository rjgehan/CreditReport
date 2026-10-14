import { get, post } from '../api.js';
import { h, sheet, toast, field, busy, icon } from '../ui.js';
import { money, monthLabel, thisMonth, parseMoney, centsToInput, privacy } from '../fmt.js';

// Bills: rent, loans and anything else that's about the same every month. What has
// gone to each, month by month, and a way to put in the payments from before the
// bank history starts.

const MAX_MONTHS = 60;

export async function render(params, app) {
  const months = Math.min(MAX_MONTHS, Number(params.get('months')) || 6);
  const [{ bills, historyStart }, accounts] = await Promise.all([get('/api/bills?months=' + months), get('/api/accounts')]);
  const refresh = () => app.rerender();
  const root = h('div', { class: 'grid' });

  if (!bills.length) {
    root.append(h('section', { class: 'card empty' },
      h('p', null, 'No bills yet. In Categories, open one (or make one, like Student loans) and tick "A bill".'),
      h('a', { class: 'btn', href: '#/categories' }, 'Categories')));
    return root;
  }

  // Before the bank history a month can be empty because nobody told the app, so it can be
  // filled in here. From then on the payment is in Activity somewhere (a Zelle to the
  // landlord, say), so an empty month links there instead and nothing is counted twice.
  const current = thisMonth();
  const beforeHistory = (m) => !historyStart || `${m}-01` < historyStart;

  for (const b of bills) {
    const missing = b.months.filter((m) => m.count === 0 && m.month < current && beforeHistory(m.month)).map((m) => m.month);
    const list = h('div', { class: 'list' });
    for (const m of b.months) {
      const paid = m.count > 0;
      const fillable = missing.includes(m.month);
      let label = 'Not filed here';
      if (paid) label = money(m.paidCents);
      else if (m.month === current) label = 'Not yet';
      else if (fillable) label = 'Nothing recorded';
      const open = () => {
        if (paid) app.go(`#/activity?category=${b.categoryId}&month=${m.month}`);
        else if (fillable) addSheet(b, accounts, m.month, missing, historyStart, refresh);
        else app.go(`#/activity?category=none&month=${m.month}`);
      };
      list.append(h('button', { class: 'row', onclick: open },
        h('div', { class: 'main' }, h('div', { class: 'title' }, monthLabel(m.month))),
        h('div', { class: 'amount' + (paid ? '' : ' muted') }, label),
        icon(fillable ? 'plus' : 'chevron', 16)));
    }
    root.append(h('section', { class: 'card flush' },
      h('div', { class: 'card-head', style: { padding: '14px 16px 2px' } },
        h('h2', null, `${b.icon ? b.icon + ' ' : ''}${b.name}`), h('strong', null, money(b.thisYearCents))),
      h('div', { class: 'meta', style: { padding: '0 16px 10px', color: 'var(--ink-3)', fontSize: '13px' } },
        `${new Date().getFullYear()} so far · ${money(b.allTimeCents)} in all${b.since ? ' since ' + monthLabel(b.since.slice(0, 7)) : ''}`),
      list,
      missing.length ? h('div', { style: { padding: '10px 16px 14px' } },
        h('button', { class: 'btn small', onclick: () => addSheet(b, accounts, null, missing, historyStart, refresh) },
          icon('plus', 16), 'Add a past payment')) : null));
  }

  if (months < MAX_MONTHS) {
    root.append(h('button', { class: 'btn', style: { width: '100%' }, onclick: () => app.go('#/bills?months=' + (months + 12)) },
      'Show earlier months'));
  }
  root.append(h('p', { class: 'hint', style: { margin: '0 4px' } },
    'A bill is any spending category marked "A bill" in Categories. "Not filed here" opens that month\'s transactions with no category, so you can find the payment and file it.'));
  return root;
}

/** One past payment, or the same one for every earlier month with nothing recorded. */
function addSheet(b, accounts, month, missing, historyStart, refresh) {
  sheet(`${b.name} payment`, (body, s) => {
    const open = accounts.filter((a) => !a.hidden);
    const fallback = open.find((a) => a.type === 'CHECKING') || open[0];
    const from = h('select', { class: 'field' }, open.map((a) =>
      h('option', { value: a.id, selected: a.id === (b.usualAccountId ?? fallback?.id) }, `${a.institution ? a.institution + ' ' : ''}${a.name}`)));
    const amount = h('input', { class: 'field', inputmode: 'decimal',
      value: b.lastPaidCents && !privacy.on ? centsToInput(b.lastPaidCents) : '', placeholder: 'e.g. 1450' });
    const target = month || missing[0];
    const day = h('input', { class: 'field', type: 'date', value: `${target}-01` });
    const others = missing.filter((m) => m !== target);
    const repeat = h('input', { type: 'checkbox' });
    const save = h('button', { class: 'btn primary' }, 'Add');
    save.addEventListener('click', busy(save, async () => {
      const cents = parseMoney(amount.value);
      if (!cents || cents <= 0) { toast('Type the amount paid'); return; }
      if (!day.value) { toast('Pick the date it was paid'); return; }
      if (historyStart && day.value >= historyStart) {
        toast('That date is inside your bank history; find the payment in Activity instead');
        return;
      }
      const days = [day.value];
      // The same day of the month, or the month's last day when it's shorter.
      if (repeat.checked) {
        const d = Number(day.value.slice(8, 10));
        for (const m of others) {
          const [y, mo] = m.split('-').map(Number);
          days.push(`${m}-${String(Math.min(d, new Date(y, mo, 0).getDate())).padStart(2, '0')}`);
        }
      }
      const r = await post(`/api/bills/${b.categoryId}/payments`, { accountId: Number(from.value), amountCents: Math.abs(cents), days });
      s.close();
      toast(`${r.added} payment${r.added === 1 ? '' : 's'} added`);
      refresh();
    }));
    body.append(h('div', { class: 'form' },
      h('p', { class: 'hint', style: { margin: 0 } }, 'For payments from before your bank history. They count toward this bill and the month they were paid.'),
      h('div', { class: 'two' }, field('Amount', amount), field('Paid on', day)),
      field('Paid from', from),
      others.length ? h('label', { class: 'check' }, repeat,
        `Also add it to the ${others.length} other earlier month${others.length === 1 ? '' : 's'} with nothing recorded`) : null,
      save));
  });
}
