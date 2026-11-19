// Staff Inquiries (#/inquiries): All / Open / Closed, search, table from GET /v1/inquiries/mine (+ local drafts).
import {
  el, clear, icon, go, showLoading, showError, fetchAll, statusChip, urgentChip, isUrgent, isOpenStatus, hasOpenBlocker,
  projectForLabel, portalTypes, inquiryHref,
} from './shared.js';

const FILTER_KEY = 'bau.staff.inquiries.filter';
const GROUP = { Draft: 0, AiRefining: 1, ReadyToPost: 1, Posted: 2, InProgress: 2, Done: 3, Cancelled: 3 };

export default async function render(root, ctx) {
  let alive = true;
  let list = [];
  let filter = 'All';
  try { filter = sessionStorage.getItem(FILTER_KEY) || 'All'; } catch { /* storage blocked */ }
  let query = '';
  let tableHost;
  let segButtons = [];

  async function load() {
    showLoading(root, ctx, 'Loading your inquiries…');
    try {
      list = await fetchAll(ctx);
    } catch (err) {
      if (alive) showError(root, ctx, err, load);
      return;
    }
    if (!alive) return;
    // Drafts, then live work, then closed — newest first inside each group (server order is newest first).
    list = list.map((inq, i) => ({ inq, i })).sort((a, b) => (GROUP[a.inq.status] ?? 2) - (GROUP[b.inq.status] ?? 2) || a.i - b.i).map((x) => x.inq);
    draw();
  }

  function draw() {
    clear(root);
    const page = el('div', { class: 'st-page' });
    root.append(page);
    const search = el('input', {
      type: 'search', class: 'st-input st-search', placeholder: 'Search inquiries', 'aria-label': 'Search inquiries', value: query,
      oninput: (e) => { query = e.target.value; drawTable(); },
    });
    page.append(el('div', { class: 'page-head st-head' }, el('h1', { class: 'st-h1' }, 'Inquiries'), search));

    segButtons = ['All', 'Open', 'Closed'].map((name) => el('button', {
      type: 'button', class: 'st-seg-btn', 'aria-pressed': String(filter === name),
      onclick: () => {
        filter = name;
        try { sessionStorage.setItem(FILTER_KEY, name); } catch { /* ignore */ }
        segButtons.forEach((b) => b.setAttribute('aria-pressed', String(b.textContent === name)));
        drawTable();
      },
    }, name));
    page.append(el('div', { class: 'st-toolbar' },
      el('div', { class: 'st-seg', role: 'group', 'aria-label': 'Filter' }, segButtons),
      el('a', { href: '#/new-inquiry', class: 'st-btn st-btn--yellow' }, icon('plus', 16), 'New inquiry')));

    tableHost = el('div');
    page.append(tableHost);
    page.append(el('p', { class: 'st-muted st-small' }, 'Open any inquiry to see today’s progress, blockers, and to edit its details.'));
    drawTable();
  }

  function drawTable() {
    clear(tableHost);
    const q = query.trim().toLowerCase();
    const rows = list
      .filter((inq) => filter === 'All' || (filter === 'Open' ? isOpenStatus(inq.status) : !isOpenStatus(inq.status)))
      .filter((inq) => !q || inq.draft.title.toLowerCase().includes(q) || projectForLabel(inq).toLowerCase().includes(q));
    if (!rows.length) {
      const [title, body] = emptyCopy(filter, query, list.length > 0);
      tableHost.append(el('div', { class: 'st-card st-empty' },
        el('h2', { class: 'st-h3' }, title), el('p', { class: 'st-muted' }, body),
        !list.length ? el('a', { href: '#/new-inquiry', class: 'st-btn st-btn--yellow' }, 'Post your first inquiry') : null));
      return;
    }
    tableHost.append(el('div', { class: 'st-card st-table-card' }, el('div', { class: 'st-table-wrap' },
      el('table', { class: 'st-table st-table--wide' },
        el('thead', {}, el('tr', {},
          ['INQUIRY', 'PROJECT FOR', 'TYPE', 'REWARD', 'STATUS', 'LATEST'].map((c) => el('th', { scope: 'col' }, c)),
          el('th', { scope: 'col', class: 'st-col-open' }, el('span', { class: 'st-sr' }, 'Open')))),
        el('tbody', {}, rows.map(row))))));
  }

  function row(inq) {
    const href = inquiryHref(inq);
    return el('tr', { class: 'st-row', onclick: (e) => { if (!e.target.closest('a')) go(ctx, href); } },
      el('td', { class: 'st-td-title' }, el('a', { href }, inq.draft.title || 'Untitled draft'), isUrgent(inq) ? urgentChip() : null),
      el('td', { class: 'st-td-muted st-nowrap' }, projectForLabel(inq)),
      el('td', { class: 'st-td-muted st-nowrap' }, portalTypes(inq.draft.types)),
      el('td', { class: 'st-nowrap' }, inq.rewardLabel || '—'),
      el('td', {}, statusChip(inq.status)),
      el('td', { class: hasOpenBlocker(inq) ? 'st-td-warn' : 'st-td-muted' }, inq.latest),
      el('td', { class: 'st-col-open' }, el('a', { href, class: 'st-open', 'aria-label': `Open ${inq.draft.title || 'draft'}` }, icon('chevron', 18))));
  }

  await load();
  return () => { alive = false; };
}

function emptyCopy(filter, query, hasAny) {
  if (query.trim()) return ['No matches', `No inquiry matches “${query.trim()}”.`];
  if (!hasAny) return ['No inquiries yet', 'Post your first inquiry and follow it here, day by day.'];
  if (filter === 'Open') return ['Nothing open', 'Every inquiry is closed. Post a new one when you need something built.'];
  if (filter === 'Closed') return ['Nothing closed yet', 'Delivered and cancelled inquiries show up here.'];
  return ['No inquiries yet', 'Post your first inquiry and follow it here, day by day.'];
}
