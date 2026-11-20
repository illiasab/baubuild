// #/my-work/:projectId: tasks, my changes, review status and the Mentor AI panel.
import {
  h, icon, str, list, items, money, difficultyLabel, duePhrase, dueChip, shortDate, daysUntil, pill, chip, button,
  textLink, loader, emptyView, loadingView, sidePanel, clickable, go, toast, errorText, githubUrl, safeUrl,
  richText, copyText, cardStrip, monoLabel, capital, repoFullName, membershipOf, githubEnabled, GITHUB_OFF_NOTE,
} from './_kit.js';
import { reviewStages, timelineView, aiReviewView, diffLink, statusChip } from './_review.js';

const MENTOR_MAX = 4000; // server: "Ask a question of 1 to 4000 characters."
const tabState = new Map(); // projectId → 'tasks' | 'changes'

export default async function render(root, ctx) {
  const projectId = str(ctx.params?.projectId ?? ctx.params?.id);
  let alive = true;
  let tab = tabState.get(projectId) || 'tasks';
  let taskPanel = null;

  const main = h('div', { class: 'sp-ws-main' });
  const side = h('div', { class: 'sp-ws-side' });
  root.replaceChildren(h('div', { class: 'sp-page sp-workspace' },
    h('nav', { class: 'sp-crumbs', 'aria-label': 'Breadcrumb' }, textLink('My Work', { href: '#/my-work' }), h('span', { 'aria-hidden': 'true' }, '/'), h('span', { class: 'sp-crumb-here' }, 'Project')),
    h('div', { class: 'sp-ws-grid' }, main, side)));
  const crumbHere = root.querySelector('.sp-crumb-here');

  const mentor = mentorPanel(side, ctx, projectId, () => alive);

  const board = loader(main, {
    isAlive: () => alive && main.isConnected,
    load: async () => {
      const enc = encodeURIComponent(projectId);
      const [project, tasks, changes, subs, ghOn] = await Promise.all([
        ctx.api(`/v1/projects/${enc}`),
        ctx.api(`/v1/projects/${enc}/tasks`).catch((err) => {
          if (err?.code === 'not_a_member') return { notMember: true };
          throw err;
        }),
        ctx.api(`/v1/projects/${enc}/changes`).catch((err) => ({ failed: err })),
        ctx.api('/v1/me/submissions').catch(() => null),
        githubEnabled(ctx),
      ]);
      return {
        project,
        notMember: !!tasks?.notMember,
        tasks: items(tasks),
        changes: changes?.failed ? null : changes,
        submissions: subs ? items(subs).filter((s) => str(s?.project_id) === projectId) : null,
        ghOn,
      };
    },
    view: (data, refresh) => {
      const { project } = data;
      if (crumbHere) crumbHere.textContent = str(project.department, 'Project');
      const mine = membershipOf(project);
      const meta = [str(project.class_code) || null, (repoFullName(project) || str(project.repo?.name)).split('/').pop() || null, difficultyLabel(project.difficulty), money(project.bounty), duePhrase(project.due_at)]
        .filter(Boolean).join(' · ');

      const head = h('header', { class: 'sp-ws-head' },
        h('div', { class: 'sp-ws-titlerow' },
          h('h1', { class: 'sp-title sp-title--ws' }, str(project.title)),
          mine === 'member' ? pill('You’re in', 'yellow') : mine === 'pending_approval' ? pill('Request sent', 'lavender') : null),
        h('p', { class: 'sp-mono sp-ws-meta' }, meta));

      const tabs = h('div', { class: 'segmented sp-seg sp-tabs', role: 'tablist', 'aria-label': 'Workspace' },
        tabButton('tasks', 'My Tasks'), tabButton('changes', 'My Changes'));
      function tabButton(id, label) {
        return h('button', {
          type: 'button', role: 'tab', class: 'sp-seg-btn', 'aria-selected': String(tab === id), 'aria-pressed': String(tab === id),
          onclick: () => { tab = id; tabState.set(projectId, id); board.repaint(); },
        }, label);
      }

      if (data.notMember) {
        return [head, emptyView({
          title: mine === 'pending_approval' ? 'Your request is waiting for approval' : 'You’re not in this project yet',
          body: mine === 'pending_approval' ? 'The workspace opens once you’re approved.' : 'Join the project to see your tasks and talk to the mentor.',
          action: { label: 'View project', kind: 'primary', onClick: () => go(ctx, `#/projects/${encodeURIComponent(projectId)}`) },
        })];
      }

      const latest = data.submissions?.[0] || null;
      const panelContent = tab === 'tasks'
        ? tasksCard(data.tasks, (task) => openTask(task, data))
        : changesCard(project, data.changes, ctx, data.tasks, data.ghOn);

      return [head, tabs, panelContent, reviewCard(latest, project, data.submissions)];
    },
  });

  function openTask(task, data) {
    taskPanel?.close();
    const panel = sidePanel({ eyebrow: ['Task', data.project.department].filter(Boolean).join(' · ').toUpperCase(), label: 'Task details', onClose: () => { panel.close(); taskPanel = null; } });
    taskPanel = panel;
    let busy = false;
    let error = null;
    // Ticked acceptance checks: optimistic, saved with PATCH {criteria_done}; a failed save reverts to the last
    // state the server confirmed. Only the newest request's outcome is applied.
    let confirmedDone = criteriaDone(task);
    let tickSeq = 0;

    const syncBoard = () => {
      const current = board.data;
      if (current) board.data = { ...current, tasks: current.tasks.map((x) => (str(x.id) === str(task.id) ? task : x)) };
    };

    const toggleCriterion = async (index) => {
      const before = criteriaDone(task);
      const next = before.includes(index) ? before.filter((i) => i !== index) : [...before, index].sort((a, b) => a - b);
      task = { ...task, criteria_done: next };
      paint();
      const mySeq = ++tickSeq;
      try {
        const updated = await ctx.api(`/v1/tasks/${encodeURIComponent(task.id)}`, { method: 'PATCH', body: { criteria_done: next } });
        const saved = updated && typeof updated === 'object' && Array.isArray(updated.criteria_done) ? criteriaDone(updated) : next;
        confirmedDone = saved;
        if (mySeq === tickSeq) task = { ...task, ...(updated && typeof updated === 'object' ? updated : {}), criteria_done: saved };
      } catch (err) {
        if (mySeq === tickSeq) {
          task = { ...task, criteria_done: confirmedDone };
          toast(ctx, err?.code === 'network' ? 'Couldn’t save that check. Check your connection and try again.' : errorText(err), 'error');
        }
      }
      if (!alive) return;
      syncBoard();
      paint();
    };

    const paint = () => {
      const t = task;
      const status = taskStatus(t);
      const dueIso = taskDue(t);
      const days = daysUntil(dueIso);
      const overdue = days != null && days < 0 && str(t.status) !== 'done';
      const criteria = list(t.acceptance_criteria ?? t.criteria).map((c) => (typeof c === 'string' ? c : str(c?.text ?? c?.criterion))).filter(Boolean);
      const done = new Set(criteriaDone(t));
      const metCount = criteria.filter((_, i) => done.has(i)).length;
      const resources = list(t.resources).map((r) => (typeof r === 'string' ? { title: r, url: r } : { title: str(r?.label ?? r?.title ?? r?.url), url: r?.url })).filter((r) => r.title);
      const description = str(t.description);
      const priority = priorityChip(t.priority);

      panel.setBody(
        h('h2', { class: 'sp-panel-title' }, str(t.title)),
        h('div', { class: 'sp-chips' }, statusPill(status), priority, str(t.lane) === 'backlog' ? chip('Backlog') : chip('Assigned to you')),
        h('section', { class: ['sp-deadline', overdue && 'is-overdue'] },
          monoLabel('DEADLINE'),
          dueIso
            ? h('div', {}, h('span', { class: 'sp-strong' }, shortDate(dueIso)), h('span', { class: 'sp-muted' }, ` · ${str(t.status) === 'done' ? 'Completed' : deadlineText(days)}`))
            : h('span', { class: 'sp-muted' }, 'No deadline')),
        description ? h('section', { class: 'sp-panel-section' }, h('h3', { class: 'sp-panel-h' }, 'Description'), h('p', { class: 'sp-prose' }, description)) : null,
        criteria.length ? h('section', { class: 'sp-panel-section' },
          h('h3', { class: 'sp-panel-h' }, 'Acceptance criteria', h('span', { class: 'sp-checklist-count' }, `${metCount} of ${criteria.length}`)),
          h('ul', { class: 'sp-checklist' }, criteria.map((c, i) => h('li', { class: done.has(i) && 'is-met' },
            h('label', { class: 'sp-criterion' },
              h('input', { type: 'checkbox', checked: done.has(i), onchange: () => toggleCriterion(i) }),
              h('span', { class: 'sp-check', 'aria-hidden': 'true' }, done.has(i) ? icon('check', 14) : null),
              h('span', { class: 'sp-criterion-text' }, c)))))) : null,
        resources.length ? h('section', { class: 'sp-panel-section' }, h('h3', { class: 'sp-panel-h' }, 'Resources'),
          h('ul', { class: 'sp-resources' }, resources.map((r) => h('li', {}, safeUrl(r.url) ? textLink(r.title, { href: safeUrl(r.url), external: true }) : r.title)))) : null,
        !description && !criteria.length && !resources.length
          ? h('p', { class: 'sp-note' }, 'This task has no brief yet. Ask the mentor where to start, or check the project description.')
          : null,
      );

      const next = str(t.status) === 'in_progress' ? { label: 'Mark as Done', status: 'done' } : str(t.status) === 'done' ? null : { label: 'Start Task', status: 'in_progress' };
      panel.setFooter(
        error ? h('p', { class: 'sp-footnote sp-footnote--error', role: 'alert' }, error) : null,
        h('div', { class: 'sp-panel-actions' },
          button('Ask Mentor AI', { kind: 'secondary', onClick: () => { panel.close(); taskPanel = null; mentor.focusTask(t); } }),
          next ? button(busy ? 'Saving…' : next.label, { kind: 'primary', disabled: busy, onClick: () => advance(next.status) }) : pill('Done', 'green')));
    };

    const advance = async (status) => {
      busy = true;
      error = null;
      paint();
      try {
        const updated = await ctx.api(`/v1/tasks/${encodeURIComponent(task.id)}`, { method: 'PATCH', body: { status } });
        task = { ...task, ...(updated && typeof updated === 'object' ? updated : { status }) };
        if (Array.isArray(updated?.criteria_done)) confirmedDone = criteriaDone(updated);
        syncBoard();
        toast(ctx, status === 'done' ? 'Task marked as done' : 'Task started', 'success');
      } catch (err) {
        error = err?.code === 'network' ? 'Couldn’t update the task. Check your connection and try again.' : errorText(err);
      } finally {
        busy = false;
        if (alive) paint();
      }
    };
    paint();
  }

  return () => {
    alive = false;
    taskPanel?.close();
    mentor.dispose();
  };
}

// ---------------------------------------------------------------------------------------------------------------

function taskStatus(task) {
  const s = str(task.status);
  if (s === 'in_progress') return 'in_progress';
  if (s === 'done') return 'done';
  return str(task.lane) === 'backlog' ? 'backlog' : 'todo';
}

function criteriaDone(task) {
  return [...new Set(list(task?.criteria_done).map(Number).filter((n) => Number.isInteger(n) && n >= 0))].sort((a, b) => a - b);
}

// due_at (timestamp) when present, else due_date (YYYY-MM-DD).
function taskDue(task) {
  return task?.due_at || task?.due_date || null;
}

function priorityChip(value) {
  const p = str(value).toLowerCase();
  if (p === 'high') return chip('High priority', 'warn');
  if (p === 'medium') return chip('Medium priority');
  if (p === 'low') return chip('Low priority');
  return null;
}

function statusPill(status) {
  if (status === 'in_progress') return pill('In Progress', 'blue');
  if (status === 'done') return pill('Done', 'green');
  return pill('To Do', 'outline');
}

function deadlineText(days) {
  if (days == null) return '';
  if (days === 0) return 'Due today';
  if (days === 1) return 'Due tomorrow';
  if (days > 1) return `Due in ${days} days`;
  return days === -1 ? 'Overdue by 1 day' : `Overdue by ${-days} days`;
}

function tasksCard(tasks, onOpen) {
  if (!tasks.length) {
    return h('section', { class: 'sp-card' }, emptyView({ title: 'No tasks yet', body: 'Your dev lead hasn’t assigned tasks on this project yet.' }));
  }
  const assigned = tasks.filter((t) => str(t.lane) !== 'backlog');
  const backlog = tasks.filter((t) => str(t.lane) === 'backlog');
  const rowFor = (t) => {
    const status = taskStatus(t);
    const total = list(t.acceptance_criteria).length;
    const met = total ? criteriaDone(t).filter((i) => i < total).length : 0;
    const sub = [
      status === 'done' ? 'Completed' : status === 'backlog' ? 'Backlog' : (dueChip(taskDue(t)) || 'No deadline'),
      str(t.priority).toLowerCase() === 'high' && status !== 'done' ? 'High priority' : null,
      total ? `${met}/${total} checks` : null,
    ].filter(Boolean).join(' · ');
    const el = h('div', { class: 'sp-task' },
      h('div', { class: 'sp-task-text' }, h('span', { class: 'sp-task-title' }, str(t.title)), h('span', { class: 'sp-task-sub' }, sub)),
      statusPill(status));
    return clickable(el, () => onOpen(t), `${str(t.title)}, ${status.replace('_', ' ')}`);
  };
  return h('div', { class: 'sp-stack' },
    assigned.length ? h('section', { class: 'sp-card sp-card--flush' }, cardStrip('ASSIGNED TO YOU'), assigned.map(rowFor)) : null,
    backlog.length ? h('section', { class: 'sp-card sp-card--flush' }, cardStrip('BACKLOG'), backlog.map(rowFor)) : null);
}

function changesCard(project, changes, ctx, tasks, ghOn) {
  const repoName = repoFullName(changes) || repoFullName(project) || str(changes?.repo ?? project.repo?.name);
  const url = githubUrl(repoName);
  const branches = list(changes?.branches);
  const connected = changes ? changes.connected === true : project.repo?.connected === true;
  const active = tasks.find((t) => str(t.status) === 'in_progress');
  const suggested = active ? branchName(active) : null;
  const cloneUrl = url ? `${url}.git` : null;

  const repo = h('section', { class: 'sp-card' },
    h('div', { class: 'sp-card-headrow' },
      h('div', {}, monoLabel('REPOSITORY'), h('h2', { class: 'sp-card-title sp-mono' }, repoName || 'No repository yet')),
      url ? h('a', { class: 'btn btn-secondary btn-sm', href: url, target: '_blank', rel: 'noopener noreferrer' }, icon('external', 16), 'Open in GitHub') : null),
    cloneUrl ? h('div', { class: 'sp-copyrow' },
      h('label', { class: 'sp-field-label', for: 'sp-clone' }, 'Clone URL'),
      h('div', { class: 'sp-copy' },
        h('input', { id: 'sp-clone', class: 'input sp-input sp-mono', readonly: true, value: cloneUrl, onfocus: (e) => e.target.select() }),
        button('Copy', { kind: 'secondary', onClick: () => copyText(ctx, cloneUrl, 'Clone URL copied') }))) : null,
    suggested ? h('div', { class: 'sp-copyrow' },
      h('span', { class: 'sp-field-label' }, 'Branch for your current task'),
      h('div', { class: 'sp-copy' },
        h('code', { class: 'sp-code sp-code--block' }, suggested),
        button('Copy', { kind: 'secondary', onClick: () => copyText(ctx, suggested, 'Branch name copied') }))) : null,
    h('p', { class: 'sp-note' }, 'Code in your own editor, push the branch to GitHub, and it shows up below for review. In-browser editing is coming to the web later.'),
    !ghOn ? h('p', { class: 'sp-footnote' }, GITHUB_OFF_NOTE, ' Your reviews still show up below when you submit a branch.')
      : !connected ? h('p', { class: 'sp-footnote' }, 'GitHub isn’t connected. ', textLink('Connect it on your profile', { href: '#/profile' }), ' so BAU BUILD can see your pushes.')
        : changes?.github_error ? h('p', { class: 'sp-footnote' }, 'Couldn’t read your latest commits from GitHub right now. Branches you submitted still show below.') : null);

  const list_ = h('section', { class: 'sp-card sp-card--flush' }, cardStrip('MY CHANGES'),
    branches.length
      ? branches.flatMap((b) => list(b?.commits).map((c) => {
        const branch = str(c?.branch ?? b?.name);
        const message = str(c?.message).trim();
        const sha = /^[0-9a-f]{7,40}$/i.test(str(c?.sha)) ? str(c.sha) : '';
        const commitUrl = sha && url ? `${url}/commit/${sha}` : null;
        const diff = safeUrl(c?.url ?? c?.diff_url);
        return h('div', { class: 'sp-task' },
          h('div', { class: 'sp-task-text' },
            message && message !== branch ? h('span', { class: 'sp-task-title sp-commit-msg' }, message) : null,
            h('span', { class: [message && message !== branch ? 'sp-task-sub sp-mono' : 'sp-task-title sp-mono', 'sp-commit-branch'] }, icon('branch', 14), branch),
            h('span', { class: 'sp-task-sub' },
              sha ? (commitUrl ? h('a', { class: 'sp-commit-sha sp-link', href: commitUrl, target: '_blank', rel: 'noopener noreferrer', title: 'Open the commit on GitHub' }, sha.slice(0, 7)) : h('span', { class: 'sp-commit-sha' }, sha.slice(0, 7))) : null,
              sha && c?.created_at ? ' · ' : null,
              c?.created_at ? shortDate(c.created_at) : '')),
          h('div', { class: 'sp-inline' }, changeChip(c), diff ? diffLink(diff) : null));
      }))
      : h('div', { class: 'sp-card-pad' }, h('p', { class: 'sp-muted' }, 'Nothing pushed yet. Push a branch and it appears here.')));
  return h('div', { class: 'sp-stack' }, repo, list_);
}

// Per-commit status: approved | changes_requested | with_dev_lead (older servers: state = lead_status).
function changeChip(commit) {
  const s = str(commit?.status);
  const lead = s === 'approved' ? 'approved' : s === 'changes_requested' ? 'sent_back' : s === 'with_dev_lead' ? 'pending' : str(commit?.state);
  return statusChip({ steps: [{ step: 'lead_decision', status: lead }] });
}

function branchName(task) {
  const slug = str(task.title).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40).replace(/-+$/, '');
  return `task/${str(task.id).toLowerCase().replace(/[^a-z0-9_-]/g, '')}-${slug}`;
}

function reviewCard(latest, project, submissions) {
  if (submissions == null) return null;
  if (!latest) {
    return h('section', { class: 'sp-card' }, cardStrip('REVIEW STATUS'),
      h('p', { class: 'sp-muted sp-card-pad0' }, 'No review yet. When you push a branch, the AI review and your dev lead’s decision show up here.'));
  }
  const r = reviewStages(latest, project);
  return h('section', { class: 'sp-card' },
    h('div', { class: 'sp-card-headrow' }, monoLabel('REVIEW STATUS'), h('div', { class: 'sp-inline' }, statusChip(latest), diffLink(r.diffUrl))),
    h('p', { class: 'sp-strong' }, r.headline),
    timelineView(r.stages),
    aiReviewView(r.aiReview),
    h('p', { class: 'sp-small' }, textLink('See all reviews', { href: '#/review' })));
}

// ---------------------------------------------------------------------------------------------------------------
// Mentor AI
// ---------------------------------------------------------------------------------------------------------------

function mentorPanel(host, ctx, projectId, isAlive) {
  const enc = encodeURIComponent(projectId);
  const log = h('div', { class: 'sp-mentor-log', role: 'log', 'aria-live': 'polite', 'aria-label': 'Mentor conversation' });
  const input = h('textarea', { class: 'sp-mentor-input', rows: '2', maxlength: String(MENTOR_MAX), placeholder: 'I’m stuck on…', 'aria-label': 'Ask the mentor' });
  const counter = h('span', { class: 'sp-mentor-count', 'aria-live': 'polite' });
  const send = button('Send', { kind: 'primary', type: 'submit' });
  const context = h('div', { class: 'sp-mentor-context', hidden: true });
  const form = h('form', { class: 'sp-mentor-form' },
    h('label', { class: 'sp-mentor-label', for: 'sp-mentor-input' }, 'Ask the mentor'), context,
    h('div', { class: 'sp-mentor-row' }, input, send), counter);
  input.id = 'sp-mentor-input';
  host.replaceChildren(h('section', { class: 'sp-mentor', 'aria-label': 'Mentor AI' }, monoLabel('MENTOR AI', 'sp-on-dark'), log, form));

  let messages = [];
  let replying = false;
  let taskContext = null;
  let controller = null;

  const updateCounter = () => {
    const n = input.value.length;
    counter.textContent = n > MENTOR_MAX - 400 ? `${n} / ${MENTOR_MAX}` : '';
    send.disabled = replying || !input.value.trim();
  };

  const bubble = (m) => h('div', { class: ['sp-bubble', m.role === 'user' ? 'sp-bubble--me' : 'sp-bubble--mentor', m.error && 'sp-bubble--error'] },
    m.status && !m.text ? h('span', { class: 'sp-bubble-status' }, h('span', { class: 'spinner sp-spinner--sm', 'aria-hidden': 'true' }), m.status) : null,
    m.text ? h('div', { class: 'sp-bubble-text' }, richText(m.text)) : null,
    list(m.citations).length ? h('div', { class: 'sp-citations' }, list(m.citations).map((c) => h('span', { class: 'sp-citation', title: str(c?.title) }, str(c?.rule_id || c?.title)))) : null,
    m.retry ? h('button', { type: 'button', class: 'sp-link sp-link--on-dark', onclick: m.retry }, 'Try again') : null);

  const paint = () => {
    if (!isAlive()) return;
    if (!messages.length) {
      log.replaceChildren(h('p', { class: 'sp-mentor-empty' }, 'Ask about your task. The mentor gives hints and questions, not the full solution.'));
    } else {
      log.replaceChildren(...messages.map(bubble));
    }
    log.scrollTop = log.scrollHeight;
    updateCounter();
  };

  const paintContext = () => {
    if (!taskContext) { context.hidden = true; context.replaceChildren(); return; }
    context.hidden = false;
    context.replaceChildren(h('span', {}, `About: ${str(taskContext.title)}`),
      h('button', { type: 'button', class: 'sp-icon-btn sp-icon-btn--sm', 'aria-label': 'Stop asking about this task', onclick: () => { taskContext = null; paintContext(); } }, icon('close', 14)));
  };

  async function loadHistory() {
    log.replaceChildren(loadingView('Loading the conversation…'));
    try {
      const out = await ctx.api(`/v1/projects/${enc}/mentor/messages`);
      messages = items(out).map((m) => ({ role: str(m?.role) === 'user' || str(m?.role) === 'student' ? 'user' : 'assistant', text: str(m?.text ?? m?.body), citations: list(m?.citations) }));
      paint();
    } catch (err) {
      if (!isAlive()) return;
      if (err?.code === 'not_a_member' || err?.status === 403) {
        log.replaceChildren(h('p', { class: 'sp-mentor-empty' }, 'Join the project to talk to the mentor.'));
        form.hidden = true;
        return;
      }
      log.replaceChildren(h('p', { class: 'sp-mentor-empty' }, 'Couldn’t load earlier messages. ', h('button', { type: 'button', class: 'sp-link sp-link--on-dark', onclick: loadHistory }, 'Try again')));
    }
  }

  async function ask(text) {
    const reply = { role: 'assistant', text: '', status: 'Thinking…', citations: [] };
    const mine = { role: 'user', text };
    messages = [...messages.filter((m) => !m.error), mine, reply];
    replying = true;
    paint();
    controller = new AbortController();
    let failed = null;
    try {
      const body = { text };
      if (taskContext?.id) body.task_id = str(taskContext.id);
      await ctx.sse(`/v1/projects/${enc}/mentor/messages`, body, {
        signal: controller.signal,
        onEvent: ({ event, data }) => {
          if (event === 'status') reply.status = str(data?.label ?? data?.text ?? data, 'Thinking…');
          else if (event === 'citation') reply.citations = [...reply.citations, data];
          else if (event === 'delta') reply.text += str(typeof data === 'string' ? data : data?.text);
          else if (event === 'done') { if (data?.text && !reply.text) reply.text = str(data.text); reply.status = null; }
          else if (event === 'error') failed = data;
          else if (event === 'message' && data && typeof data === 'object' && (data.text || data.reply)) reply.text = str(data.text ?? data.reply);
          paint();
        },
      });
      if (!reply.text && !failed) failed = { message: 'empty' };
    } catch (err) {
      if (err?.name === 'AbortError') return;
      failed = err;
    } finally {
      replying = false;
      controller = null;
    }
    if (failed) {
      const quota = failed?.code === 'quota_exceeded' || failed?.status === 429;
      reply.text = '';
      reply.status = null;
      reply.error = true;
      reply.role = 'assistant';
      reply.text = quota ? str(failed.message, 'You’ve reached today’s mentor limit.') : 'Mentor AI isn’t available right now.';
      reply.retry = quota ? null : () => { messages = messages.filter((m) => m !== reply && m !== mine); ask(text); };
    } else {
      reply.status = null;
    }
    paint();
  }

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = input.value.trim();
    if (!text || replying) return;
    if (text.length > MENTOR_MAX) { toast(ctx, `Keep it under ${MENTOR_MAX} characters.`, 'error'); return; }
    input.value = '';
    ask(text);
  });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); form.requestSubmit(); }
  });
  input.addEventListener('input', updateCounter);

  updateCounter();
  loadHistory();

  return {
    focusTask(task) {
      taskContext = task;
      paintContext();
      input.focus();
    },
    dispose() { controller?.abort(); },
  };
}

