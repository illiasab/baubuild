// Staff profile (#/staff-profile): identity card + bio (PATCH /v1/me), stats from GET /v1/inquiries/mine,
// DETAILS, NOTIFICATIONS (this browser only until the backend stores preferences), senior engineers
// (GET /v1/senior-engineers; 404 → hidden; names open GET /v1/users/{id}), Sign out.
import {
  el, clear, toast, showLoading, showError, formatUsd, fetchMine, displayName, staffTitle, initialsOf,
  fetchSeniorEngineers, parseEngineer, loadMe, confirmAction,
} from './shared.js';

const NOTIFY = [
  { key: 'projectDone', label: 'When a project is done' },
  { key: 'studentJoins', label: 'When a student joins' },
];

export default async function render(root, ctx) {
  let alive = true;
  const prefsKey = () => `bau.staff.notify.${ctx.user?.id || 'me'}`;

  async function load() {
    showLoading(root, ctx, 'Loading your profile…');
    let me;
    let mine;
    try {
      [me, mine] = await Promise.all([loadMe(ctx), fetchMine(ctx.api)]);
    } catch (err) {
      if (alive) showError(root, ctx, err, load);
      return;
    }
    const engineers = await fetchSeniorEngineers(ctx.api);
    if (alive) draw(me || {}, mine, engineers);
  }

  function draw(me, mine, engineers) {
    clear(root);
    const page = el('div', { class: 'st-page' });
    root.append(page);
    page.append(el('div', { class: 'page-head st-head' }, el('h1', { class: 'st-h1' }, 'Profile')));

    const subtitle = [staffTitle(me), me.department].filter((s) => s && String(s).trim()).join(' · ');
    const bioText = el('p', { class: me.bio ? 'st-bio' : 'st-bio st-muted' }, me.bio || 'No bio yet. Add a line so students know who posts the work.');
    page.append(el('section', { class: 'st-card st-identity' },
      el('span', { class: 'st-avatar', 'aria-hidden': 'true' }, initialsOf(me)),
      el('div', { class: 'st-identity-text' },
        el('h2', { class: 'st-h2 st-h2--lg' }, displayName(me) || me.email || 'Staff member'),
        subtitle ? el('p', { class: 'st-muted' }, subtitle) : null,
        bioText),
      el('button', { type: 'button', class: 'st-btn st-btn--outline', onclick: () => editBio(me) }, 'Edit profile')));

    const delivered = mine.filter((i) => i.status === 'Done');
    page.append(el('div', { class: 'st-stats st-stats--3' },
      stat('Projects posted', String(mine.length)),
      stat('Delivered', String(delivered.length)),
      stat('Raised money', formatUsd(delivered.reduce((a, i) => a + (i.draft.priceUsd || 0), 0)))));

    const classes = (Array.isArray(me.classes) ? me.classes : []).filter((c) => String(c).trim());
    const details = el('section', { class: 'st-card st-kv-card' },
      el('div', { class: 'st-strip' }, 'DETAILS'),
      kv('Department', me.department || '—'),
      kv('Role', staffTitle(me)),
      kv('Classes', classes.length ? classes.join(', ') : 'No classes'),
      me.email ? kv('Email', me.email) : null);

    const prefs = loadPrefs();
    const notifications = el('section', { class: 'st-card st-kv-card' },
      el('div', { class: 'st-strip' }, 'NOTIFICATIONS'),
      NOTIFY.map((n) => {
        const sw = el('button', {
          type: 'button', class: 'st-switch', role: 'switch', 'aria-checked': String(prefs[n.key] !== false), 'aria-label': n.label,
          onclick: () => {
            prefs[n.key] = !(prefs[n.key] !== false);
            sw.setAttribute('aria-checked', String(prefs[n.key]));
            savePrefs(prefs);
          },
        }, el('span', { class: 'st-switch-knob' }));
        return el('div', { class: 'st-kv' }, el('span', {}, n.label), sw);
      }),
      el('p', { class: 'st-small st-muted st-kv-note' }, 'Saved in this browser only for now. Email and push settings are coming to your account.'),
      engineers && engineers.length ? el('div', { class: 'st-kv' }, el('span', {}, 'Senior engineers notified'),
        el('span', { class: 'st-engineers' }, engineers.map((e, i) => [
          i ? ', ' : null,
          el('button', { type: 'button', class: 'st-text-link', onclick: () => openEngineer(e) }, e.name.split(' ')[0]),
        ]))) : null);

    page.append(el('div', { class: 'st-two-col st-two-col--even' }, details, notifications));

    if (engineers && engineers.length) {
      page.append(el('section', { class: 'st-card st-engineers-card', 'aria-labelledby': 'st-eng' },
        el('h2', { class: 'st-h2', id: 'st-eng' }, 'Senior engineers'),
        el('p', { class: 'st-muted' }, 'They review delivered work and are notified when a project is done.'),
        el('div', { class: 'st-eng-list' }, engineers.map((e) => el('button', { type: 'button', class: 'st-eng', onclick: () => openEngineer(e) },
          el('span', { class: 'st-avatar st-avatar--sm', 'aria-hidden': 'true' }, initialsOf(e)),
          el('span', { class: 'st-eng-text' }, el('strong', {}, e.name), e.title ? el('span', { class: 'st-muted st-small' }, e.title) : null))))));
    }

    const signOutBtn = el('button', { type: 'button', class: 'st-btn st-btn--outline st-signout', onclick: () => signOut(signOutBtn) }, 'Sign out');
    page.append(el('div', { class: 'st-footer st-footer--start' }, signOutBtn));
  }

  function loadPrefs() {
    try { return JSON.parse(localStorage.getItem(prefsKey()) || '{}') || {}; } catch { return {}; }
  }
  function savePrefs(prefs) {
    try { localStorage.setItem(prefsKey(), JSON.stringify(prefs)); } catch { toast(ctx, 'This browser blocks local storage, so the setting won’t stick.', 'warning'); }
  }

  function editBio(me) {
    const area = el('textarea', { class: 'st-input st-textarea', maxlength: 280, rows: 4, value: me.bio || '', autofocus: true, 'aria-label': 'Bio' });
    const count = el('span', { class: 'st-hint' }, `${(me.bio || '').length}/280`);
    const error = el('span', { class: 'st-field-error', role: 'alert', hidden: true });
    area.addEventListener('input', () => { count.textContent = `${area.value.length}/280`; });
    const body = el('div', { class: 'st-field' }, el('label', { class: 'st-label' }, 'Bio'), area, count, error,
      el('p', { class: 'st-small st-muted' }, 'Name, department and classes come from your BAU account.'));
    ctx.ui.modal({
      title: 'Edit profile',
      body,
      actions: [
        { label: 'Cancel', kind: 'secondary' },
        {
          label: 'Save',
          kind: 'primary',
          onClick: async () => {
            const bio = area.value.trim();
            if (!bio || bio.length > 280) {
              error.textContent = 'Bio must be 1 to 280 characters.';
              error.hidden = false;
              return false;
            }
            try {
              const updated = await ctx.api('/v1/me', { method: 'PATCH', body: { bio } });
              try { ctx.session?.set?.(null, updated); } catch { /* shell keeps its own copy */ }
              try { await ctx.refreshUser?.(); } catch { /* optional */ }
              toast(ctx, 'Profile saved.', 'success');
              if (alive) load();
              return true;
            } catch (err) {
              error.textContent = err?.message || 'Couldn’t save. Try again.';
              error.hidden = false;
              return false;
            }
          },
        },
      ],
    });
  }

  function openEngineer(engineer) {
    const host = el('div', { class: 'st-eng-bio' });
    const paint = (e, note) => {
      clear(host);
      host.append(
        el('div', { class: 'st-eng-bio-head' }, el('span', { class: 'st-avatar', 'aria-hidden': 'true' }, initialsOf(e)),
          el('div', {}, el('strong', { class: 'st-h3' }, e.name), e.title ? el('p', { class: 'st-muted' }, e.title) : null,
            e.department ? el('p', { class: 'st-muted st-small' }, e.department) : null)),
        el('p', { class: e.bio ? '' : 'st-muted' }, e.bio || 'No bio yet.'),
        e.skills.length ? el('div', { class: 'st-pills' }, e.skills.map((s) => el('span', { class: 'st-pill' }, s))) : null,
        e.github ? el('a', { class: 'st-link', href: `https://github.com/${encodeURIComponent(e.github)}`, target: '_blank', rel: 'noopener noreferrer' }, `github.com/${e.github}`) : null,
        e.email ? el('a', { class: 'st-link', href: `mailto:${e.email}` }, e.email) : null,
        note ? el('p', { class: 'st-small st-muted' }, note) : null);
    };
    paint(engineer, 'Loading the full profile…');
    ctx.ui.modal({ title: engineer.name, body: host, actions: [{ label: 'Close', kind: 'secondary' }] });
    ctx.api(`/v1/users/${encodeURIComponent(engineer.id)}`)
      .then((res) => { if (host.isConnected) paint(parseEngineer(res?.user || res, engineer) || engineer); })
      .catch((err) => { if (host.isConnected) paint(engineer, err?.status === 404 ? 'The full profile isn’t available.' : 'Couldn’t load the full profile.'); });
  }

  async function signOut(btn) {
    const ok = await confirmAction(ctx, { title: 'Sign out?', body: 'You’ll need your email and password to sign back in.', confirmLabel: 'Sign out' });
    if (!ok) return;
    btn.disabled = true;
    try { await ctx.api('/v1/auth/logout', { method: 'POST' }); } catch { /* sign out locally anyway */ }
    // The shell listens to the session and takes the user to the login page.
    ctx.session?.signOut?.('manual');
  }

  await load();
  return () => { alive = false; };
}

function stat(label, value) {
  return el('div', { class: 'st-stat' }, el('span', { class: 'st-stat-label' }, label), el('span', { class: 'st-stat-value' }, value));
}

function kv(label, value) {
  return el('div', { class: 'st-kv' }, el('span', { class: 'st-muted' }, label), el('span', { class: 'st-kv-value' }, value));
}
