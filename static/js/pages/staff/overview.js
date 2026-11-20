// Staff Overview (#/overview): greeting, stat cards, blocker banner, recent inquiries — or, with no inquiries yet,
// "Post your first inquiry" and real-world project ideas (GET /v1/world-projects; 404 → hidden).
// Numbers come from GET /v1/staff/overview; an older server (404) gets them computed from GET /v1/inquiries/mine.
import {
  el, clear, icon, go, showLoading, showError, formatUsd, fetchAll, fetchOverview, staffGreeting, statusChip,
  urgentChip, isUrgent, hasOpenBlocker, projectForLabel, inquiryHref, projectTypeOf, portalTypes, loadMe,
} from './shared.js';

export default async function render(root, ctx) {
  let alive = true;

  async function load() {
    showLoading(root, ctx, 'Loading your overview…');
    let list;
    let me = ctx.user;
    try {
      [list, me] = await Promise.all([fetchAll(ctx), loadMe(ctx).catch(() => ctx.user)]);
    } catch (err) {
      if (alive) showError(root, ctx, err, load);
      return;
    }
    if (!alive) return;
    const [stats, world] = await Promise.all([fetchOverview(ctx, list), list.length ? [] : worldProjects(ctx)]);
    if (!alive) return;
    draw(list, me, world, stats);
  }

  function draw(list, me, world, stats) {
    clear(root);
    const page = el('div', { class: 'st-page' });
    root.append(page);
    const greeting = staffGreeting(me);
    page.append(el('div', { class: 'page-head st-head' },
      el('div', { class: 'st-head-titles' },
        greeting ? el('span', { class: 'st-eyebrow' }, greeting) : null,
        el('h1', { class: 'st-h1' }, 'Overview')),
      el('a', { href: '#/new-inquiry', class: 'st-btn st-btn--yellow' }, icon('plus', 16), 'New inquiry')));

    page.append(el('div', { class: 'st-stats' },
      stat('Raised money · this semester', formatUsd(stats.raisedUsd)),
      stat('Wasted budget · this semester', formatUsd(stats.wastedUsd), stats.wastedUsd > 0 ? 'warn' : null),
      stat('Open inquiries', String(stats.openCount)),
      stat('Delivered', String(stats.deliveredCount))));

    if (stats.needsAttention) {
      const inq = stats.needsAttention;
      page.append(el('div', { class: 'st-attention', role: 'status' },
        el('span', { class: 'st-attention-label' }, icon('alert', 18), 'Needs your attention'),
        el('span', { class: 'st-attention-text' }, el('strong', {}, inq.draft.title), ` is blocked: ${inq.progress.blocker.text || 'the team raised a blocker.'}`),
        el('a', { href: inquiryHref(inq), class: 'st-btn st-btn--dark' }, 'Open inquiry')));
    }

    if (!list.length) {
      page.append(el('div', { class: 'st-card st-first' },
        el('div', { class: 'st-first-text' },
          el('h2', { class: 'st-h2' }, 'Post your first inquiry'),
          el('p', { class: 'st-muted' }, 'Describe something you need built. AI tidies the wording, then students pick it up and you follow it day by day.')),
        el('a', { href: '#/new-inquiry', class: 'st-btn st-btn--yellow' }, 'New inquiry')));
      if (world.length) page.append(worldGrid(world));
      return;
    }

    const recent = list.slice(0, 5);
    const tbody = el('tbody', {}, recent.map((inq) => row(inq)));
    page.append(el('section', { class: 'st-card st-table-card', 'aria-labelledby': 'st-recent' },
      el('div', { class: 'st-card-head' },
        el('h2', { class: 'st-h2', id: 'st-recent' }, 'Recent inquiries'),
        el('a', { href: '#/inquiries', class: 'st-link' }, 'See all')),
      el('div', { class: 'st-table-wrap' },
        el('table', { class: 'st-table' },
          el('thead', {}, el('tr', {},
            el('th', { scope: 'col' }, 'INQUIRY'), el('th', { scope: 'col' }, 'PROJECT FOR'), el('th', { scope: 'col' }, 'STATUS'),
            el('th', { scope: 'col' }, 'TODAY'), el('th', { scope: 'col', class: 'st-col-open' }, el('span', { class: 'st-sr' }, 'Open')))),
          tbody))));
  }

  function row(inq) {
    const href = inquiryHref(inq);
    return el('tr', { class: 'st-row', onclick: (e) => { if (!e.target.closest('a')) go(ctx, href); } },
      el('td', { class: 'st-td-title' }, el('a', { href }, inq.draft.title || 'Untitled draft'), isUrgent(inq) ? urgentChip() : null),
      el('td', { class: 'st-td-muted st-nowrap' }, projectForLabel(inq)),
      el('td', {}, statusChip(inq.status)),
      el('td', { class: hasOpenBlocker(inq) ? 'st-td-warn' : 'st-td-muted' }, inq.latest),
      el('td', { class: 'st-col-open' }, el('a', { href, class: 'st-open', 'aria-label': `Open ${inq.draft.title || 'draft'}` }, icon('chevron', 18))));
  }

  await load();
  return () => { alive = false; };
}

function stat(label, value, tone) {
  return el('div', { class: 'st-stat' }, el('span', { class: 'st-stat-label' }, label), el('span', { class: `st-stat-value${tone ? ` st-stat-value--${tone}` : ''}` }, value));
}

/** GET /v1/world-projects: `[...]` or `{items}`; 404 or any error → [] (section hidden). */
async function worldProjects(ctx) {
  try {
    const res = await ctx.api('/v1/world-projects');
    const items = Array.isArray(res) ? res : Array.isArray(res?.items) ? res.items : [];
    return items.filter((p) => p && p.id && p.title).map((p) => ({
      id: String(p.id),
      title: String(p.title),
      summary: String(p.summary || p.description || ''),
      organization: String(p.organization || ''),
      types: [...new Set((Array.isArray(p.project_types) ? p.project_types : Array.isArray(p.types) ? p.types : []).map(projectTypeOf).filter(Boolean))],
      url: typeof p.url === 'string' && /^https?:\/\//i.test(p.url) ? p.url : null,
    }));
  } catch {
    return [];
  }
}

function worldGrid(projects) {
  return el('section', { class: 'st-world', 'aria-labelledby': 'st-world-title' },
    el('div', {}, el('h2', { class: 'st-h2', id: 'st-world-title' }, 'Projects from around the world'),
      el('p', { class: 'st-muted' }, 'Real-world ideas to get you started.')),
    el('div', { class: 'st-world-grid' }, projects.map((p) => el('article', { class: 'st-card st-world-card' },
      p.organization ? el('span', { class: 'st-mono-label' }, p.organization.toUpperCase()) : null,
      el('h3', { class: 'st-h3' }, p.title),
      p.summary ? el('p', { class: 'st-muted' }, p.summary) : null,
      p.types.length ? el('span', { class: 'st-mono' }, portalTypes(p.types)) : null,
      p.url ? el('a', { href: p.url, target: '_blank', rel: 'noopener noreferrer', class: 'st-link' }, 'Learn more') : null))));
}
