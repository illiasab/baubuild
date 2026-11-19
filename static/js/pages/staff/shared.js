// Shared logic for the staff portal pages. Ports of the staff app's data layer
// (app/shared/.../data/http/staff: StaffMappers.kt, InquiryHeuristics.kt, HttpStaffRepository.kt) so the app and
// the web read and write each other's inquiries the same way.

// ---------------------------------------------------------------------------------------------------------------
// DOM
// ---------------------------------------------------------------------------------------------------------------

/** Safe element builder: strings become text nodes, never HTML. on* attrs are listeners. */
export function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'class') node.className = value;
    else if (key === 'style' && typeof value === 'object') Object.assign(node.style, value);
    else if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key === 'value' && 'value' in node) node.value = value;
    else if (key === 'checked' || key === 'disabled' || key === 'selected') node[key] = !!value;
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  append(node, children);
  return node;
}

function append(node, children) {
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    if (Array.isArray(child)) append(node, child);
    else node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(node) {
  while (node.firstChild) node.firstChild.remove();
}

/** Inline SVG icon from a fixed path table (no untrusted input). */
const ICONS = {
  chevron: 'M9 6l6 6-6 6',
  check: 'M5 12l5 5 9-10',
  alert: 'M12 8v5M12 16.5v.01M10.3 3.9L2.4 18a2 2 0 001.7 3h15.8a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z',
  bell: 'M6 8a6 6 0 1112 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.9 1.9 0 003.4 0',
  sparkle: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z',
  plus: 'M12 5v14M5 12h14',
  search: 'M11 4a7 7 0 100 14 7 7 0 000-14zM21 21l-5-5',
};
export function icon(name, size = 18) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '2');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', ICONS[name] || '');
  svg.append(path);
  return svg;
}

// ---------------------------------------------------------------------------------------------------------------
// ctx helpers (lenient about the shell's ui contract)
// ---------------------------------------------------------------------------------------------------------------

export function go(ctx, hash) {
  if (typeof ctx?.navigate === 'function') ctx.navigate(hash);
  else location.hash = hash;
}

export function toast(ctx, message, kind) {
  const fn = ctx?.toast || ctx?.ui?.toast;
  if (typeof fn === 'function') {
    try { fn(message, kind); return; } catch { /* fall through */ }
  }
  console.info(message);
}

/** Query string of the current hash route (`#/new-inquiry?draft=x`). */
export function hashQuery() {
  const raw = location.hash.split('?')[1] || '';
  return Object.fromEntries(new URLSearchParams(raw));
}

/** Loading placeholder inside root. */
export function showLoading(root, ctx, label = 'Loading…') {
  clear(root);
  if (typeof ctx?.ui?.loading === 'function') {
    try {
      const out = ctx.ui.loading(root);
      if (out instanceof Node && !out.isConnected) root.append(out);
      if (root.childNodes.length) return;
    } catch { /* fall back */ }
  }
  root.append(el('div', { class: 'st-state', role: 'status' }, el('span', { class: 'st-spinner', 'aria-hidden': 'true' }), label));
}

/** Error card with a retry button. */
export function showError(root, ctx, err, retry) {
  clear(root);
  root.append(errorBox(ctx, err, retry));
}

export function errorBox(ctx, err, retry) {
  if (typeof ctx?.ui?.errorState === 'function') {
    try {
      const out = ctx.ui.errorState(null, err, retry);
      if (out instanceof Node) return out;
    } catch { /* fall back */ }
  }
  return el('div', { class: 'st-card st-state st-state--error', role: 'alert' },
    el('strong', {}, err?.offline ? 'You’re offline' : 'Couldn’t load this'),
    el('p', {}, err?.message || 'Something went wrong.'),
    retry ? el('button', { type: 'button', class: 'st-btn st-btn--dark', onclick: retry }, 'Try again') : null,
  );
}

export async function confirmAction(ctx, { title, body, confirmLabel = 'Confirm', danger = false }) {
  const fn = ctx?.ui?.confirmDialog;
  if (typeof fn === 'function') {
    try {
      const result = await fn({ title, body, message: body, confirmLabel, confirmText: confirmLabel, danger, kind: danger ? 'danger' : undefined });
      return result === true || result?.confirmed === true;
    } catch { /* fall back */ }
  }
  return window.confirm(`${title}\n\n${body}`);
}

// ---------------------------------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------------------------------

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export function formatUsd(amount) {
  const n = Math.round(Number(amount) || 0);
  return (n < 0 ? '-$' : '$') + Math.abs(n).toLocaleString('en-US');
}

/** "2026-12-03" → "Dec 3". */
export function formatShortDate(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
  if (!m) return null;
  const month = MONTHS[Number(m[2]) - 1];
  return month ? `${month} ${Number(m[3])}` : null;
}

export function todayIso() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// ---------------------------------------------------------------------------------------------------------------
// Catalogues (labels must match the Kotlin enums: they are written into inquiry descriptions)
// ---------------------------------------------------------------------------------------------------------------

export const PROJECT_TYPES = [
  { key: 'IosApp', label: 'iOS App', short: 'iOS' },
  { key: 'AndroidApp', label: 'Android App', short: 'Android' },
  { key: 'Website', label: 'Website', short: 'Website' },
  { key: 'WebApp', label: 'Web App', short: 'Web App' },
  { key: 'DesktopApp', label: 'Desktop App', short: 'Desktop App' },
  { key: 'BackendApi', label: 'Backend / API', short: 'Backend / API' },
  { key: 'DataScript', label: 'Data / Script', short: 'Data / Script' },
  { key: 'Other', label: 'Other', short: 'Other' },
];
export const REWARDS = [
  { key: 'Price', label: 'Price' },
  { key: 'Ssn', label: 'SSN' },
  { key: 'JobAtBau', label: 'Job at BAU' },
  { key: 'SchoolDiscount', label: '% off School Expenses' },
  { key: 'Volunteer', label: 'Volunteer' },
];
export const PROJECT_FOR = { ClassProject: 'Class Project', BauProject: 'BAU Project' };
export const DIFFICULTIES = ['Easy', 'Medium', 'Hard'];
export const MAX_BOUNTY = 500; // backend: bounty 0..500

const typeLabel = (key) => PROJECT_TYPES.find((t) => t.key === key)?.label || key;
const alnum = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9]/g, '');

/** Lenient project type: enum name ("IosApp"), label ("iOS App") or snake case ("ios_app"). */
export function projectTypeOf(raw) {
  const key = alnum(raw);
  if (!key) return null;
  return PROJECT_TYPES.find((t) => alnum(t.key) === key || alnum(t.label) === key)?.key || null;
}

/** Catalogue-ordered keys. */
export function orderTypes(keys) {
  const set = new Set(keys || []);
  return PROJECT_TYPES.filter((t) => set.has(t.key)).map((t) => t.key);
}

/** TYPE column: "iOS · Android · Website"; "—" when none. */
export function portalTypes(keys) {
  const set = new Set(keys || []);
  return PROJECT_TYPES.filter((t) => set.has(t.key)).map((t) => t.short).join(' · ') || '—';
}

export function emptyDraft() {
  return {
    title: '', explanation: '', expectation: '', types: [], projectFor: 'BauProject', classCode: null,
    rewards: ['Price'], priceUsd: null, schoolDiscountPercent: null, priority: 'Normal', deadline: null, difficulty: null,
  };
}

export function normalizeClassCode(raw) {
  return String(raw || '').replace(/\s+/g, '').toUpperCase();
}

export function isValidClassCode(code) {
  return code.length >= 3 && code.length <= 12 && /^[A-Z0-9-]+$/.test(code) && /[A-Z]/.test(code) && /[0-9]/.test(code);
}

// ---------------------------------------------------------------------------------------------------------------
// Heuristics (InquiryHeuristics.kt)
// ---------------------------------------------------------------------------------------------------------------

export const BASIC_REWRITE_LABEL = 'Basic rewrite (AI refine not available yet)';

const capitalize = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

export function defaultMinScore(difficulty) {
  if (difficulty === 'Easy') return 0;
  if (difficulty === 'Hard') return 300;
  return 150;
}

/** "$40 + SSN"; "—" when there is no reward. */
export function rewardLabel(d) {
  const out = [];
  const rewards = new Set(d.rewards || []);
  if (rewards.has('Price') && d.priceUsd !== null && d.priceUsd !== undefined) out.push(`$${d.priceUsd}`);
  if (rewards.has('Ssn')) out.push('SSN');
  if (rewards.has('JobAtBau')) out.push('Job at BAU');
  if (rewards.has('SchoolDiscount')) out.push(`${d.schoolDiscountPercent ?? 0}% off`);
  if (rewards.has('Volunteer')) out.push('Volunteer');
  return out.join(' + ') || '—';
}

/** Expectation text → acceptance items. One per line; a single line is split on sentences (and commas). */
export function splitDoneItems(expectation, splitCommas = false) {
  const text = String(expectation || '');
  const lines = text.split(/\r?\n/).map((l) => l.trim().replace(/^- /, '').trim()).filter(Boolean);
  const pieces = lines.length > 1 ? lines : text.split(splitCommas ? /[.,\n]/ : /[.\n]/);
  return pieces.map((p) => p.trim().replace(/\.+$/, '')).filter((p) => p.length > 3).map(capitalize);
}

/** CLIENT-SIDE FALLBACK for "Refine with AI" (not AI). */
export function heuristicRefine(draft) {
  const title = capitalize(draft.title.trim());
  const types = draft.types.length ? orderTypes(draft.types) : ['Other'];
  const difficulty = draft.difficulty || (types.length >= 3 || (draft.priceUsd || 0) >= 50 ? 'Hard' : types.length === 2 ? 'Medium' : 'Easy');
  let brief = capitalize(draft.explanation.trim());
  if (brief && !brief.endsWith('.')) brief += '.';
  const expectation = draft.expectation.trim().replace(/\.+$/, '');
  if (expectation) brief += ` ${capitalize(expectation)}.`;
  const done = splitDoneItems(draft.expectation, true);
  return {
    title: /^build/i.test(title) ? title : `Build ${title}`.trim(),
    brief: brief.trim(),
    doneLooksLike: (done.length ? done : ['The brief is delivered and reviewed by a dev lead']).slice(0, 4),
    types,
    difficulty,
    minScore: defaultMinScore(difficulty),
    rewardSummary: rewardLabel(draft),
    deadline: draft.deadline,
    whatAiChanged: 'Rewrote the title and brief in plain language, turned your expectations into acceptance checks, '
      + `and suggested ${difficulty} difficulty.`,
    basic: true,
  };
}

/** Lenient parse of a refine endpoint answer (snake or camel case, optionally wrapped). */
export function parseRefined(raw, draft) {
  const r = raw?.refined || raw?.inquiry || raw || {};
  const list = (v) => (Array.isArray(v) ? v : typeof v === 'string' ? v.split(/\r?\n/) : []).map((s) => String(s).trim()).filter(Boolean);
  const diff = capitalize(String(r.difficulty || '').toLowerCase());
  const difficulty = DIFFICULTIES.includes(diff) ? diff : (draft.difficulty || 'Medium');
  const types = list(r.types || r.project_types).map(projectTypeOf).filter(Boolean);
  const title = String(r.title || '').trim();
  if (!title) return null;
  return {
    title,
    brief: String(r.brief || r.description || r.summary || '').trim() || draft.explanation.trim(),
    doneLooksLike: list(r.done_looks_like || r.doneLooksLike || r.acceptance || r.done),
    types: types.length ? orderTypes(types) : orderTypes(draft.types),
    difficulty,
    minScore: Number.isFinite(Number(r.min_score ?? r.minScore)) ? Number(r.min_score ?? r.minScore) : defaultMinScore(difficulty),
    rewardSummary: rewardLabel(draft),
    deadline: r.deadline || dueAtToDeadline(r.due_at) || draft.deadline,
    whatAiChanged: String(r.what_ai_changed || r.whatAiChanged || r.changes || '').trim(),
    notifiesOnDone: r.notifies_on_done || r.notifiesOnDone || null,
    basic: false,
  };
}

// ---------------------------------------------------------------------------------------------------------------
// Description footer (StaffMappers.kt InquiryDescription)
// ---------------------------------------------------------------------------------------------------------------

const DONE_HEADER = 'What done looks like:';
const TYPES_PREFIX = 'Project type: ';
const FOR_PREFIX = 'For: ';
const REWARD_PREFIX = 'Reward: ';

export function composeDescription({ body, done, types, projectFor, classCode, draft }) {
  let out = String(body || '').trim();
  if (done.length) out += `\n\n${DONE_HEADER}` + done.map((d) => `\n- ${d.trim()}`).join('');
  const footer = [];
  if (types.length) footer.push(TYPES_PREFIX + orderTypes(types).map(typeLabel).join(', '));
  const code = String(classCode || '').trim();
  footer.push(FOR_PREFIX + PROJECT_FOR[projectFor] + (projectFor === 'ClassProject' && code ? ` · ${code}` : ''));
  const reward = rewardLabel(draft);
  if (reward !== '—') footer.push(REWARD_PREFIX + reward);
  out += `\n\n${footer.join('\n')}`;
  return out.trim();
}

export function parseDescription(description) {
  const body = [];
  const done = [];
  let types = [];
  let projectFor = null;
  let classCode = null;
  let rewards = null;
  let discount = null;
  let inDone = false;
  for (const raw of String(description || '').split(/\r?\n/)) {
    const line = raw.trim();
    if (line === DONE_HEADER) {
      inDone = true;
    } else if (line.startsWith(TYPES_PREFIX) && line.slice(TYPES_PREFIX.length).split(',').some((t) => projectTypeOf(t))) {
      types = orderTypes(line.slice(TYPES_PREFIX.length).split(',').map(projectTypeOf).filter(Boolean));
    } else if (line.startsWith(FOR_PREFIX) && Object.values(PROJECT_FOR).some((l) => line.slice(FOR_PREFIX.length).toLowerCase().startsWith(l.toLowerCase()))) {
      const parts = line.slice(FOR_PREFIX.length).split('·').map((s) => s.trim());
      projectFor = Object.keys(PROJECT_FOR).find((k) => PROJECT_FOR[k].toLowerCase() === parts[0].toLowerCase()) || null;
      classCode = parts[1] || null;
    } else if (line.startsWith(REWARD_PREFIX)) {
      const set = new Set();
      for (const token of line.slice(REWARD_PREFIX.length).split('+').map((s) => s.trim())) {
        const lower = token.toLowerCase();
        if (token.startsWith('$')) set.add('Price');
        else if (lower === 'ssn') set.add('Ssn');
        else if (lower === 'job at bau') set.add('JobAtBau');
        else if (lower.endsWith('% off')) {
          set.add('SchoolDiscount');
          const n = parseInt(token.split('%')[0].trim(), 10);
          discount = Number.isFinite(n) ? n : null;
        } else if (lower === 'volunteer') set.add('Volunteer');
      }
      rewards = [...set];
    } else if (inDone && line.startsWith('- ')) {
      done.push(line.slice(2).trim());
    } else if (inDone && line === '') {
      // blank line inside the checklist
    } else {
      inDone = false;
      body.push(raw);
    }
  }
  return { body: body.join('\n').trim(), done, types, projectFor, classCode, rewards, schoolDiscountPercent: discount };
}

// ---------------------------------------------------------------------------------------------------------------
// Inquiry mapping (backend → staff view model)
// ---------------------------------------------------------------------------------------------------------------

/** Backend status → app status key. */
export function statusOf(raw) {
  switch (String(raw || '').toLowerCase()) {
    case 'in_progress': case 'inprogress': case 'building': return 'InProgress';
    case 'closed': case 'done': case 'delivered': case 'completed': return 'Done';
    case 'cancelled': case 'canceled': case 'rejected': case 'withdrawn': return 'Cancelled';
    case 'draft': return 'Draft';
    default: return 'Posted';
  }
}

export const STATUS_LABEL = { Draft: 'Draft', AiRefining: 'AI Refining', ReadyToPost: 'Ready to Post', Posted: 'Posted', InProgress: 'In Progress', Done: 'Done', Cancelled: 'Cancelled' };
export const isOpenStatus = (s) => s !== 'Done' && s !== 'Cancelled';

function latestFor(raw) {
  const s = String(raw || '').toLowerCase();
  if (s === 'submitted') return 'Waiting for review';
  if (s === 'approved') return 'Approved · going live soon';
  if (s === 'published' || s === 'posted' || s === 'open') return 'Live on the board';
  const st = statusOf(s);
  if (st === 'InProgress') return 'Students are building it';
  if (st === 'Done') return 'Delivered';
  if (st === 'Cancelled') return 'Withdrawn';
  if (st === 'Draft') return 'Draft';
  return capitalize(s.replace(/_/g, ' '));
}

export function deadlineToDueAt(deadline) {
  const d = String(deadline || '').trim();
  if (!d) return null;
  return d.length === 10 ? `${d}T23:59:59Z` : d;
}

export function dueAtToDeadline(dueAt) {
  const d = String(dueAt || '').trim();
  return d.length >= 10 ? d.slice(0, 10) : null;
}

const int = (v, fallback = 0) => {
  const n = Number(v);
  return Number.isFinite(n) ? Math.round(n) : fallback;
};

/** Optional progress block, if a future backend sends one (none today). */
function parseProgress(dto) {
  const p = dto.progress;
  if (!p || typeof p !== 'object') return null;
  const b = p.blocker && typeof p.blocker === 'object' ? p.blocker : null;
  return {
    dayOf: int(p.day_of ?? p.dayOf, 0),
    totalDays: int(p.total_days ?? p.totalDays, 0),
    percent: Math.max(0, Math.min(100, int(p.percent ?? p.percent_done, 0))),
    doneToday: (Array.isArray(p.done_today ?? p.doneToday) ? (p.done_today ?? p.doneToday) : []).map(String),
    students: (Array.isArray(p.student_names ?? p.students) ? (p.student_names ?? p.students) : []).map((s) => (typeof s === 'string' ? s : s?.name)).filter(Boolean),
    blocker: b ? {
      id: String(b.id ?? ''), text: String(b.text ?? b.message ?? ''), raisedBy: b.raised_by ?? b.raisedBy ?? null,
      at: b.raised_at ?? b.at ?? null, resolved: !!b.resolved, contact: b.contact_url ?? b.contact_email ?? null,
    } : null,
    log: (Array.isArray(p.log) ? p.log : []).map((e) => ({ date: e.date ?? e.day ?? '', text: String(e.text ?? e.summary ?? ''), blocker: e.blocker ?? null })),
  };
}

export function toStaffInquiry(dto) {
  const parsed = parseDescription(dto.description);
  const bounty = int(dto.bounty, 0);
  const diffRaw = capitalize(String(dto.difficulty || '').toLowerCase());
  const difficulty = DIFFICULTIES.includes(diffRaw) ? diffRaw : null;
  const reviewerType = dto.reviewer_type || 'ai_then_lead';
  const projectFor = parsed.projectFor || (reviewerType === 'professor' ? 'ClassProject' : 'BauProject');
  const rewards = new Set(parsed.rewards || ['Price']);
  if (bounty > 0) rewards.add('Price');
  const draft = {
    title: String(dto.title || ''),
    explanation: parsed.body,
    expectation: parsed.done.join('\n'),
    types: parsed.types,
    projectFor,
    classCode: parsed.classCode,
    rewards: REWARDS.map((r) => r.key).filter((k) => rewards.has(k)),
    priceUsd: bounty > 0 || rewards.has('Price') ? bounty : null,
    schoolDiscountPercent: parsed.schoolDiscountPercent,
    priority: dto.is_urgent ? 'Urgent' : 'Normal',
    deadline: dueAtToDeadline(dto.due_at),
    difficulty,
  };
  const effective = difficulty || 'Medium';
  const minScore = int(dto.min_score, 0);
  const progress = parseProgress(dto);
  const status = statusOf(dto.status);
  let latest = latestFor(dto.status);
  if (progress && status === 'InProgress' && progress.totalDays) {
    latest = `Day ${progress.dayOf} of ${progress.totalDays}` + (progress.blocker && !progress.blocker.resolved ? ' · 1 blocker' : '');
  }
  return {
    id: String(dto.id),
    rawStatus: String(dto.status || 'submitted'),
    status,
    draft,
    refined: {
      title: draft.title, brief: parsed.body, doneLooksLike: parsed.done, types: parsed.types, difficulty: effective,
      minScore: minScore >= 0 ? minScore : defaultMinScore(effective), rewardSummary: rewardLabel(draft), deadline: draft.deadline,
    },
    latest,
    rewardLabel: rewardLabel(draft),
    reviewedBy: reviewerType === 'professor' ? 'professor' : reviewerType === 'ai_then_lead' ? 'dev lead' : null,
    progress,
    projectId: dto.project_id || null,
    createdAt: dto.created_at || null,
    department: dto.department || '',
    local: false,
  };
}

export const isUrgent = (inq) => inq.draft.priority === 'Urgent';
export const hasOpenBlocker = (inq) => !!(inq.progress?.blocker && !inq.progress.blocker.resolved);

/** "BAU Project" / "Class Project · CMPS433". */
export function projectForLabel(inq) {
  const d = inq.draft || inq;
  const code = String(d.classCode || '').trim();
  return PROJECT_FOR[d.projectFor] + (d.projectFor === 'ClassProject' && code ? ` · ${code}` : '');
}

export function detailMeta(inq) {
  const parts = [projectForLabel(inq)];
  if (inq.draft.types.length) parts.push(portalTypes(inq.draft.types));
  if (inq.rewardLabel && inq.rewardLabel !== '—') parts.push(inq.rewardLabel);
  const students = inq.progress?.students?.length || 0;
  if (students) parts.push(students === 1 ? '1 student' : `${students} students`);
  if (inq.reviewedBy) parts.push(`${inq.reviewedBy} reviews`);
  return parts.join(' · ');
}

/** Status summary card copy (InquiryDetailScreen.summaryOf). */
export function summaryOf(inq) {
  switch (inq.status) {
    case 'Draft': return { title: 'Draft', body: 'Saved on this device only. Finish the details, then refine and post it.' };
    case 'Posted':
      if (inq.rawStatus === 'submitted') return { title: inq.latest, body: 'A reviewer checks the brief before students can see it. You’ll be notified when it goes live.' };
      if (inq.rawStatus === 'approved') return { title: inq.latest, body: 'A reviewer approved it. It goes live on the Projects board shortly.' };
      return { title: inq.latest || 'Posted', body: 'Students can find it in Projects and ask to join. Daily updates start once a team is approved.' };
    case 'InProgress': return { title: inq.latest || 'In progress', body: 'Students are building it. Daily updates and blockers show up here.' };
    case 'Done': return { title: 'Delivered', body: `The team delivered it and the work was reviewed${inq.reviewedBy ? ` by the ${inq.reviewedBy}` : ''}.` };
    case 'Cancelled': return { title: inq.latest || 'Cancelled', body: 'This inquiry was cancelled. Unspent budget from cancelled inquiries counts as wasted.', warning: true };
    default: return { title: inq.latest || STATUS_LABEL[inq.status] || 'Posted', body: '' };
  }
}

const CHIP_CLASS = { Draft: 'draft', AiRefining: 'refining', ReadyToPost: 'refining', Posted: 'posted', InProgress: 'progress', Done: 'done', Cancelled: 'cancelled' };
export function statusChip(status) {
  return el('span', { class: `st-chip st-chip--${CHIP_CLASS[status] || 'posted'}` }, STATUS_LABEL[status] || status);
}
export function urgentChip() {
  return el('span', { class: 'st-chip st-chip--urgent' }, 'Urgent');
}

// ---------------------------------------------------------------------------------------------------------------
// Request bodies (HttpStaffRepository.kt)
// ---------------------------------------------------------------------------------------------------------------

export function bountyOf(draft) {
  return draft.rewards.includes('Price') ? Math.max(0, Math.min(MAX_BOUNTY, int(draft.priceUsd, 0))) : 0;
}

export function reviewerTypeOf(draft) {
  return draft.projectFor === 'ClassProject' ? 'professor' : 'ai_then_lead';
}

export function createBody(draft, refined, department) {
  return {
    title: (refined.title || draft.title).trim(),
    description: composeDescription({
      body: refined.brief || draft.explanation,
      done: refined.doneLooksLike,
      types: refined.types.length ? refined.types : draft.types,
      projectFor: draft.projectFor,
      classCode: draft.classCode,
      draft,
    }),
    department: department || 'BAU',
    difficulty: String(refined.difficulty || 'Medium').toLowerCase(),
    bounty: bountyOf(draft),
    is_urgent: draft.priority === 'Urgent',
    due_at: deadlineToDueAt(refined.deadline || draft.deadline),
    min_score: Math.max(0, Math.min(500, int(refined.minScore, 0))),
    joining: 'approval',
    reviewer_type: reviewerTypeOf(draft),
  };
}

export function patchBody(draft) {
  const body = {
    title: draft.title.trim(),
    description: composeDescription({
      body: draft.explanation,
      done: splitDoneItems(draft.expectation),
      types: draft.types,
      projectFor: draft.projectFor,
      classCode: draft.classCode,
      draft,
    }),
    bounty: bountyOf(draft),
    is_urgent: draft.priority === 'Urgent',
    due_at: deadlineToDueAt(draft.deadline),
    reviewer_type: reviewerTypeOf(draft),
  };
  if (draft.difficulty) body.difficulty = draft.difficulty.toLowerCase();
  return body;
}

// ---------------------------------------------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------------------------------------------

/** GET /v1/inquiries/mine → mapped list (server order: newest first). */
export async function fetchMine(api) {
  const res = await api('/v1/inquiries/mine');
  const items = Array.isArray(res) ? res : Array.isArray(res?.items) ? res.items : [];
  return items.filter((x) => x && x.id).map(toStaffInquiry);
}

/** Drafts live in this browser until the backend has a drafts endpoint. */
function draftsKey(ctx) {
  const id = ctx?.user?.id || ctx?.session?.user?.id || 'me';
  return `bau.staff.drafts.${id}`;
}

export function loadDrafts(ctx) {
  try {
    const list = JSON.parse(localStorage.getItem(draftsKey(ctx)) || '[]');
    return Array.isArray(list) ? list.filter((d) => d && d.id && d.draft) : [];
  } catch {
    return [];
  }
}

export function saveDraft(ctx, draft, id) {
  const list = loadDrafts(ctx).filter((d) => d.id !== id);
  const entry = { id: id || `localdraft-${Date.now().toString(36)}`, draft, savedAt: new Date().toISOString() };
  list.unshift(entry);
  localStorage.setItem(draftsKey(ctx), JSON.stringify(list.slice(0, 50)));
  return entry;
}

export function deleteDraft(ctx, id) {
  try {
    localStorage.setItem(draftsKey(ctx), JSON.stringify(loadDrafts(ctx).filter((d) => d.id !== id)));
  } catch { /* storage blocked */ }
}

export function draftAsInquiry(entry) {
  const draft = { ...emptyDraft(), ...entry.draft };
  return {
    id: entry.id, rawStatus: 'draft', status: 'Draft', draft, refined: null, latest: 'Draft · on this device',
    rewardLabel: rewardLabel(draft), reviewedBy: null, progress: null, createdAt: entry.savedAt, local: true,
  };
}

/** Local drafts first, then the backend list (HttpStaffRepository.inquiries). */
export async function fetchAll(ctx) {
  const remote = await fetchMine(ctx.api);
  return [...loadDrafts(ctx).map(draftAsInquiry), ...remote];
}

/** Overview numbers computed from the list (no GET /v1/staff/overview yet). */
export function computeOverview(list) {
  const sum = (status) => list.filter((i) => i.status === status).reduce((acc, i) => acc + (i.draft.priceUsd || 0), 0);
  return {
    raisedUsd: sum('Done'),
    wastedUsd: sum('Cancelled'),
    openCount: list.filter((i) => isOpenStatus(i.status)).length,
    deliveredCount: list.filter((i) => i.status === 'Done').length,
    needsAttention: list.find(hasOpenBlocker) || null,
  };
}

export function inquiryHref(inq) {
  return inq.local ? `#/new-inquiry?draft=${encodeURIComponent(inq.id)}` : `#/inquiries/${encodeURIComponent(inq.id)}`;
}

// ---------------------------------------------------------------------------------------------------------------
// People
// ---------------------------------------------------------------------------------------------------------------

/** Title shown on the profile: an explicit `title` from the payload, else the role. */
export function staffTitle(user) {
  const explicit = String(user?.title || user?.job_title || '').trim();
  if (explicit) return explicit;
  return String(user?.role || '').toLowerCase() === 'admin' ? 'Administrator' : 'Staff';
}

const HONORIFICS = new Set(['dr', 'prof', 'mr', 'mrs', 'ms']);

export function isProfessor(user) {
  return /professor/i.test(staffTitle(user));
}

/** "Hello, Dr. Hale" for professors, "Hello, Nora" otherwise. */
export function staffGreeting(user) {
  if (!user) return null;
  const words = String(user.name || '').split(' ').map((w) => w.trim()).filter(Boolean)
    .filter((w) => !HONORIFICS.has(w.toLowerCase().replace(/\.+$/, '')));
  const fromEmail = String(user.email || '').split('@')[0].split(/[._-]/).filter(Boolean).map(capitalize);
  const first = words[0] || fromEmail[0];
  const last = (words.length >= 2 ? words[words.length - 1] : null) || (fromEmail.length >= 2 ? fromEmail[fromEmail.length - 1] : null);
  if (isProfessor(user) && last) return `Hello, Dr. ${last}`;
  return first ? `Hello, ${first}` : null;
}

/** "Dr. Nora Hale" for professors. */
export function displayName(user) {
  const name = String(user?.name || '').trim();
  return isProfessor(user) && !/^dr[. ]/i.test(name) ? `Dr. ${name}` : name;
}

export function initialsOf(user) {
  if (user?.initials) return String(user.initials).slice(0, 3);
  return String(user?.name || '?').split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('') || '?';
}

/** GET /v1/senior-engineers (proposed). 404 → [] (section hidden); other errors → null. */
export async function fetchSeniorEngineers(api) {
  try {
    const res = await api('/v1/senior-engineers');
    const items = Array.isArray(res) ? res : res?.items || res?.senior_engineers || [];
    return items.map(parseEngineer).filter(Boolean);
  } catch (err) {
    if (err?.status === 404) return [];
    return null;
  }
}

export function parseEngineer(o, fallback = null) {
  if (!o || typeof o !== 'object') return fallback;
  const s = (...keys) => {
    for (const k of keys) {
      const v = o[k];
      if (typeof v === 'string' && v.trim()) return v.trim();
    }
    return null;
  };
  const id = s('id') || fallback?.id;
  const name = s('name', 'display_name', 'displayName') || fallback?.name;
  if (!id || !name) return null;
  const roles = [...(Array.isArray(o.roles) ? o.roles : []), o.role].filter(Boolean).map((r) => String(r).toLowerCase());
  let skills = o.skills;
  if (typeof skills === 'string') skills = skills.split(',');
  skills = (Array.isArray(skills) ? skills : []).map((k) => (typeof k === 'string' ? k : k?.name || k?.label)).filter(Boolean).map((k) => String(k).trim());
  return {
    id,
    name,
    title: s('title', 'job_title', 'jobTitle', 'headline') || fallback?.title || (roles.includes('lead') ? 'Lead Engineer' : ''),
    bio: s('bio', 'about') || fallback?.bio || '',
    skills: skills.length ? skills : fallback?.skills || [],
    github: (s('github_username', 'githubUsername', 'github_login', 'github') || '').replace(/^@/, '').replace('https://github.com/', '').replace(/\/+$/, '') || fallback?.github || null,
    email: s('email') || fallback?.email || null,
    department: s('department') || fallback?.department || null,
  };
}

/** "Ilias and Arian, the senior engineers". */
export function notifyPhrase(engineers) {
  const names = (engineers || []).map((e) => e.name.split(' ')[0]).filter(Boolean);
  if (!names.length) return null;
  const joined = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
  return `${joined}, the senior engineer${names.length === 1 ? '' : 's'}`;
}

/** Current user: ctx.user, refreshed from GET /v1/me when possible. */
export async function loadMe(ctx) {
  try {
    const me = await ctx.api('/v1/me');
    return me?.user && typeof me.user === 'object' ? me.user : me;
  } catch (err) {
    if (ctx.user) return ctx.user;
    throw err;
  }
}
