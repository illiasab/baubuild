// Profile: identity, professional rows, GitHub, Become a Lead, score card, earnings, sign out.
import {
  h, str, num, list, money, capital, button, textLink, pageHead, loader, modal, toast, confirmAction, errorText,
  monoLabel, cardStrip, safeUrl, icon, githubEnabled, GITHUB_OFF_NOTE,
} from './_kit.js';

const TIERS = [
  { name: 'rookie', min: 0 }, { name: 'builder', min: 100 }, { name: 'skilled', min: 200 }, { name: 'expert', min: 300 }, { name: 'honored', min: 400 },
];
const LEAD_STAGES = {
  exam: ['Proctored exam', 'A short coding and analytical-thinking exam, taken with a proctor.'],
  in_person: ['In-person stage', 'Pass the exam, then meet Ylyas, Tech Lead Engineer, in person.'],
  lead_status: ['Lead status', 'Your role changes to Lead and you can approve or send back code.'],
};
const ROLE_LABEL = { student: 'Student', lead: 'Lead', staff: 'Staff', admin: 'Admin' };

let cached = null;

export default async function render(root, ctx) {
  let alive = true;
  let leadNotice = null;
  const body = h('div', { class: 'sp-profile-body' });
  root.replaceChildren(h('div', { class: 'sp-page sp-profile' }, pageHead('Profile'), body));

  const board = loader(body, {
    initial: cached,
    isAlive: () => alive && body.isConnected,
    load: async () => {
      const ghOn = await githubEnabled(ctx);
      const [me, stats, github, lead] = await Promise.all([
        ctx.api('/v1/me'),
        ctx.api('/v1/me/stats').catch(() => cached?.stats ?? null),
        ghOn ? ctx.api('/v1/integrations/github').catch(() => cached?.github ?? null) : null,
        ctx.api('/v1/me/lead-application').catch(() => cached?.lead ?? null),
      ]);
      return { me, stats, github, lead, ghOn };
    },
    view: (data) => {
      cached = data;
      const { me, stats, github, lead, ghOn } = data;
      const role = str(me?.role, 'student');
      const left = h('div', { class: 'sp-profile-main' },
        identityCard(me, () => editProfile(me)),
        professionalCard(me, github, ghOn, connectGitHub, () => manageGitHub(github)),
        role === 'student' || role === 'admin' ? leadCard(lead, leadNotice, startExam) : null,
        h('div', { class: 'sp-signout' }, button('Sign Out', { kind: 'danger', onClick: signOut })));
      const right = h('div', { class: 'sp-profile-side' },
        scoreCard(stats),
        h('div', { class: 'sp-statcards sp-statcards--2' },
          statCard('My earnings', stats ? money(stats.earnings_total) : '—'),
          statCard('Approved projects', stats ? String(num(stats.approved_project_count)) : '—')));
      return h('div', { class: 'sp-profile-grid' }, left, right);
    },
  });

  function editProfile(me) {
    const name = h('input', { id: 'sp-name', class: 'input sp-input', maxlength: '80', autocomplete: 'name', value: str(me?.display_name ?? me?.name) });
    const gh = h('input', { id: 'sp-gh', class: 'input sp-input sp-mono', maxlength: '40', autocomplete: 'off', spellcheck: 'false', placeholder: 'octocat', value: str(me?.github_username) });
    const bio = h('textarea', { id: 'sp-bio', class: 'textarea sp-input', rows: '4', maxlength: '280', value: str(me?.bio) });
    const skills = h('input', { id: 'sp-skills', class: 'input sp-input', value: list(me?.skills).join(', '), placeholder: 'JavaScript, Python' });
    const count = h('span', { class: 'sp-small sp-muted' });
    const err = h('p', { class: 'sp-footnote sp-footnote--error', role: 'alert', hidden: true });
    const sync = () => { count.textContent = `${bio.value.length} / 280`; };
    bio.addEventListener('input', sync);
    sync();
    const save = button('Save', { kind: 'primary' });
    const dialog = modal({
      title: 'Edit profile',
      body: h('div', { class: 'sp-form' },
        h('label', { class: 'sp-field-label', for: 'sp-name' }, 'Name'), name,
        h('label', { class: 'sp-field-label', for: 'sp-bio' }, 'Bio'), bio, count,
        h('label', { class: 'sp-field-label', for: 'sp-skills' }, 'Skills'), skills,
        h('span', { class: 'sp-small sp-muted' }, 'Separate skills with commas. Up to 20.'),
        h('label', { class: 'sp-field-label', for: 'sp-gh' }, 'GitHub username'), gh,
        err),
      actions: [button('Cancel', { kind: 'ghost', onClick: () => dialog.close() }), save],
    });
    save.addEventListener('click', async () => {
      const text = bio.value.trim();
      const displayName = name.value.trim().replace(/\s+/g, ' ');
      const ghName = gh.value.trim().replace(/^@/, '');
      const list_ = [...new Set(skills.value.split(',').map((s) => s.trim()).filter(Boolean))].slice(0, 20);
      err.hidden = true;
      const fail = (msg, el) => { err.textContent = msg; err.hidden = false; el.focus(); };
      if (!displayName) { fail('Enter your name.', name); return; }
      if (!list_.length && list(me?.skills).length) { fail('Add at least one skill.', skills); return; }
      if (ghName && !/^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/.test(ghName)) { fail('That isn’t a valid GitHub username.', gh); return; }
      save.disabled = true;
      save.textContent = 'Saving…';
      try {
        const sent = { display_name: displayName, bio: text, github_username: ghName || null };
        if (list_.length) sent.skills = list_;
        const updated = await ctx.api('/v1/me', { method: 'PATCH', body: sent });
        dialog.close();
        toast(ctx, 'Profile saved', 'success');
        const local = { ...sent, name: displayName };
        if (alive && board.data) board.data = { ...board.data, me: { ...board.data.me, ...(updated && typeof updated === 'object' ? updated : local) } };
        try { await ctx.refreshUser?.(); } catch { /* the page already shows the saved values */ }
      } catch (e) {
        err.textContent = errorText(e);
        err.hidden = false;
        save.disabled = false;
        save.textContent = 'Save';
      }
    });
  }

  async function connectGitHub() {
    try {
      const out = await ctx.api('/v1/integrations/github/connect', { method: 'POST' });
      const url = safeUrl(out?.authorize_url);
      if (!url) throw new Error('GitHub didn’t return a sign-in link.');
      window.open(url, '_blank', 'noopener');
      toast(ctx, 'Finish connecting in the GitHub tab, then come back here.');
    } catch (e) {
      toast(ctx, e?.code === 'github_not_configured' ? 'GitHub sign-in isn’t set up on this server yet.' : errorText(e), 'error');
    }
  }

  async function manageGitHub(github) {
    const ok = await confirmAction(ctx, {
      title: 'Disconnect GitHub?',
      body: `BAU BUILD will stop seeing pushes from @${str(github?.login, 'your account')}. You can connect again any time.`,
      confirmLabel: 'Disconnect',
      danger: true,
    });
    if (!ok) return;
    try {
      const out = await ctx.api('/v1/integrations/github', { method: 'DELETE' });
      toast(ctx, 'GitHub disconnected');
      if (alive && board.data) board.data = { ...board.data, github: out || { connected: false, login: null } };
    } catch (e) {
      toast(ctx, errorText(e), 'error');
    }
  }

  async function startExam() {
    try {
      const lead = await ctx.api('/v1/me/lead-application', { method: 'POST' });
      leadNotice = 'Exam booking opens soon. We’ll let you know when a proctor slot is available.';
      if (alive && board.data) board.data = { ...board.data, lead: lead || board.data.lead };
    } catch (e) {
      toast(ctx, errorText(e), 'error');
    }
  }

  async function signOut() {
    const ok = await confirmAction(ctx, { title: 'Sign out of BAU BUILD?', confirmLabel: 'Sign Out', danger: true });
    if (!ok) return;
    try { await ctx.api('/v1/auth/logout', { method: 'POST' }); } catch { /* sign out locally anyway */ }
    cached = null;
    ctx.session?.signOut?.('manual');
  }

  return () => { alive = false; };
}

function identityCard(me, onEdit) {
  const initials = str(me?.initials) || str(me?.name).split(/\s+/).filter(Boolean).map((p) => p[0]).slice(0, 2).join('').toUpperCase();
  return h('section', { class: 'sp-card sp-identity' },
    h('span', { class: 'sp-avatar', 'aria-hidden': 'true' }, initials || '?'),
    h('div', { class: 'sp-identity-text' },
      h('h2', { class: 'sp-identity-name' }, str(me?.display_name || me?.name, 'Student')),
      me?.bio ? h('p', { class: 'sp-muted' }, str(me.bio)) : h('p', { class: 'sp-faint' }, 'No bio yet. Tell leads what you like to build.'),
      me?.email ? h('p', { class: 'sp-small sp-mono sp-faint' }, str(me.email)) : null),
    button('Edit profile', { kind: 'secondary', onClick: onEdit }));
}

function professionalCard(me, github, ghOn, onConnect, onManage) {
  const skills = list(me?.skills).join(', ') || 'None yet';
  const classes = list(me?.classes).map((c) => (typeof c === 'string' ? c : str(c?.code ?? c?.name))).filter(Boolean).join(', ') || 'None yet';
  const connected = github?.connected === true;
  const login = str(github?.login) || str(me?.github_username);
  const ghValue = !ghOn
    ? h('span', { class: 'sp-gh-off' }, login ? h('span', { class: 'sp-mono' }, `@${login}`) : null, h('span', { class: 'sp-small sp-muted' }, GITHUB_OFF_NOTE))
    : connected
      ? h('span', { class: 'sp-inline' }, login ? `Connected · @${login}` : 'Connected', textLink('Manage', { onClick: onManage }))
      : h('span', { class: 'sp-inline' }, login ? `@${login} · Not connected` : 'Not connected', textLink('Connect', { onClick: onConnect }));
  const row = (label, value) => h('div', { class: 'sp-row sp-row--pro' }, h('span', { class: 'sp-row-label sp-strong' }, label), h('span', { class: 'sp-row-value' }, value));
  return h('section', { class: 'sp-card sp-card--flush' }, cardStrip('PROFESSIONAL'),
    row('Skills', skills), row('Classes', classes), row('GitHub', ghValue), row('Role', ROLE_LABEL[str(me?.role)] || capital(me?.role) || 'Student'));
}

function leadCard(lead, notice, onStart) {
  const stages = list(lead?.stages).length ? list(lead.stages) : [{ id: 'exam' }, { id: 'in_person' }, { id: 'lead_status' }];
  const current = str(lead?.current);
  const started = lead?.started === true;
  const isLead = stages.find((s) => s.id === 'lead_status')?.done === true;
  if (isLead) return null;
  return h('section', { class: 'sp-card sp-lead' },
    h('div', { class: 'sp-card-headrow' },
      h('div', {}, h('h2', { class: 'sp-card-title sp-display' }, 'Become a Lead'),
        h('p', { class: 'sp-muted' }, 'Leads review student code and decide what gets approved.')),
      started
        ? button(current === 'in_person' ? 'Exam passed' : 'Exam requested', { kind: 'secondary', disabled: true })
        : button('Start proctored exam', { kind: 'primary', onClick: onStart })),
    h('ol', { class: 'sp-lead-stages' }, stages.map((s, i) => {
      const [title, detail] = LEAD_STAGES[str(s.id)] || [capital(s.id), ''];
      const state = s.done ? 'done' : current === str(s.id) ? 'current' : '';
      return h('li', { class: ['sp-lead-stage', state && `is-${state}`] },
        h('span', { class: 'sp-lead-num' }, s.done ? icon('check', 14) : String(i + 1).padStart(2, '0'), s.done ? h('span', { class: 'sp-sr' }, 'Done: ') : null),
        h('span', { class: 'sp-strong' }, title),
        h('span', { class: 'sp-small sp-muted' }, detail));
    })),
    notice ? h('p', { class: 'sp-footnote sp-footnote--info', role: 'status' }, notice) : null);
}

function scoreCard(stats) {
  const max = num(stats?.max, 500) || 500;
  const score = Math.max(0, Math.min(max, num(stats?.score)));
  const tiers = list(stats?.tiers).length ? list(stats.tiers).map((t) => ({ name: str(t.name), min: num(t.min) })) : TIERS;
  const status = str(stats?.status) || [...tiers].reverse().find((t) => score >= t.min)?.name || 'rookie';
  const bar = h('div', { class: 'sp-tierbar', role: 'img', 'aria-label': `Score ${score} of ${max}. My status: ${capital(status)}` },
    tiers.map((t, i) => {
      const end = tiers[i + 1]?.min ?? max;
      return h('span', { class: ['sp-tier', `sp-tier--${t.name}`, t.name === status && 'is-current'], style: { flexGrow: String(Math.max(1, end - t.min)) } }, t.name.toUpperCase());
    }),
    h('span', { class: 'sp-tier-marker', style: { left: `${(score / max) * 100}%` } }));
  const ticks = h('div', { class: 'sp-tier-ticks', 'aria-hidden': 'true' },
    [...tiers.map((t) => t.min), max].map((n) => h('span', { style: { left: `${(n / max) * 100}%` } }, String(n))));
  return h('section', { class: 'sp-scorecard' },
    monoLabel('BAU BUILD SCORE', 'sp-on-dark'),
    bar, ticks,
    h('div', { class: 'sp-score-row' },
      h('div', {}, h('span', { class: 'sp-score' }, stats ? String(score) : '—'), h('span', { class: 'sp-score-max' }, ` / ${max}`)),
      h('div', { class: 'sp-score-status' }, h('span', { class: 'sp-small' }, 'My status'), h('span', { class: 'sp-strong' }, capital(status)))),
    h('p', { class: 'sp-score-note' }, 'Hard projects can ask for a minimum score. Many stay open to everyone, so you can take one on and earn a status raise.'));
}

function statCard(label, value) {
  return h('div', { class: 'sp-statcard' }, h('span', { class: 'sp-statcard-label' }, label), h('span', { class: 'sp-statcard-value' }, value));
}
