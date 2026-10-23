// One investment account, laid out like a brokerage app: what it's worth, how
// today went, and a plain list of positions. Tap a position to change its shares.

import { get, post, put, del } from '../api.js';
import { h, sheet, toast, field, busy, confirmSheet, icon } from '../ui.js';
import { money, shares, parseMoney, centsToInput, shortDate, ago, privacy, balance, pct } from '../fmt.js';
import { editAccount } from './accounts.js';

export async function render(params, app) {
  const id = params.get('id');
  const inv = await get('/api/investments/' + id);
  const a = inv.account;
  const refresh = () => app.rerender();


  const up = inv.dayChangeCents >= 0;
  const today = inv.holdings.length
    ? h('div', { class: 'inv-day ' + (up ? 'up' : 'down') },
      privacy.on ? '' : money(inv.dayChangeCents, { sign: true, cents: true }) + ' ',
      inv.dayChangePercent === null ? '' : `(${signed(inv.dayChangePercent)})`,
      h('span', { class: 'quiet' }, ' today'))
    : null;

  const head = h('section', { class: 'inv-head' },
    h('div', { class: 'inv-name' }, `${a.institution ? a.institution + ' ' : ''}${a.name}`),
    h('div', { class: 'inv-value' }, balance(a.balanceCents, a.monthAgoCents, { cents: true })),
    privacy.on ? h('div', { class: 'quiet' }, 'change over the past 30 days') : null,
    today);

  const list = h('section', { class: 'card flush' },
    h('div', { class: 'inv-cols' }, h('span', null, 'Position'), h('span', null, 'Value'), h('span', null, 'Today')));
  for (const x of inv.holdings) {
    const yesterday = x.valueCents - x.dayChangeCents;
    const pctToday = yesterday > 0 ? (100 * x.dayChangeCents) / yesterday : null;
    list.append(h('button', { class: 'inv-row', onclick: () => positionSheet(a, x, refresh) },
      h('span', { class: 'sym' }, h('strong', null, x.symbol), h('span', { class: 'sh' }, `${shares(x.shares)} sh`)),
      // Hidden: each position's share of the account instead of its value.
      h('span', { class: 'val' }, privacy.on ? pct(a.balanceCents ? x.valueCents / a.balanceCents : null) : money(x.valueCents, { cents: true })),
      h('span', { class: 'chg ' + (pctToday === null ? '' : pctToday >= 0 ? 'up' : 'down') }, pctToday === null ? '—' : signed(pctToday))));
  }
  if (inv.cashCents) {
    list.append(h('button', { class: 'inv-row', onclick: () => cashSheet(a, inv, refresh) },
      h('span', { class: 'sym' }, h('strong', null, 'Cash')),
      h('span', { class: 'val' }, privacy.on ? pct(a.balanceCents ? inv.cashCents / a.balanceCents : null) : money(inv.cashCents, { cents: true })),
      h('span', { class: 'chg' })));
  }
  if (!inv.holdings.length && !inv.cashCents) {
    list.append(h('div', { class: 'empty' }, 'No positions yet.'));
  }

  const plans = inv.plans.map((p) => h('section', { class: 'card flush' },
    h('div', { class: 'row' },
      h('div', { class: 'main' },
        h('div', { class: 'title' }, `${money(p.amountCents, { cents: true })} into ${p.symbol}, ${cadenceText(p)}`),
        h('div', { class: 'meta' }, p.active ? `Next ${shortDate(p.nextDate)}` : 'Paused')),
      h('button', { class: 'btn small', onclick: async () => {
        if (!(await confirmSheet('Stop this contribution?', 'Shares already added stay. Future contributions stop.', 'Stop'))) return;
        await del('/api/investments/plans/' + p.id); refresh();
      } }, 'Stop'))));

  const updated = inv.holdings.map((x) => x.priceUpdatedAt).filter(Boolean).sort().pop();
  const root = h('div', { class: 'grid inv', dataset: { title: `${a.name} · Finance` } },
    head,
    list,
    ...plans,
    h('div', { class: 'btn-row' },
      h('button', { class: 'btn', onclick: () => positionSheet(a, null, refresh) }, icon('plus', 18), 'Position'),
      h('button', { class: 'btn', onclick: () => cashSheet(a, inv, refresh) }, 'Cash'),
      h('button', { class: 'btn', onclick: () => planSheet(a, refresh) }, 'Recurring'),
      h('button', { class: 'btn', onclick: () => editAccount(a, refresh, () => app.go('#/accounts')) }, 'Account')),
    h('p', { class: 'hint inv-foot' }, updated ? `Prices updated ${ago(updated)}. They refresh three times each trading day.` : ''));
  return root;
}

function signed(percent) {
  return (percent >= 0 ? '+' : '−') + Math.abs(percent).toFixed(2) + '%';
}

function cadenceText(p) {
  if (p.cadence === 'SEMIMONTHLY') return 'on the 15th and last day';
  if (p.cadence === 'MONTHLY') return 'monthly';
  return p.intervalDays === 14 ? 'every other week' : `every ${p.intervalDays} days`;
}

/** Short on purpose: a ticker and a share count, nothing to scroll. */
function positionSheet(a, x, done) {
  sheet(x ? x.symbol : 'Add a position', (body, s) => {
    const symbol = h('input', { class: 'field', value: x?.symbol || '', placeholder: 'Ticker, e.g. VTI', autocapitalize: 'characters', autocomplete: 'off' });
    const count = h('input', { class: 'field', inputmode: 'decimal', value: x && !privacy.on ? String(x.shares) : '',
      placeholder: x && privacy.on ? 'Hidden. Type a new count' : 'Shares, e.g. 12.5' });
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      if (x && privacy.on && !count.value.trim()) { s.close(); return; }
      const n = Number(count.value);
      if (!symbol.value.trim() || !count.value.trim() || !Number.isFinite(n) || n < 0) throw new Error('Enter a ticker and a share count');
      await put(`/api/investments/${a.id}/holdings`, { symbol: symbol.value.trim(), shares: n });
      s.close(); toast('Saved'); done();
    }));
    const remove = x ? h('button', { class: 'btn danger', onclick: async () => {
      await put(`/api/investments/${a.id}/holdings`, { symbol: x.symbol, shares: 0 }); s.close(); done();
    } }, 'Remove') : null;
    if (x) symbol.disabled = true;
    body.append(h('div', { class: 'form' }, x ? null : symbol, count, h('div', { class: 'btn-row' }, save, remove)));
  });
}

function cashSheet(a, inv, done) {
  sheet('Cash', (body, s) => {
    const cash = h('input', { class: 'field', inputmode: 'decimal', value: centsToInput(inv.cashCents), placeholder: '0.00' });
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      await put(`/api/investments/${a.id}/cash`, { cashCents: parseMoney(cash.value) || 0 });
      s.close(); done();
    }));
    body.append(h('div', { class: 'form' }, cash, save));
  });
}

/** For a 401(k)-style account: the same amount into the same fund every payday. */
function planSheet(a, done) {
  sheet('Recurring contribution', (body, s) => {
    const symbol = h('input', { class: 'field', placeholder: 'e.g. VOO', autocapitalize: 'characters' });
    const amount = h('input', { class: 'field', inputmode: 'decimal', placeholder: 'e.g. 382.50' });
    const cadence = h('select', { class: 'field' },
      h('option', { value: 'EVERY_N_DAYS:14' }, 'Every other week'),
      h('option', { value: 'EVERY_N_DAYS:15' }, 'Every 15 days'),
      h('option', { value: 'SEMIMONTHLY' }, '15th and last day'),
      h('option', { value: 'MONTHLY' }, 'Monthly'),
      h('option', { value: 'EVERY_N_DAYS:7' }, 'Weekly'));
    const asOf = h('input', { class: 'field', type: 'date' });
    const startMode = h('select', { class: 'field' }, h('option', { value: 'shares' }, 'Shares'), h('option', { value: 'balance' }, 'Balance'));
    const start = h('input', { class: 'field', inputmode: 'decimal', placeholder: 'From a statement' });
    const first = h('input', { class: 'field', type: 'date' });
    const save = h('button', { class: 'btn primary' }, 'Start tracking');
    save.addEventListener('click', busy(save, async () => {
      const [cad, days] = cadence.value.split(':');
      const startValue = start.value.trim();
      if (!symbol.value.trim() || !parseMoney(amount.value) || !first.value) throw new Error('Fill in the fund, amount and first contribution');
      toast('Fetching prices…');
      await post(`/api/investments/${a.id}/plans`, {
        symbol: symbol.value.trim(), amountCents: parseMoney(amount.value), cadence: cad, intervalDays: days ? Number(days) : null,
        firstContribution: first.value, asOf: asOf.value || null,
        startShares: startValue && startMode.value === 'shares' ? Number(startValue) : null,
        startBalanceCents: startValue && startMode.value === 'balance' ? parseMoney(startValue) : null,
      });
      s.close(); toast('Tracking'); done();
    }));
    body.append(h('div', { class: 'form' },
      h('div', { class: 'two' }, field('Fund', symbol), field('Each time', amount)),
      field('How often', cadence),
      h('div', { class: 'two' }, field('You hold', startMode), field('Amount', start)),
      h('div', { class: 'two' }, field('As of', asOf), field('Next payday', first)),
      save));
  });
}
