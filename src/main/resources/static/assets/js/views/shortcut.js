// Setting up the iPhone shortcut that files leftover transactions with Apple
// Intelligence: make a token, then a few actions in the Shortcuts app.

import { get, post } from '../api.js';
import { h, toast, busy, confirmSheet, copyText } from '../ui.js';

// The token is shown once; kept here so a re-render mid-setup (amounts re-hiding,
// rotating the phone) doesn't lose it before it's been pasted.
let lastToken = null;

export async function render(params, app) {
  const status = await get('/api/shortcut/status');
  const origin = location.origin;
  const tokenBox = h('div');
  const copy = async (text, label) => toast((await copyText(text)) ? `Copied ${label}` : 'Press and hold to copy it instead');

  const showToken = (token) => tokenBox.replaceChildren(h('div', { class: 'share-new' },
    h('div', { class: 'hint' }, 'Shown only here. Step 2 puts it in the shortcut once:'),
    h('div', { class: 'share-url' }, 'Bearer ' + token),
    h('button', { class: 'btn small', onclick: () => copy('Bearer ' + token, '"Bearer …"') }, 'Copy')));
  if (lastToken) showToken(lastToken);

  const make = h('button', { class: status.configured ? 'btn' : 'btn primary' }, status.configured ? 'Make a new token' : 'Make a token');
  make.addEventListener('click', busy(make, async () => {
    if (status.configured && !(await confirmSheet('Make a new token?',
      'The shortcut on your phone stops working until you paste the new one into it.', 'Make new token', false))) return;
    lastToken = (await post('/api/shortcut/token')).token;
    showToken(lastToken);
  }));

  const step = (n, title, ...body) => h('li', { class: 'step' }, h('div', { class: 'step-n' }, String(n)),
    h('div', { class: 'step-body' }, h('div', { class: 'title' }, title), ...body));
  const code = (text) => h('code', { class: 'copyable', title: 'Tap to copy', onclick: () => copy(text, 'address') }, text);

  return h('div', { class: 'grid' },
    h('section', { class: 'card' },
      h('div', { class: 'title', style: { fontWeight: 600 } },
        status.waiting ? `${status.waiting} transaction${status.waiting === 1 ? '' : 's'} waiting for a category` : 'Nothing waiting right now'),
      h('div', { class: 'hint', style: { margin: '4px 0 12px' } },
        'Rules and your bank file most things. This shortcut sends the rest to Apple Intelligence on your phone and files its answers. Your rules and your own picks always win.'),
      make, tokenBox),
    h('section', { class: 'card' },
      h('ol', { class: 'steps' },
        step(1, 'New shortcut', h('div', { class: 'hint' }, 'Shortcuts app, +, name it "File transactions".')),
        step(2, 'Text', h('div', { class: 'hint' }, 'Paste the Bearer line from above. This holds the token for the next steps.')),
        step(3, 'Get Contents of URL',
          code(origin + '/api/shortcut/prompt'),
          h('div', { class: 'hint' }, 'Tap the arrow, Headers, add one: key Authorization, value the Text from step 2.')),
        step(4, 'Use Model',
          h('div', { class: 'hint' }, 'Model: On-Device (or Private Cloud Compute). Request: Contents of URL from step 3. Follow Up off.')),
        step(5, 'Get Contents of URL',
          code(origin + '/api/shortcut/answer'),
          h('div', { class: 'hint' }, 'Method POST. Header Authorization = the Text from step 2. Request Body: File, set to the Response from step 4.')),
        step(6, 'Show Notification', h('div', { class: 'hint' }, 'With Contents of URL from step 5. It says how many were filed.')),
        step(7, 'Run it once by hand', h('div', { class: 'hint' },
          'Tap the shortcut and choose Always Allow on each prompt, so it can run unattended later.')),
        step(8, 'Run it daily', h('div', { class: 'hint' },
          'Automation tab, +, Time of Day (say 9 PM), Daily, Run Immediately, pick the shortcut. Tailscale needs to be on for it to reach the app.')))),
    h('p', { class: 'hint', style: { textAlign: 'center' } },
      'Needs an iPhone with Apple Intelligence (15 Pro or newer) on iOS 26. Each run files up to 40 transactions.'));
}
