// Auth screens: Log In, Sign Up, Forgot Password (ask support), Not Allowed.
// Email + password, BAU domains only (@stu.bau.edu students, @bau.edu staff). No email is sent:
// registration signs in directly.
import { api, DEVICE } from './api.js';
import { session, accountKind } from './session.js';
import { navigate } from './router.js';
import { h, svg, icons, busy } from './ui.js';

const PASSWORD_RULE = 'At least 8 characters, with a letter and a number.';
const DOMAIN_RULE = 'Only @stu.bau.edu and @bau.edu emails can sign up.';
const SUPPORT_EMAIL = 'support@baubuild.vesperconnect.org';

export function normalizeEmail(email) {
  return String(email || '').trim().toLowerCase();
}

export function passwordOk(pw) {
  return pw.length >= 8 && /[A-Za-z]/.test(pw) && /\d/.test(pw);
}

function waitText(seconds) {
  if (!seconds) return 'Try again in a moment.';
  if (seconds < 60) return `Try again in ${seconds}s.`;
  return `Try again in ${Math.ceil(seconds / 60)} min.`;
}

// Map an ApiError to a sentence for the given flow ('login' | 'signup').
export function authMessage(err, flow) {
  if (!err) return 'Something went wrong. Try again.';
  if (err.code === 'network') return 'Can’t reach BAU BUILD. Check your connection and try again.';
  // Until the backend ships these routes it answers 404 (or 401 `unauthorized`, since unknown routes need a token).
  if (err.status === 404 || err.status === 405 || err.status === 501 || err.code === 'unauthorized') {
    return {
      signup: 'Sign-up isn’t available yet. Try again soon.',
      login: 'Log in isn’t available yet. Try again soon.',
    }[flow] || 'This isn’t available yet. Try again soon.';
  }
  switch (err.code) {
    case 'invalid_credentials': return 'That email and password don’t match. Check them and try again.';
    case 'domain_not_allowed': return DOMAIN_RULE;
    case 'email_taken': return 'An account with this email already exists. Log in instead.';
    case 'weak_password': return `Choose a stronger password. ${PASSWORD_RULE}`;
    case 'account_reserved': return 'This account is reserved. Ask a BAU BUILD admin to activate it.';
    case 'too_many_attempts':
    case 'too_many_requests': return `Too many tries. ${waitText(err.retryAfterS)}`;
    case 'invalid_body': return err.message || 'Check the form and try again.';
    default:
      if (err.status >= 500) return 'The server had a problem. Try again in a moment.';
      return err.message || 'Something went wrong. Try again.';
  }
}

// ---------- Layout ----------
function layout(title, subtitle, ...form) {
  return h('div', { class: 'auth' },
    h('aside', { class: 'auth-brand' },
      h('a', { class: 'auth-logo', href: '/' }, svg(icons.logo), h('span', {}, 'BAU BUILD')),
      h('div', { class: 'auth-pitch' },
        h('h2', {}, 'Build something real.'),
        h('p', {}, 'Real projects from BAU staff. Ship them with a team, get reviewed by seniors, and grow your score.'),
        h('ul', { class: 'auth-points' },
          h('li', {}, 'Pick a project — no experience needed'),
          h('li', {}, 'Work in a team with a lead and a mentor'),
          h('li', {}, 'Get reviewed, earn score, get paid'))),
      h('p', { class: 'auth-foot' }, 'For Bahçeşehir University students and staff.')),
    h('main', { class: 'auth-main' },
      h('div', { class: 'auth-card' },
        h('a', { class: 'auth-logo auth-logo-mobile', href: '/' }, svg(icons.logo), h('span', {}, 'BAU BUILD')),
        h('h1', {}, title),
        subtitle ? h('p', { class: 'auth-sub' }, subtitle) : null,
        ...form)));
}

function field({ label, type = 'text', name, autocomplete, value = '', placeholder, hint, inputmode, autofocus }) {
  const input = h('input', { class: 'input', id: `f-${name}`, name, type, autocomplete, placeholder, inputmode, value, autofocus, spellcheck: 'false', autocapitalize: type === 'email' ? 'none' : undefined });
  const hintEl = h('p', { class: 'field-hint', id: `f-${name}-hint`, 'aria-live': 'polite' }, hint || '');
  input.setAttribute('aria-describedby', hintEl.id);
  const wrap = h('div', { class: 'field' }, h('label', { for: input.id }, label), input, hintEl);
  return { wrap, input, hint: hintEl };
}

function passwordField(opts) {
  const f = field({ ...opts, type: 'password' });
  const toggle = h('button', { type: 'button', class: 'pw-toggle', 'aria-label': 'Show password', 'aria-pressed': 'false' }, svg(icons.eye));
  toggle.addEventListener('click', () => {
    const show = f.input.type === 'password';
    f.input.type = show ? 'text' : 'password';
    toggle.setAttribute('aria-pressed', String(show));
    toggle.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
    toggle.replaceChildren(svg(show ? icons.eyeOff : icons.eye));
    f.input.focus();
  });
  const wrap = h('div', { class: 'pw-wrap' });
  f.input.before(wrap);
  wrap.append(f.input, toggle);
  return f;
}

function setHint(f, text, kind) {
  f.hint.textContent = text || '';
  f.hint.className = `field-hint${kind ? ` is-${kind}` : ''}`;
  f.input.toggleAttribute('aria-invalid', kind === 'error');
}

function formError() {
  return h('div', { class: 'form-error', role: 'alert', hidden: true });
}
function showError(el, text, extra) {
  el.replaceChildren(h('span', {}, text), extra || '');
  el.hidden = !text;
}

function submitButton(label) {
  return h('button', { type: 'submit', class: 'btn btn-primary btn-block btn-lg' }, h('span', { class: 'btn-label' }, label));
}

// Wires submit (button click + Enter) with disabled-while-submitting.
function onSubmit(form, btn, fn) {
  let running = false;
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (running) return;
    running = true;
    try { await busy(btn, fn); } finally { running = false; }
  });
}

function signedIn(tokens) {
  session.set(tokens);
  // main.js reacts to the session change and opens the portal.
}

// ---------- Log In ----------
export function renderLogin(root, { query = {} } = {}) {
  const email = field({ label: 'BAU email', type: 'email', name: 'email', autocomplete: 'username', placeholder: 'you@stu.bau.edu', value: query.email || '', autofocus: !query.email });
  const pw = passwordField({ label: 'Password', name: 'password', autocomplete: 'current-password', autofocus: !!query.email });
  const err = formError();
  const btn = submitButton('Log In');
  const form = h('form', { class: 'auth-form', novalidate: true },
    err, email.wrap, pw.wrap, btn,
    h('div', { class: 'auth-row-end' }, h('a', { class: 'link', href: `#/forgot${email.input.value ? `?email=${encodeURIComponent(email.input.value)}` : ''}`, onClick: (e) => {
      e.preventDefault();
      const v = normalizeEmail(email.input.value);
      navigate(`#/forgot${v ? `?email=${encodeURIComponent(v)}` : ''}`);
    } }, 'Forgot password?')),
    h('p', { class: 'auth-switch' }, 'New here? ', h('a', { class: 'link link-strong', href: '#/signup' }, 'Sign Up')));

  onSubmit(form, btn, async () => {
    showError(err, '');
    const e = normalizeEmail(email.input.value);
    const p = pw.input.value;
    setHint(email, ''); setHint(pw, '');
    if (!e || !e.includes('@')) { setHint(email, 'Enter your BAU email.', 'error'); email.input.focus(); return; }
    if (!accountKind(e)) { setHint(email, DOMAIN_RULE, 'error'); email.input.focus(); return; }
    if (!p) { setHint(pw, 'Enter your password.', 'error'); pw.input.focus(); return; }
    try {
      const tokens = await api('/v1/auth/login', { method: 'POST', auth: false, body: { email: e, password: p, device: DEVICE } });
      signedIn(tokens);
    } catch (error) {
      if (error.code === 'domain_not_allowed') { navigate('#/not-allowed'); return; }
      showError(err, authMessage(error, 'login'));
      if (error.code === 'invalid_credentials') { pw.input.select(); pw.input.focus(); }
    }
  });
  root.replaceChildren(layout('Log in', 'Welcome back. Use your BAU email and password.', form));
  return null;
}

// ---------- Sign Up ----------
export function renderSignup(root, { query = {} } = {}) {
  const name = field({ label: 'Full name', name: 'name', autocomplete: 'name', placeholder: 'Sam Carter', autofocus: true });
  const email = field({ label: 'BAU email', type: 'email', name: 'email', autocomplete: 'email', placeholder: 'you@stu.bau.edu', value: query.email || '', hint: 'Use your @stu.bau.edu or @bau.edu email.' });
  const pw = passwordField({ label: 'Password', name: 'password', autocomplete: 'new-password', hint: PASSWORD_RULE });
  const confirm = passwordField({ label: 'Confirm password', name: 'confirm', autocomplete: 'new-password' });
  const terms = h('input', { type: 'checkbox', id: 'f-terms', name: 'terms' });
  const termsWrap = h('label', { class: 'check', for: 'f-terms' }, terms,
    h('span', {}, 'I agree to the ', h('a', { class: 'link', href: '/docs', target: '_blank', rel: 'noopener' }, 'Terms'), ' and ', h('a', { class: 'link', href: '/docs', target: '_blank', rel: 'noopener' }, 'Privacy Policy'), '.'));
  const err = formError();
  const btn = submitButton('Create Account');

  const emailHint = () => {
    const v = normalizeEmail(email.input.value);
    if (!v || !v.includes('@') || !v.split('@')[1]) { setHint(email, 'Use your @stu.bau.edu or @bau.edu email.'); return; }
    const kind = accountKind(v);
    if (kind === 'student') setHint(email, 'Student account', 'ok');
    else if (kind === 'staff') setHint(email, 'Staff account · can request projects', 'ok');
    else setHint(email, DOMAIN_RULE, 'error');
  };
  const pwHint = () => {
    const v = pw.input.value;
    if (!v) setHint(pw, PASSWORD_RULE);
    else setHint(pw, passwordOk(v) ? 'Strong enough.' : PASSWORD_RULE, passwordOk(v) ? 'ok' : 'warn');
    if (confirm.input.value) confirmHint();
  };
  const confirmHint = () => {
    const v = confirm.input.value;
    if (!v) setHint(confirm, '');
    else if (v === pw.input.value) setHint(confirm, 'Passwords match.', 'ok');
    else setHint(confirm, 'Passwords don’t match.', 'warn');
  };
  email.input.addEventListener('input', emailHint);
  pw.input.addEventListener('input', pwHint);
  confirm.input.addEventListener('input', confirmHint);
  if (email.input.value) emailHint();

  const form = h('form', { class: 'auth-form', novalidate: true }, err, name.wrap, email.wrap, pw.wrap, confirm.wrap, termsWrap, btn,
    h('p', { class: 'auth-switch' }, 'Already have an account? ', h('a', { class: 'link link-strong', href: '#/login' }, 'Log In')));

  onSubmit(form, btn, async () => {
    showError(err, '');
    const n = name.input.value.trim().replace(/\s+/g, ' ');
    const e = normalizeEmail(email.input.value);
    const p = pw.input.value;
    let first = null;
    const bad = (f, text) => { setHint(f, text, 'error'); first = first || f.input; };
    if (n.length < 2) bad(name, 'Enter your full name.'); else setHint(name, '');
    if (!e || !e.includes('@')) bad(email, 'Enter your BAU email.');
    else if (!accountKind(e)) bad(email, DOMAIN_RULE);
    if (!passwordOk(p)) bad(pw, PASSWORD_RULE);
    if (confirm.input.value !== p) bad(confirm, 'Passwords don’t match.');
    if (first) { first.focus(); return; }
    if (!terms.checked) { showError(err, 'Please accept the Terms and Privacy Policy to continue.'); terms.focus(); return; }
    try {
      const res = await api('/v1/auth/register', { method: 'POST', auth: false, body: { name: n, email: e, password: p, device: DEVICE } });
      if (res?.access_token) { signedIn(res); return; }
      showError(err, 'Couldn’t create the account. Try again.');
    } catch (error) {
      if (error.code === 'email_taken') {
        showError(err, authMessage(error, 'signup'), h('a', { class: 'link link-strong', href: `#/login?email=${encodeURIComponent(e)}` }, ' Log In'));
        return;
      }
      if (error.code === 'weak_password') { setHint(pw, authMessage(error, 'signup'), 'error'); pw.input.focus(); return; }
      if (error.code === 'domain_not_allowed') { setHint(email, DOMAIN_RULE, 'error'); email.input.focus(); return; }
      showError(err, authMessage(error, 'signup'));
    }
  });
  root.replaceChildren(layout('Create your account', 'Students and staff of BAU only.', form));
  return null;
}

// ---------- Forgot Password ----------
// No email is sent, so support resets passwords by hand.
export function renderForgot(root) {
  const body = h('div', { class: 'auth-form' },
    h('div', { class: 'notice notice-info' },
      h('p', {}, 'Forgot your password? Ask BAU BUILD support to reset it.')),
    h('a', { class: 'btn btn-primary btn-block btn-lg', href: `mailto:${SUPPORT_EMAIL}?subject=${encodeURIComponent('Password reset')}` }, `Email ${SUPPORT_EMAIL}`),
    h('p', { class: 'auth-switch' }, h('a', { class: 'link link-strong', href: '#/login' }, 'Back to log in')));
  root.replaceChildren(layout('Reset your password', null, body));
  return null;
}

// ---------- Not Allowed ----------
export function renderNotAllowed(root) {
  const body = h('div', { class: 'auth-form' },
    h('div', { class: 'notice notice-warn' },
      h('p', {}, 'BAU BUILD is open to Bahçeşehir University only. Sign up with your ', h('strong', {}, '@stu.bau.edu'), ' (students) or ', h('strong', {}, '@bau.edu'), ' (staff) email.')),
    h('a', { class: 'btn btn-primary btn-block btn-lg', href: '#/signup' }, 'Sign Up with a BAU email'),
    h('p', { class: 'auth-switch' }, 'Have a BAU account? ', h('a', { class: 'link link-strong', href: '#/login' }, 'Log In')),
    h('p', { class: 'auth-switch' }, h('a', { class: 'link', href: '/' }, 'Back to the website')));
  root.replaceChildren(layout('This email can’t use BAU BUILD', null, body));
  return null;
}
