// My Work: stat cards + the projects I'm in or waiting on (GET /v1/me/work).
import { h, str, num, list, money, plural, pill, pageHead, loader, emptyView, textLink, go } from './_kit.js';

let cached = null;

export default async function render(root, ctx) {
  let alive = true;
  const body = h('div', { class: 'sp-mywork-body' });
  root.replaceChildren(h('div', { class: 'sp-page sp-mywork' }, pageHead('My Work'), body));

  const openWorkspace = (id) => go(ctx, `#/my-work/${encodeURIComponent(id)}`);
  const openProject = (id) => go(ctx, `#/projects/${encodeURIComponent(id)}`);

  loader(body, {
    initial: cached,
    isAlive: () => alive && body.isConnected,
    load: async () => {
      const work = await ctx.api('/v1/me/work');
      return {
        active: list(work?.in_progress).filter((p) => p && p.id != null),
        waiting: list(work?.awaiting_approval).filter((p) => p && p.id != null),
      };
    },
    view: (data) => {
      cached = data;
      const openTasks = data.active.reduce((sum, p) => sum + num(p.open_task_count ?? p.task_count), 0);
      const stats = h('div', { class: 'sp-statcards' },
        statCard('I’m in', plural(data.active.length, 'project')),
        statCard('Waiting for approval', plural(data.waiting.length, 'project')),
        statCard('Tasks assigned to me', String(openTasks)));

      if (!data.active.length && !data.waiting.length) {
        return [stats, emptyView({
          title: 'No projects yet',
          body: 'Pick a project on the board and tap I’m In. It shows up here with your tasks.',
          action: { label: 'Browse projects', kind: 'primary', onClick: () => go(ctx, '#/projects') },
        })];
      }

      const rows = [
        ...data.active.map((p) => {
          const n = num(p.open_task_count ?? p.task_count);
          return workRow(p, pill('I’m In', 'yellow'), n ? `${plural(n, 'task')} assigned to you` : 'No open tasks', 'Open', () => openWorkspace(p.id));
        }),
        ...data.waiting.map((p) => workRow(p, pill('In Review', 'lavender'), `Reviewing: ${str(p.reviewer_label, 'pending')}`, 'View', () => openProject(p.id))),
      ];
      const table = h('div', { class: 'table-wrap sp-table-wrap' },
        h('table', { class: 'table sp-table' },
          h('thead', {}, h('tr', {},
            h('th', { scope: 'col' }, 'Project'), h('th', { scope: 'col' }, 'From'), h('th', { scope: 'col' }, 'Status'),
            h('th', { scope: 'col' }, 'Where it stands'), h('th', { scope: 'col' }, 'Bounty'), h('th', { scope: 'col' }, h('span', { class: 'sp-sr' }, 'Action')))),
          h('tbody', {}, rows)));
      return [stats, table, h('p', { class: 'sp-footnote' }, 'Most projects let you in straight away. A few need a professor or dev lead to approve you first.')];
    },
  });

  return () => { alive = false; };
}

function statCard(label, value) {
  return h('div', { class: 'sp-statcard' }, h('span', { class: 'sp-statcard-label' }, label), h('span', { class: 'sp-statcard-value' }, value));
}

function workRow(project, status, stands, actionLabel, onOpen) {
  const tr = h('tr', {},
    h('td', { class: 'sp-strong' }, str(project.title)),
    h('td', { class: 'sp-mono' }, str(project.department)),
    h('td', {}, status),
    h('td', { class: 'sp-muted' }, stands),
    h('td', { class: 'sp-num' }, money(project.bounty)),
    h('td', { class: 'sp-right' }, textLink(actionLabel, { onClick: (e) => { e.stopPropagation(); onOpen(); } })));
  // The row is a mouse shortcut; keyboard users reach the same action through the link.
  tr.classList.add('sp-clickable');
  tr.addEventListener('click', onOpen);
  return tr;
}
