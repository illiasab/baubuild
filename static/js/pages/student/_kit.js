// Shared helpers for the student pages. DOM is built with h()/textContent only: never innerHTML with data.
// Local on purpose: the shell's ui.js is used where it exists (toast, confirm), with a fallback here.

const SVG_NS = 'http://www.w3.org/2000/svg';

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  applyAttrs(el, attrs);
  append(el, children);
  return el;
}

function applyAttrs(el, attrs) {
  if (!attrs) return;
  for (const [key, value] of Object.entries(attrs)) {
    if (value == null || value === false) continue;
    if (key === 'class') el.className = Array.isArray(value) ? value.filter(Boolean).join(' ') : value;
    else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'value' && 'value' in el) el.value = value;
    else if (key === 'text') el.textContent = value;
    else el.setAttribute(key, value === true ? '' : String(value));
  }
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false || child === true) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

// Small stroked icons (paths are constants, never data).
const ICONS = {
  close: 'M5 5l10 10M15 5L5 15',
  check: 'M4 10.5l4 4 8-9',
  external: 'M8 4H4v12h12v-4M11 3h6v6M17 3l-8 8',
  copy: 'M7 7h9v9H7zM4 13V4h9',
  arrow: 'M4 10h12M11 5l5 5-5 5',
  send: 'M3 10l14-6-6 14-2-6z',
  branch: 'M6 3v10M6 13a2 2 0 100 4 2 2 0 000-4zM14 7a2 2 0 100-4 2 2 0 000 4zM14 7c0 4-8 3-8 6',
  bell: 'M5 14V9a5 5 0 0110 0v5l1.5 2h-13zM8 18h4',
  search: 'M9 15a6 6 0 100-12 6 6 0 000 12zM13.5 13.5L17 17',
  warn: 'M10 3l8 14H2zM10 8v4M10 14.5v.5',
  dot: 'M10 10h0',
};

export function icon(name, size = 18) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 20 20');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'sp-icon');
  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('d', ICONS[name] || '');
  path.setAttribute('fill', 'none');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', '1.8');
  path.setAttribute('stroke-linecap', 'round');
  path.setAttribute('stroke-linejoin', 'round');
  svg.append(path);
  return svg;
}

// ---------------------------------------------------------------------------------------------------------------
// Lenient readers
// ---------------------------------------------------------------------------------------------------------------

export const str = (v, fallback = '') => (v == null ? fallback : String(v));
export const num = (v, fallback = 0) => {
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? n : fallback;
};
export const list = (v) => (Array.isArray(v) ? v : []);
export const items = (payload) => list(payload?.items ?? payload?.data ?? (Array.isArray(payload) ? payload : []));

export function money(value) {
  const n = num(value);
  return `$${n.toLocaleString('en-US', { maximumFractionDigits: n % 1 ? 2 : 0 })}`;
}

export function capital(text) {
  const s = str(text).replace(/_/g, ' ').trim();
  return s ? s[0].toUpperCase() + s.slice(1).toLowerCase() : '';
}

export const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

export function parseDate(iso) {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

const DAY = 86_400_000;

// Whole days until iso, rounded up (negative when past). null when there is no date.
export function daysUntil(iso) {
  const d = parseDate(iso);
  if (!d) return null;
  const ms = d.getTime() - Date.now();
  return ms >= 0 ? Math.ceil(ms / DAY) : -Math.ceil(-ms / DAY);
}

// Card chip: "Due today", "Due in 1 day", "Due in 2 weeks", "Due in 10 days".
export function dueChip(iso) {
  const days = daysUntil(iso);
  if (days == null) return null;
  if (days < 0) return 'Past due';
  if (days === 0) return 'Due today';
  if (days === 1) return 'Due in 1 day';
  if (days % 7 === 0) return days === 7 ? 'Due in 1 week' : `Due in ${days / 7} weeks`;
  return `Due in ${days} days`;
}

// Lower-case phrase for meta lines: "due in 3 days", "due tomorrow", "overdue".
export function duePhrase(iso) {
  const days = daysUntil(iso);
  if (days == null) return null;
  if (days < 0) return 'overdue';
  if (days === 0) return 'due today';
  if (days === 1) return 'due tomorrow';
  return `due in ${days} days`;
}

export function shortDate(iso) {
  const d = parseDate(iso);
  if (!d) return '';
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
}

export function relTime(iso) {
  const d = parseDate(iso);
  if (!d) return '';
  const s = Math.round((Date.now() - d.getTime()) / 1000);
  if (s < 45) return 'just now';
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const hr = Math.round(m / 60);
  if (hr < 24) return `${hr} h ago`;
  const days = Math.round(hr / 24);
  if (days < 7) return days === 1 ? 'yesterday' : `${days} days ago`;
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: d.getFullYear() === new Date().getFullYear() ? undefined : 'numeric' });
}

export function difficultyLabel(value) {
  const v = str(value).toLowerCase();
  return v === 'medium' ? 'Medium' : v === 'hard' ? 'Hard' : v === 'easy' ? 'Easy' : capital(v);
}

export function minScoreLabel(project) {
  if (project?.min_score_label) return str(project.min_score_label);
  const n = num(project?.min_score);
  return n > 0 ? `Score ${n}+` : 'Any score';
}

// "Everyone welcome" / "Professor approves" / "Dev lead approves" (same as the portal card footer).
export function joiningLabel(project) {
  if (str(project?.joining) === 'everyone') return 'Everyone welcome';
  return str(project?.reviewed_by) === 'professor' ? 'Professor approves' : 'Dev lead approves';
}

export function eligibilityMessage(project, score) {
  const reason = str(project?.eligibility?.reason);
  if (reason === 'score_too_low') {
    const need = num(project?.min_score);
    return score != null ? `This project needs a score of ${need}. Yours is ${score}.` : `This project needs a score of ${need}.`;
  }
  if (reason === 'class_not_enrolled') return 'This project is for students enrolled in its class.';
  if (reason === 'payment_required') return 'A paid plan is required before joining.';
  return reason ? 'You can’t join this project right now.' : '';
}

export function githubUrl(repoName) {
  const name = str(repoName).trim();
  if (!/^[\w.-]+\/[\w.-]+$/.test(name)) return null;
  return `https://github.com/${name}`;
}

// Only http(s) links are ever put in an href.
export function safeUrl(value) {
  try {
    const url = new URL(str(value), location.href);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
  } catch {
    return null;
  }
}

export function errorText(err) {
  if (!err) return 'Something went wrong.';
  if (err.code === 'network') return 'Can’t reach BAU BUILD. Check your connection and try again.';
  return str(err.message, 'Something went wrong.');
}

// Splits on backticks; odd segments render as inline code.
export function richText(text) {
  const parts = str(text).split('`');
  if (parts.length % 2 === 0) {
    const tail = parts.splice(parts.length - 2, 2).join('`');
    parts.push(tail);
  }
  return parts.filter((p) => p !== '').map((p, i) => (i % 2 === 1 ? h('code', { class: 'sp-code' }, p) : p));
}

// ---------------------------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------------------------

export function chip(text, kind = '') {
  return h('span', { class: ['sp-chip', kind && `sp-chip--${kind}`] }, text);
}

export function pill(text, kind = '') {
  return h('span', { class: ['sp-pill', kind && `sp-pill--${kind}`] }, text);
}

// Student kinds → shell button classes (app.css): primary = yellow CTA, dark = ink, secondary = outlined.
const BTN = { primary: 'btn-yellow', dark: 'btn-primary', secondary: 'btn-secondary', ghost: 'btn-ghost', danger: 'btn-danger', link: 'btn-link' };

export function button(label, { kind = 'secondary', onClick, disabled, type = 'button', title, small } = {}) {
  return h('button', { type, class: ['btn', BTN[kind] || 'btn-secondary', small && 'btn-sm', 'sp-btn'], onclick: onClick, disabled, title }, label);
}

export function textLink(label, { href, onClick, external } = {}) {
  if (href) {
    const attrs = { class: 'sp-link', href };
    if (external) Object.assign(attrs, { target: '_blank', rel: 'noopener noreferrer' });
    return h('a', attrs, label, external ? icon('external', 14) : null);
  }
  return h('button', { type: 'button', class: 'sp-link', onclick: onClick }, label);
}

export function pageHead(title, ...extra) {
  return h('header', { class: 'page-head sp-head' }, h('h1', {}, title), extra.length ? h('div', { class: 'page-actions' }, extra) : null);
}

export function monoLabel(text, cls = '') {
  return h('span', { class: ['sp-mono-label', cls] }, text);
}

export function card(...children) {
  return h('section', { class: 'sp-card' }, children);
}

export function cardStrip(label) {
  return h('div', { class: 'sp-card-strip' }, label);
}

// Clickable non-button element: pointer, Enter/Space, focusable.
export function clickable(el, onActivate, label) {
  el.tabIndex = 0;
  el.setAttribute('role', 'button');
  if (label) el.setAttribute('aria-label', label);
  el.classList.add('sp-clickable');
  el.addEventListener('click', (e) => {
    if (e.target.closest('a, button, input, textarea, select') && e.target.closest('a, button, input, textarea, select') !== el) return;
    onActivate(e);
  });
  el.addEventListener('keydown', (e) => {
    if (e.target !== el) return;
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onActivate(e); }
  });
  return el;
}

// ---------------------------------------------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------------------------------------------

export function loadingView(label = 'Loading…') {
  return h('div', { class: 'state state-loading sp-state', role: 'status', 'aria-live': 'polite' },
    h('span', { class: 'spinner', 'aria-hidden': 'true' }), h('span', { class: 'state-body' }, label));
}

export function emptyView({ title, body, action } = {}) {
  return h('div', { class: 'state state-empty sp-state' },
    title ? h('h2', { class: 'state-title' }, title) : null,
    body ? h('p', { class: 'state-body' }, body) : null,
    action ? button(action.label, { kind: action.kind || 'secondary', onClick: action.onClick }) : null);
}

export function errorView(err, retry, title) {
  return h('div', { class: 'state state-error sp-state', role: 'alert' },
    h('h2', { class: 'state-title' }, title || (err?.code === 'network' ? 'You’re offline' : 'Couldn’t load this page')),
    h('p', { class: 'state-body' }, errorText(err)),
    retry ? button('Try again', { kind: 'secondary', onClick: retry }) : null);
}

export function staleBanner(err, retry) {
  return h('div', { class: 'stale-banner sp-stale', role: 'status' },
    h('span', {}, err?.code === 'network' ? 'You’re offline. Showing saved data.' : 'Showing saved data. Couldn’t refresh.'),
    retry ? button('Retry', { kind: 'link', onClick: () => retry() }) : null);
}

/**
 * Loads data into `container`: spinner first, error state with retry when nothing loaded yet, and on a failed
 * refresh keeps the old view with a stale banner. `initial` seeds the view (a cached copy) while it refreshes.
 */
export function loader(container, { load, view, initial = null, isAlive = () => container.isConnected }) {
  let data = initial;
  let stale = null;
  let busy = null;
  const paint = () => {
    if (!isAlive()) return;
    const content = view(data, refresh);
    container.replaceChildren(...(stale ? [staleBanner(stale, refresh)] : []), ...[content].flat().filter(Boolean));
  };
  async function refresh({ quiet = false } = {}) {
    if (busy) return busy;
    if (data == null) container.replaceChildren(loadingView());
    else if (!quiet) container.classList.add('sp-refreshing');
    busy = (async () => {
      try {
        data = await load();
        stale = null;
        paint();
      } catch (err) {
        if (err?.name === 'AbortError' || !isAlive()) return;
        if (data == null) container.replaceChildren(errorView(err, () => refresh()));
        else { stale = err; paint(); }
      } finally {
        container.classList.remove('sp-refreshing');
        busy = null;
      }
    })();
    return busy;
  }
  if (initial != null) paint();
  refresh({ quiet: true });
  return {
    refresh,
    repaint: paint,
    get data() { return data; },
    set data(next) { data = next; paint(); },
  };
}

// ---------------------------------------------------------------------------------------------------------------
// Overlays
// ---------------------------------------------------------------------------------------------------------------

export function toast(ctx, message, kind) {
  const fn = ctx?.toast || ctx?.ui?.toast;
  if (typeof fn === 'function') {
    try { fn(message, kind || 'info'); return; } catch { /* fall back */ }
  }
  let host = document.querySelector('.sp-toasts');
  if (!host) { host = h('div', { class: 'sp-toasts', 'aria-live': 'polite' }); document.body.append(host); }
  const el = h('div', { class: ['sp-toast', kind && `sp-toast--${kind}`] }, message);
  host.append(el);
  setTimeout(() => el.remove(), 3200);
}

export async function confirmAction(ctx, { title, body, confirmLabel = 'Confirm', danger = false }) {
  const fn = ctx?.ui?.confirmDialog;
  if (typeof fn === 'function') {
    try {
      const result = await fn({ title, body, confirmLabel, danger, kind: danger ? 'danger' : 'primary' });
      return !!result;
    } catch { /* fall back */ }
  }
  return window.confirm(body ? `${title}\n\n${body}` : title);
}

/**
 * Right-hand side panel over a scrim. Esc / scrim / close button call onClose (the caller decides, e.g. navigate).
 * Returns { el, body, footer, setEyebrow, close }.
 */
export function sidePanel({ eyebrow = '', label = 'Details', onClose }) {
  const previous = document.activeElement;
  const eyebrowEl = h('span', { class: 'sp-panel-eyebrow' }, eyebrow);
  const closeBtn = h('button', { type: 'button', class: 'sp-icon-btn', 'aria-label': `Close ${label.toLowerCase()}`, onclick: () => onClose?.() }, icon('close', 20));
  const body = h('div', { class: 'sp-panel-body' });
  const footer = h('div', { class: 'sp-panel-footer', hidden: true });
  const panel = h('aside', { class: 'sp-panel', role: 'dialog', 'aria-modal': 'true', 'aria-label': label, tabindex: '-1' },
    h('div', { class: 'sp-panel-top' }, eyebrowEl, closeBtn), body, footer);
  const scrim = h('div', { class: 'sp-scrim', onclick: () => onClose?.() });
  const root = h('div', { class: 'sp-overlay' }, scrim, panel);
  const onKey = (e) => {
    if (e.key === 'Escape') { e.stopPropagation(); onClose?.(); }
    if (e.key === 'Tab') {
      const focusables = [...panel.querySelectorAll('a[href], button:not([disabled]), textarea, input, [tabindex="0"]')];
      if (!focusables.length) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  };
  root.addEventListener('keydown', onKey);
  document.body.append(root);
  requestAnimationFrame(() => { root.classList.add('is-open'); panel.focus({ preventScroll: true }); });
  let closed = false;
  return {
    el: panel,
    body,
    footer,
    setEyebrow: (text) => { eyebrowEl.textContent = text; },
    setBody: (...nodes) => { body.replaceChildren(...nodes.flat().filter((n) => n != null && n !== false)); },
    setFooter: (...nodes) => { footer.replaceChildren(...nodes.flat().filter(Boolean)); footer.hidden = !footer.childNodes.length; },
    close() {
      if (closed) return;
      closed = true;
      root.classList.remove('is-open');
      root.classList.add('is-closing');
      const done = () => root.remove();
      if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) done();
      else setTimeout(done, 200);
      if (previous && previous.isConnected && typeof previous.focus === 'function') previous.focus({ preventScroll: true });
    },
  };
}

/** Centered modal; returns { el, body, close }. */
export function modal({ title, body, actions = [], onClose }) {
  const previous = document.activeElement;
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    root.remove();
    onClose?.();
    if (previous?.isConnected) previous.focus?.({ preventScroll: true });
  };
  const box = h('div', { class: 'sp-modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title, tabindex: '-1' },
    h('div', { class: 'sp-modal-head' }, h('h2', { class: 'sp-modal-title' }, title),
      h('button', { type: 'button', class: 'sp-icon-btn', 'aria-label': 'Close', onclick: close }, icon('close', 20))),
    h('div', { class: 'sp-modal-body' }, body),
    actions.length ? h('div', { class: 'sp-modal-actions' }, actions) : null);
  const root = h('div', { class: 'sp-overlay sp-overlay--center is-open' }, h('div', { class: 'sp-scrim' }), box);
  root.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.stopPropagation(); close(); } });
  document.body.append(root);
  requestAnimationFrame(() => (box.querySelector('textarea, input') || box).focus({ preventScroll: true }));
  return { el: box, close };
}

// Reads ?q= style query params from ctx or the hash.
export function queryParam(ctx, key) {
  const fromCtx = ctx?.query?.[key] ?? ctx?.params?.[key];
  if (fromCtx != null) return str(fromCtx);
  const raw = location.hash.split('?')[1] || '';
  return new URLSearchParams(raw).get(key) || '';
}

export function go(ctx, hash) {
  if (typeof ctx?.navigate === 'function') ctx.navigate(hash);
  else location.hash = hash;
}

export async function copyText(ctx, text, done = 'Copied') {
  try {
    await navigator.clipboard.writeText(text);
    toast(ctx, done);
  } catch {
    toast(ctx, 'Couldn’t copy. Select the text and copy it.', 'error');
  }
}
