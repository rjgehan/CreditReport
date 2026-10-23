// Number and date formatting, and the privacy switch that masks every amount.

// Amounts start hidden every time the app opens, and hide again after a few
// minutes in the background, so handing someone the phone never shows them.
// Deliberately not remembered: showing is a choice made each visit.
const REHIDE_AFTER_MS = 2 * 60 * 1000;
let hidden = true;
let hiddenAt = null;
let base = null;
const listeners = new Set();

export const privacy = {
  get on() { return hidden; },
  /**
   * A month of take-home pay. With amounts hidden, money shows as a share of it
   * ("12%") instead of a mask, so a glance still says something.
   */
  setBase(cents) { base = cents > 0 ? cents : null; },
  set(on) {
    hidden = !!on;
    document.body.classList.toggle('private', hidden);
    listeners.forEach((fn) => fn(hidden));
  },
  toggle() { this.set(!hidden); },
  onChange(fn) { listeners.add(fn); },
};
document.body.classList.toggle('private', hidden);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') {
    hiddenAt = Date.now();
  } else if (!hidden && hiddenAt && Date.now() - hiddenAt > REHIDE_AFTER_MS) {
    privacy.set(true);
  }
});

const MASK = '$•••••';
const whole = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const exact = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const compact = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', notation: 'compact', maximumFractionDigits: 1 });

/**
 * @param cents integer cents
 * @param opts.cents show cents (transactions); otherwise whole dollars
 * @param opts.sign prefix + for positive amounts
 * @param opts.compact $12.4K style, for axes and tight spaces
 */
export function money(cents, opts = {}) {
  if (cents === null || cents === undefined) return '—';
  if (hidden) {
    const text = base ? ofPay(cents) : MASK;
    return opts.sign && cents > 0 ? '+' + text : (cents < 0 ? '−' + text : text);
  }
  const dollars = cents / 100;
  const f = opts.compact ? compact : opts.cents ? exact : whole;
  const text = f.format(Math.abs(dollars));
  if (cents < 0) return '−' + text;
  return opts.sign && cents > 0 ? '+' + text : text;
}

/** An amount as a share of a month's take-home pay: 0.4%, 12%, 150%. */
function ofPay(cents) {
  const p = Math.abs(cents) / base * 100;
  if (p === 0) return '0%';
  if (p < 0.1) return '<0.1%';
  return (p < 10 ? p.toFixed(1) : Math.round(p).toLocaleString('en-US')) + '%';
}

/**
 * A balance (an account, net worth): the amount, or with amounts hidden, how much it
 * moved since a month ago (+3.2%), which says how it's going without saying how much.
 */
export function balance(cents, monthAgoCents, opts = {}) {
  if (!hidden) return money(cents, opts);
  // A card or loan: the change in what you owe, so paying it down reads as a fall.
  if (opts.owed) return change(Math.abs(cents), monthAgoCents === null || monthAgoCents === undefined ? null : Math.abs(monthAgoCents));
  return change(cents, monthAgoCents);
}

/** +3.2% / −1.0% from then to now; — when there's nothing to compare with. */
export function change(now, then) {
  if (now === null || now === undefined || then === null || then === undefined || then === 0) return '—';
  const p = (now - then) / Math.abs(then) * 100;
  if (Math.abs(p) < 0.05) return '0.0%';
  return (p > 0 ? '+' : '−') + Math.abs(p).toFixed(1) + '%';
}

/** Shares are a quantity, not money, but still say how much someone owns. */
export function shares(n) {
  if (hidden) return '•••';
  return n.toLocaleString('en-US', { maximumFractionDigits: 4 });
}

export function pct(fraction, digits = 0) {
  if (fraction === null || fraction === undefined || Number.isNaN(fraction)) return '—';
  return (fraction * 100).toFixed(digits) + '%';
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const SHORT = MONTHS.map((m) => m.slice(0, 3));

export function monthLabel(ym, short = false) {
  const [y, m] = ym.split('-').map(Number);
  const now = new Date();
  const name = (short ? SHORT : MONTHS)[m - 1];
  return y === now.getFullYear() ? name : `${name} ${y}`;
}

export function shiftMonth(ym, delta) {
  const [y, m] = ym.split('-').map(Number);
  const d = new Date(y, m - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

export function thisMonth() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

export function dayLabel(iso) {
  const d = parseDay(iso);
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const diff = Math.round((today - d) / 86400000);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  const opts = { weekday: 'short', month: 'short', day: 'numeric' };
  if (d.getFullYear() !== today.getFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString('en-US', opts);
}

export function shortDate(iso) {
  const d = parseDay(iso);
  const opts = { month: 'short', day: 'numeric' };
  if (d.getFullYear() !== new Date().getFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString('en-US', opts);
}

export function parseDay(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export function ago(instant) {
  if (!instant) return 'never';
  const s = Math.round((Date.now() - new Date(instant).getTime()) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}

/** "$1,234.56" or "-12" typed by a person, to cents. */
export function parseMoney(text) {
  if (text === null || text === undefined) return null;
  const t = String(text).replace(/[$,\s]/g, '');
  if (t === '') return null;
  const n = Number(t);
  if (!Number.isFinite(n)) return null;
  return Math.round(n * 100);
}

export function centsToInput(cents) {
  return cents === null || cents === undefined ? '' : (cents / 100).toFixed(2);
}

export const KIND_COLOR = {
  income: 'var(--income)', INCOME: 'var(--income)',
  expense: 'var(--expense)', EXPENSE: 'var(--expense)',
  savings: 'var(--savings)', SAVINGS: 'var(--savings)',
  tax: 'var(--tax)', TRANSFER: 'var(--tax)',
};

export const TYPE_LABEL = {
  CHECKING: 'Checking', SAVINGS: 'Savings', CREDIT_CARD: 'Credit card', BROKERAGE: 'Brokerage',
  RETIREMENT: 'Retirement', LOAN: 'Loan', OTHER: 'Other',
};

export const PAY_FREQUENCY = {
  WEEKLY: 'weekly', BIWEEKLY: 'every other week', SEMIMONTHLY: 'twice a month', MONTHLY: 'monthly',
};

/** What a regular deposit looks like in one line: "$2,150 twice a month into SoFi Checking". */
export function depositLine(p) {
  return `${money(p.averageCents)} ${PAY_FREQUENCY[p.frequency] || ''} into ${p.accountName}${p.accountHidden ? ' (hidden)' : ''}`;
}

/** The toast after filing deposits as Paycheck: { matched, refiled } from the server. */
export function filedLine(filed, pattern) {
  if (!filed.matched) return pattern ? `No regular deposit contains "${pattern}"` : 'No deposits found to file';
  const n = filed.refiled;
  return n ? `${n} deposit${n === 1 ? '' : 's'} filed as Paycheck` : 'Already filed as Paycheck';
}
