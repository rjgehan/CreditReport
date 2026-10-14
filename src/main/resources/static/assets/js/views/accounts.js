import { get, post, put, patch, del, upload, loadScript } from '../api.js';
import { h, clear, sheet, toast, field, busy, confirmSheet, icon, onMount } from '../ui.js';
import { money, pct, shares, ago, parseMoney, centsToInput, TYPE_LABEL, shortDate } from '../fmt.js';
import { lineChart } from '../charts.js';

const GROUP_ORDER = ['Cash', 'Credit', 'Investments', 'Retirement', 'Loans', 'Other'];

export async function render(params, app) {
  const [accounts, connections, investments, summary] = await Promise.all([
    get('/api/accounts'), get('/api/connections'), get('/api/investments'), get('/api/summary')]);
  const root = h('div', { class: 'grid' });
  const refresh = () => app.rerender();

  if (accounts.length) {
    const chart = h('div', { style: { marginTop: '10px' } });
    root.append(h('section', { class: 'card' },
      h('div', { class: 'nw-top' }, h('span', { class: 'label' }, 'Net worth')),
      h('div', { class: 'nw-figure', style: { fontSize: '28px' } }, money(summary.netWorthCents)),
      chart));
    onMount(() => lineChart(chart, summary.netWorthHistory, { height: 140 }));
  }

  root.append(h('div', { class: 'btn-row' },
    h('button', { class: 'btn primary', onclick: () => connectBank(app, null, refresh) }, icon('plus', 18), 'Connect a bank'),
    h('button', { class: 'btn', onclick: () => addManual(refresh) }, 'Add by hand'),
    h('button', { class: 'btn', onclick: () => importSheet(accounts, refresh) }, icon('upload', 18), 'Import statement')));

  for (const c of connections.filter((c) => c.status !== 'OK')) {
    root.append(h('div', { class: 'banner' }, '⚠️', h('div', { style: { flex: 1 } },
      h('strong', null, c.displayName), ' ', c.status === 'NEEDS_REAUTH' ? 'needs you to sign in again.' : 'failed to sync.',
      c.lastError ? h('div', { class: 'hint' }, c.lastError) : null),
    c.provider === 'TELLER' ? h('button', { class: 'btn small', onclick: () => connectBank(app, c, refresh) }, 'Fix') : null));
  }

  const holdingsByAccount = new Map(investments.map((i) => [i.account.id, i]));
  const groups = new Map(GROUP_ORDER.map((g) => [g, []]));
  for (const a of accounts) groups.get(a.group)?.push(a);

  for (const [name, list] of groups) {
    if (!list.length) continue;
    const total = list.filter((a) => !a.hidden).reduce((s, a) => s + a.balanceCents, 0);
    const section = h('section', { class: 'card flush' },
      h('div', { class: 'card-head', style: { padding: '14px 16px 4px' } }, h('h2', null, name), h('strong', null, money(total))));
    const ul = h('div', { class: 'list' });
    for (const a of list) {
      const inv = holdingsByAccount.get(a.id);
      const sub = [];
      if (a.mask) sub.push('••' + a.mask);
      if (a.type === 'CREDIT_CARD' && a.creditLimitCents) sub.push(`${pct(-a.balanceCents / a.creditLimitCents)} of ${money(a.creditLimitCents)} limit`);
      if (a.type === 'CREDIT_CARD' && a.paymentDueDate) sub.push('due ' + shortDate(a.paymentDueDate));
      sub.push(inv && (inv.holdings.length || inv.plans.length) ? `${inv.holdings.length} holding${inv.holdings.length === 1 ? '' : 's'}`
        : a.manual ? 'updated by hand' : a.provider === 'TELLER' ? 'Teller' : a.provider === 'SIMPLEFIN' ? 'SimpleFIN' : '');
      if (a.balanceUpdatedAt) sub.push(ago(a.balanceUpdatedAt));
      if (a.hidden) sub.push('hidden');
      ul.append(h('button', { class: 'row', style: a.hidden ? { opacity: 0.55 } : null, onclick: () => editAccount(a, inv, refresh) },
        h('div', { class: 'avatar' }, avatarFor(a)),
        h('div', { class: 'main' },
          h('div', { class: 'title' }, `${a.institution ? a.institution + ' ' : ''}${a.name}`),
          h('div', { class: 'meta' }, sub.filter(Boolean).join(' · '))),
        h('div', { class: 'amount' }, money(a.balanceCents, { cents: true }))));
    }
    section.append(ul);
    root.append(section);
  }

  if (!accounts.length) {
    root.append(h('div', { class: 'card empty' }, h('div', { class: 'big' }, '🏦'),
      h('p', null, 'Connect SoFi and Chase through Teller, add Webull and PCS by hand, or import a statement download.')));
  }

  // Connections: what syncs, and when it last did.
  if (connections.length) {
    const ul = h('div', { class: 'list' });
    for (const c of connections) {
      ul.append(h('div', { class: 'row' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, c.displayName),
          h('div', { class: 'meta' }, `${c.provider === 'TELLER' ? 'Teller' : 'SimpleFIN'} · ${c.accountCount} account${c.accountCount === 1 ? '' : 's'} · synced ${ago(c.lastSyncedAt)}`)),
        h('button', { class: 'btn small', onclick: async (e) => {
          const b = e.currentTarget; b.disabled = true;
          const r = await post(`/api/connections/${c.id}/sync`).finally(() => { b.disabled = false; });
          toast(r.ok ? 'Synced' : r.error); refresh();
        } }, 'Sync'),
        h('button', { class: 'btn small danger', onclick: async () => {
          if (!(await confirmSheet(`Remove ${c.displayName}?`, 'Its accounts, transactions and balance history are deleted from this app. Nothing changes at the bank.', 'Remove'))) return;
          await del('/api/connections/' + c.id); toast('Removed'); refresh();
        } }, 'Remove')));
    }
    root.append(h('div', { class: 'section-title' }, 'Connections'), h('section', { class: 'card flush' }, ul));
  }
  return root;
}

function avatarFor(a) {
  return { CHECKING: '🏦', SAVINGS: '🐖', CREDIT_CARD: '💳', BROKERAGE: '📈', RETIREMENT: '🏖️', LOAN: '📄' }[a.type] || '💰';
}

// --- Connecting ---------------------------------------------------------------------

async function connectBank(app, repair, done) {
  const me = app.me;
  if (!me.tellerApplicationId) {
    sheet('Connect a bank', (body) => {
      body.append(
        h('p', null, 'Bank connections go through Teller, which is free for personal use. Set it up on the server first:'),
        h('ol', { style: { paddingLeft: '20px', lineHeight: 1.6 } },
          h('li', null, 'Sign up at teller.io and create an application.'),
          h('li', null, 'Download certificate.pem and private_key.pem from the dashboard.'),
          h('li', null, 'Set TELLER_APPLICATION_ID, TELLER_CERTIFICATE and TELLER_PRIVATE_KEY, then restart.')),
        h('p', { class: 'hint' }, 'Or, for about $15 a year, SimpleFIN Bridge works without any server setup:'),
        simpleFinForm(done));
    });
    return;
  }
  if (!me.tellerReady) {
    toast('Teller needs its certificate: set TELLER_CERTIFICATE and TELLER_PRIVATE_KEY.');
    return;
  }
  try {
    await loadScript('https://cdn.teller.io/connect/connect.js');
  } catch {
    toast('Could not load Teller Connect. Check your connection.');
    return;
  }
  const connect = window.TellerConnect.setup({
    applicationId: me.tellerApplicationId,
    environment: me.tellerEnvironment,
    products: ['transactions', 'balance'],
    enrollmentId: repair?.externalId || undefined,
    selectAccount: 'multiple',
    onSuccess: async (enrollment) => {
      toast('Connected. Pulling transactions…');
      try {
        const r = await post('/api/connections/teller', {
          accessToken: enrollment.accessToken,
          enrollmentId: enrollment.enrollment?.id,
          institutionName: enrollment.enrollment?.institution?.name,
        });
        toast(r.syncError ? 'Connected, but the first sync failed: ' + r.syncError : 'All set');
      } catch (e) {
        toast(e.message);
      }
      done();
    },
  });
  connect.open();
}

function simpleFinForm(done) {
  const token = h('textarea', { class: 'field', rows: 3, placeholder: 'Paste the Setup Token from beta-bridge.simplefin.org' });
  const go = h('button', { class: 'btn' }, 'Connect SimpleFIN');
  go.addEventListener('click', busy(go, async () => {
    const r = await post('/api/connections/simplefin', { setupToken: token.value.trim() });
    toast(r.syncError ? 'Connected; first sync failed: ' + r.syncError : 'Connected');
    document.querySelector('.sheet-backdrop')?.remove();
    document.body.style.overflow = '';
    done();
  }));
  return h('div', { class: 'form' }, token, go);
}

// --- Manual accounts ------------------------------------------------------------

function addManual(done) {
  sheet('Add an account', (body, s) => {
    const name = h('input', { class: 'field', placeholder: 'e.g. Individual Brokerage' });
    const inst = h('input', { class: 'field', placeholder: 'e.g. Webull' });
    const type = h('select', { class: 'field' }, Object.entries(TYPE_LABEL).map(([k, v]) => h('option', { value: k }, v)));
    const balance = h('input', { class: 'field', inputmode: 'decimal', placeholder: '0.00' });
    const hint = h('p', { class: 'hint', style: { margin: 0 } });
    const paint = () => {
      const invest = type.value === 'BROKERAGE' || type.value === 'RETIREMENT';
      balance.closest('label').hidden = invest;
      hint.textContent = invest ? 'After saving, add what it holds (Webull) or a recurring contribution (PCS); prices update on their own.'
        : type.value === 'CREDIT_CARD' ? 'Enter what you owe as a positive number.' : '';
    };
    type.addEventListener('change', paint);
    const save = h('button', { class: 'btn primary' }, 'Add account');
    save.addEventListener('click', busy(save, async () => {
      if (!name.value.trim()) throw new Error('Give it a name');
      let cents = parseMoney(balance.value) || 0;
      if (type.value === 'CREDIT_CARD' || type.value === 'LOAN') cents = -Math.abs(cents);
      const a = await post('/api/accounts', { name: name.value.trim(), institution: inst.value.trim() || null, type: type.value, balanceCents: cents });
      s.close();
      done();
      if (type.value === 'BROKERAGE' || type.value === 'RETIREMENT') {
        editAccount(a, { account: a, holdings: [], plans: [], cashCents: null }, done);
      }
    }));
    body.append(h('div', { class: 'form' },
      field('Institution', inst), field('Name', name), field('Type', type), field('Current balance', balance), hint, save));
    type.value = 'BROKERAGE';
    paint();
  });
}

function editAccount(a, inv, done) {
  sheet(`${a.institution ? a.institution + ' ' : ''}${a.name}`, (body, s) => {
    const invest = a.type === 'BROKERAGE' || a.type === 'RETIREMENT';
    const tracked = inv && (inv.holdings.length || inv.plans.length);

    body.append(h('div', { class: 'hero', style: { padding: '0 0 12px' } },
      h('div', { class: 'label' }, TYPE_LABEL[a.type] + (a.manual ? ' · updated by hand' : '')),
      h('div', { class: 'figure', style: { fontSize: '34px' } }, money(a.balanceCents, { cents: true }))));

    if (invest && inv) body.append(investmentEditor(a, inv, () => { s.close(); done(); }));

    if (a.manual && !tracked && !invest) {
      const bal = h('input', { class: 'field', inputmode: 'decimal', value: centsToInput(a.type === 'CREDIT_CARD' || a.type === 'LOAN' ? -a.balanceCents : a.balanceCents) });
      const go = h('button', { class: 'btn primary' }, 'Update balance');
      go.addEventListener('click', busy(go, async () => {
        let cents = parseMoney(bal.value);
        if (cents === null) throw new Error('Enter an amount');
        if (a.type === 'CREDIT_CARD' || a.type === 'LOAN') cents = -Math.abs(cents);
        await post(`/api/accounts/${a.id}/balance`, { balanceCents: cents });
        s.close(); toast('Balance updated'); done();
      }));
      body.append(h('div', { class: 'form', style: { marginBottom: '18px' } }, field('Balance today', bal), go));
    }

    const name = h('input', { class: 'field', value: a.name });
    const type = h('select', { class: 'field' }, Object.entries(TYPE_LABEL).map(([k, v]) => h('option', { value: k, selected: k === a.type }, v)));
    const hidden = h('input', { type: 'checkbox', checked: a.hidden });
    const fields = [field('Name', name), field('Type', type, 'Changing the type re-files this account\'s transactions.')];
    let limit, due;
    if (a.type === 'CREDIT_CARD') {
      limit = h('input', { class: 'field', inputmode: 'decimal', value: centsToInput(a.creditLimitCents) });
      due = h('input', { class: 'field', type: 'date', value: a.paymentDueDate || '' });
      fields.push(h('div', { class: 'two' }, field('Credit limit', limit), field('Payment due', due)));
    }
    fields.push(h('label', { class: 'check' }, hidden, 'Hide from totals'));
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      await patch('/api/accounts/' + a.id, {
        name: name.value.trim() || null, type: type.value, hidden: hidden.checked,
        creditLimitCents: limit ? parseMoney(limit.value) ?? 0 : null,
        paymentDueDate: due && due.value ? due.value : null,
      });
      s.close(); toast('Saved'); done();
    }));
    const buttons = h('div', { class: 'btn-row' }, save);
    if (a.manual) {
      buttons.append(h('button', { class: 'btn danger', onclick: async () => {
        if (!(await confirmSheet('Delete this account?', 'Its transactions, holdings and history go with it.', 'Delete'))) return;
        await del('/api/accounts/' + a.id); s.close(); toast('Deleted'); done();
      } }, 'Delete'));
    }
    body.append(h('details', { open: !invest || null },
      h('summary', { style: { cursor: 'pointer', fontWeight: 600, margin: '8px 0 12px' } }, 'Account settings'),
      h('div', { class: 'form' }, fields, buttons)));
  });
}

// --- Investments ------------------------------------------------------------------

function investmentEditor(a, inv, done) {
  const box = h('div', { class: 'form', style: { marginBottom: '18px' } });

  const list = h('div', { class: 'list', style: { border: '1px solid var(--border)', borderRadius: '12px' } });
  for (const x of inv.holdings) {
    list.append(h('div', { class: 'row' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, x.symbol), h('div', { class: 'meta' }, `${shares(x.shares)} sh · ${x.name || ''}`)),
      h('div', { class: 'amount' }, money(x.valueCents),
        h('div', { class: 'meta' }, x.price ? money(Math.round(x.price * 100), { cents: true }) + ' ea' : 'no price')),
      h('button', { class: 'btn small', onclick: () => holdingSheet(a, x, done) }, 'Edit')));
  }
  if (!inv.holdings.length) list.append(h('div', { class: 'empty' }, 'No holdings yet.'));
  if (inv.cashCents) {
    list.append(h('div', { class: 'row' }, h('div', { class: 'main' }, h('div', { class: 'title' }, 'Cash')), h('div', { class: 'amount' }, money(inv.cashCents, { cents: true }))));
  }
  box.append(list);

  for (const p of inv.plans) {
    box.append(h('div', { class: 'banner info', style: { margin: 0 } }, '🔁', h('div', null,
      h('strong', null, `${money(p.amountCents, { cents: true })} into ${p.symbol} `),
      cadenceText(p), h('div', { class: 'hint' }, `${p.active ? 'Next ' + shortDate(p.nextDate) : 'Paused'}${p.lastApplied ? ' · last applied ' + shortDate(p.lastApplied) : ''}`)),
    h('button', { class: 'btn small', onclick: async () => {
      if (!(await confirmSheet('Stop this contribution?', 'Shares already added stay. Future contributions stop.', 'Stop'))) return;
      await del('/api/investments/plans/' + p.id); done();
    } }, 'Stop')));
  }

  box.append(h('div', { class: 'btn-row' },
    h('button', { class: 'btn', onclick: () => holdingSheet(a, null, done) }, icon('plus', 18), 'Holding'),
    h('button', { class: 'btn', onclick: () => planSheet(a, done) }, '🔁 Recurring contribution'),
    h('button', { class: 'btn', onclick: () => cashSheet(a, inv, done) }, 'Cash')));
  return box;
}

function cadenceText(p) {
  if (p.cadence === 'SEMIMONTHLY') return 'on the 15th and last day of each month';
  if (p.cadence === 'MONTHLY') return 'every month';
  return p.intervalDays === 14 ? 'every other week' : `every ${p.intervalDays} days`;
}

function holdingSheet(a, x, done) {
  sheet(x ? `Edit ${x.symbol}` : 'Add a holding', (body, s) => {
    const symbol = h('input', { class: 'field', value: x?.symbol || '', placeholder: 'e.g. VTI', autocapitalize: 'characters', disabled: !!x });
    const count = h('input', { class: 'field', inputmode: 'decimal', value: x ? String(x.shares) : '', placeholder: 'e.g. 12.5' });
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      const n = Number(count.value);
      if (!symbol.value.trim() || !Number.isFinite(n)) throw new Error('Enter a ticker and a share count');
      await put(`/api/investments/${a.id}/holdings`, { symbol: symbol.value.trim(), shares: n });
      s.close(); toast('Saved'); done();
    }));
    const remove = x ? h('button', { class: 'btn danger', onclick: async () => {
      await put(`/api/investments/${a.id}/holdings`, { symbol: x.symbol, shares: 0 }); s.close(); done();
    } }, 'Remove') : null;
    body.append(h('div', { class: 'form' }, field('Ticker', symbol), field('Shares', count, 'Prices update three times each trading day.'),
      h('div', { class: 'btn-row' }, save, remove)));
  });
}

function cashSheet(a, inv, done) {
  sheet('Uninvested cash', (body, s) => {
    const cash = h('input', { class: 'field', inputmode: 'decimal', value: centsToInput(inv.cashCents) });
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      await put(`/api/investments/${a.id}/cash`, { cashCents: parseMoney(cash.value) || 0 });
      s.close(); done();
    }));
    body.append(h('div', { class: 'form' }, field('Cash balance', cash), save));
  });
}

function planSheet(a, done) {
  sheet('Recurring contribution', (body, s) => {
    const symbol = h('input', { class: 'field', placeholder: 'The fund it buys, e.g. VOO', autocapitalize: 'characters' });
    const amount = h('input', { class: 'field', inputmode: 'decimal', placeholder: 'e.g. 382.50' });
    const cadence = h('select', { class: 'field' },
      h('option', { value: 'EVERY_N_DAYS:14' }, 'Every other week'),
      h('option', { value: 'EVERY_N_DAYS:15' }, 'Every 15 days'),
      h('option', { value: 'SEMIMONTHLY' }, '15th and last day of the month'),
      h('option', { value: 'MONTHLY' }, 'Monthly'),
      h('option', { value: 'EVERY_N_DAYS:7' }, 'Weekly'));
    const asOf = h('input', { class: 'field', type: 'date' });
    const startMode = h('select', { class: 'field' }, h('option', { value: 'shares' }, 'Shares'), h('option', { value: 'balance' }, 'Dollar balance'));
    const start = h('input', { class: 'field', inputmode: 'decimal', placeholder: 'From your latest statement' });
    const first = h('input', { class: 'field', type: 'date' });
    const save = h('button', { class: 'btn primary' }, 'Start tracking');
    save.addEventListener('click', busy(save, async () => {
      const [cad, days] = cadence.value.split(':');
      const startValue = start.value.trim();
      if (!symbol.value.trim() || !parseMoney(amount.value) || !first.value) throw new Error('Fill in the fund, amount and first contribution date');
      toast('Fetching prices and rebuilding history…');
      await post(`/api/investments/${a.id}/plans`, {
        symbol: symbol.value.trim(), amountCents: parseMoney(amount.value), cadence: cad, intervalDays: days ? Number(days) : null,
        firstContribution: first.value, asOf: asOf.value || null,
        startShares: startValue && startMode.value === 'shares' ? Number(startValue) : null,
        startBalanceCents: startValue && startMode.value === 'balance' ? parseMoney(startValue) : null,
      });
      s.close(); toast('Tracking'); done();
    }));
    body.append(h('div', { class: 'form' },
      h('p', { class: 'hint', style: { margin: 0 } }, 'Each contribution buys the fund at that day\'s closing price, so the balance tracks itself. Correct it from a statement any time by editing the holding.'),
      field('Fund ticker', symbol),
      h('div', { class: 'two' }, field('Amount each time', amount), field('How often', cadence)),
      h('div', { class: 'two' }, field('Starting point', startMode), field('Amount', start)),
      field('Starting point is as of', asOf, 'The statement date. Blank means today.'),
      field('First contribution to add', first, 'The first payday after the statement date.'),
      save));
  });
}

// --- Import -------------------------------------------------------------------------

function importSheet(accounts, done) {
  sheet('Import a statement', (body, s) => {
    const account = h('select', { class: 'field' }, accounts.map((a) => h('option', { value: a.id }, `${a.institution ? a.institution + ' · ' : ''}${a.name}`)));
    const file = h('input', { class: 'field', type: 'file', accept: '.csv,.ofx,.qfx,.qbo,text/csv' });
    const flip = h('input', { type: 'checkbox' });
    const result = h('div');
    const go = h('button', { class: 'btn primary' }, 'Import');
    go.addEventListener('click', busy(go, async () => {
      if (!file.files[0]) throw new Error('Choose a file');
      const form = new FormData();
      form.append('accountId', account.value);
      form.append('file', file.files[0]);
      form.append('flipSign', flip.checked);
      const r = await upload('/api/import', form);
      clear(result).append(h('div', { class: 'banner info' }, '✅', h('div', null,
        `${r.imported} new, ${r.duplicates} already here (${r.format}).`,
        r.balanceCents !== null && r.balanceCents !== undefined ? ` Balance set to ${money(r.balanceCents, { cents: true })}.` : '',
        r.problems.length ? h('div', { class: 'hint' }, r.problems.join(' · ')) : null)));
      done();
    }));
    if (!accounts.length) {
      body.append(h('p', null, 'Add the account first (Accounts → Add by hand), then import into it.'));
      return;
    }
    body.append(h('div', { class: 'form' },
      h('p', { class: 'hint', style: { margin: 0 } }, 'Download the "Quicken (QFX)" or "OFX" file from your bank\'s website if it offers one: it carries the bank\'s own ids and your balance. CSV works too. Importing the same file twice adds nothing.'),
      field('Into account', account), field('File', file),
      h('label', { class: 'check' }, flip, 'CSV shows purchases as positive numbers'),
      go, result));
  });
}
