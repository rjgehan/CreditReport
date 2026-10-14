import { get, post, put, patch, del, upload } from '../api.js';
import { h, clear, sheet, toast, field, busy, confirmSheet, icon, onMount, logoAvatar, swipeable } from '../ui.js';
import { money, pct, shares, ago, parseMoney, centsToInput, TYPE_LABEL, shortDate, privacy } from '../fmt.js';
import { lineChart } from '../charts.js';

const GROUP_ORDER = ['Cash', 'Credit', 'Investments', 'Retirement', 'Loans', 'Other'];
let hiddenOpen = false;

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
    c.provider === 'SIMPLEFIN' ? h('button', { class: 'btn small', onclick: () => connectBank(app, c, refresh) }, 'Fix') : null));
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
      h('p', null, 'Connect SoFi and Chase through SimpleFIN, add Webull and PCS by hand, or import a statement. The buttons are at the bottom.')));
  }

  // Swipe left on an account to hide it (or, in the Hidden list, to bring it back).
  const setHidden = async (a, hidden) => {
    await patch('/api/accounts/' + a.id, { hidden });
    const name = `${a.institution ? a.institution + ' ' : ''}${a.name}`;
    toast(hidden ? `${name} hidden` : `${name} is back`);
    refresh();
  };
  const accountRow = (a) => {
    const investment = a.type === 'BROKERAGE' || a.type === 'RETIREMENT';
    const sub = [];
    if (a.mask) sub.push('••' + a.mask);
    if (a.type === 'CREDIT_CARD' && a.creditLimitCents) sub.push(`${pct(-a.balanceCents / a.creditLimitCents)} of limit`);
    if (a.type === 'CREDIT_CARD' && a.paymentDueDate) sub.push('due ' + shortDate(a.paymentDueDate));
    const row = h('button', { class: 'row',
      onclick: () => (investment ? app.go('#/investment?id=' + a.id) : editAccount(a, refresh)) },
    logoAvatar(a.logo, avatarFor(a)),
    h('div', { class: 'main' },
      h('div', { class: 'title' }, `${a.institution ? a.institution + ' ' : ''}${a.name}`),
      sub.length ? h('div', { class: 'meta' }, sub.join(' · ')) : null),
    h('div', { class: 'amount' }, money(a.balanceCents, { cents: true })));
    return a.hidden
      ? swipeable(row, 'Show', () => setHidden(a, false), 'show')
      : swipeable(row, 'Hide', () => setHidden(a, true));
  };

  const groups = new Map(GROUP_ORDER.map((g) => [g, []]));
  for (const a of accounts) if (!a.hidden) groups.get(a.group)?.push(a);
  for (const [name, list] of groups) {
    if (!list.length) continue;
    const total = list.reduce((s, a) => s + a.balanceCents, 0);
    root.append(h('section', { class: 'card flush' },
      h('div', { class: 'card-head', style: { padding: '14px 16px 4px' } }, h('h2', null, name), h('strong', null, money(total))),
      h('div', { class: 'list' }, list.map(accountRow))));
  }

  // Hidden accounts keep syncing but count nowhere. Folded away at the bottom.
  const hiddenAccounts = accounts.filter((a) => a.hidden);
  if (hiddenAccounts.length) {
    const list = h('div', { class: 'list', hidden: !hiddenOpen }, hiddenAccounts.map(accountRow));
    const chevron = icon('chevron', 18);
    const head = h('button', { class: 'row fold' + (hiddenOpen ? ' open' : ''), 'aria-expanded': String(hiddenOpen) },
      h('div', { class: 'main' }, h('div', { class: 'title' }, `Hidden (${hiddenAccounts.length})`),
        h('div', { class: 'meta' }, 'Not in net worth or any total. Swipe left to show one again.')),
      chevron);
    head.addEventListener('click', () => {
      hiddenOpen = list.hidden;
      list.hidden = !hiddenOpen;
      head.classList.toggle('open', hiddenOpen);
      head.setAttribute('aria-expanded', String(hiddenOpen));
    });
    root.append(h('section', { class: 'card flush' }, head, list));
  }

  // Everything for setting things up lives below the numbers.
  root.append(h('div', { class: 'section-title' }, 'Add'),
    h('div', { class: 'btn-row' },
      h('button', { class: 'btn primary', onclick: () => connectBank(app, null, refresh) }, icon('plus', 18), 'Connect a bank'),
      h('button', { class: 'btn', onclick: () => addManual(app, refresh) }, 'Add by hand'),
      h('button', { class: 'btn', onclick: () => importSheet(accounts, refresh) }, icon('upload', 18), 'Import statement')));

  root.append(h('div', { class: 'section-title' }, 'More'),
    h('section', { class: 'card flush' },
      h('a', { class: 'row tap', href: '#/recurring' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, 'Monthly expenses'),
          h('div', { class: 'meta' }, 'Subscriptions, bills and everything else that comes out on its own')),
        icon('chevron', 18)),
      h('a', { class: 'row tap', href: '#/bills' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, 'Rent and loans'),
          h('div', { class: 'meta' }, 'Rent and loans: what you\'ve paid, month by month')),
        icon('chevron', 18)),
      h('a', { class: 'row tap', href: '#/categories' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, 'Categories'),
          h('div', { class: 'meta' }, 'Make, rename or delete categories')),
        icon('chevron', 18)),
      h('a', { class: 'row tap', href: '#/paychecks' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, 'Paychecks'),
          h('div', { class: 'meta' }, 'Which deposits are pay, and a paystub for taxes and 401(k)')),
        icon('chevron', 18)),
      h('a', { class: 'row tap', href: '#/shortcut' },
        h('div', { class: 'main' }, h('div', { class: 'title' }, 'iPhone shortcut'),
          h('div', { class: 'meta' }, 'Files leftover transactions with Apple Intelligence')),
        icon('chevron', 18))));

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

/**
 * Banks and cards come in through SimpleFIN Bridge: you connect them on the
 * bridge, then hand this app one setup token. A broken login is fixed on the
 * bridge too, after which Sync pulls again.
 */
function connectBank(app, repair, done) {
  sheet(repair ? `Fix ${repair.displayName}` : 'Connect a bank', (body, s) => {
    if (repair) {
      body.append(
        h('p', { style: { marginTop: 0 } }, 'Sign in to the bank again on the SimpleFIN bridge, then sync here.'),
        h('div', { class: 'btn-row' },
          h('a', { class: 'btn primary', href: 'https://bridge.simplefin.org', target: '_blank', rel: 'noopener' }, 'Open SimpleFIN'),
          h('button', { class: 'btn', onclick: async () => {
            const r = await post(`/api/connections/${repair.id}/sync`);
            toast(r.ok ? 'Synced' : r.error); s.close(); done();
          } }, 'Sync now')),
        h('p', { class: 'hint' }, 'Still failing? Make a new setup token on the bridge and paste it below to replace this connection.'),
        simpleFinForm(s, done));
      return;
    }
    body.append(
      h('ol', { class: 'steps' },
        step(1, 'Sign up at SimpleFIN Bridge', 'About $15 a year. It connects to your banks and hands this app read-only access.'),
        step(2, 'Connect SoFi and Chase there', 'Add any other banks or cards too. Leave Webull and PCS out; they are tracked here by hand.'),
        step(3, 'Create a setup token', 'On the bridge: Apps, New app connection. Copy the token it shows.'),
        step(4, 'Paste it here', 'Accounts appear right away; transactions follow over the next few minutes.')),
      h('a', { class: 'btn', href: 'https://bridge.simplefin.org', target: '_blank', rel: 'noopener', style: { margin: '14px 0' } }, 'Open SimpleFIN Bridge'),
      simpleFinForm(s, done));
  });
}

function step(n, title, text) {
  return h('li', { class: 'step' }, h('div', { class: 'step-n' }, String(n)),
    h('div', { class: 'step-body' }, h('div', { class: 'title' }, title), h('div', { class: 'hint' }, text)));
}

function simpleFinForm(s, done) {
  const token = h('textarea', { class: 'field', rows: 3, placeholder: 'Paste the setup token', autocapitalize: 'off', autocomplete: 'off', spellcheck: 'false' });
  const go = h('button', { class: 'btn primary' }, 'Connect');
  go.addEventListener('click', busy(go, async () => {
    if (!token.value.trim()) throw new Error('Paste the setup token first');
    toast('Connecting…');
    const r = await post('/api/connections/simplefin', { setupToken: token.value.trim() });
    toast(r.syncError ? 'Connected, but the first sync failed: ' + r.syncError : 'Connected');
    s.close();
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
