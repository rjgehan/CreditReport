// Connecting Claude: what's connected, and how to add it. Claude signs in the way
// it does with any connector (like the stocks app's); there's no key to paste.

import { get, del } from '../api.js';
import { h, toast, confirmSheet, copyText } from '../ui.js';
import { ago } from '../fmt.js';

export async function render(params, app) {
  const s = await get('/api/claude');
  const url = s.publicUrl || 'https://finance.<your-domain>/mcp';

  const list = h('section', { class: 'card flush' });
  if (!s.connections.length) {
    list.append(h('div', { class: 'row' }, h('div', { class: 'main' },
      h('div', { class: 'title' }, 'Not connected yet'),
      h('div', { class: 'meta' }, 'In claude.ai: Customize, Connectors, Add custom connector, then Connect and Allow.'))));
  }
  for (const c of s.connections) {
    list.append(h('div', { class: 'row' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, c.name),
        h('div', { class: 'meta' }, c.lastUsedAt ? `Last looked ${ago(c.lastUsedAt)}` : `Connected ${ago(c.connectedAt)}`)),
      h('button', { class: 'btn small danger', onclick: async () => {
        if (!(await confirmSheet(`Disconnect ${c.name}?`, 'It stops reading your data on its next question.', 'Disconnect'))) return;
        await del(`/api/claude/connections/${c.id}`);
        toast('Disconnected');
        app.rerender();
      } }, 'Disconnect')));
  }

  return h('div', { class: 'grid' },
    h('section', { class: 'card' },
      h('div', { class: 'title', style: { fontWeight: 600 } }, 'Ask Claude about your money'),
      h('p', { class: 'hint', style: { margin: '6px 0 10px' } },
        'Claude reads your transactions and what the app works out from them. It can\'t see balances, net worth or '
        + 'investments, and it can\'t change anything. Connector address:'),
      h('code', { class: 'copyable', title: 'Tap to copy',
        onclick: async () => toast((await copyText(url)) ? 'Copied address' : 'Press and hold to copy it instead') }, url)),
    list);
}
