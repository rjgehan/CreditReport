import { get, post, put, patch, del, upload, loadScript } from '../api.js';
import { h, clear, sheet, toast, field, busy, confirmSheet, icon, onMount, logoAvatar } from '../ui.js';
import { money, pct, shares, ago, parseMoney, centsToInput, TYPE_LABEL, shortDate, privacy } from '../fmt.js';
import { lineChart } from '../charts.js';

const GROUP_ORDER = ['Cash', 'Credit', 'Investments', 'Retirement', 'Loans', 'Other'];

export async function render(params, app) {
  const [accounts, connections, summary] = await Promise.all([
    get('/api/accounts'), get('/api/connections'), get('/api/summary')]);
  const root = h('div', { class: 'grid' });
  const refresh = () => app.rerender();

  // A broken connection is the one thing worth seeing before the numbers.
  for (const c of connections.filter((c) => c.status !== 'OK')) {
    root.append(h('div', { class: 'banner' }, h('div', { style: { flex: 1 } },
      h('strong', null, c.displayName), ' ', c.status === 'NEEDS_REAUTH' ? 'needs you to sign in again.' : 'failed to sync.',
      c.lastError ? h('div', { class: 'hint' }, c.lastError) : null),
    c.provider === 'TELLER' ? h('button', { class: 'btn small', onclick: () => connectBank(app, c, refresh) }, 'Fix') : null));
  }

  if (accounts.length) {
    const chart = h('div', { style: { marginTop: '8px' } });
    root.append(h('section', { class: 'card' },
      h('div', { class: 'nw-top' }, h('span', { class: 'label' }, 'Net worth')),
      h('div', { class: 'nw-figure' }, money(summary.netWorthCents)),
      chart));
    onMount(() => lineChart(chart, summary.netWorthHistory, { height: 120 }));
  } else {
    root.append(h('div', { class: 'card empty' },
      h('p', null, 'Connect SoFi and Chase, add Webull and PCS by hand, or import a statement. The buttons are at the bottom.')));
  }

  const groups = new Map(GROUP_ORDER.map((g) => [g, []]));
  for (const a of accounts) groups.get(a.group)?.push(a);
  for (const [name, list] of groups) {
    if (!list.length) continue;
    const total = list.filter((a) => !a.hidden).reduce((s, a) => s + a.balanceCents, 0);
    const section = h('section', { class: 'card flush' },
      h('div', { class: 'card-head', style: { padding: '14px 16px 4px' } }, h('h2', null, name), h('strong', null, money(total))));
    const ul = h('div', { class: 'list' });
    for (const a of list) {
      const investment = a.type === 'BROKERAGE' || a.type === 'RETIREMENT';
      const sub = [];
      if (a.mask) sub.push('••' + a.mask);
      if (a.type === 'CREDIT_CARD' && a.creditLimitCents) sub.push(`${pct(-a.balanceCents / a.creditLimitCents)} of limit`);
      if (a.type === 'CREDIT_CARD' && a.paymentDueDate) sub.push('due ' + shortDate(a.paymentDueDate));
      if (a.hidden) sub.push('hidden');
      ul.append(h('button', { class: 'row', style: a.hidden ? { opacity: 0.55 } : null,
        onclick: () => (investment ? app.go('#/investment?id=' + a.id) : editAccount(a, refresh)) },
      logoAvatar(a.logo, avatarFor(a)),
      h('div', { class: 'main' },
        h('div', { class: 'title' }, `${a.institution ? a.institution + ' ' : ''}${a.name}`),
        sub.length ? h('div', { class: 'meta' }, sub.join(' · ')) : null),
      h('div', { class: 'amount' }, money(a.balanceCents, { cents: true }))));
    }
    section.append(ul);
    root.append(section);
  }

  // Everything for setting things up lives below the numbers.
  root.append(h('div', { class: 'section-title' }, 'Add'),
    h('div', { class: 'btn-row' },
      h('button', { class: 'btn primary', onclick: () => connectBank(app, null, refresh) }, icon('plus', 18), 'Connect a bank'),
      h('button', { class: 'btn', onclick: () => addManual(app, refresh) }, 'Add by hand'),
      h('button', { class: 'btn', onclick: () => importSheet(accounts, refresh) }, icon('upload', 18), 'Import statement')));

  if (connections.length) {
    const ul = h('div', { class: 'list' });
    for (const c of connections) {
      ul.append(h('div', { class: 'row' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, c.displayName),
          h('div', { class: 'meta' }, `${c.accountCount} account${c.accountCount === 1 ? '' : 's'} · synced ${ago(c.lastSyncedAt)}`)),
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

  // Tucked at the very bottom on purpose: easy to find when you know, unlikely to
  // be tapped by someone poking around. Amounts hide again whenever the app opens.
  const showAmounts = h('input', { type: 'checkbox', class: 'switch', checked: !privacy.on, 'aria-label': 'Show amounts' });
  showAmounts.addEventListener('change', () => privacy.set(!showAmounts.checked));
  root.append(h('div', { class: 'section-title' }, 'Privacy'),
    h('section', { class: 'card flush' },
      h('label', { class: 'row' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, 'Show amounts'),
          h('div', { class: 'meta' }, 'Hidden again whenever the app opens or sits in the background')),
        showAmounts)));
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

function addManual(app, done) {
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
      if (type.value === 'BROKERAGE' || type.value === 'RETIREMENT') app.go('#/investment?id=' + a.id);
      else done();
    }));
    body.append(h('div', { class: 'form' },
      field('Institution', inst), field('Name', name), field('Type', type), field('Current balance', balance), hint, save));
    type.value = 'BROKERAGE';
    paint();
  });
}

export function editAccount(a, done, afterDelete) {
  sheet(`${a.institution ? a.institution + ' ' : ''}${a.name}`, (body, s) => {
    const invest = a.type === 'BROKERAGE' || a.type === 'RETIREMENT';

    body.append(h('div', { class: 'hero', style: { padding: '0 0 12px' } },
      h('div', { class: 'label' }, TYPE_LABEL[a.type] + (a.manual ? ' · updated by hand' : '')),
      h('div', { class: 'figure', style: { fontSize: '34px' } }, money(a.balanceCents, { cents: true }))));

    if (a.manual && !invest) {
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
        await del('/api/accounts/' + a.id); s.close(); toast('Deleted'); (afterDelete || done)();
      } }, 'Delete'));
    }
    body.append(h('div', { class: 'form' }, fields, buttons));
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
