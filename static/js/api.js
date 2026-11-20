// Same-origin API client for /v1/*. Bearer auth, single-flight refresh, typed errors, SSE over POST.
import { session } from './session.js';

export const DEVICE = Object.freeze({ platform: 'web', app_version: '1.0.0' });

export class ApiError extends Error {
  constructor({ status = 0, code = 'unknown', message = 'Something went wrong.', retryAfterS = null, details = null } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.message = message;
    this.retryAfterS = retryAfterS;
    this.details = details;
  }
  get offline() { return this.code === 'network'; }
}

let refreshing = null;

// One refresh at a time; concurrent 401s wait for the same promise. Resolves true when tokens were rotated.
function refreshTokens() {
  if (refreshing) return refreshing;
  const token = session.refreshToken;
  if (!token) return Promise.resolve(false);
  refreshing = (async () => {
    try {
      const res = await fetch('/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ refresh_token: token }),
      });
      if (!res.ok) {
        const err = await toError(res);
        // Network-ish server errors: keep the session, let the caller fail softly.
        if (res.status >= 500) throw err;
        session.signOut(err.code === 'domain_not_allowed' ? 'domain_not_allowed' : 'expired');
        return false;
      }
      session.set(await res.json());
      return true;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

async function toError(res) {
  let payload = null;
  try { payload = await res.json(); } catch { /* not JSON */ }
  const e = (payload && typeof payload.error === 'object' && payload.error) || {};
  const header = Number(res.headers.get('Retry-After'));
  const retry = e.retry_after_s ?? payload?.retry_after_s ?? (Number.isFinite(header) && header > 0 ? header : null);
  return new ApiError({
    status: res.status,
    code: e.code || (typeof payload?.error === 'string' ? 'error' : `http_${res.status}`),
    message: e.message || (typeof payload?.error === 'string' ? payload.error : defaultMessage(res.status)),
    retryAfterS: retry,
    details: e,
  });
}

function defaultMessage(status) {
  if (status === 404) return 'Not found.';
  if (status === 401) return 'Sign in again.';
  if (status === 403) return 'You don’t have access to this.';
  if (status === 429) return 'Too many tries. Wait a moment and try again.';
  if (status >= 500) return 'The server had a problem. Try again in a moment.';
  return 'Something went wrong.';
}

function networkError(err) {
  if (err?.name === 'AbortError') return err;
  return new ApiError({ status: 0, code: 'network', message: 'Can’t reach BAU BUILD. Check your connection and try again.' });
}

async function send(path, { method, body, auth, signal, headers = {}, accept = 'application/json' }) {
  const h = { Accept: accept, ...headers };
  let payload;
  if (body instanceof FormData || body instanceof Blob) payload = body;
  else if (body !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  const token = auth ? session.accessToken : null;
  if (token) h.Authorization = `Bearer ${token}`;
  try {
    return { res: await fetch(path, { method, headers: h, body: payload, signal }), token };
  } catch (err) {
    throw networkError(err);
  }
}

// Fetch with auth + one refresh-and-retry on 401. Returns the Response (ok) or throws ApiError.
async function request(path, opts) {
  const o = { method: 'GET', auth: true, ...opts };
  o.method = o.method.toUpperCase();
  const isAuthRoute = path.startsWith('/v1/auth/');
  // Proactive refresh shortly before expiry.
  if (o.auth && !isAuthRoute && session.refreshToken && session.expiresAt && Date.now() > session.expiresAt - 30_000) {
    try { await refreshTokens(); } catch { /* fall through; the request decides */ }
  }
  let { res, token } = await send(path, o);
  if (res.status === 401 && o.auth && !isAuthRoute && token && !session.refreshToken) {
    const err = await toError(res);
    if (session.accessToken === token) session.signOut('expired');
    throw err;
  }
  if (res.status === 401 && o.auth && !isAuthRoute && session.refreshToken) {
    // Another tab / request may already have rotated the tokens.
    const rotated = session.accessToken && session.accessToken !== token ? true : await refreshTokens().catch(() => false);
    if (!rotated) throw await toError(res);
    ({ res } = await send(path, o));
    if (res.status === 401) {
      const err = await toError(res);
      session.signOut('expired');
      throw err;
    }
  }
  if (!res.ok) throw await toError(res);
  return res;
}

export async function api(path, { method = 'GET', body, auth = true, signal, headers } = {}) {
  const res = await request(path, { method, body, auth, signal, headers });
  if (res.status === 204) return null;
  const text = await res.text();
  if (!text) return null;
  try { return JSON.parse(text); } catch { return text; }
}

// POST (or method) an endpoint that answers text/event-stream; calls onEvent({event, data}) per event.
// data is JSON-parsed when possible. Resolves when the stream ends.
export async function sse(path, body, { onEvent = () => {}, signal, method = 'POST' } = {}) {
  const res = await request(path, { method, body, signal, accept: 'text/event-stream' });
  const type = res.headers.get('Content-Type') || '';
  if (!type.includes('text/event-stream')) {
    // Server answered plain JSON: surface it as a single "message" event.
    const text = await res.text();
    let data = text;
    try { data = JSON.parse(text); } catch { /* keep text */ }
    onEvent({ event: 'message', data });
    return;
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let dataLines = [];
  const dispatch = () => {
    if (!dataLines.length) { event = 'message'; return; }
    const raw = dataLines.join('\n');
    let data = raw;
    try { data = JSON.parse(raw); } catch { /* keep raw */ }
    onEvent({ event, data });
    event = 'message';
    dataLines = [];
  };
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf('\n')) >= 0) {
        let line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        if (line === '') dispatch();
        else if (line.startsWith(':')) continue;
        else if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
      }
    }
    if (buffer.trim().startsWith('data:')) dataLines.push(buffer.trim().slice(5).trim());
    dispatch();
  } catch (err) {
    throw networkError(err);
  }
}
