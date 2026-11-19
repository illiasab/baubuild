// Projects board: filters, urgent hero, project cards. Cards open #/projects/:id (detail panel over this page).
import {
  h, str, num, list, items, money, capital, dueChip, difficultyLabel, minScoreLabel, joiningLabel,
  chip, button, pageHead, loader, emptyView, clickable, go, toast,
} from './_kit.js';

const DIFFICULTIES = [null, 'easy', 'medium', 'hard'];

// Survives route changes so returning to the board (or opening a detail over it) paints instantly.
const cache = { byDifficulty: new Map(), filters: { difficulty: null, myClasses: false, urgent: false, openToMyScore: false } };

/** Drop a project from the cached board (after joining it moves to My Work). */
export function forgetProject(id) {
  for (const entry of cache.byDifficulty.values()) {
    entry.projects = entry.projects.filter((p) => str(p.id) !== str(id));
  }
}

async function loadBoard(ctx, difficulty, previous) {
  const q = new URLSearchParams();
  if (difficulty) q.set('difficulty', difficulty);
  const qs = q.toString();
  const [page, urgent, stats, classes] = await Promise.all([
    ctx.api(`/v1/projects${qs ? `?${qs}` : ''}`),
    ctx.api('/v1/inquiries/urgent').catch((err) => ({ failed: err })),
    ctx.api('/v1/me/stats').catch(() => previous?.stats ?? null),
    ctx.api('/v1/me/classes').catch(() => previous?.classes ?? null),
  ]);
  return {
    projects: items(page).filter((p) => p && p.id != null && !p.my_membership),
    nextCursor: page?.next_cursor ?? null,
    urgent: urgent?.failed ? previous?.urgent ?? [] : items(urgent),
    urgentFailed: !!urgent?.failed,
    stats,
    classes: classes ? items(classes) : [],
  };
}

export default async function render(root, ctx) {
  const filters = cache.filters;
  let alive = true;
  let loadingMore = false;

  const filterBar = h('div', { class: 'sp-filters', role: 'toolbar', 'aria-label': 'Filter projects' });
  const body = h('div', { class: 'sp-projects-body' });
  root.replaceChildren(h('div', { class: 'sp-page sp-projects' }, pageHead('Projects'), filterBar, body));

  const paintFilters = () => {
    const group = h('div', { class: 'segmented sp-seg', role: 'group', 'aria-label': 'Difficulty' },
      DIFFICULTIES.map((d) => h('button', {
        type: 'button', class: 'sp-seg-btn', 'aria-pressed': String(filters.difficulty === d),
        onclick: () => {
          if (filters.difficulty === d) return;
          filters.difficulty = d;
          paintFilters();
          board = mount();
        },
      }, d ? capital(d) : 'All')));
    const toggle = (key, label) => h('button', {
      type: 'button', class: 'sp-toggle', 'aria-pressed': String(filters[key]),
      onclick: () => { filters[key] = !filters[key]; paintFilters(); board.repaint(); },
    }, label);
    filterBar.replaceChildren(group, toggle('myClasses', 'My Classes'), toggle('urgent', 'Urgent'), toggle('openToMyScore', 'Open to my score'));
  };

  const open = (project) => go(ctx, `#/projects/${encodeURIComponent(project.id)}`);

  const view = (data) => {
    cache.byDifficulty.set(filters.difficulty, data);
    const score = data.stats ? num(data.stats.score) : null;
    const myClassIds = new Set(data.classes.map((c) => str(c.id)));
    const myClassCodes = new Set([...data.classes.map((c) => str(c.code)), ...list(ctx.user?.classes).map(String)]);
    const urgentIds = new Set(data.urgent.map((u) => str(u.id)));

    const visible = data.projects.filter((p) => {
      if (filters.myClasses && !(p.class_id != null && (myClassIds.has(str(p.class_id)) || myClassCodes.has(str(p.class_id))))) return false;
      if (filters.urgent && !(urgentIds.has(str(p.id)) || p.urgent === true)) return false;
      if (filters.openToMyScore) {
        if (p.eligibility && p.eligibility.eligible === false) return false;
        if (score != null && num(p.min_score) > score) return false;
      }
      return true;
    });

    const heroCard = data.urgent.find((u) => visible.some((p) => str(p.id) === str(u.id)));
    const heroProject = heroCard && visible.find((p) => str(p.id) === str(heroCard.id));
    const grid = visible.filter((p) => p !== heroProject);
    const out = [];

    if (heroProject) out.push(hero(heroProject, () => open(heroProject)));

    if (!heroProject && !grid.length) {
      const isDefault = !filters.difficulty && !filters.myClasses && !filters.urgent && !filters.openToMyScore;
      if (filters.urgent && data.urgentFailed) {
        out.push(emptyView({ title: 'Couldn’t load urgent requests right now.', action: { label: 'Try Again', onClick: () => board.refresh() } }));
      } else {
        out.push(emptyView({
          title: emptyMessage(filters),
          action: isDefault ? null : { label: 'Clear filters', onClick: () => { Object.assign(filters, { difficulty: null, myClasses: false, urgent: false, openToMyScore: false }); paintFilters(); board = mount(); } },
        }));
      }
    }

    if (grid.length) out.push(h('div', { class: 'sp-grid' }, grid.map((p) => projectCard(p, score, () => open(p)))));

    if (data.nextCursor) {
      out.push(h('div', { class: 'sp-more' }, button(loadingMore ? 'Loading…' : 'Load more projects', {
        disabled: loadingMore,
        onClick: async () => {
          loadingMore = true;
          board.repaint();
          try {
            const q = new URLSearchParams({ cursor: data.nextCursor });
            if (filters.difficulty) q.set('difficulty', filters.difficulty);
            const page = await ctx.api(`/v1/projects?${q}`);
            const seen = new Set(data.projects.map((p) => str(p.id)));
            const extra = items(page).filter((p) => p && !p.my_membership && !seen.has(str(p.id)));
            loadingMore = false;
            if (alive) board.data = { ...data, projects: [...data.projects, ...extra], nextCursor: page?.next_cursor ?? null };
          } catch (err) {
            loadingMore = false;
            if (alive) { board.repaint(); toast(ctx, err.message || 'Couldn’t load more projects.', 'error'); }
          }
        },
      })));
    }
    return out;
  };

  const mount = () => {
    const previous = cache.byDifficulty.get(filters.difficulty) || null;
    return loader(body, { load: () => loadBoard(ctx, filters.difficulty, previous), view, initial: previous, isAlive: () => alive && body.isConnected });
  };

  paintFilters();
  let board = mount();
  return () => { alive = false; };
}

function emptyMessage(filters) {
  if (filters.urgent) return 'No urgent projects right now.';
  if (filters.myClasses) return 'No open projects from your classes.';
  if (filters.openToMyScore) return 'No projects open to your score with these filters.';
  if (filters.difficulty) return `No ${filters.difficulty} projects open right now.`;
  return 'No open projects right now. Check back soon.';
}

function hero(project, onOpen) {
  const tags = ['Urgent', difficultyLabel(project.difficulty), num(project.min_score) > 0 ? minScoreLabel(project) : 'Any score welcome']
    .filter(Boolean).join(' · ').toUpperCase();
  return h('section', { class: 'sp-hero', 'aria-label': 'Urgent project' },
    h('div', { class: 'sp-hero-text' },
      h('span', { class: 'sp-mono-label sp-hero-tags' }, tags),
      h('h2', { class: 'sp-hero-title' }, str(project.title)),
      project.description ? h('p', { class: 'sp-hero-body' }, str(project.description)) : null,
      button(`View project · ${money(project.bounty)}`, { kind: 'primary', onClick: onOpen })),
    cubes());
}

export function projectCard(project, score, onOpen) {
  const owner = str(project.department || project.class_code || project.class_id).toUpperCase();
  const blocked = project.eligibility?.eligible === false || (score != null && num(project.min_score) > score);
  const everyone = str(project.joining) === 'everyone';
  const chips = [difficultyLabel(project.difficulty), minScoreLabel(project), dueChip(project.due_at)].filter(Boolean);
  const action = button(everyone ? 'I’m In' : 'Request', {
    kind: everyone ? 'primary' : 'secondary',
    onClick: (e) => { e.stopPropagation(); onOpen(); },
    title: blocked ? 'See why you can’t join yet' : undefined,
  });
  const el = h('article', { class: ['sp-pcard', blocked && 'is-blocked'] },
    h('div', { class: 'sp-pcard-top' },
      h('span', { class: 'sp-mono-label' }, owner),
      h('span', { class: 'sp-pcard-bounty' }, money(project.bounty))),
    h('h3', { class: 'sp-pcard-title' }, str(project.title)),
    project.description ? h('p', { class: 'sp-pcard-desc' }, str(project.description)) : null,
    h('div', { class: 'sp-chips' }, chips.map((c, i) => chip(c, i === 1 && blocked ? 'warn' : '')), project.urgent ? chip('Urgent', 'urgent') : null),
    h('div', { class: 'sp-pcard-foot' }, h('span', { class: 'sp-muted' }, joiningLabel(project)), action));
  return clickable(el, onOpen, `Open ${str(project.title)}`);
}

function cubes() {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 120 148');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'sp-hero-art');
  const cube = (x, y, faces, stroke) => {
    const g = document.createElementNS(NS, 'g');
    g.setAttribute('transform', `translate(${x} ${y})`);
    g.setAttribute('stroke', stroke);
    g.setAttribute('stroke-width', '1.2');
    g.setAttribute('stroke-linejoin', 'round');
    [['0,0 24,13 0,26 -24,13', faces[0]], ['-24,13 0,26 0,52 -24,39', faces[1]], ['24,13 0,26 0,52 24,39', faces[2]]].forEach(([points, fill]) => {
      const p = document.createElementNS(NS, 'polygon');
      p.setAttribute('points', points);
      p.setAttribute('fill', fill);
      g.append(p);
    });
    return g;
  };
  const navy = ['#27335C', '#1A2444', '#131B36'];
  svg.append(cube(36, 62, navy, '#8FA8FF'), cube(60, 76, navy, '#8FA8FF'), cube(84, 90, navy, '#8FA8FF'), cube(60, 48, navy, '#8FA8FF'),
    cube(60, 0, ['#FFE76B', '#FFD60A', '#D9B400'], '#0D1220'));
  return svg;
}

