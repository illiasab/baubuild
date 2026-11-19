// Inquiry detail (#/inquiries/:id): header, today card + daily log (when the backend sends progress) or the
// status summary, and the editable Details form (PATCH /v1/inquiries/{id}; only while `submitted`, else 409
// inquiry_locked) with Withdraw (DELETE /v1/inquiries/{id}, also only while `submitted`).
import {
  el, clear, icon, go, toast, showLoading, showError, confirmAction, formatShortDate, todayIso, toStaffInquiry,
  statusChip, urgentChip, isUrgent, detailMeta, summaryOf, PROJECT_TYPES, DIFFICULTIES, MAX_BOUNTY, patchBody,
  rewardLabel, normalizeClassCode, isValidClassCode, formatUsd,
} from './shared.js';
import { field, input, textarea, checkGroup, segmented } from './form.js';

export default async function render(root, ctx) {
  let alive = true;
  const id = ctx.params?.id || decodeURIComponent((location.hash.split('?')[0].match(/inquiries\/([^/]+)/) || [])[1] || '');

  if (id.startsWith('localdraft-')) {
    go(ctx, `#/new-inquiry?draft=${encodeURIComponent(id)}`);
    return undefined;
  }

  async function load() {
    showLoading(root, ctx, 'Loading the inquiry…');
    let inq;
    try {
      inq = toStaffInquiry(await ctx.api(`/v1/inquiries/${encodeURIComponent(id)}`));
    } catch (err) {
      if (!alive) return;
      if (err?.status === 404) {
        clear(root);
        root.append(el('div', { class: 'st-page' }, breadcrumb('Not found'), el('div', { class: 'st-card st-empty' },
          el('h2', { class: 'st-h3' }, 'This inquiry is gone'),
          el('p', { class: 'st-muted' }, 'It was withdrawn or never existed.'),
          el('a', { href: '#/inquiries', class: 'st-btn st-btn--dark' }, 'Back to inquiries'))));
        return;
      }
      showError(root, ctx, err, load);
      return;
    }
    if (alive) draw(inq);
  }

  function draw(inq) {
    clear(root);
    const page = el('div', { class: 'st-page' });
    root.append(page);
    page.append(el('div', { class: 'page-head st-head' }, el('h1', { class: 'st-h1' }, 'Inquiry')));
    page.append(el('div', { class: 'st-detail-head' },
      breadcrumb(inq.draft.title),
      el('div', { class: 'st-detail-title' }, el('h2', { class: 'st-h2 st-h2--lg' }, inq.draft.title), statusChip(inq.status), isUrgent(inq) ? urgentChip() : null),
      el('span', { class: 'st-mono' }, detailMeta(inq))));

    const left = el('div', { class: 'st-col' });
    if (inq.status === 'InProgress' && inq.progress) {
      left.append(todayCard(inq), logCard(inq));
    } else {
      left.append(summaryCard(inq), briefCard(inq));
    }
    page.append(el('div', { class: 'st-detail-grid' }, left, detailsForm(inq)));
  }

  // ---- left column ----
  function summaryCard(inq) {
    const s = summaryOf(inq);
    const deadline = inq.draft.deadline ? `Due ${formatShortDate(inq.draft.deadline)}` : null;
    return el('section', { class: `st-card st-summary${s.warning ? ' st-summary--warn' : ''}` },
      el('span', { class: 'st-label' }, 'Status'),
      el('h3', { class: 'st-h3' }, s.title),
      s.body ? el('p', { class: 'st-muted' }, s.body) : null,
      deadline ? el('span', { class: 'st-mono' }, deadline) : null,
      inq.rawStatus === 'submitted' ? el('p', { class: 'st-small st-muted' }, 'You can edit or withdraw it until a reviewer approves it.') : null);
  }

  function briefCard(inq) {
    const r = inq.refined;
    const chips = [
      ...r.types.map((k) => PROJECT_TYPES.find((t) => t.key === k)?.label || k),
      r.difficulty, r.minScore > 0 ? `Score ${r.minScore}+` : 'Any score',
      r.deadline ? `Due ${formatShortDate(r.deadline)}` : null,
    ].filter(Boolean);
    return el('section', { class: 'st-card st-refined', 'aria-label': 'Posted brief' },
      el('div', { class: 'st-refined-top' }, el('span', { class: 'st-mono-label st-mono-label--blue' }, 'WHAT STUDENTS SEE'),
        inq.rewardLabel !== '—' ? el('span', { class: 'st-refined-reward' }, inq.rewardLabel) : null),
      el('h3', { class: 'st-refined-title st-refined-title--sm' }, r.title),
      r.brief ? el('p', { class: 'st-refined-brief st-pre' }, r.brief) : null,
      r.doneLooksLike.length ? el('div', { class: 'st-done' }, el('span', { class: 'st-label' }, 'Done looks like'),
        r.doneLooksLike.map((d) => el('div', { class: 'st-done-item' }, el('span', { class: 'st-done-tick' }, icon('check', 14)), el('span', {}, d)))) : null,
      el('div', { class: 'st-pills' }, chips.map((c) => el('span', { class: 'st-pill' }, c))));
  }

  function todayCard(inq) {
    const p = inq.progress;
    const due = inq.draft.deadline ? ` · due ${formatShortDate(inq.draft.deadline)}` : '';
    const card = el('section', { class: 'st-card st-today', 'aria-labelledby': 'st-today' },
      el('div', { class: 'st-today-head' },
        el('h2', { class: 'st-h2', id: 'st-today' }, `Today · ${formatShortDate(todayIso())}`),
        p.totalDays ? el('span', { class: 'st-mono' }, `Day ${p.dayOf} of ${p.totalDays}${due}`) : null),
      el('div', { class: 'st-progress' },
        el('div', { class: 'st-progress-bar', role: 'img', 'aria-label': `${p.percent} percent done` }, el('div', { class: 'st-progress-fill', style: { width: `${p.percent}%` } })),
        el('span', { class: 'st-progress-pct' }, `${p.percent}%`)));
    if (p.doneToday.length) {
      card.append(el('div', { class: 'st-done' }, el('span', { class: 'st-label' }, 'Done today'),
        p.doneToday.map((d) => el('div', { class: 'st-done-item' }, el('span', { class: 'st-done-tick' }, icon('check', 14)), el('span', {}, d)))));
    }
    const b = p.blocker;
    if (b && !b.resolved) {
      const resolveBtn = b.id ? el('button', { type: 'button', class: 'st-btn st-btn--white', onclick: () => resolve(inq, b, resolveBtn) }, 'Mark resolved') : null;
      const contact = b.contact && /^(https?:|mailto:)/i.test(b.contact) ? b.contact : (b.contact && /@/.test(b.contact) ? `mailto:${b.contact}` : null);
      card.append(el('div', { class: 'st-blocker' },
        el('span', { class: 'st-blocker-label' }, icon('alert', 16), 'Blocker'),
        el('span', {}, b.text),
        b.raisedBy ? el('span', { class: 'st-small' }, `Raised by ${b.raisedBy}${b.at ? ` · ${b.at}` : ''}`) : null,
        (contact || resolveBtn) ? el('div', { class: 'st-blocker-actions' },
          contact ? el('a', { href: contact, class: 'st-btn st-btn--dark', target: '_blank', rel: 'noopener noreferrer' }, 'Contact IT') : null,
          resolveBtn) : null));
    }
    card.append(el('span', { class: 'st-small st-muted' }, 'Summarized by AI from the team’s commits and notes.'));
    return card;
  }

  async function resolve(inq, blocker, btn) {
    btn.disabled = true;
    try {
      await ctx.api(`/v1/inquiries/${encodeURIComponent(inq.id)}/blockers/${encodeURIComponent(blocker.id)}/resolve`, { method: 'POST' });
      toast(ctx, 'Blocker marked resolved.', 'success');
      load();
    } catch (err) {
      btn.disabled = false;
      toast(ctx, err?.status === 404 ? 'Resolving blockers isn’t available yet.' : (err?.message || 'Couldn’t update the blocker.'), 'error');
    }
  }

  function logCard(inq) {
    const log = inq.progress.log;
    return el('section', { class: 'st-card st-log', 'aria-labelledby': 'st-log' },
      el('h2', { class: 'st-h2', id: 'st-log' }, 'Daily log'),
      log.length ? el('ol', { class: 'st-log-list' }, log.map((e) => el('li', { class: 'st-log-item' },
        el('span', { class: 'st-log-dot', 'aria-hidden': 'true' }),
        el('div', {}, el('span', { class: 'st-log-date' }, [formatShortDate(e.date) || e.date, e.blocker ? ` · ${e.blocker}` : ''].join('')), el('p', { class: 'st-muted' }, e.text)))))
        : el('p', { class: 'st-muted' }, 'No updates yet.'));
  }

  // ---- right column: Details ----
  function detailsForm(inq) {
    const editable = inq.rawStatus === 'submitted';
    const d = { ...inq.draft, types: [...inq.draft.types], rewards: [...inq.draft.rewards] };
    let priceText = d.priceUsd ? String(d.priceUsd) : '';
    const status = el('div', { class: 'st-inline-note', role: 'status', hidden: true });
    const setNote = (text, kind = 'warn') => {
      status.textContent = text || '';
      status.hidden = !text;
      status.className = `st-inline-note st-inline-note--${kind}`;
    };

    const title = field('Project title', input({ value: d.title, maxlength: 140, oninput: (e) => { d.title = e.target.value; } }));
    const explain = field('Project explanation', textarea({ value: d.explanation, oninput: (e) => { d.explanation = e.target.value; } }));
    const expect = field('What you expect', textarea({ value: d.expectation, oninput: (e) => { d.expectation = e.target.value; } }), { hint: 'One check per line' });
    const types = checkGroup('Project type', PROJECT_TYPES, d.types, (keys) => { d.types = keys; });
    const priority = segmented('Priority', [{ key: 'Normal', label: 'Normal' }, { key: 'Urgent', label: 'Urgent' }], d.priority, (k) => { d.priority = k; });
    const difficulty = segmented('Difficulty', DIFFICULTIES.map((x) => ({ key: x, label: x })), d.difficulty || 'Medium', (k) => { d.difficulty = k; });
    const deadline = field('Deadline', input({ type: 'date', value: d.deadline || '', oninput: (e) => { d.deadline = e.target.value || null; } }));
    const hasPrice = d.rewards.includes('Price');
    const price = field('Price (USD)', input({ value: priceText, inputmode: 'numeric', placeholder: '$18', disabled: !hasPrice, oninput: (e) => { priceText = e.target.value; } }),
      { hint: hasPrice ? `Reward: ${rewardLabel(d)}` : `Reward: ${rewardLabel(d)} (no cash price)` });
    const classCode = d.projectFor === 'ClassProject'
      ? field('Class code', input({ value: d.classCode || '', maxlength: 12, oninput: (e) => { d.classCode = e.target.value; } })) : null;

    const controls = [title, explain, expect, deadline, price, classCode].filter(Boolean);
    const saveBtn = el('button', { type: 'button', class: 'st-btn st-btn--yellow', onclick: save }, 'Save changes');
    const cancelBtn = el('button', { type: 'button', class: 'st-btn st-btn--outline', onclick: () => draw(inq) }, 'Cancel');
    const withdrawBtn = el('button', { type: 'button', class: 'st-btn st-btn--danger-text', onclick: withdraw }, 'Withdraw inquiry');

    const card = el('section', { class: 'st-card st-form-card', 'aria-labelledby': 'st-details' },
      el('div', { class: 'st-card-titles' }, el('h2', { class: 'st-h2', id: 'st-details' }, 'Details'),
        el('p', { class: 'st-muted' }, editable ? 'Edit and save. Reviewers see changes right away.' : 'Locked: only an inquiry waiting for review can be edited.')),
      status,
      title.wrap, explain.wrap, expect.wrap, types.wrap, priority.wrap, difficulty.wrap,
      el('div', { class: 'st-row-fields' }, deadline.wrap, price.wrap),
      classCode ? classCode.wrap : null,
      editable ? el('div', { class: 'st-form-actions' }, withdrawBtn, el('span', { class: 'st-spacer' }), cancelBtn, saveBtn) : null);

    if (!editable) {
      setNote(inq.rawStatus === 'approved' ? 'Approved by a reviewer: the details are locked while it goes live.' : 'This inquiry is live, so its details are locked.', 'info');
      lock();
    }

    function lock() {
      controls.forEach((c) => c.wrap.querySelectorAll('input, textarea').forEach((n) => { n.disabled = true; }));
      types.setDisabled(true);
      priority.setDisabled(true);
      difficulty.setDisabled(true);
    }

    async function save() {
      const priceNum = parseInt(String(priceText).replace(/[^0-9]/g, ''), 10);
      d.priceUsd = hasPrice ? (Number.isFinite(priceNum) ? priceNum : null) : d.priceUsd;
      title.setError(d.title.trim() ? null : 'Give the project a title.');
      explain.setError(d.explanation.trim() ? null : 'Explain the problem and who has it.');
      types.setError(d.types.length ? null : 'Pick at least one project type.');
      price.setError(hasPrice && (d.priceUsd === null || d.priceUsd < 0 || d.priceUsd > MAX_BOUNTY) ? `Enter a price from $0 to $${MAX_BOUNTY}.` : null);
      deadline.setError(d.deadline && d.deadline <= todayIso() ? 'Pick a date in the future.' : null);
      if (classCode) {
        const code = normalizeClassCode(d.classCode);
        classCode.setError(code && !isValidClassCode(code) ? 'Use the course code, e.g. CMPS433.' : null);
        d.classCode = code || null;
      }
      if (card.querySelector('.st-field-error:not([hidden])')) return;
      saveBtn.disabled = true;
      saveBtn.textContent = 'Saving…';
      setNote(null);
      try {
        const updated = toStaffInquiry(await ctx.api(`/v1/inquiries/${encodeURIComponent(inq.id)}`, { method: 'PATCH', body: patchBody(d) }));
        if (!alive) return;
        toast(ctx, 'Changes saved.', 'success');
        draw(updated);
      } catch (err) {
        if (!alive) return;
        saveBtn.disabled = false;
        saveBtn.textContent = 'Save changes';
        if (err?.code === 'inquiry_locked') {
          setNote('A reviewer already approved this inquiry, so it can’t be edited any more.', 'warn');
          lock();
          card.querySelector('.st-form-actions')?.remove();
        } else {
          setNote(err?.message || 'Couldn’t save. Try again.', 'error');
        }
      }
    }

    async function withdraw() {
      const ok = await confirmAction(ctx, {
        title: 'Withdraw this inquiry?',
        body: `“${inq.draft.title}” is removed before students see it. ${inq.draft.priceUsd ? `The ${formatUsd(inq.draft.priceUsd)} reward is not spent. ` : ''}This can’t be undone.`,
        confirmLabel: 'Withdraw inquiry',
        danger: true,
      });
      if (!ok || !alive) return;
      withdrawBtn.disabled = true;
      try {
        await ctx.api(`/v1/inquiries/${encodeURIComponent(inq.id)}`, { method: 'DELETE' });
        if (!alive) return;
        toast(ctx, 'Inquiry withdrawn.', 'success');
        go(ctx, '#/inquiries');
      } catch (err) {
        if (!alive) return;
        withdrawBtn.disabled = false;
        if (err?.code === 'inquiry_locked') {
          setNote('A reviewer already approved this inquiry, so it can’t be withdrawn here.', 'warn');
          lock();
          card.querySelector('.st-form-actions')?.remove();
        } else {
          setNote(err?.message || 'Couldn’t withdraw it. Try again.', 'error');
        }
      }
    }

    return card;
  }

  await load();
  return () => { alive = false; };
}

function breadcrumb(current) {
  return el('nav', { class: 'st-crumbs', 'aria-label': 'Breadcrumb' },
    el('a', { href: '#/inquiries', class: 'st-link' }, 'Inquiries'), el('span', { 'aria-hidden': 'true' }, '/'), el('span', { 'aria-current': 'page' }, current));
}
