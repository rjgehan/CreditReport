// Connecting Claude to the app: the address to give it, and the apps you've let in.
// Claude signs in the way it does with any connector; there's no key to paste.

import { get, del } from '../api.js';
import { h, toast, confirmSheet, copyText } from '../ui.js';
import { ago } from '../fmt.js';

export async function render(params, app) {
  const s = await get('/api/claude');
  const copy = async (text, label) => toast((await copyText(text)) ? `Copied ${label}` : 'Press and hold to copy it instead');
  const code = (text, label) => h('code', { class: 'copyable', title: 'Tap to copy', onclick: () => copy(text, label) }, text);
  const step = (n, title, ...body) => h('li', { class: 'step' }, h('div', { class: 'step-n' }, String(n)),
    h('div', { class: 'step-body' }, h('div', { class: 'title' }, title), ...body));

  const connected = h('section', { class: 'card flush' });
  if (!s.connections.length) {
    connected.append(h('div', { class: 'row' }, h('div', { class: 'main' },
      h('div', { class: 'title' }, 'Not connected yet'),
      h('div', { class: 'meta' }, 'Follow the steps below once. After that Claude can answer from your data in any chat.'))));
  }
  for (const c of s.connections) {
    connected.append(h('div', { class: 'row' },
      h('div', { class: 'main' }, h('div', { class: 'title' }, c.name),
        h('div', { class: 'meta' }, c.lastUsedAt ? `Last looked ${ago(c.lastUsedAt)}` : `Connected ${ago(c.connectedAt)}, not used yet`)),
      h('button', { class: 'btn small danger', onclick: async () => {
        if (!(await confirmSheet(`Disconnect ${c.name}?`, 'It stops reading your data on its next question. You can connect again any time.', 'Disconnect'))) return;
        await del(`/api/claude/connections/${c.id}`);
        toast('Disconnected');
        app.rerender();
      } }, 'Disconnect')));
  }

  const url = s.publicUrl || s.mcpUrl;
  return h('div', { class: 'grid' },
    h('section', { class: 'card' },
      h('div', { class: 'title', style: { fontWeight: 600 } }, 'Ask Claude about your money'),
      h('p', { class: 'hint', style: { margin: '6px 0 0' } },
        'Claude reads your transactions, monthly totals, this month\'s outlook, targets, goals, monthly expenses, '
        + 'rent and loans, and paychecks. It can\'t see account balances, net worth or investments, and it can\'t '
        + 'change anything. It runs on your Claude subscription.')),
    connected,
    h('div', { class: 'section-title' }, 'In the Claude app'),
    h('section', { class: 'card' },
      h('ol', { class: 'steps' },
        step(1, 'Add a connector', h('div', { class: 'hint' },
          'On claude.ai (or in the Claude app): Customize, Connectors, Add custom connector.')),
        step(2, 'Name it Finance, with this address', code(url, 'address'),
          h('div', { class: 'hint' }, 'If it asks how Claude should register, pick Register automatically. Leave request headers empty.')),
        step(3, 'Connect', h('div', { class: 'hint' },
          'Click Connect. This app opens on your phone or computer: tap Allow. You need to be on Tailscale for this step.')),
        step(4, 'Ask', h('div', { class: 'hint' },
          'In a chat, turn on Finance under + then Connectors, and ask something like "What did I spend on food last month?" '
          + 'It works in the iPhone app and in Claude Code too.'))),
      s.publicUrl ? null : h('p', { class: 'hint', style: { margin: '12px 0 0' } },
        'Claude connects from Anthropic\'s servers, so this address has to be reachable from the internet. '
        + 'Set that up once on the server (DEPLOY.md, section 5), or use Claude Code below.')),
    h('div', { class: 'section-title' }, 'Claude Code only, over Tailscale'),
    h('section', { class: 'card' },
      h('p', { class: 'hint', style: { marginTop: 0 } },
        'Nothing has to be public for this. On a computer on your tailnet, run this once, then /mcp in Claude Code to sign in:'),
      code(`claude mcp add --transport http --scope user finance ${s.mcpUrl}`, 'command')));
}
