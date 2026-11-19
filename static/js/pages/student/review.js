// Review: my submissions (GET /v1/me/submissions) with the timeline of the selected one.
import { h, str, items, money, relTime, pageHead, loader, emptyView, clickable, go, monoLabel, textLink, queryParam } from './_kit.js';
import { reviewStages, timelineView, aiReviewView, diffLink, statusChip, step } from './_review.js';

let cached = null;

export default async function render(root, ctx) {
  let alive = true;
  let selected = queryParam(ctx, 'id') || null;
  const body = h('div', { class: 'sp-review-body' });
  root.replaceChildren(h('div', { class: 'sp-page sp-review' }, pageHead('Review'), body));

  const board = loader(body, {
    initial: cached,
    isAlive: () => alive && body.isConnected,
    load: async () => {
      const subs = items(await ctx.api('/v1/me/submissions')).filter((s) => s && s.id != null);
      const ids = [...new Set(subs.map((s) => str(s.project_id)))];
      const known = cached?.projects || {};
      const projects = { ...known };
      await Promise.all(ids.map(async (id) => {
        try { projects[id] = await ctx.api(`/v1/projects/${encodeURIComponent(id)}`); } catch { /* keep a cached copy, or show the id */ }
      }));
      return { subs, projects };
    },
    view: (data) => {
      cached = data;
      if (!data.subs.length) {
        return emptyView({
          title: 'Nothing in review yet',
          body: 'Push a branch for a project you’re in. The AI review and your dev lead’s decision show up here.',
          action: { label: 'Go to My Work', kind: 'primary', onClick: () => go(ctx, '#/my-work') },
        });
      }
      if (!selected || !data.subs.some((s) => str(s.id) === selected)) selected = str(data.subs[0].id);
      const current = data.subs.find((s) => str(s.id) === selected);

      const listEl = h('ul', { class: 'sp-review-list', 'aria-label': 'Submissions' }, data.subs.map((s) => {
        const p = data.projects[str(s.project_id)];
        const branch = str(step(s, 'pushed')?.branch);
        const li = h('li', { class: ['sp-review-item', str(s.id) === selected && 'is-selected'], 'aria-current': str(s.id) === selected ? 'true' : null },
          h('span', { class: 'sp-strong' }, str(p?.title, 'Project')),
          h('span', { class: 'sp-mono sp-small' }, branch),
          h('span', { class: 'sp-review-item-foot' }, statusChip(s), h('span', { class: 'sp-muted sp-small' }, relTime(s.created_at))));
        return clickable(li, () => { selected = str(s.id); board.repaint(); }, `${str(p?.title, 'Project')} ${branch}`);
      }));

      const p = data.projects[str(current.project_id)] || {};
      const r = reviewStages(current, p);
      const lead = step(current, 'lead_decision');
      const detail = h('section', { class: 'sp-card sp-review-detail' },
        h('div', { class: 'sp-card-headrow' },
          h('div', {}, monoLabel(str(p.department, 'PROJECT').toUpperCase()), h('h2', { class: 'sp-card-title' }, str(p.title, 'Project'))),
          h('div', { class: 'sp-inline' }, statusChip(current), diffLink(r.diffUrl))),
        h('p', { class: 'sp-strong' }, r.headline),
        timelineView(r.stages),
        r.findings.length ? h('div', { class: 'sp-panel-section' }, h('h3', { class: 'sp-panel-h' }, 'AI findings'),
          h('ul', { class: 'sp-findings' }, r.findings.map((f) => h('li', { class: ['sp-finding', str(f?.kind) === 'pass' ? 'sp-finding--pass' : 'sp-finding--warning'] },
            h('span', {}, str(f?.text)))))) : null,
        aiReviewView(r.aiReview),
        h('div', { class: 'sp-panel-section' }, h('h3', { class: 'sp-panel-h' }, 'Lead decision'),
          h('div', { class: 'sp-rows' },
            h('div', { class: 'sp-row' }, h('span', { class: 'sp-row-label' }, 'Status'), h('span', { class: 'sp-row-value' }, statusChip(current))),
            h('div', { class: 'sp-row' }, h('span', { class: 'sp-row-label' }, 'Reviewer'), h('span', { class: 'sp-row-value' }, str(lead?.reviewer?.name, 'Not assigned yet'))),
            lead?.comment ? h('div', { class: 'sp-row' }, h('span', { class: 'sp-row-label' }, 'Comment'), h('span', { class: 'sp-row-value' }, str(lead.comment))) : null,
            h('div', { class: 'sp-row' }, h('span', { class: 'sp-row-label' }, 'Bounty'), h('span', { class: 'sp-row-value' }, money(step(current, 'bounty')?.amount ?? p.bounty))))),
        current.project_id ? h('p', { class: 'sp-small' }, textLink('Open workspace', { href: `#/my-work/${encodeURIComponent(str(current.project_id))}` })) : null);

      return h('div', { class: 'sp-review-grid' }, listEl, detail);
    },
  });

  return () => { alive = false; };
}

