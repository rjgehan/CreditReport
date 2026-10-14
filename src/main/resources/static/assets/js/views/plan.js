import { get, post, put, del } from '../api.js';
import { h, sheet, toast, field, busy, confirmSheet, icon } from '../ui.js';
import { money, pct, parseMoney, centsToInput, shortDate, thisMonth, privacy, depositLine, filedLine } from '../fmt.js';
import { meter } from '../charts.js';
import { categories } from './activity.js';

// The screens behind Home's targets and goals cards, Flow's income and Activity's rules.

function page(builder) {
  return async (params, app) => {
    const root = h('div', { class: 'grid' });
    await builder(root, () => app.rerender());
    return root;
  };
}

export const targetsPage = page((root, refresh) => targets(root, refresh));
export const goalsPage = page((root, refresh) => goals(root, refresh));
export const paychecksPage = page((root, refresh) => pay(root, refresh));
export const rulesPage = page((root, refresh) => rules(root, refresh));

// --- Spending targets ---------------------------------------------------------------

async function targets(root, refresh) {
  const [progress, cats, settings] = await Promise.all([
    get('/api/targets?month=' + thisMonth()), categories(), get('/api/settings')]);
  const target = settings.savingsTargetCents;
  root.append(fillButton(refresh, progress.spending.length === 0 && target === null));
  // The savings target is set once and rarely changes, so it lives here rather
  // than on Home; Home's left-to-spend already has it taken out.
  root.append(h('section', { class: 'card flush' },
    h('button', { class: 'row', onclick: () => savingsTargetSheet(target, refresh) },
      h('div', { class: 'main' }, h('div', { class: 'title' }, 'Savings target'),
        h('div', { class: 'meta' }, 'Kept aside each month before anything is spendable')),
      h('div', { class: 'amount' }, target === null ? h('span', { style: { color: 'var(--accent)' } }, 'Set') : money(target)),
      icon('chevron', 18))));
  const card = h('section', { class: 'card flush' });
  const list = h('div', { class: 'list' });
  for (const s of progress.spending) {
    const row = targetRow(s);
    row.addEventListener('click', (e) => { e.preventDefault(); targetSheet(cats, s, refresh); });
    list.append(row);
  }
  if (!progress.spending.length) list.append(h('div', { class: 'empty' }, 'No targets yet. A monthly cap per category shows how you\'re pacing.'));
  card.append(list);
  root.append(card,
    h('div', null, h('button', { class: 'btn primary', onclick: () => targetSheet(cats, null, refresh) }, icon('plus', 18), 'Add a target')),
    h('p', { class: 'hint' }, 'The line in each bar is where an even pace would put you today.'));
}

function targetSheet(cats, existing, refresh) {
  sheet(existing ? existing.name : 'New spending target', (body, s) => {
    const cat = h('select', { class: 'field', disabled: !!existing },
      cats.filter((c) => c.kind === 'EXPENSE').map((c) => h('option', { value: c.id, selected: existing && c.id === existing.categoryId }, `${c.icon || ''} ${c.name}`)));
    // With amounts hidden, the current limit isn't prefilled; a blank field keeps it.
    const hidden = privacy.on && existing;
    const limit = h('input', { class: 'field', inputmode: 'decimal', value: existing && !hidden ? centsToInput(existing.limitCents) : '',
      placeholder: hidden ? 'Hidden. Type a new amount' : 'e.g. 400' });
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      const cents = parseMoney(limit.value);
      if ((!cents || cents <= 0) && hidden) { s.close(); return; }
      if (!cents || cents <= 0) throw new Error('Enter a monthly amount');
      await put('/api/targets/' + cat.value, { monthlyLimitCents: cents });
      s.close(); refresh();
    }));
    const remove = existing ? h('button', { class: 'btn danger', onclick: async () => {
      await put('/api/targets/' + existing.categoryId, { monthlyLimitCents: 0 }); s.close(); refresh();
    } }, 'Remove') : null;
    const txns = existing ? h('a', { class: 'btn', href: `#/activity?category=${existing.categoryId}&month=${thisMonth()}` }, 'Transactions') : null;
    body.append(h('div', { class: 'form' }, field('Category', cat), field('Monthly limit', limit), h('div', { class: 'btn-row' }, save, txns, remove)));
  });
}

// --- Filled in for you ------------------------------------------------------------------

function fillButton(refresh, prominent) {
  const b = h('button', { class: prominent ? 'btn primary' : 'btn' }, 'Fill in for me');
  b.addEventListener('click', busy(b, () => fillSheet(refresh)));
  return h('div', null, b);
}

/** What the server works out from your pay and spending, shown before anything is saved. */
async function fillSheet(refresh) {
  const s = await get('/api/targets/suggest');
  sheet('Fill in for me', (body, sh) => {
    const line = (title, value, meta) => h('div', { class: 'row' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, title), meta ? h('div', { class: 'meta wrap' }, meta) : null),
      h('div', { class: 'amount' }, value));
    const facts = h('div', { class: 'list' });
    facts.append(line('Take-home pay', s.incomeCents === null ? '—' : money(s.incomeCents),
      s.incomeCents === null ? 'Not known yet: tap This is my pay on Home first' : 'A month, from your paychecks'));
    facts.append(line('Bills', s.billsCents === null ? '—' : money(s.billsCents),
      s.billsCents === null ? 'Not known until a full month of bank history' : 'Rent, loans and other monthly bills'));
    if (s.otherCents) facts.append(line('Everything else', money(s.otherCents), 'Venmo, Zelle and other spending no target covers'));
    if (s.savingsTargetSet) {
      facts.append(line('Savings target', money(s.savingsTargetCents), 'Yours; it stays'));
    } else if (s.savingsTargetCents) {
      facts.append(line('Savings target', money(s.savingsTargetCents),
        s.savingsTrimmed ? 'What fits after bills and needs (a fifth of take-home doesn\'t)' : 'Kept each month: a fifth of take-home'));
    } else if (s.incomeCents !== null) {
      facts.append(line('Savings target', '—', 'Nothing fits after bills and needs, so none is set'));
    }
    const list = h('div', { class: 'list' });
    for (const t of s.targets) {
      const meta = t.existing ? 'Yours already; it stays'
        : t.averageCents === null ? 'Typical without a car in Hoboken'
          : t.limitCents < t.averageCents ? `You spend about ${money(t.averageCents)}; trimmed to fit`
            : `You spend about ${money(t.averageCents)}`;
      list.append(line(t.name, money(t.limitCents), meta));
    }
    if (!s.targets.length) list.append(h('div', { class: 'empty' }, 'No spending to base targets on yet.'));
    const rest = h('div', { class: 'list' });
    if (s.incomeCents !== null) {
      rest.append(line(s.unplannedCents >= 0 ? 'Left unplanned' : 'Short each month', money(Math.abs(s.unplannedCents)),
        s.unplannedCents >= 0 ? 'A cushion for the month' : 'All of this comes to more than take-home'));
    }
    if (s.goal) {
      rest.append(line(s.goal.name, money(s.goal.targetCents),
        `Three months of bills and needs, in ${s.goal.accountName} by ${shortDate(s.goal.targetDate)}`));
    }
    const box = (el) => h('div', { style: { border: '1px solid var(--border)', borderRadius: '12px', marginBottom: '12px', overflow: 'hidden' } }, el);
    const use = h('button', { class: 'btn primary' }, 'Use these');
    use.addEventListener('click', busy(use, async () => {
      const r = await post('/api/targets/suggest', {});
      sh.close();
      const parts = [];
      if (r.targets) parts.push(`${r.targets} target${r.targets === 1 ? '' : 's'}`);
      if (r.savingsTarget) parts.push('savings target');
      if (r.goal) parts.push('emergency fund');
      toast(parts.length ? 'Added ' + parts.join(', ') : 'Nothing new to add');
      refresh();
    }));
    body.append(
      h('p', { class: 'hint', style: { margin: '0 0 12px' } }, s.months
        ? `From your last ${s.months} full month${s.months === 1 ? '' : 's'} of spending. Change any of them later.`
        : 'No full month of history yet, so these are typical amounts. Change any of them later.'),
      box(facts), h('div', { class: 'cat-group' }, 'Monthly targets'), box(list), rest.childElementCount ? box(rest) : null,
      h('div', { class: 'btn-row' }, use));
  });
}

// --- Goals ---------------------------------------------------------------------------

async function goals(root, refresh) {
  const [progress, cats, accounts] = await Promise.all([get('/api/targets?month=' + thisMonth()), categories(), get('/api/accounts')]);
  const card = h('section', { class: 'card flush' });
  const list = h('div', { class: 'list' });
  for (const g of progress.goals) {
    const row = goalRow(g);
    row.classList.add('tap');
    row.addEventListener('click', () => goalSheet(cats, accounts, g, refresh));
    list.append(row);
  }
  if (!progress.goals.length) list.append(h('div', { class: 'empty' }, 'No goals yet.'));
  card.append(list);
  if (!progress.goals.length) root.append(fillButton(refresh, true));
  root.append(card, h('div', null, h('button', { class: 'btn primary', onclick: () => goalSheet(cats, accounts, null, refresh) }, icon('plus', 18), 'Add a goal')));
}

function goalSheet(cats, accounts, g, refresh) {
  sheet(g ? g.name : 'New goal', (body, s) => {
    const name = h('input', { class: 'field', value: g?.name || '', placeholder: 'e.g. Emergency fund' });
    const type = h('select', { class: 'field' },
      h('option', { value: 'BALANCE', selected: g?.type === 'BALANCE' }, 'Reach a balance'),
      h('option', { value: 'MONTHLY_AMOUNT', selected: g?.type === 'MONTHLY_AMOUNT' }, 'Save an amount every month'),
      h('option', { value: 'SAVINGS_RATE', selected: g?.type === 'SAVINGS_RATE' }, 'Save a share of income'));
    const amount = h('input', { class: 'field', inputmode: 'decimal', value: g?.targetCents ? centsToInput(g.targetCents) : '' });
    const percent = h('input', { class: 'field', inputmode: 'decimal', value: g?.targetPercent ?? '', placeholder: 'e.g. 20' });
    const account = h('select', { class: 'field' }, h('option', { value: '' }, 'All savings & investment accounts'),
      accounts.filter((a) => ['SAVINGS', 'BROKERAGE', 'RETIREMENT', 'CHECKING'].includes(a.type))
        .map((a) => h('option', { value: a.id, selected: g?.accountId === a.id }, `${a.institution ? a.institution + ' · ' : ''}${a.name}`)));
    const category = h('select', { class: 'field' }, h('option', { value: '' }, 'All saving & investing'),
      cats.filter((c) => c.kind === 'SAVINGS').map((c) => h('option', { value: c.id, selected: g?.categoryId === c.id }, c.name)));
    const date = h('input', { class: 'field', type: 'date', value: g?.targetDate || '' });
    const fAmount = field('Amount', amount);
    const fPercent = field('Percent of gross income', percent);
    const fAccount = field('Which account', account);
    const fCategory = field('Counts', category);
    const fDate = field('By (optional)', date, 'Shows how much a month gets you there.');
    const paint = () => {
      const t = type.value;
      fAmount.hidden = t === 'SAVINGS_RATE';
      fPercent.hidden = t !== 'SAVINGS_RATE';
      fAccount.hidden = t !== 'BALANCE';
      fDate.hidden = t !== 'BALANCE';
      fCategory.hidden = t !== 'MONTHLY_AMOUNT';
    };
    type.addEventListener('change', paint);
    paint();
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      const payload = {
        name: name.value.trim(), type: type.value,
        targetCents: type.value === 'SAVINGS_RATE' ? null : parseMoney(amount.value),
        targetPercent: type.value === 'SAVINGS_RATE' ? Number(percent.value) : null,
        accountId: type.value === 'BALANCE' && account.value ? Number(account.value) : null,
        categoryId: type.value === 'MONTHLY_AMOUNT' && category.value ? Number(category.value) : null,
        targetDate: type.value === 'BALANCE' && date.value ? date.value : null,
      };
      if (!payload.name) throw new Error('Give it a name');
      if (g) await put('/api/goals/' + g.id, payload); else await post('/api/goals', payload);
      s.close(); refresh();
    }));
    const remove = g ? h('button', { class: 'btn danger', onclick: async () => { await del('/api/goals/' + g.id); s.close(); refresh(); } }, 'Delete') : null;
    body.append(h('div', { class: 'form' }, field('Name', name), field('Kind', type), fAmount, fPercent, fAccount, fCategory, fDate,
      h('div', { class: 'btn-row' }, save, remove)));
  });
}

// --- Paychecks ------------------------------------------------------------------------

async function pay(root, refresh) {
  const d = await get('/api/paychecks');
  const o = d.overview;
  root.append(h('div', { class: 'tiles' },
    h('div', { class: 'tile' }, h('div', { class: 'label' }, 'Average monthly take-home'), h('div', { class: 'value' }, money(o.averageMonthlyCents)))));

  const detected = h('div', { class: 'list' });
  for (const p of o.detected) {
    let action = h('span', { class: 'pill good' }, '✓ Paycheck');
    if (!p.tracked) {
      action = h('button', { class: 'btn small' }, 'This is my pay');
      action.addEventListener('click', busy(action, async () => {
        const r = await post('/api/paychecks/track', { pattern: p.pattern });
        toast(filedLine(r.filed)); refresh();
      }));
    }
    detected.append(h('div', { class: 'row' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, p.pattern),
        h('div', { class: 'meta' }, `${depositLine(p)} · next ${shortDate(p.nextExpected)}`)),
      action));
  }
  if (!o.detected.length) detected.append(h('div', { class: 'empty' }, 'No regular deposits found yet. They show up after two or three paydays.'));
  root.append(h('div', { class: 'section-title' }, 'Regular deposits'), h('section', { class: 'card flush' }, detected));

  const stubs = h('div', { class: 'list' });
  for (const s of d.paystubs) {
    stubs.append(h('button', { class: 'row', onclick: () => paystubSheet(s, refresh) },
      h('div', { class: 'main' }, h('div', { class: 'title' }, s.employer),
        h('div', { class: 'meta' }, `${money(s.grossCents, { cents: true })} gross → ${money(s.netCents, { cents: true })} net, ${s.frequency.toLowerCase()}`)),
      icon('chevron', 18)));
  }
  if (!d.paystubs.length) stubs.append(h('div', { class: 'empty' }, 'Add one paystub so the cash flow starts from gross pay and shows taxes and your 401(k).'));
  root.append(h('div', { class: 'section-title' }, 'Paystub'), h('section', { class: 'card flush' }, stubs),
    h('div', null, h('button', { class: 'btn', onclick: () => paystubSheet(null, refresh) }, icon('plus', 18), 'Add paystub')));

  const months = h('div', { class: 'list' });
  for (const m of [...o.byMonth].reverse()) {
    months.append(h('div', { class: 'row' }, h('div', { class: 'main' }, h('div', { class: 'title' }, m.month), h('div', { class: 'meta' }, `${m.count} paycheck${m.count === 1 ? '' : 's'}`)),
      h('div', { class: 'amount' }, money(m.totalCents))));
  }
  root.append(h('div', { class: 'section-title' }, 'Take-home by month'), h('section', { class: 'card flush' }, months));
}

function paystubSheet(p, refresh) {
  sheet(p ? p.employer : 'Paystub', (body, s) => {
    const input = (cents) => h('input', { class: 'field', inputmode: 'decimal', value: cents ? centsToInput(cents) : '' });
    const employer = h('input', { class: 'field', value: p?.employer || '' });
    const pattern = h('input', { class: 'field', value: p?.matchPattern || '', placeholder: 'e.g. ACME PAYROLL' });
    const freq = h('select', { class: 'field' }, ['WEEKLY', 'BIWEEKLY', 'SEMIMONTHLY', 'MONTHLY'].map((f) =>
      h('option', { value: f, selected: (p?.frequency || 'BIWEEKLY') === f }, { WEEKLY: 'Weekly', BIWEEKLY: 'Every other week', SEMIMONTHLY: 'Twice a month', MONTHLY: 'Monthly' }[f])));
    const gross = input(p?.grossCents);
    const fed = input(p?.federalTaxCents);
    const state = input(p?.stateTaxCents);
    const fica = input(p?.ficaCents);
    const k401 = input(p?.retirementCents);
    const benefits = input(p?.benefitsCents);
    const other = input(p?.otherDeductionsCents);
    const match = input(p?.employerMatchCents);
    const net = h('div', { class: 'hint' });
    const calc = () => {
      const v = (el) => parseMoney(el.value) || 0;
      const n = v(gross) - v(fed) - v(state) - v(fica) - v(k401) - v(benefits) - v(other);
      net.textContent = `Net pay per check: ${money(n, { cents: true })}. It should match what lands in checking.`;
    };
    [gross, fed, state, fica, k401, benefits, other].forEach((el) => el.addEventListener('input', calc));
    calc();
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      const v = (el) => parseMoney(el.value) || 0;
      const payload = { employer: employer.value.trim(), matchPattern: pattern.value.trim() || null, frequency: freq.value,
        grossCents: v(gross), federalTaxCents: v(fed), stateTaxCents: v(state), ficaCents: v(fica), retirementCents: v(k401),
        benefitsCents: v(benefits), otherDeductionsCents: v(other), employerMatchCents: v(match), active: true };
      const r = p ? await put('/api/paystubs/' + p.id, payload) : await post('/api/paystubs', payload);
      if (r.filed) toast(filedLine(r.filed, payload.matchPattern));
      s.close(); refresh();
    }));
    const remove = p ? h('button', { class: 'btn danger', onclick: async () => { await del('/api/paystubs/' + p.id); s.close(); refresh(); } }, 'Delete') : null;
    body.append(h('div', { class: 'form' },
      h('p', { class: 'hint', style: { margin: 0 } }, 'Copy the amounts from one recent paystub, per paycheck.'),
      h('div', { class: 'two' }, field('Employer', employer), field('How often', freq)),
      field('Deposit description contains', pattern, 'A word or two from the deposit, like the employer name. Matching deposits are filed as Paycheck.'),
      field('Gross pay', gross),
      h('div', { class: 'two' }, field('Federal tax', fed), field('State tax', state)),
      h('div', { class: 'two' }, field('Social Security + Medicare', fica), field('401(k) (pre-tax)', k401)),
      h('div', { class: 'two' }, field('Health / benefits', benefits), field('Other deductions', other)),
      field('Employer 401(k) match', match, 'Not on the check, but it is your money.'),
      net, h('div', { class: 'btn-row' }, save, remove)));
  });
}

// --- Rules ------------------------------------------------------------------------------

async function rules(root, refresh) {
  const [list, cats] = await Promise.all([get('/api/rules'), categories()]);
  const mine = list.filter((r) => !r.builtIn);
  const builtIn = list.filter((r) => r.builtIn);
  const section = (title, rows) => {
    const ul = h('div', { class: 'list' });
    for (const r of rows) {
      ul.append(h('div', { class: 'row' },
        h('div', { class: 'avatar' }, r.categoryIcon || '•'),
        h('div', { class: 'main' }, h('div', { class: 'title' }, r.pattern),
          h('div', { class: 'meta' }, `→ ${r.categoryName}${r.sign !== 'ANY' ? ' · money ' + r.sign.toLowerCase() : ''}${r.accountType ? ' · ' + r.accountType.toLowerCase().replace('_', ' ') + 's only' : ''}`)),
        h('button', { class: 'btn small danger', 'aria-label': 'Delete rule', onclick: async () => {
          const res = await del('/api/rules/' + r.id); toast(`Deleted · ${res.refiled} re-filed`); refresh();
        } }, 'Delete')));
    }
    if (!rows.length) ul.append(h('div', { class: 'empty' }, 'None yet. Recategorize a transaction and tick "always file like this".'));
    return [h('div', { class: 'section-title' }, title), h('section', { class: 'card flush' }, ul)];
  };
  root.append(h('div', null, h('button', { class: 'btn primary', onclick: () => ruleSheet(cats, refresh) }, icon('plus', 18), 'Add a rule')));
  root.append(...section('Your rules', mine));
  const details = h('details', null, h('summary', { style: { cursor: 'pointer', margin: '16px 4px', fontWeight: 600 } }, `Built-in rules (${builtIn.length})`));
  details.append(...section('Built-in', builtIn));
  root.append(details);
}

function ruleSheet(cats, refresh) {
  sheet('New rule', (body, s) => {
    const pattern = h('input', { class: 'field', placeholder: 'e.g. PLANET FITNESS' });
    const cat = h('select', { class: 'field' }, cats.map((c) => h('option', { value: c.id }, `${c.icon || ''} ${c.name}`)));
    const sign = h('select', { class: 'field' }, h('option', { value: 'ANY' }, 'Money in or out'), h('option', { value: 'OUT' }, 'Money out'), h('option', { value: 'IN' }, 'Money in'));
    const save = h('button', { class: 'btn primary' }, 'Add rule');
    save.addEventListener('click', busy(save, async () => {
      const r = await post('/api/rules', { pattern: pattern.value.trim(), categoryId: Number(cat.value), sign: sign.value });
      s.close(); toast(`${r.refiled} transactions re-filed`); refresh();
    }));
    body.append(h('div', { class: 'form' }, field('Description contains', pattern, 'Whole words, any case.'), field('File as', cat), field('Applies to', sign), save));
  });
}

// --- Rows (the detailed versions; Home shows compact ones) -------------------------

function targetRow(s) {
  const badge = s.status === 'over' ? h('span', { class: 'pill bad' }, 'Over')
    : s.status === 'watch' ? h('span', { class: 'pill warn' }, 'Watch') : null;
  const left = s.limitCents - s.spentCents;
  return h('a', { class: 'meter-row tap', style: { display: 'block' }, href: `#/activity?category=${s.categoryId}` },
    h('div', { class: 'top' }, h('span', { class: 'name' }, s.name), badge,
      h('span', { class: 'nums' }, `${money(s.spentCents)} / ${money(s.limitCents)}`)),
    meter(s.fraction, s.status, s.expectedFraction),
    h('div', { class: 'foot' }, h('span', null, left >= 0 ? `${money(left)} left` : `${money(-left)} over`),
      h('span', null, pct(s.fraction) + ' used')));
}

function goalRow(g) {
  let nums;
  let foot;
  if (g.type === 'SAVINGS_RATE') {
    nums = `${g.currentPercent === null ? '—' : g.currentPercent.toFixed(0) + '%'} / ${g.targetPercent}%`;
    foot = g.detail;
  } else {
    nums = `${money(g.currentCents)} / ${money(g.targetCents)}`;
    foot = g.neededPerMonthCents ? `${g.detail} · ${money(g.neededPerMonthCents)}/mo to hit it by ${shortDate(g.targetDate)}` : g.detail;
  }
  return h('div', { class: 'meter-row' },
    h('div', { class: 'top' }, h('span', { class: 'name' }, g.name),
      g.status === 'done' ? h('span', { class: 'pill good' }, 'Done') : g.status === 'behind' ? h('span', { class: 'pill warn' }, 'Behind') : null,
      h('span', { class: 'nums' }, nums)),
    meter(g.fraction, g.status === 'done' ? 'done' : g.status === 'behind' ? 'behind' : 'savings'),
    h('div', { class: 'foot' }, h('span', null, foot), h('span', null, pct(Math.min(g.fraction, 9.99)))));
}

function savingsTargetSheet(current, refresh) {
  sheet('Savings target', (body, s) => {
    // With amounts hidden the current target isn't shown, even here; typing a new
    // one still works, and a blank field leaves it as it was.
    const hidden = privacy.on;
    const amount = h('input', { class: 'field', inputmode: 'decimal', value: hidden ? '' : centsToInput(current),
      placeholder: hidden && current !== null ? 'Hidden. Type a new amount' : 'e.g. 1000' });
    const save = h('button', { class: 'btn primary' }, 'Save');
    save.addEventListener('click', busy(save, async () => {
      const cents = parseMoney(amount.value);
      if (!cents || cents <= 0) {
        if (hidden || current === null) { s.close(); return; }
        throw new Error('Enter an amount, or use Remove target');
      }
      await put('/api/settings', { savingsTargetCents: cents });
      s.close(); toast('Saved'); refresh();
    }));
    const remove = current === null ? null : h('button', { class: 'btn danger', onclick: async () => {
      await put('/api/settings', { savingsTargetCents: null });
      s.close(); toast('Target removed'); refresh();
    } }, 'Remove target');
    body.append(h('div', { class: 'form' },
      field('Keep this much each month', amount, 'Left to spend on Home is income minus this minus what you\'ve spent.'),
      h('div', { class: 'btn-row' }, save, remove)));
  });
}
