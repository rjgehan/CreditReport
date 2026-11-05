// Ask Claude about your money. It reads transactions and what the app works out
// from them through read-only lookups on the server; it never sees a balance.

import { get, post, put, del } from '../api.js';
import { h, icon, toast, busy, sheet, confirmSheet, segmented } from '../ui.js';

const STORE = 'ask-conversation';

const SUGGESTIONS = [
  'Am I on track this month?',
  'What did I spend the most on last month?',
  'Which subscriptions am I paying for?',
  'How much have I paid in rent this year?',
];

// Kept between renders: the shell re-renders on rotation or when amounts re-hide,
// and an answer that's still on its way has to land in whatever is on screen then.
const state = { id: null, turns: null, waiting: null, error: null, draft: '', root: null };

function saved() {
  try { return localStorage.getItem(STORE); } catch { return null; }
}
function remember(id) {
  try { if (id) localStorage.setItem(STORE, id); else localStorage.removeItem(STORE); } catch { /* private mode */ }
}

export async function render(params, app) {
  const status = await get('/api/assistant');
  if (!status.configured) return setup(status, app);
  if (state.turns === null) {
    state.id = saved();
    state.turns = [];
    if (state.id) {
      try {
        state.turns = (await get(`/api/assistant/chats/${state.id}`)).turns;
      } catch {
        // Gone: the app restarted or the chat is old. Start a fresh one.
        state.id = null;
        remember(null);
      }
    }
  }
  return chat(status, app);
}

// --- Chat ---------------------------------------------------------------------------

function chat(status, app) {
  const list = h('div', { class: 'ask-list', 'aria-live': 'polite' });
  const input = h('textarea', { class: 'field ask-input', rows: 1, placeholder: 'Ask about your spending',
    enterkeyhint: 'send', maxlength: 2000, 'aria-label': 'Question' });
  input.value = state.draft;
  const send = h('button', { class: 'icon-btn ask-send', 'aria-label': 'Send', type: 'submit' }, icon('send'));
  const grow = () => { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight, 140) + 'px'; };
  input.addEventListener('input', () => { state.draft = input.value; grow(); });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); ask(input.value); }
  });
  const form = h('form', { class: 'ask-composer', onsubmit: (e) => { e.preventDefault(); ask(input.value); } }, input, send);

  const footer = h('div', { class: 'ask-foot hint' });
  const root = h('div', { class: 'ask' }, list, form, footer);
  state.root = { list, input, send, footer, status, app };

  paint();
  requestAnimationFrame(() => { grow(); if (state.turns.length) scrollDown(); });
  return root;
}

async function ask(text) {
  const question = text.trim();
  if (!question || state.waiting) return;
  state.waiting = question;
  state.error = null;
  state.draft = '';
  if (state.root) state.root.input.value = '';
  paint();
  scrollDown();
  try {
    const r = await post('/api/assistant/ask', { conversationId: state.id, question });
    // The server forgot the old chat (it restarted): Claude answered without it, so don't show it as context.
    if (state.id && r.conversationId !== state.id) state.turns = [];
    state.id = r.conversationId;
    remember(r.conversationId);
    state.turns.push({ role: 'user', text: question, looked: [] },
      { role: 'assistant', text: r.text, looked: r.looked || [] });
    if (state.root) Object.assign(state.root.status, await get('/api/assistant').catch(() => ({})));
  } catch (e) {
    state.error = e.message;
    // Put the question back so it can be sent again as is, or reworded.
    if (!state.draft) state.draft = question;
  } finally {
    state.waiting = null;
    paint();
    scrollDown();
    if (state.root && state.draft) { state.root.input.value = state.draft; state.root.input.dispatchEvent(new Event('input')); }
  }
}

function paint() {
  if (!state.root) return;
  const { list, send, footer, status, app } = state.root;
  list.replaceChildren();
  if (!state.turns.length && !state.waiting) {
    list.append(h('div', { class: 'ask-empty' },
      h('div', { class: 'title' }, 'Ask about your money'),
      h('div', { class: 'hint' }, 'Claude looks through your transactions, monthly totals, bills, targets and goals. It can\'t see balances or change anything.'),
      h('div', { class: 'ask-suggest' }, SUGGESTIONS.map((s) => h('button', { class: 'chip', type: 'button', onclick: () => ask(s) }, s)))));
  }
  for (const t of state.turns) list.append(bubble(t));
  if (state.waiting) {
    list.append(bubble({ role: 'user', text: state.waiting }),
      h('div', { class: 'ask-msg assistant waiting', role: 'status' },
        h('span', { class: 'dots', 'aria-label': 'Looking it up' }, h('i'), h('i'), h('i'))));
  }
  if (state.error) list.append(h('div', { class: 'ask-msg assistant error' }, state.error));
  send.disabled = !!state.waiting;

  const model = status.models.find((m) => m.id === status.model);
  footer.replaceChildren(...[
    state.turns.length ? h('button', { class: 'link', type: 'button', onclick: () => newChat() }, 'New chat') : null,
    h('span', null, `${model ? model.name : status.model} · ${usage(status)}`),
    h('button', { class: 'link', type: 'button', onclick: () => settings(status, app) }, 'Settings'),
  ].filter(Boolean));
}

function bubble(t) {
  return h('div', { class: 'ask-msg ' + t.role },
    h('div', { class: 'ask-text' }, t.text),
    t.role === 'assistant' && t.looked?.length ? h('div', { class: 'ask-looked' }, 'Looked at ' + t.looked.join(', ')) : null);
}

function newChat() {
  if (state.waiting) return;
  if (state.id) del(`/api/assistant/chats/${state.id}`).catch(() => {});
  state.id = null;
  state.turns = [];
  state.error = null;
  remember(null);
  paint();
  state.root?.input.focus();
}

function scrollDown() {
  requestAnimationFrame(() => window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' }));
}

function usage(status) {
  const n = status.questions;
  const cost = status.costCents < 100 ? `${status.costCents}¢` : `$${(status.costCents / 100).toFixed(2)}`;
  return n ? `${n} question${n === 1 ? '' : 's'} this month, about ${cost}` : 'No questions yet this month';
}

// --- Setup --------------------------------------------------------------------------

const WHAT = 'Claude answers from your transactions, monthly totals, this month\'s outlook, targets, goals, '
  + 'monthly expenses, rent and loans, and paychecks. It can\'t see account balances, net worth or investments, '
  + 'and it can\'t change anything.';
const PRIVACY = 'Each question, and what Claude looks up to answer it, is sent to Anthropic. '
  + 'Anthropic doesn\'t train its models on API data.';

function setup(status, app) {
  let model = status.model;
  const key = h('input', { class: 'field', type: 'password', placeholder: 'sk-ant-…', autocomplete: 'off',
    autocapitalize: 'off', spellcheck: 'false', 'aria-label': 'Anthropic API key' });
  const models = h('div');
  const drawModels = () => models.replaceChildren(modelPicker(status.models, model, (m) => { model = m; drawModels(); }));
  drawModels();
  const save = h('button', { class: 'btn primary' }, 'Turn on');
  save.addEventListener('click', busy(save, async () => {
    if (!key.value.trim()) { key.focus(); return; }
    if (model !== status.model) await put('/api/assistant/model', { model });
    await put('/api/assistant/key', { key: key.value.trim() });
    key.value = '';
    toast('Claude is ready');
    app.rerender();
  }));

  return h('div', { class: 'grid' },
    h('section', { class: 'card' },
      h('div', { class: 'title', style: { fontWeight: 600 } }, 'Ask Claude about your money'),
      h('p', { class: 'hint', style: { margin: '6px 0 0' } }, WHAT)),
    h('section', { class: 'card form' },
      h('label', { class: 'stack' }, 'Anthropic API key', key,
        h('span', { class: 'hint' }, 'From console.anthropic.com, under API keys. Billed by Anthropic per question, '
          + 'separately from a Claude subscription. Kept encrypted on your server.')),
      h('div', { class: 'stack' }, h('span', { class: 'label-text' }, 'Model'), models),
      h('div', { class: 'btn-row' }, save)),
    h('p', { class: 'hint', style: { textAlign: 'center', margin: '0 12px' } }, PRIVACY));
}

function modelPicker(options, value, onChange) {
  const chosen = options.find((m) => m.id === value);
  return h('div', { class: 'stack' },
    segmented(options.map((m) => [m.id, m.name]), value, onChange),
    h('span', { class: 'hint' }, chosen ? chosen.note : ''));
}

function settings(status, app) {
  sheet('Claude', (body, s) => {
    let model = status.model;
    const picker = h('div');
    const draw = () => picker.replaceChildren(modelPicker(status.models, model, async (m) => {
      try {
        Object.assign(status, await put('/api/assistant/model', { model: m }));
        model = m;
        draw();
        paint();
      } catch (e) { toast(e.message); }
    }));
    draw();

    const key = h('input', { class: 'field', type: 'password', placeholder: 'New key, sk-ant-…', autocomplete: 'off',
      autocapitalize: 'off', spellcheck: 'false', 'aria-label': 'New Anthropic API key' });
    const replace = h('button', { class: 'btn' }, 'Replace key');
    replace.addEventListener('click', busy(replace, async () => {
      if (!key.value.trim()) { key.focus(); return; }
      await put('/api/assistant/key', { key: key.value.trim() });
      key.value = '';
      toast('Key replaced');
    }));
    const remove = h('button', { class: 'btn danger' }, 'Turn off');
    remove.addEventListener('click', busy(remove, async () => {
      if (!(await confirmSheet('Turn off Claude?', 'The API key is deleted from the server. You can add one again any time.', 'Turn off'))) return;
      await del('/api/assistant/key');
      s.close();
      app.rerender();
    }));

    body.append(h('div', { class: 'form' },
      h('div', { class: 'stack' }, h('span', { class: 'label-text' }, 'Model'), picker),
      h('p', { class: 'hint', style: { margin: 0 } }, usage(status) + '. Anthropic\'s console has the exact bill.'),
      status.keySource === 'environment'
        ? h('p', { class: 'hint', style: { margin: 0 } }, 'The key comes from ANTHROPIC_API_KEY on the server.')
        : h('label', { class: 'stack' }, 'API key', key, h('div', { class: 'btn-row' }, replace, remove)),
      h('p', { class: 'hint', style: { margin: 0 } }, WHAT + ' ' + PRIVACY)));
  });
}
