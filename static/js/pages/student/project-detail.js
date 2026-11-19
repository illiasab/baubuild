// #/projects/:id: the Projects board with the project's detail panel slid in from the right.
import renderProjects, { forgetProject } from './projects.js';
import {
  h, str, num, money, difficultyLabel, minScoreLabel, dueChip, shortDate, chip, button, textLink,
  sidePanel, loadingView, errorView, eligibilityMessage, githubUrl, go, toast, errorText,
} from './_kit.js';

export default async function render(root, ctx) {
  const id = str(ctx.params?.id);
  const cleanupBoard = await renderProjects(root, ctx);
  let alive = true;

  const panel = sidePanel({ eyebrow: 'Project', label: 'Project details', onClose: () => go(ctx, '#/projects') });
  let score = null;
  ctx.api('/v1/me/stats').then((s) => { score = num(s?.score); }).catch(() => {});

  async function load() {
    panel.setBody(loadingView());
    panel.setFooter();
    try {
      const project = await ctx.api(`/v1/projects/${encodeURIComponent(id)}`);
      if (!alive) return;
      paint(project);
    } catch (err) {
      if (!alive) return;
      panel.setBody(errorView(err, load, err?.status === 404 ? 'That project is not on the board' : 'Couldn’t load this project'));
    }
  }

  function paint(project, joinError = null, joining = false) {
    const urgent = project.urgent === true;
    panel.setEyebrow(['Project', project.department].filter(Boolean).join(' · ').toUpperCase());
    const repoName = str(project.repo?.name);
    const repoLink = githubUrl(repoName);
    const due = dueChip(project.due_at);
    const reviewer = str(project.reviewer_label) || (str(project.reviewed_by) === 'professor' ? 'professor' : 'dev lead');

    panel.setBody(
      h('h2', { class: 'sp-panel-title' }, str(project.title)),
      h('div', { class: 'sp-chips' },
        urgent ? chip('Urgent', 'urgent') : null,
        chip(difficultyLabel(project.difficulty)),
        chip(minScoreLabel(project)),
        due ? chip(due) : null),
      h('dl', { class: 'sp-stats' },
        stat('Bounty', money(project.bounty)),
        stat('Difficulty', difficultyLabel(project.difficulty)),
        stat('Due', project.due_at ? shortDate(project.due_at) : 'No date'),
        stat('Min score', num(project.min_score) > 0 ? String(num(project.min_score)) : 'Any')),
      project.description ? h('section', { class: 'sp-panel-section' },
        h('h3', { class: 'sp-panel-h' }, 'About'),
        h('p', { class: 'sp-prose' }, str(project.description))) : null,
      h('section', { class: 'sp-panel-section' },
        h('h3', { class: 'sp-panel-h' }, 'Details'),
        h('div', { class: 'sp-rows' },
          row('Repository', repoName ? (repoLink ? textLink(repoName, { href: repoLink, external: true }) : h('span', { class: 'sp-mono' }, repoName)) : 'Not linked yet'),
          row('Joining', str(project.joining) === 'everyone' ? 'Everyone welcome. You’re in straight away.' : `Request to join. The ${reviewer} approves.`),
          row('Reviewer', str(project.reviewed_by) === 'professor' ? `The ${reviewer}` : `AI review, then the ${reviewer}`),
          row('Minimum score', minScoreLabel(project)))),
    );

    const mine = str(project.my_membership?.status);
    const eligible = project.eligibility?.eligible !== false;
    const everyone = str(project.joining) === 'everyone';
    if (mine === 'joined') {
      panel.setFooter(
        h('p', { class: 'sp-footnote' }, 'You’re in this project.'),
        button('Open workspace', { kind: 'primary', onClick: () => go(ctx, `#/my-work/${encodeURIComponent(project.id)}`) }));
    } else if (mine === 'pending_approval') {
      panel.setFooter(
        h('p', { class: 'sp-footnote' }, `Request sent. Waiting on the ${reviewer}.`),
        button('Request sent', { kind: 'secondary', disabled: true }));
    } else {
      const reason = eligible ? '' : eligibilityMessage(project, score);
      panel.setFooter(
        reason ? h('p', { class: 'sp-footnote sp-footnote--warn', id: 'sp-join-reason' }, reason) : null,
        joinError ? h('p', { class: 'sp-footnote sp-footnote--error', role: 'alert' }, joinError) : null,
        h('div', { class: 'sp-panel-actions' },
          button('Not now', { kind: 'ghost', onClick: () => go(ctx, '#/projects') }),
          (() => {
            const b = button(joining ? 'Joining…' : everyone ? 'I’m In' : 'Request to Join', {
              kind: 'primary', disabled: !eligible || joining, onClick: () => join(project),
            });
            if (reason) b.setAttribute('aria-describedby', 'sp-join-reason');
            return b;
          })()));
    }
  }

  async function join(project) {
    paint(project, null, true);
    try {
      const out = await ctx.api(`/v1/projects/${encodeURIComponent(project.id)}/join`, { method: 'POST' });
      if (!alive) return;
      forgetProject(project.id);
      const joined = str(out?.result) === 'joined';
      toast(ctx, joined ? 'You’re in' : 'Request sent', 'success');
      go(ctx, '#/projects');
    } catch (err) {
      if (!alive) return;
      paint(project, errorText(err), false);
    }
  }

  load();

  return () => {
    alive = false;
    panel.close();
    if (typeof cleanupBoard === 'function') cleanupBoard();
  };
}

function stat(label, value) {
  return h('div', { class: 'sp-stat' }, h('dt', {}, label), h('dd', {}, value));
}

function row(label, value) {
  return h('div', { class: 'sp-row' }, h('span', { class: 'sp-row-label' }, label), h('span', { class: 'sp-row-value' }, value));
}
