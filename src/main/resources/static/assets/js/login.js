// Posts the login form with the CSRF header and goes home on success.

const form = document.getElementById('login');
const error = document.getElementById('error');

function xsrf() {
  const c = document.cookie.split('; ').find((p) => p.startsWith('XSRF-TOKEN='));
  return c ? decodeURIComponent(c.slice('XSRF-TOKEN='.length)) : '';
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  error.hidden = true;
  const button = form.querySelector('button');
  button.disabled = true;
  const body = new URLSearchParams();
  body.set('username', form.username.value.trim());
  body.set('password', form.password.value);
  if (form['remember-me'].checked) body.set('remember-me', 'on');
  try {
    const res = await fetch('/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': xsrf() },
      body,
      credentials: 'same-origin',
    });
    if (res.ok) {
      location.href = '/';
      return;
    }
    error.textContent = res.status === 429
      ? 'Too many attempts. Try again in 15 minutes.'
      : res.status === 403 ? 'The page expired. Reload and try again.' : 'Wrong username or password.';
    error.hidden = false;
  } catch {
    error.textContent = 'Could not reach the server.';
    error.hidden = false;
  } finally {
    button.disabled = false;
  }
});
