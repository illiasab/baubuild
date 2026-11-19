// Auth screens: Log In, Sign Up, Verify Email, Forgot / Reset Password, Not Allowed.
// Email + password, BAU domains only (@stu.bau.edu students, @bau.edu staff), 6-digit email codes.
import { api, DEVICE } from './api.js';
import { session, accountKind } from './session.js';
import { navigate } from './router.js';
import { h, svg, icons, busy } from './ui.js';

const PASSWORD_RULE = 'At least 8 characters, with a letter and a number.';
const DOMAIN_RULE = 'Only @stu.bau.edu and @bau.edu emails can sign up.';

// Remember the latest resend cooldown per email across screens (verify / reset).
const cooldowns = new Map();
function setCooldown(email, seconds) {
  if (seconds) cooldowns.set(email.toLowerCase(), Date.now() + seconds * 1000);
}
function cooldownLeft(email) {
  const until = cooldowns.get(String(email).toLowerCase()) || 0;
  return Math.max(0, Math.ceil((until - Date.now()) / 1000));
}

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

// Map an ApiError to a sentence for the given flow ('login' | 'signup' | 'verify' | 'forgot' | 'reset' | 'resend').
export function authMessage(err, flow) {
  if (!err) return 'Something went wrong. Try again.';
  if (err.code === 'network') return 'Can’t reach BAU BUILD. Check your connection and try again.';
  // Until the backend ships these routes it answers 404 (or 401 `unauthorized`, since unknown routes need a token).
  if (err.status === 404 || err.status === 405 || err.status === 501 || err.code === 'unauthorized') {
    return {
      signup: 'Sign-up isn’t available yet. Try again soon.',
      login: 'Log in isn’t available yet. Try again soon.',
      forgot: 'Password reset isn’t available yet. Try again soon.',
      reset: 'Password reset isn’t available yet. Try again soon.',
      verify: 'Email verification isn’t available yet. Try again soon.',
      resend: 'Sending codes isn’t available yet. Try again soon.',
    }[flow] || 'This isn’t available yet. Try again soon.';
  }
  switch (err.code) {
    case 'invalid_credentials': return 'That email and password don’t match. Check them and try again.';
    case 'domain_not_allowed': return DOMAIN_RULE;
    case 'email_taken': return 'An account with this email already exists. Log in instead.';
    case 'weak_password': return `Choose a stronger password. ${PASSWORD_RULE}`;
    case 'invalid_code': return 'That code isn’t right. Check the email and try again.';
    case 'code_expired': return 'That code has expired. Send a new one.';
    case 'too_many_attempts':
      return flow === 'login' ? `Too many tries. ${waitText(err.retryAfterS)}` : 'Too many wrong codes. Send a new one.';
    case 'too_many_requests': return `You asked for codes too often. ${waitText(err.retryAfterS)}`;
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
      if (error.code === 'email_not_verified') {
        setCooldown(e, error.resendAfterS || 60);
        navigate(`#/verify?email=${encodeURIComponent(e)}`);
        return;
      }
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
      setCooldown(e, res?.resend_after_s || 60);
      navigate(`#/verify?email=${encodeURIComponent(res?.email || e)}`);
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

// ---------- 6-digit code boxes ----------
function codeBoxes(onComplete) {
  const inputs = Array.from({ length: 6 }, (_, i) => h('input', {
    class: 'code-box', type: 'text', inputmode: 'numeric', maxlength: '1', pattern: '[0-9]*',
    autocomplete: i === 0 ? 'one-time-code' : 'off', 'aria-label': `Digit ${i + 1} of 6`,
  }));
  const value = () => inputs.map((x) => x.value).join('');
  const fill = (digits, from = 0) => {
    const d = digits.replace(/\D/g, '').slice(0, 6 - from).split('');
    d.forEach((c, k) => { inputs[from + k].value = c; });
    const next = Math.min(from + d.length, 5);
    inputs[next].focus();
    if (value().length === 6) onComplete(value());
  };
  inputs.forEach((input, i) => {
    input.addEventListener('input', () => {
      const v = input.value.replace(/\D/g, '');
      if (v.length > 1) { input.value = ''; fill(v, i); return; }
      input.value = v;
      if (v && i < 5) inputs[i + 1].focus();
      if (value().length === 6) onComplete(value());
    });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Backspace' && !input.value && i > 0) { inputs[i - 1].value = ''; inputs[i - 1].focus(); e.preventDefault(); }
      if (e.key === 'ArrowLeft' && i > 0) { inputs[i - 1].focus(); e.preventDefault(); }
      if (e.key === 'ArrowRight' && i < 5) { inputs[i + 1].focus(); e.preventDefault(); }
    });
    input.addEventListener('focus', () => input.select());
    input.addEventListener('paste', (e) => {
      const text = (e.clipboardData || window.clipboardData)?.getData('text') || '';
      if (!/\d/.test(text)) return;
      e.preventDefault();
      fill(text, text.replace(/\D/g, '').length >= 6 ? 0 : i);
    });
  });
  const el = h('div', { class: 'code-boxes', role: 'group', 'aria-label': '6-digit code' }, inputs);
  return {
    el, value,
    reset() { inputs.forEach((x) => { x.value = ''; }); inputs[0].focus(); },
    setInvalid(on) { el.classList.toggle('is-invalid', on); },
    focus() { inputs[0].focus(); },
    disable(on) { inputs.forEach((x) => { x.disabled = on; }); },
  };
}

// "Resend code" link with a live countdown. Returns {el, start(seconds), stop()}.
function resendControl(email, purpose, onError, onSent) {
  let timer = null;
  const link = h('button', { type: 'button', class: 'btn btn-link' }, 'Resend code');
  const text = h('span', { class: 'muted' });
  const el = h('p', { class: 'resend' }, 'Didn’t get it? ', link, text);
  const tick = () => {
    const left = cooldownLeft(email);
    link.disabled = left > 0;
    text.textContent = left > 0 ? ` in ${left}s` : '';
    if (!left) { clearInterval(timer); timer = null; }
    if (!el.isConnected && timer) { clearInterval(timer); timer = null; }
  };
  const start = (seconds) => {
    if (seconds) setCooldown(email, seconds);
    clearInterval(timer);
    tick();
    if (cooldownLeft(email)) timer = setInterval(tick, 1000);
  };
  link.addEventListener('click', async () => {
    await busy(link, async () => {
      try {
        const res = await api('/v1/auth/resend-code', { method: 'POST', auth: false, body: { email, purpose } });
        onSent?.();
        start(res?.resend_after_s || 60);
      } catch (error) {
        if (error.retryAfterS) start(error.retryAfterS);
        onError(authMessage(error, 'resend'));
      }
    });
    tick();
  });
  start(0);
  return { el, start, stop: () => clearInterval(timer) };
}

// ---------- Verify Email ----------
export function renderVerify(root, { query = {} } = {}) {
  const email = normalizeEmail(query.email);
  if (!email) { navigate('#/signup', { replace: true }); return null; }
  const err = formError();
  const status = h('p', { class: 'form-ok', role: 'status', hidden: true });
  let submitting = false;
  const btn = submitButton('Verify');
  const code = codeBoxes(() => form.requestSubmit());
  const resend = resendControl(email, 'verify', (m) => showError(err, m), () => {
    showError(err, '');
    status.textContent = `We sent a new code to ${email}.`;
    status.hidden = false;
    code.reset();
  });
  const form = h('form', { class: 'auth-form', novalidate: true }, err, status, code.el, btn, resend.el,
    h('p', { class: 'auth-switch' }, 'Wrong email? ', h('a', { class: 'link link-strong', href: `#/signup?email=${encodeURIComponent(email)}` }, 'Change it')));

  onSubmit(form, btn, async () => {
    if (submitting) return;
    showError(err, '');
    status.hidden = true;
    const c = code.value();
    if (c.length !== 6) { code.setInvalid(true); showError(err, 'Enter the 6-digit code from the email.'); code.focus(); return; }
    submitting = true;
    code.disable(true);
    try {
      const tokens = await api('/v1/auth/verify-email', { method: 'POST', auth: false, body: { email, code: c, device: DEVICE } });
      resend.stop();
      signedIn(tokens);
    } catch (error) {
      code.setInvalid(true);
      showError(err, authMessage(error, 'verify'));
      code.disable(false);
      if (['invalid_code', 'code_expired', 'too_many_attempts'].includes(error.code)) code.reset();
    } finally {
      submitting = false;
      code.disable(false);
    }
  });
  code.el.addEventListener('input', () => code.setInvalid(false));
  root.replaceChildren(layout('Check your email', h('span', {}, 'We sent a 6-digit code to ', h('strong', {}, email), '. It expires in 15 minutes.'), form));
  requestAnimationFrame(() => code.focus());
  return () => resend.stop();
}

// ---------- Forgot / Reset Password ----------
export function renderForgot(root, { query = {} } = {}) {
  let stepEmail = normalizeEmail(query.email);
  let cleanup = null;

  const stepOne = () => {
    const email = field({ label: 'BAU email', type: 'email', name: 'email', autocomplete: 'username', placeholder: 'you@stu.bau.edu', value: stepEmail, autofocus: true });
    const err = formError();
    const btn = submitButton('Send Code');
    const form = h('form', { class: 'auth-form', novalidate: true }, err, email.wrap, btn,
      h('p', { class: 'auth-switch' }, 'Remembered it? ', h('a', { class: 'link link-strong', href: '#/login' }, 'Log In')));
    onSubmit(form, btn, async () => {
      showError(err, '');
      const e = normalizeEmail(email.input.value);
      if (!e || !e.includes('@')) { setHint(email, 'Enter your BAU email.', 'error'); email.input.focus(); return; }
      if (!accountKind(e)) { setHint(email, DOMAIN_RULE, 'error'); email.input.focus(); return; }
      setHint(email, '');
      try {
        const res = await api('/v1/auth/password/forgot', { method: 'POST', auth: false, body: { email: e } });
        setCooldown(e, res?.resend_after_s || 60);
        stepEmail = e;
        stepTwo();
      } catch (error) {
        showError(err, authMessage(error, 'forgot'));
      }
    });
    root.replaceChildren(layout('Reset your password', 'Enter your BAU email and we’ll send you a 6-digit code.', form));
  };

  const stepTwo = () => {
    const err = formError();
    const status = h('p', { class: 'form-ok', role: 'status', hidden: true });
    const code = codeBoxes(() => pw.input.focus());
    const pw = passwordField({ label: 'New password', name: 'new-password', autocomplete: 'new-password', hint: PASSWORD_RULE });
    const confirm = passwordField({ label: 'Confirm new password', name: 'confirm', autocomplete: 'new-password' });
    pw.input.addEventListener('input', () => {
      const v = pw.input.value;
      setHint(pw, v && passwordOk(v) ? 'Strong enough.' : PASSWORD_RULE, v ? (passwordOk(v) ? 'ok' : 'warn') : null);
    });
    const btn = submitButton('Reset Password');
    const resend = resendControl(stepEmail, 'reset', (m) => showError(err, m), () => {
      showError(err, '');
      status.textContent = `We sent a new code to ${stepEmail}.`;
      status.hidden = false;
      code.reset();
    });
    cleanup = () => resend.stop();
    const form = h('form', { class: 'auth-form', novalidate: true }, err, status,
      h('div', { class: 'field' }, h('span', { class: 'label' }, 'Code from the email'), code.el), resend.el,
      pw.wrap, confirm.wrap, btn,
      h('p', { class: 'auth-switch' }, 'Wrong email? ', h('button', { type: 'button', class: 'btn btn-link link-strong', onClick: () => { resend.stop(); stepOne(); } }, 'Change it')));
    onSubmit(form, btn, async () => {
      showError(err, '');
      status.hidden = true;
      const c = code.value();
      const p = pw.input.value;
      if (c.length !== 6) { code.setInvalid(true); showError(err, 'Enter the 6-digit code from the email.'); code.focus(); return; }
      if (!passwordOk(p)) { setHint(pw, PASSWORD_RULE, 'error'); pw.input.focus(); return; }
      if (confirm.input.value !== p) { setHint(confirm, 'Passwords don’t match.', 'error'); confirm.input.focus(); return; }
      setHint(confirm, '');
      try {
        const tokens = await api('/v1/auth/password/reset', { method: 'POST', auth: false, body: { email: stepEmail, code: c, new_password: p, device: DEVICE } });
        resend.stop();
        signedIn(tokens);
      } catch (error) {
        if (error.code === 'weak_password') { setHint(pw, authMessage(error, 'reset'), 'error'); pw.input.focus(); return; }
        if (['invalid_code', 'code_expired', 'too_many_attempts'].includes(error.code)) { code.setInvalid(true); code.reset(); }
        showError(err, authMessage(error, 'reset'));
      }
    });
    code.el.addEventListener('input', () => code.setInvalid(false));
    root.replaceChildren(layout('Enter the code', h('span', {}, 'If ', h('strong', {}, stepEmail), ' has an account, we sent it a 6-digit code. Choose a new password below.'), form));
    requestAnimationFrame(() => code.focus());
  };

  stepOne();
  return () => cleanup?.();
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
