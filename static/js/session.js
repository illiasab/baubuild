// Session: tokens + the signed-in user, persisted in localStorage under "bau.session".
const KEY = 'bau.session';
const listeners = new Set();

function read() {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function write(value) {
  try {
    if (value) localStorage.setItem(KEY, JSON.stringify(value));
    else localStorage.removeItem(KEY);
  } catch { /* storage blocked: session lives in memory only */ }
}

let state = read();

function emit(reason) {
  for (const fn of listeners) {
    try { fn(state, reason); } catch (err) { console.error(err); }
  }
}

export const session = {
  get user() { return state?.user || null; },
  get accessToken() { return state?.access_token || null; },
  get refreshToken() { return state?.refresh_token || null; },
  get expiresAt() { return state?.expires_at || 0; },
  get signedIn() { return !!state?.access_token; },

  // tokens: a token response {access_token, refresh_token, expires_in, user?}; user overrides tokens.user.
  set(tokens, user) {
    const next = { ...(state || {}) };
    if (tokens) {
      if (tokens.access_token) next.access_token = tokens.access_token;
      if (tokens.refresh_token) next.refresh_token = tokens.refresh_token;
      if (tokens.expires_in) next.expires_at = Date.now() + tokens.expires_in * 1000;
      if (tokens.user) next.user = tokens.user;
    }
    if (user) next.user = { ...(next.user || {}), ...user };
    state = next;
    write(state);
    emit('set');
  },

  // reason: 'manual' | 'expired' | 'domain_not_allowed' | ...
  signOut(reason = 'manual') {
    state = null;
    write(null);
    emit(reason);
  },

  onChange(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};

// Another tab signed in / out.
window.addEventListener('storage', (event) => {
  if (event.key !== KEY) return;
  state = read();
  emit(state ? 'set' : 'expired');
});

export function emailDomain(email) {
  return String(email || '').trim().toLowerCase().split('@')[1] || '';
}

export function isStaff(user) {
  if (!user) return false;
  const roles = [user.role, ...(Array.isArray(user.roles) ? user.roles : [])];
  if (roles.includes('staff') || roles.includes('admin')) return true;
  return emailDomain(user.email) === 'bau.edu';
}

export function isStudent(user) {
  return !!user && !isStaff(user);
}

// "Student account" / "Staff account" / null for an email address (client-side domain gate).
export function accountKind(email) {
  const domain = emailDomain(email);
  if (domain === 'stu.bau.edu') return 'student';
  if (domain === 'bau.edu') return 'staff';
  return null;
}
