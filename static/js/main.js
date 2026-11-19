// Boot + routing + the signed-in shell (sidebar layout).
import { api, sse, ApiError } from './api.js';
import { session, isStaff, isStudent } from './session.js';
import { route, fallback, navigate, start, currentRoute } from './router.js';
import * as ui from './ui.js';
import { renderLogin, renderSignup, renderVerify, renderForgot, renderNotAllowed } from './auth.js';

const { h, svg, icons, toast } = ui;
const app = document.getElementById('app');

const STUDENT_NAV = [
  { hash: '#/projects', label: 'Projects', icon: 'projects', match: ['projects', 'search'] },
  { hash: '#/my-work', label: 'My Work', icon: 'work', match: ['my-work'] },
  { hash: '#/review', label: 'Review', icon: 'review', match: ['review'] },
  { hash: '#/profile', label: 'Profile', icon: 'profile', match: ['profile'] },
];
const STAFF_NAV = [
  { hash: '#/overview', label: 'Overview', icon: 'overview', match: ['overview'] },
  { hash: '#/inquiries', label: 'Inquiries', icon: 'inquiries', match: ['inquiries'] },
  { hash: '#/new-inquiry', label: 'New Inquiry', icon: 'plus', match: ['new-inquiry'], accent: true },
  { hash: '#/staff-profile', label: 'Profile', icon: 'profile', match: ['staff-profile'] },
];

const homeFor = (user) => (isStaff(user) ? '#/overview' : '#/projects');

// ---------- Routes ----------
const AUTH = { login: renderLogin, signup: renderSignup, verify: renderVerify, forgot: renderForgot, 'not-allowed': renderNotAllowed };
for (const [name, render] of Object.entries(AUTH)) {
  route(`#/${name}`, (r) => showAuth(render, r, name));
}

// role: 'student' | 'staff' | 'any'
const PAGES = [
  ['#/projects', 'student', 'student/projects', 'Projects'],
  ['#/projects/:id', 'student', 'student/project-detail', 'Project'],
  ['#/my-work', 'student', 'student/my-work', 'My Work'],
  ['#/my-work/:projectId', 'student', 'student/workspace', 'Workspace'],
  ['#/review', 'student', 'student/review', 'Review'],
  ['#/profile', 'student', 'student/profile', 'Profile'],
  ['#/notifications', 'any', 'student/notifications', 'Notifications'],
  ['#/search', 'student', 'student/search', 'Search'],
  ['#/overview', 'staff', 'staff/overview', 'Overview'],
  ['#/inquiries', 'staff', 'staff/inquiries', 'Inquiries'],
  ['#/inquiries/:id', 'staff', 'staff/inquiry-detail', 'Inquiry'],
  ['#/new-inquiry', 'staff', 'staff/new-inquiry', 'New Inquiry'],
  ['#/staff-profile', 'staff', 'staff/profile', 'Profile'],
];
for (const [pattern, role, module, title] of PAGES) {
  route(pattern, (r) => showPage({ role, module, title }, r));
}
fallback(() => {
  if (session.signedIn) navigate(homeFor(session.user), { replace: true });
  else navigate('#/login', { replace: true });
});

// ---------- Auth screens ----------
let authCleanup = null;
let pageCleanup = null;

function teardownPage() {
  try { pageCleanup?.(); } catch (err) { console.error(err); }
  pageCleanup = null;
}

function showAuth(render, r, name) {
  if (session.signedIn && name !== 'not-allowed') { navigate(homeFor(session.user), { replace: true }); return; }
  teardownShell();
  try { authCleanup?.(); } catch { /* ignore */ }
  document.title = { login: 'Log In', signup: 'Sign Up', verify: 'Verify Email', forgot: 'Reset Password', 'not-allowed': 'Not Allowed' }[name] + ' · BAU BUILD';
  authCleanup = render(app, r) || null;
  window.scrollTo(0, 0);
}

// ---------- Shell ----------
let shell = null; // {root, content, sidebar, ...}
let renderSeq = 0;
let unreadTimer = null;
const shellRefs = { searchInput: null };

function teardownShell() {
  if (!shell) return;
  teardownPage();
  clearInterval(unreadTimer);
  unreadTimer = null;
  shell = null;
}

function navItem(item) {
  return h('a', { class: ['nav-item', item.accent && 'nav-accent'], href: item.hash, dataset: { match: item.match.join(' ') } },
    svg(icons[item.icon]), h('span', {}, item.label));
}

function buildShell(user) {
  const staff = isStaff(user);
  const nav = staff ? STAFF_NAV : STUDENT_NAV;
  const content = h('main', { class: 'content', id: 'content', tabindex: '-1' });
  const badge = h('span', { class: 'badge', hidden: true });
  const bell = h('a', { class: 'side-icon-btn', href: '#/notifications', 'aria-label': 'Notifications', dataset: { match: 'notifications' } }, svg(icons.bell), badge);
  const topBadge = h('span', { class: 'badge', hidden: true });
  const topBell = h('a', { class: 'topbar-btn', href: '#/notifications', 'aria-label': 'Notifications' }, svg(icons.bell), topBadge);

  let search = null;
  if (!staff) {
    const input = h('input', { type: 'search', class: 'side-search-input', placeholder: 'Search projects', 'aria-label': 'Search projects', enterkeyhint: 'search' });
    search = h('form', { class: 'side-search', role: 'search', onSubmit: (e) => {
      e.preventDefault();
      const q = input.value.trim();
      closeDrawer();
      navigate(`#/search${q ? `?q=${encodeURIComponent(q)}` : ''}`);
    } }, svg(icons.search), input);
    shellRefs.searchInput = input;
  } else {
    shellRefs.searchInput = null;
  }

  const avatar = h('span', { class: 'avatar', 'aria-hidden': 'true' });
  const userName = h('span', { class: 'user-name' });
  const userMeta = h('span', { class: 'user-meta' });
  const userCard = h('div', { class: 'user-card' },
    h('a', { class: 'user-link', href: staff ? '#/staff-profile' : '#/profile' }, avatar, h('span', { class: 'user-text' }, userName, userMeta)),
    h('button', { type: 'button', class: 'side-icon-btn signout', 'aria-label': 'Sign out', title: 'Sign out', onClick: signOut }, svg(icons.signOut)));

  const sidebar = h('aside', { class: 'sidebar', id: 'sidebar', 'aria-label': 'Sidebar' },
    h('div', { class: 'side-top' },
      h('a', { class: 'brand', href: homeFor(user) }, svg(icons.logo), h('span', {}, 'BAU BUILD')),
      bell),
    search,
    h('nav', { class: 'side-nav', 'aria-label': 'Portal' }, nav.map(navItem)),
    userCard);

  const scrim = h('div', { class: 'scrim', onClick: closeDrawer });
  const menuBtn = h('button', { type: 'button', class: 'topbar-btn', 'aria-label': 'Open menu', 'aria-controls': 'sidebar', 'aria-expanded': 'false', onClick: () => toggleDrawer() }, svg(icons.menu));
  const topbar = h('header', { class: 'topbar' }, menuBtn,
    h('a', { class: 'brand', href: homeFor(user) }, svg(icons.logo), h('span', {}, 'BAU BUILD')), topBell);

  const root = h('div', { class: ['shell', staff ? 'is-staff' : 'is-student'] },
    h('a', { class: 'skip-link', href: '#content', onClick: (e) => { e.preventDefault(); content.focus(); } }, 'Skip to content'),
    topbar, sidebar, scrim, content);

  sidebar.addEventListener('click', (e) => { if (e.target.closest('a[href^="#/"]')) closeDrawer(); });

  shell = { root, content, sidebar, menuBtn, staff, badges: [badge, topBadge], avatar, userName, userMeta, stats: null };
  app.replaceChildren(root);
  paintUser();
  refreshUnread();
  clearInterval(unreadTimer);
  unreadTimer = setInterval(() => { if (!document.hidden) refreshUnread(); }, 60_000);
  if (!staff) loadStats();
}

function toggleDrawer(force) {
  if (!shell) return;
  const open = force ?? !shell.root.classList.contains('drawer-open');
  shell.root.classList.toggle('drawer-open', open);
  shell.menuBtn.setAttribute('aria-expanded', String(open));
  shell.menuBtn.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
  if (open) shell.sidebar.querySelector('a, button, input')?.focus();
}
function closeDrawer() { toggleDrawer(false); }
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && shell?.root.classList.contains('drawer-open')) closeDrawer(); });

function paintUser() {
  if (!shell) return;
  const user = session.user || {};
  const name = user.display_name || user.name || user.email || 'You';
  shell.avatar.replaceChildren();
  if (user.avatar_url || user.avatar) {
    shell.avatar.append(h('img', { src: user.avatar_url || user.avatar, alt: '' }));
  } else {
    shell.avatar.textContent = user.initials || ui.initials(name);
  }
  shell.userName.textContent = name;
  if (shell.staff) {
    const title = user.title || (user.role === 'admin' ? 'Admin' : 'Faculty');
    shell.userMeta.textContent = `${title} · Staff`;
  } else if (shell.stats) {
    const tier = String(shell.stats.status || 'builder');
    shell.userMeta.textContent = `Score ${shell.stats.score ?? 0} · ${tier.charAt(0).toUpperCase()}${tier.slice(1)}`;
  } else {
    shell.userMeta.textContent = user.department || 'Student';
  }
}

async function loadStats() {
  try {
    const stats = await api('/v1/me/stats');
    if (shell) { shell.stats = stats; paintUser(); }
  } catch { /* keep the fallback line */ }
}

async function refreshUnread() {
  if (!shell) return;
  try {
    const res = await api('/v1/notifications/unread-count');
    const n = Number(res?.unread ?? res?.count ?? 0);
    for (const b of shell?.badges || []) {
      b.hidden = !n;
      b.textContent = n > 99 ? '99+' : String(n);
    }
    for (const a of shell?.root.querySelectorAll('[aria-label^="Notifications"]') || []) {
      a.setAttribute('aria-label', n ? `Notifications, ${n} unread` : 'Notifications');
    }
  } catch { /* badge stays as it was */ }
}

async function refreshUser() {
  const me = await api('/v1/me');
  if (me) session.set(null, me);
  paintUser();
  if (shell && !shell.staff) loadStats();
  return session.user;
}

function markActive(path) {
  const top = path.split('/')[0];
  for (const a of shell.root.querySelectorAll('[data-match]')) {
    const on = a.dataset.match.split(' ').includes(top);
    a.classList.toggle('is-active', on);
    if (on) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  }
}

async function showPage({ role, module, title }, r) {
  if (!session.signedIn) {
    navigate('#/login', { replace: true });
    return;
  }
  const user = session.user;
  if ((role === 'student' && !isStudent(user)) || (role === 'staff' && !isStaff(user))) {
    navigate(homeFor(user), { replace: true });
    return;
  }
  try { authCleanup?.(); } catch { /* ignore */ }
  authCleanup = null;
  if (!shell) buildShell(user);
  teardownPage();
  closeDrawer();
  markActive(r.path);
  if (shellRefs.searchInput && r.path === 'search') shellRefs.searchInput.value = r.query.q || '';
  document.title = `${title} · BAU BUILD`;

  const seq = ++renderSeq;
  const content = shell.content;
  content.replaceChildren();
  content.className = `content page-${module.replace('/', '-')}`;
  window.scrollTo(0, 0);
  const pageRoot = h('div', { class: 'page' });
  content.append(pageRoot);
  ui.loading(pageRoot);

  let mod;
  try {
    mod = await import(`./pages/${module}.js`);
  } catch (err) {
    console.error(err);
    if (seq === renderSeq) ui.errorState(pageRoot, new ApiError({ code: 'load_failed', message: 'This page couldn’t load. Refresh to try again.' }), () => location.reload());
    return;
  }
  if (seq !== renderSeq) return;
  pageRoot.replaceChildren();
  const ctx = {
    api, sse, session, ui, toast,
    user: session.user,
    params: { ...r.query, ...r.params },
    query: r.query,
    path: r.path,
    navigate,
    refreshUser,
    refreshUnread,
    setTitle: (t) => { document.title = `${t} · BAU BUILD`; },
  };
  try {
    const cleanup = await mod.default(pageRoot, ctx);
    if (seq !== renderSeq) { if (typeof cleanup === 'function') cleanup(); return; }
    pageCleanup = typeof cleanup === 'function' ? cleanup : null;
  } catch (err) {
    console.error(err);
    if (seq === renderSeq) ui.errorState(pageRoot, err, () => showPage({ role, module, title }, currentRoute()));
  }
}

// ---------- Session lifecycle ----------
async function signOut() {
  const ok = await ui.confirmDialog({ title: 'Sign out?', body: 'You’ll need your email and password to sign back in.', confirmLabel: 'Sign out' });
  if (!ok) return;
  try { await api('/v1/auth/logout', { method: 'POST' }); } catch { /* sign out locally anyway */ }
  session.signOut('manual');
}

let wasSignedIn = session.signedIn;
session.onChange((state, reason) => {
  const now = !!state?.access_token;
  if (now && !wasSignedIn) {
    wasSignedIn = true;
    enterApp();
    return;
  }
  if (!now && wasSignedIn) {
    wasSignedIn = false;
    teardownShell();
    if (reason === 'domain_not_allowed') navigate('#/not-allowed', { replace: true });
    else {
      if (reason === 'expired') toast('Your session ended. Log in again.', 'info');
      navigate('#/login', { replace: true });
    }
    return;
  }
  if (now && shell) paintUser();
});

async function enterApp() {
  // Refresh the user so roles / names are current; fall back to the cached user when offline.
  try {
    const me = await api('/v1/me');
    if (me && session.signedIn) session.set(null, me);
  } catch (err) {
    if (!session.signedIn) return; // 401 → signed out by the client
    if (err.code === 'domain_not_allowed') { session.signOut('domain_not_allowed'); return; }
    if (!session.user) { toast(ui.errorMessage(err), 'error'); }
  }
  if (!session.signedIn) return;
  const r = currentRoute();
  const onAuth = !r.pattern || Object.keys(AUTH).some((k) => r.pattern === `#/${k}`);
  if (onAuth) navigate(homeFor(session.user), { replace: true });
  else start();
}

if (session.signedIn) {
  ui.loading(app, 'Opening BAU BUILD…');
  enterApp();
} else {
  start();
}
