// Search: GET /v1/search?q= → projects, classes, published inquiries.
import { h, str, num, list, money, difficultyLabel, minScoreLabel, pageHead, loader, emptyView, clickable, go, queryParam, chip, plural } from './_kit.js';

export default async function render(root, ctx) {
  let alive = true;
  const q = queryParam(ctx, 'q').trim();
  const input = h('input', { type: 'search', class: 'input sp-input', name: 'q', value: q, placeholder: 'Search projects, classes and requests', 'aria-label': 'Search' });
  const form = h('form', { class: 'sp-searchform', role: 'search' }, input, h('button', { type: 'submit', class: 'btn btn-primary' }, 'Search'));
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const next = input.value.trim();
    if (next) go(ctx, `#/search?q=${encodeURIComponent(next)}`);
  });
  const body = h('div', { class: 'sp-search-body' });
  root.replaceChildren(h('div', { class: 'sp-page sp-search' }, pageHead('Search'), form, body));

  if (!q) {
    body.replaceChildren(emptyView({ title: 'Search BAU BUILD', body: 'Find open projects, your classes and staff requests by title, department or course code.' }));
    requestAnimationFrame(() => input.focus());
    return () => { alive = false; };
  }

  loader(body, {
    isAlive: () => alive && body.isConnected,
    load: () => ctx.api(`/v1/search?q=${encodeURIComponent(q)}`),
    view: (out) => {
      const projects = list(out?.projects);
      const classes = list(out?.classes);
      const inquiries = list(out?.inquiries).filter((i) => i?.project_id || i?.id);
      const total = projects.length + classes.length + inquiries.length;
      if (!total) return emptyView({ title: `No results for “${q}”`, body: 'Try a shorter word, a department, or a course code like CMPS433.' });
      const section = (label, rows) => (rows.length ? h('section', { class: 'sp-card sp-card--flush' },
        h('div', { class: 'sp-card-strip' }, `${label.toUpperCase()} · ${rows.length}`), rows) : null);
      return [
        h('p', { class: 'sp-muted' }, `${plural(total, 'result')} for “${q}”`),
        section('Projects', projects.map((p) => resultRow(
          str(p.title), [str(p.department), difficultyLabel(p.difficulty), minScoreLabel(p)].filter(Boolean).join(' · '), money(p.bounty),
          () => go(ctx, `#/projects/${encodeURIComponent(p.id)}`), p.urgent ? chip('Urgent', 'urgent') : null))),
        section('Classes', classes.map((c) => resultRow(
          `${str(c.code)} · ${str(c.name)}`, [str(c.instructor), str(c.term)].filter(Boolean).join(' · '),
          plural(num(c.open_project_count), 'open project'), null))),
        section('Requests', inquiries.map((i) => resultRow(
          str(i.title), [str(i.department), difficultyLabel(i.difficulty)].filter(Boolean).join(' · '), money(i.bounty),
          i.project_id ? () => go(ctx, `#/projects/${encodeURIComponent(i.project_id)}`) : null, i.is_urgent ? chip('Urgent', 'urgent') : null))),
      ];
    },
  });

  return () => { alive = false; };
}

function resultRow(title, meta, aside, onOpen, extra) {
  const el = h('div', { class: 'sp-result' },
    h('div', { class: 'sp-task-text' }, h('span', { class: 'sp-task-title' }, title, extra ? ' ' : null, extra), meta ? h('span', { class: 'sp-task-sub' }, meta) : null),
    aside ? h('span', { class: 'sp-mono sp-result-aside' }, aside) : null);
  return onOpen ? clickable(el, onOpen, `Open ${title}`) : el;
}

