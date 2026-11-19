// Shared DOM + UI helpers for the shell and every page module (ctx.ui).

// h('button', {class: 'btn', onClick: fn, disabled: true, style: {width: '10px'}, dataset: {id: 1}, html: '<b>raw</b>'}, 'child', node, [more])
export function h(tag, attrs, ...children) {
  const el = tag === 'svg' || attrs?.xmlns ? document.createElementNS('http://www.w3.org/2000/svg', tag) : document.createElement(tag);
  if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) {
    children.unshift(attrs);
    attrs = null;
  }
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class' || key === 'className') el.setAttribute('class', Array.isArray(value) ? value.filter(Boolean).join(' ') : value);
    else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key === 'html') el.innerHTML = value;
    else if (key === 'text') el.textContent = value;
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'value' && 'value' in el) el.value = value;
    else if (key === 'checked' && 'checked' in el) el.checked = !!value;
    else if (value === true) el.setAttribute(key, '');
    else el.setAttribute(key, String(value));
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false || child === true) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

// Inline SVG from markup (trusted, static strings only).
export function svg(markup) {
  const t = document.createElement('template');
  t.innerHTML = markup.trim();
  return t.content.firstElementChild;
}

export function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export function clear(root) {
  if (root) root.replaceChildren();
  return root;
}

// ---------- Toasts ----------
function toastHost() {
  let host = document.getElementById('toasts');
  if (!host) {
    host = h('div', { id: 'toasts', class: 'toasts', role: 'status', 'aria-live': 'polite' });
    document.body.append(host);
  }
  return host;
}

// kind: 'info' | 'success' | 'error' | 'warning'
export function toast(message, kind = 'info', { timeout = 4000 } = {}) {
  const el = h('div', { class: `toast toast-${kind}` }, h('span', {}, message),
    h('button', { class: 'toast-close', type: 'button', 'aria-label': 'Dismiss', onClick: () => el.remove() }, '×'));
  toastHost().append(el);
  if (timeout) setTimeout(() => { el.classList.add('leaving'); setTimeout(() => el.remove(), 200); }, timeout);
  return el;
}

// ---------- States ----------
// Each state helper replaces root's children when root is given and returns the state element.
function place(root, el) {
  if (root) root.replaceChildren(el);
  return el;
}

export function loading(root, label = 'Loading…') {
  return place(root, h('div', { class: 'state state-loading', role: 'status', 'aria-live': 'polite' },
    h('span', { class: 'spinner', 'aria-hidden': 'true' }), h('span', { class: 'state-body' }, label)));
}

export function emptyState(root, { title = 'Nothing here yet', body, action } = {}) {
  return place(root, h('div', { class: 'state state-empty' },
    h('h2', { class: 'state-title' }, title),
    body ? h('p', { class: 'state-body' }, body) : null,
    action ? (action instanceof Node ? action : button(action.label, { kind: action.kind || 'primary', onClick: action.onClick })) : null));
}

export function errorMessage(err) {
  if (!err) return 'Something went wrong.';
  if (err.code === 'network') return err.message;
  if (err.status === 404) return 'This isn’t available yet.';
  return err.message || 'Something went wrong.';
}

export function errorState(root, err, retry) {
  return place(root, h('div', { class: 'state state-error', role: 'alert' },
    h('h2', { class: 'state-title' }, err?.code === 'network' ? 'You’re offline' : 'Couldn’t load this'),
    h('p', { class: 'state-body' }, errorMessage(err)),
    retry ? button('Try again', { kind: 'secondary', onClick: retry }) : null));
}

// Thin banner shown above content when showing cached / possibly outdated data.
export function staleBanner(text = 'Showing saved data — couldn’t refresh.', retry) {
  return h('div', { class: 'stale-banner', role: 'status' }, h('span', {}, text),
    retry ? button('Retry', { kind: 'link', onClick: retry }) : null);
}

// ---------- Formatting ----------
export function formatUsd(value, { cents = false } = {}) {
  const n = Number(value) || 0;
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: cents ? 2 : 0, minimumFractionDigits: cents ? 2 : 0 }).format(n);
}

export function formatDate(value, opts = { month: 'short', day: 'numeric', year: 'numeric' }) {
  if (!value) return '';
  const d = value instanceof Date ? value : new Date(/^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T00:00:00` : value);
  if (Number.isNaN(d.getTime())) return String(value);
  return d.toLocaleDateString('en-US', opts);
}

export function relativeTime(value) {
  if (!value) return '';
  const d = value instanceof Date ? value : new Date(value);
  const diff = (d.getTime() - Date.now()) / 1000;
  if (Number.isNaN(diff)) return String(value);
  const abs = Math.abs(diff);
  const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
  if (abs < 45) return 'just now';
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour');
  if (abs < 86400 * 7) return rtf.format(Math.round(diff / 86400), 'day');
  if (abs < 86400 * 30) return rtf.format(Math.round(diff / (86400 * 7)), 'week');
  return formatDate(d);
}

export function initials(name = '') {
  const parts = String(name).trim().split(/\s+/).filter((p) => !/^(dr|prof)\.?$/i.test(p));
  return ((parts[0]?.[0] || '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase() || '?';
}

// ---------- Small components ----------
// kind: 'neutral' | 'blue' | 'yellow' | 'green' | 'red' | 'dark'
export function chip(text, kind = 'neutral') {
  return h('span', { class: `chip chip-${kind}` }, text);
}

// kind: 'primary' | 'secondary' | 'danger' | 'link' | 'yellow' | 'ghost'
export function button(label, { kind = 'primary', onClick, type = 'button', disabled, icon, attrs = {} } = {}) {
  return h('button', { type, class: `btn btn-${kind}`, disabled, onClick, ...attrs }, icon || null, label);
}

// Disable a button and show a spinner while fn runs.
export async function busy(btn, fn) {
  btn.disabled = true;
  btn.classList.add('is-busy');
  btn.setAttribute('aria-busy', 'true');
  try { return await fn(); } finally {
    btn.disabled = false;
    btn.classList.remove('is-busy');
    btn.removeAttribute('aria-busy');
  }
}

// ---------- Overlays ----------
let openOverlays = 0;
function lockScroll(on) {
  openOverlays += on ? 1 : -1;
  document.documentElement.classList.toggle('no-scroll', openOverlays > 0);
}

function overlay({ className, title, body, actions = [], onClose, labelledBy }) {
  const previous = document.activeElement;
  const close = (result) => {
    if (!backdrop.isConnected) return;
    backdrop.classList.add('leaving');
    document.removeEventListener('keydown', onKey);
    lockScroll(false);
    setTimeout(() => backdrop.remove(), 180);
    previous?.focus?.();
    onClose?.(result);
  };
  const titleId = labelledBy || `ov-${Math.random().toString(36).slice(2)}`;
  const content = typeof body === 'function' ? body(close) : body;
  const footer = actions.length ? h('div', { class: 'overlay-actions' }, actions.map((a) => a instanceof Node ? a
    : button(a.label, { kind: a.kind || 'secondary', onClick: async (e) => {
      if (a.onClick) {
        const r = await busy(e.currentTarget, () => a.onClick(close));
        if (r !== false) close(a.value ?? true);
      } else close(a.value ?? false);
    } }))) : null;
  const panel = h('div', { class: `overlay-panel ${className}`, role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titleId, tabindex: '-1' },
    h('div', { class: 'overlay-head' }, h('h2', { id: titleId }, title || ''),
      h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Close', onClick: () => close(false) }, '×')),
    h('div', { class: 'overlay-body' }, content),
    footer);
  const backdrop = h('div', { class: `overlay-backdrop ${className}-backdrop`, onMousedown: (e) => { if (e.target === backdrop) close(false); } }, panel);
  const onKey = (e) => {
    if (e.key === 'Escape') close(false);
    if (e.key === 'Tab') {
      const items = [...panel.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')].filter((x) => !x.disabled && x.offsetParent !== null);
      if (!items.length) return;
      const first = items[0], last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  };
  document.addEventListener('keydown', onKey);
  document.body.append(backdrop);
  lockScroll(true);
  requestAnimationFrame(() => {
    const auto = panel.querySelector('[autofocus], .overlay-body input, .overlay-body textarea');
    (auto || panel).focus();
  });
  return { close, panel, element: backdrop };
}

// actions: [{label, kind, onClick(close) -> false to keep open, value}] or Nodes. Returns {close, panel, element}.
export function modal({ title, body, actions, onClose } = {}) {
  return overlay({ className: 'modal', title, body, actions, onClose });
}

// 480px right slide-in panel.
export function sidePanel({ title, body, actions, onClose } = {}) {
  return overlay({ className: 'side-panel', title, body, actions, onClose });
}

// Resolves true (confirmed) / false.
export function confirmDialog({ title = 'Are you sure?', body = '', confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger = false } = {}) {
  return new Promise((resolve) => {
    let result = false;
    modal({
      title,
      body: typeof body === 'string' ? h('p', { class: 'muted' }, body) : body,
      actions: [
        { label: cancelLabel, kind: 'secondary', value: false },
        { label: confirmLabel, kind: danger ? 'danger' : 'primary', onClick: () => { result = true; } },
      ],
      onClose: () => resolve(result),
    });
  });
}

// ---------- Icons used by the shell (and free for pages) ----------
const stroke = (w, d) => `<svg aria-hidden="true" width="${w}" height="${w}" viewBox="0 0 22 22" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`;
export const icons = {
  logo: '<svg aria-hidden="true" width="28" height="28" viewBox="0 0 28 28"><rect x="2" y="16" width="24" height="9" fill="#FFD60A"></rect><rect x="2" y="4" width="11" height="9" fill="#FFD60A"></rect><rect x="15.75" y="4.75" width="9.5" height="7.5" fill="none" stroke="#FFD60A" stroke-width="1.5" stroke-dasharray="2.5 2"></rect></svg>',
  projects: stroke(20, '<rect x="3" y="12" width="16" height="6" rx="1.5"></rect><rect x="3" y="4" width="7" height="6" rx="1.5"></rect><rect x="12" y="4" width="7" height="6" rx="1.5"></rect>'),
  work: stroke(20, '<path d="M8 6l-5 5 5 5M14 6l5 5-5 5"></path>'),
  review: stroke(20, '<circle cx="11" cy="11" r="8"></circle><path d="M7.5 11.5l2.5 2.5 4.5-5"></path>'),
  profile: stroke(20, '<circle cx="11" cy="8" r="3.5"></circle><path d="M4 19c1-3.5 4-5 7-5s6 1.5 7 5"></path>'),
  overview: stroke(20, '<path d="M4 18v-6M9 18V5M14 18v-9M19 18v-4"></path>'),
  inquiries: stroke(20, '<path d="M3 12.5L5.5 5h11L19 12.5V18H3z"></path><path d="M3 12.5h4.5l1 2.5h5l1-2.5H19"></path>'),
  plus: stroke(20, '<rect x="3" y="3" width="16" height="16" rx="3"></rect><path d="M11 7v8M7 11h8"></path>'),
  bell: '<svg aria-hidden="true" width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 8a5 5 0 0110 0c0 4 1.5 5 1.5 5h-13S5 12 5 8zM8.5 16a1.6 1.6 0 003 0"></path></svg>',
  search: stroke(18, '<circle cx="10" cy="10" r="6"></circle><path d="M14.5 14.5L19 19"></path>'),
  menu: stroke(22, '<path d="M4 6h14M4 11h14M4 16h14"></path>'),
  signOut: stroke(18, '<path d="M9 4H5a1 1 0 00-1 1v12a1 1 0 001 1h4M14 15l4-4-4-4M18 11H9"></path>'),
  eye: stroke(20, '<path d="M2 11s3.5-6 9-6 9 6 9 6-3.5 6-9 6-9-6-9-6z"></path><circle cx="11" cy="11" r="2.5"></circle>'),
  eyeOff: stroke(20, '<path d="M3 3l16 16M9 5.3A9 9 0 0111 5c5.5 0 9 6 9 6a15 15 0 01-2.6 3.2M6 6.6C3.6 8.2 2 11 2 11s3.5 6 9 6a8.6 8.6 0 004-.9"></path><path d="M9.2 9.3a2.5 2.5 0 003.5 3.5"></path>'),
};
