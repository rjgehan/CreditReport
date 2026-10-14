// fetch() with the CSRF header Spring Security expects and a redirect to the login page on 401.

function xsrf() {
  const c = document.cookie.split('; ').find((p) => p.startsWith('XSRF-TOKEN='));
  return c ? decodeURIComponent(c.slice('XSRF-TOKEN='.length)) : '';
}

export async function api(path, { method = 'GET', body, form } = {}) {
  const headers = { Accept: 'application/json' };
  let payload;
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = xsrf();
  if (form) {
    payload = form;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetch(path, { method, headers, body: payload, credentials: 'same-origin' });
  if (res.status === 401) {
    location.href = '/login.html';
    throw new Error('Signed out');
  }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  if (!res.ok) {
    throw new Error((data && data.error) || `Request failed (${res.status})`);
  }
  return data;
}

export const get = (path) => api(path);
export const post = (path, body) => api(path, { method: 'POST', body: body ?? {} });
export const put = (path, body) => api(path, { method: 'PUT', body: body ?? {} });
export const patch = (path, body) => api(path, { method: 'PATCH', body: body ?? {} });
export const del = (path) => api(path, { method: 'DELETE' });
export const upload = (path, form) => api(path, { method: 'POST', form });

export function loadScript(src) {
  return new Promise((resolve, reject) => {
    if (document.querySelector(`script[src="${src}"]`)) return resolve();
    const s = document.createElement('script');
    s.src = src;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error('Could not load ' + src));
    document.head.append(s);
  });
}
