// Setting up Claude's notes: Claude Code on the server, signed in with a token from
// your Claude subscription (no API key), the way the stocks app's trader runs.

import { get, put, del } from '../api.js';
import { h, toast, busy, confirmSheet, copyText } from '../ui.js';
import { ago } from '../fmt.js';

export async function render(params, app) {
  const [s, mcp] = await Promise.all([get('/api/insights'), get('/api/claude')]);
  const copy = async (text) => toast((await copyText(text)) ? 'Copied' : 'Press and hold to copy it instead');

  const intro = h('section', { class: 'card' },
    h('div', { class: 'title', style: { fontWeight: 600 } }, 'Claude, on your Claude subscription'),
    h('p', { class: 'hint', style: { margin: '6px 0 0' } },
      'Each morning Claude writes a short take on Home, files transactions your rules missed, flags odd charges and '
      + 'suggests rules and targets for you to approve. It sees transactions and what the app works out from them, '
      + 'never balances. No API key: it uses your Claude plan.'));

  let setup;
  if (s.configured) {
    const remove = h('button', { class: 'btn small danger' }, 'Turn off');
    remove.addEventListener('click', busy(remove, async () => {
      if (!(await confirmSheet('Turn off Claude?', 'The token is deleted from the server. Notes already written stay.', 'Turn off'))) return;
      await del('/api/insights/token');
      app.rerender();
    }));
    setup = h('section', { class: 'card flush' }, h('div', { class: 'row' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, 'Signed in'),
        h('div', { class: 'meta' }, s.lastRunAt ? `Last ran ${ago(s.lastRunAt)}` : 'Runs every morning at 7')),
      remove));
  } else {
    const token = h('input', { class: 'field', type: 'password', placeholder: 'sk-ant-oat…', autocomplete: 'off',
      autocapitalize: 'off', spellcheck: 'false', 'aria-label': 'Claude Code token' });
    const save = h('button', { class: 'btn primary' }, 'Connect');
    save.addEventListener('click', busy(save, async () => {
      if (!token.value.trim()) return token.focus();
      save.textContent = 'Checking…';
      try {
        await put('/api/insights/token', { token: token.value.trim() });
        token.value = '';
        toast('Claude is connected');
        app.rerender();
      } finally {
        save.textContent = 'Connect';
      }
    }));
    setup = h('section', { class: 'card form' },
      h('div', { class: 'stack' }, h('span', { class: 'label-text' }, '1. On your computer, run'),
        h('code', { class: 'copyable', title: 'Tap to copy', onclick: () => copy('claude setup-token') }, 'claude setup-token'),
        h('span', { class: 'hint' }, 'It signs in to your Claude account and prints a long token.')),
      h('label', { class: 'stack' }, '2. Paste the token', token),
      h('div', { class: 'btn-row' }, save));
  }

  // Apps that read the app themselves over MCP (claude.ai connectors), if any were ever connected.
  const apps = mcp.connections.length ? h('section', { class: 'card flush' },
    mcp.connections.map((c) => h('div', { class: 'row' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, c.name),
        h('div', { class: 'meta' }, c.lastUsedAt ? `Connector, last looked ${ago(c.lastUsedAt)}` : 'Connector')),
      h('button', { class: 'btn small danger', onclick: async () => {
        await del(`/api/claude/connections/${c.id}`);
        app.rerender();
      } }, 'Disconnect')))) : null;

  return h('div', { class: 'grid' }, intro, setup, apps);
}
