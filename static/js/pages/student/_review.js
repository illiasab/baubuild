// Review timeline built from a submission's `steps` (GET /v1/me/submissions items), shared by Review and Workspace.
import { h, icon, str, num, list, money, capital, chip, safeUrl, textLink } from './_kit.js';

export function step(submission, name) {
  return list(submission?.steps).find((s) => str(s?.step) === name) || null;
}

export function leadStatus(submission) {
  return str(step(submission, 'lead_decision')?.status, 'pending');
}

export function statusChip(submission) {
  const status = leadStatus(submission);
  if (status === 'approved') return chip('Approved', 'green');
  if (status === 'sent_back') return chip('Changes requested', 'warn');
  return chip('With the reviewer', 'lavender');
}

/** Four stages: pushed → AI review → lead decision → bounty. state: done | active | pending. */
export function reviewStages(submission, project) {
  const pushed = step(submission, 'pushed');
  const ai = step(submission, 'ai_review');
  const lead = step(submission, 'lead_decision');
  const bounty = step(submission, 'bounty');
  const decider = str(project?.reviewed_by) === 'professor' ? 'Professor' : 'Dev Lead';
  const deciderLower = decider.toLowerCase();
  const amount = bounty?.amount != null ? num(bounty.amount) : num(project?.bounty);
  const paid = !!bounty?.paid_at;
  const reviewerName = str(lead?.reviewer?.name);
  const findings = list(ai?.findings);
  const passes = findings.filter((f) => str(f?.kind) === 'pass').map((f) => str(f.text));
  const warnings = findings.filter((f) => str(f?.kind) !== 'pass').map((f) => str(f?.text)).filter(Boolean);
  const aiDone = !!ai && (ai.status == null || str(ai.status) === 'complete');
  const comment = str(lead?.comment);
  const branch = str(pushed?.branch);

  let leadStage;
  if (str(lead?.status) === 'approved') {
    leadStage = { title: `${decider} approved`, detail: [reviewerName && `Approved by ${reviewerName}.`, comment].filter(Boolean).join(' '), state: 'done' };
  } else if (str(lead?.status) === 'sent_back') {
    leadStage = {
      title: 'Changes requested',
      detail: reviewerName ? `${reviewerName} sent the branch back.` : `The ${deciderLower} sent the branch back with notes.`,
      note: comment || null,
      state: 'active',
    };
  } else {
    leadStage = { title: `${decider} is deciding`, detail: `A vetted ${deciderLower} is reading the AI summary and your diff.`, state: aiDone ? 'active' : 'pending' };
  }

  const stages = [
    { title: 'Pushed to GitHub', detail: branch ? `Your latest commit is on ${branch}.` : 'Your branch reached BAU BUILD.', state: 'done', mono: branch },
    {
      title: aiDone ? 'AI review complete' : 'AI review',
      detail: passes.join(' ') || (aiDone ? 'No blocking findings.' : 'The AI review is running.'),
      note: warnings.join(' ') || null,
      state: aiDone ? 'done' : 'active',
    },
    leadStage,
    paid
      ? { title: 'Bounty and record', detail: `${money(amount)} was added to your record and your score.`, state: 'done' }
      : { title: 'Bounty and record', detail: `${money(amount)} is paid and the project joins your verified record once approved.`, state: 'pending' },
  ];

  let headline;
  if (str(lead?.status) === 'approved') headline = paid ? `Approved. ${money(amount)} was added to your record.` : 'Approved. The bounty follows once every change is approved.';
  else if (str(lead?.status) === 'sent_back') headline = 'Your branch was sent back with notes.';
  else headline = `Your fix is with the ${deciderLower}.`;

  return { stages, headline, branch, diffUrl: safeUrl(submission?.diff_url), aiReview: ai?.ai_review || null, findings };
}

export function timelineView(stages) {
  return h('ol', { class: 'sp-timeline' }, stages.map((s) => h('li', { class: ['sp-tl', `sp-tl--${s.state}`] },
    h('span', { class: 'sp-tl-dot', 'aria-hidden': 'true' }, s.state === 'done' ? icon('check', 14) : null),
    h('div', { class: 'sp-tl-text' },
      h('span', { class: 'sp-tl-title' }, s.title, h('span', { class: 'sp-sr' }, ` (${s.state === 'done' ? 'done' : s.state === 'active' ? 'in progress' : 'not started'})`)),
      s.detail ? h('span', { class: 'sp-tl-detail' }, s.detail) : null,
      s.note ? h('span', { class: 'sp-tl-note' }, s.note) : null))));
}

/** The latest AI review (reviews table): verdict, summary, findings with rule ids. */
export function aiReviewView(review) {
  if (!review || typeof review !== 'object') return null;
  const verdict = str(review.verdict);
  const findings = list(review.findings);
  const criteria = list(review.criteria);
  return h('div', { class: 'sp-aireview' },
    h('div', { class: 'sp-aireview-head' },
      h('span', { class: 'sp-mono-label' }, 'AI REVIEW'),
      verdict ? chip(capital(verdict), verdict === 'looks_good' ? 'green' : verdict === 'blocking' ? 'danger' : 'warn') : null),
    review.summary ? h('p', { class: 'sp-prose' }, str(review.summary)) : null,
    criteria.length ? h('ul', { class: 'sp-checklist' }, criteria.map((c) => h('li', { class: c?.met ? 'is-met' : '' },
      h('span', { class: 'sp-check', 'aria-hidden': 'true' }, c?.met ? icon('check', 12) : null),
      h('span', {}, str(c?.criterion), c?.met ? h('span', { class: 'sp-sr' }, ' (met)') : h('span', { class: 'sp-sr' }, ' (not met yet)'))))) : null,
    findings.length ? h('ul', { class: 'sp-findings' }, findings.map((f) => h('li', { class: ['sp-finding', `sp-finding--${str(f?.severity, 'info')}`] },
      h('div', { class: 'sp-finding-head' },
        f?.rule_id ? h('span', { class: 'sp-mono' }, str(f.rule_id)) : null,
        h('span', { class: 'sp-strong' }, str(f?.title)),
        f?.severity ? chip(capital(f.severity), f.severity === 'blocking' ? 'danger' : f.severity === 'warning' ? 'warn' : '') : null),
      f?.explanation ? h('p', { class: 'sp-muted' }, str(f.explanation)) : null,
      f?.file ? h('span', { class: 'sp-mono sp-small' }, `${str(f.file)}${num(f.line) ? `:${num(f.line)}` : ''}`) : null,
      f?.hint ? h('p', { class: 'sp-small' }, str(f.hint)) : null))) : null);
}

export function diffLink(url) {
  return url ? textLink('View diff', { href: url, external: true }) : null;
}
