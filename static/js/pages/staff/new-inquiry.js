// New inquiry (#/new-inquiry[?draft=<local id>]): Details + Reward & timing on one page → Refine → Ready to post →
// POST /v1/inquiries. The backend has no refine endpoint yet: POST /v1/inquiries/refine is tried first and a 404
// falls back to the app's client-side rewrite (labelled "Basic rewrite"). Drafts are kept in this browser.
import {
  el, clear, icon, go, toast, hashQuery, formatShortDate, todayIso, PROJECT_TYPES, REWARDS, PROJECT_FOR, MAX_BOUNTY,
  emptyDraft, normalizeClassCode, isValidClassCode, heuristicRefine, parseRefined, rewardLabel, createBody,
  loadDrafts, saveDraft, deleteDraft, fetchSeniorEngineers, notifyPhrase, loadMe, BASIC_REWRITE_LABEL, confirmAction,
  orderTypes,
} from './shared.js';
import { field, input, textarea, checkGroup, segmented } from './form.js';

export default async function render(root, ctx) {
  let alive = true;
  let view = 'form';
  let refined = null;
  let refineAbort = null;
  let attempted = false;
  let me = ctx.user || {};
  let notify = null;

  // ---- initial draft: a resumed local draft, else a fresh one (professors usually post for their class) ----
  const resumeId = hashQuery().draft || null;
  let draftId = null;
  let draft = emptyDraft();
  if (resumeId) {
    const entry = loadDrafts(ctx).find((d) => d.id === resumeId);
    if (entry) {
      draftId = entry.id;
      draft = { ...emptyDraft(), ...entry.draft };
    } else {
      toast(ctx, 'That draft is no longer on this device.', 'warning');
    }
  }
  const classes = () => (Array.isArray(me.classes) ? me.classes.map(normalizeClassCode).filter(Boolean) : []);
  if (!draftId && classes().length) {
    draft.projectFor = 'ClassProject';
    draft.classCode = classes()[0];
  }
  let priceText = draft.priceUsd !== null && draft.priceUsd !== undefined ? String(draft.priceUsd) : '';
  let discountText = draft.schoolDiscountPercent ? String(draft.schoolDiscountPercent) : '';

  // Best-effort extras: fresh profile (class codes, department) and who gets notified on done.
  loadMe(ctx).then((fresh) => {
    if (!alive || !fresh) return;
    const hadClasses = classes().length;
    me = fresh;
    if (!draftId && !hadClasses && classes().length && view === 'form' && !draft.title && !draft.explanation) {
      draft.projectFor = 'ClassProject';
      draft.classCode = classes()[0];
      draw();
    }
  }).catch(() => {});
  fetchSeniorEngineers(ctx.api).then((list) => { if (alive && list) notify = notifyPhrase(list); }).catch(() => {});

  function syncNumbers() {
    const price = parseInt(String(priceText).replace(/[^0-9]/g, ''), 10);
    draft.priceUsd = Number.isFinite(price) ? price : null;
    const disc = parseInt(String(discountText).replace(/[^0-9]/g, ''), 10);
    draft.schoolDiscountPercent = Number.isFinite(disc) ? disc : null;
  }

  // ---- validation (NewInquiryViewModel rules; price capped by the backend's 0..500 bounty) ----
  function problems() {
    syncNumbers();
    const p = {};
    if (!draft.title.trim()) p.title = 'Give the project a title.';
    if (!draft.explanation.trim()) p.explanation = 'Explain the problem and who has it.';
    if (!draft.types.length) p.types = 'Pick at least one project type.';
    if (draft.projectFor === 'ClassProject') {
      const code = normalizeClassCode(draft.classCode);
      if (!code) p.classCode = 'Enter the class code, e.g. CMPS433.';
      else if (!isValidClassCode(code)) p.classCode = 'Use the course code, e.g. CMPS433.';
    }
    if (!draft.rewards.length) p.rewards = 'Pick at least one reward.';
    if (draft.rewards.includes('Price')) {
      if (!draft.priceUsd || draft.priceUsd <= 0) p.price = 'Enter a price in USD.';
      else if (draft.priceUsd > MAX_BOUNTY) p.price = `Keep the price at $${MAX_BOUNTY} or less.`;
    }
    if (draft.rewards.includes('SchoolDiscount')) {
      const d = draft.schoolDiscountPercent;
      if (!d || d < 1 || d > 100) p.discount = 'Enter a discount from 1 to 100%.';
    }
    if (!draft.deadline) p.deadline = 'Pick a deadline.';
    else if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.deadline)) p.deadline = 'Use a date like 2026-12-03.';
    else if (draft.deadline <= todayIso()) p.deadline = 'Pick a date in the future.';
    return p;
  }

  function cleanDraft() {
    syncNumbers();
    return {
      ...draft,
      title: draft.title.trim(),
      explanation: draft.explanation.trim(),
      expectation: draft.expectation.trim(),
      classCode: draft.projectFor === 'ClassProject' ? normalizeClassCode(draft.classCode) || null : null,
      types: orderTypes(draft.types),
    };
  }

  // ---- views ----
  function draw() {
    if (!alive) return;
    clear(root);
    const page = el('div', { class: 'st-page' });
    root.append(page);
    if (view === 'form') drawForm(page);
    else if (view === 'refining') drawRefining(page);
    else drawReady(page);
    window.scrollTo?.(0, 0);
  }

  function drawForm(page) {
    page.append(el('div', { class: 'page-head st-head' },
      el('div', { class: 'st-head-titles' },
        draftId ? el('span', { class: 'st-eyebrow' }, 'Draft · saved on this device') : null,
        el('h1', { class: 'st-h1' }, 'New inquiry'))));

    const errs = attempted ? problems() : {};

    // Details card
    const title = field('Project title', input({ value: draft.title, maxlength: 80, placeholder: 'e.g. Lab equipment booking app', oninput: (e) => { draft.title = e.target.value; } }));
    const explain = field('Project explanation', textarea({ value: draft.explanation, maxlength: 2000, placeholder: 'What is the problem, and who has it?', oninput: (e) => { draft.explanation = e.target.value; } }));
    const expect = field('What you expect', textarea({ value: draft.expectation, maxlength: 2000, placeholder: 'What does done look like? Any must-haves?', oninput: (e) => { draft.expectation = e.target.value; } }));
    const types = checkGroup('Project type', PROJECT_TYPES, draft.types, (keys) => { draft.types = keys; });
    title.setError(errs.title);
    explain.setError(errs.explanation);
    types.setError(errs.types);
    const details = el('section', { class: 'st-card st-form-card', 'aria-labelledby': 'ni-details' },
      el('div', { class: 'st-card-titles' }, el('h2', { class: 'st-h2', id: 'ni-details' }, 'Details'),
        el('p', { class: 'st-muted' }, 'Describe the work in your own words. AI tidies the wording before it is posted.')),
      title.wrap, explain.wrap, expect.wrap, types.wrap);

    // Reward & timing card
    const classHost = el('div', { class: 'st-field' });
    const drawClass = () => {
      clear(classHost);
      classHost.hidden = draft.projectFor !== 'ClassProject';
      if (classHost.hidden) return;
      const code = field('Class code', input({
        value: draft.classCode || '', maxlength: 12, placeholder: 'e.g. CMPS433', autocapitalize: 'characters',
        oninput: (e) => { draft.classCode = e.target.value; },
      }));
      code.setError(errs.classCode);
      const suggestions = classes().filter((c) => c !== normalizeClassCode(draft.classCode));
      classHost.append(code.wrap);
      if (suggestions.length) {
        classHost.append(el('div', { class: 'st-suggest' }, el('span', { class: 'st-hint' }, 'Your classes:'),
          suggestions.map((c) => el('button', { type: 'button', class: 'st-suggest-chip', onclick: () => { draft.classCode = c; drawClass(); } }, c))));
      }
    };
    drawClass();
    const projectFor = segmented('Project for', [{ key: 'ClassProject', label: 'Class project' }, { key: 'BauProject', label: 'BAU project' }],
      draft.projectFor, (key) => { draft.projectFor = key; drawClass(); });

    const amountsHost = el('div', { class: 'st-row-fields' });
    const drawAmounts = () => {
      clear(amountsHost);
      if (draft.rewards.includes('Price')) {
        const price = field('Price (USD)', input({ value: priceText, inputmode: 'numeric', placeholder: '$18', oninput: (e) => { priceText = e.target.value; } }), { hint: `Up to $${MAX_BOUNTY}` });
        price.setError(errs.price);
        amountsHost.append(price.wrap);
      }
      if (draft.rewards.includes('SchoolDiscount')) {
        const disc = field('School discount', input({ value: discountText, inputmode: 'numeric', placeholder: 'e.g. 5%', oninput: (e) => { discountText = e.target.value; } }));
        disc.setError(errs.discount);
        amountsHost.append(disc.wrap);
      }
      amountsHost.hidden = !amountsHost.childNodes.length;
    };
    drawAmounts();
    const rewards = checkGroup('Reward', REWARDS, draft.rewards, (keys) => { draft.rewards = keys; drawAmounts(); }, { hint: 'Tick one or more' });
    rewards.setError(errs.rewards);
    const priority = segmented('Priority', [{ key: 'Normal', label: 'Normal' }, { key: 'Urgent', label: 'Urgent' }], draft.priority, (k) => { draft.priority = k; });
    const tomorrow = new Date(Date.now() + 86400000);
    const minDate = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`;
    const deadline = field('Deadline', input({ type: 'date', value: draft.deadline || '', min: minDate, oninput: (e) => { draft.deadline = e.target.value || null; } }));
    deadline.setError(errs.deadline);
    const difficulty = segmented('Difficulty',
      [{ key: '', label: 'AI suggests' }, { key: 'Easy', label: 'Easy' }, { key: 'Medium', label: 'Medium' }, { key: 'Hard', label: 'Hard' }],
      draft.difficulty || '', (k) => { draft.difficulty = k || null; });

    const reward = el('section', { class: 'st-card st-form-card', 'aria-labelledby': 'ni-reward' },
      el('div', { class: 'st-card-titles' }, el('h2', { class: 'st-h2', id: 'ni-reward' }, 'Reward & timing')),
      projectFor.wrap, classHost, rewards.wrap, amountsHost, priority.wrap,
      el('div', { class: 'st-row-fields' }, deadline.wrap), difficulty.wrap);

    page.append(el('div', { class: 'st-two-col' }, details, reward));

    const refineBtn = el('button', { type: 'button', class: 'st-btn st-btn--yellow', onclick: startRefine }, icon('sparkle', 16), 'Refine with AI');
    page.append(el('div', { class: 'st-footer' },
      el('span', { class: 'st-muted st-footer-note' }, 'You check the AI’s version before anything is posted.'),
      draftId ? el('button', { type: 'button', class: 'st-btn st-btn--ghost', onclick: removeDraft }, 'Delete draft') : null,
      el('button', { type: 'button', class: 'st-btn st-btn--outline', onclick: onSaveDraft }, 'Save draft'),
      refineBtn));

    if (attempted && Object.keys(errs).length) {
      const first = page.querySelector('[aria-invalid="true"], .st-field-error:not([hidden])');
      first?.scrollIntoView?.({ block: 'center' });
    }
  }

  function onSaveDraft() {
    syncNumbers();
    if (!draft.title.trim() && !draft.explanation.trim() && !draft.expectation.trim()) {
      toast(ctx, 'Type something first: a title or a short explanation.', 'warning');
      return;
    }
    try {
      const entry = saveDraft(ctx, cleanDraftLoose(), draftId);
      draftId = entry.id;
      toast(ctx, 'Draft saved on this device.', 'success');
      try { history.replaceState(null, '', `${location.pathname}${location.search}#/new-inquiry?draft=${encodeURIComponent(draftId)}`); } catch { /* ignore */ }
      draw();
    } catch {
      toast(ctx, 'Couldn’t save the draft: this browser blocks local storage.', 'error');
    }
  }

  function cleanDraftLoose() {
    return { ...draft, classCode: draft.classCode ? normalizeClassCode(draft.classCode) : null };
  }

  async function removeDraft() {
    const ok = await confirmAction(ctx, { title: 'Delete this draft?', body: 'It is only saved in this browser. This can’t be undone.', confirmLabel: 'Delete draft', danger: true });
    if (!ok || !alive) return;
    deleteDraft(ctx, draftId);
    toast(ctx, 'Draft deleted.', 'success');
    go(ctx, '#/inquiries');
  }

  async function startRefine() {
    attempted = true;
    if (Object.keys(problems()).length) {
      draw();
      return;
    }
    const clean = cleanDraft();
    view = 'refining';
    draw();
    const controller = new AbortController();
    refineAbort = controller;
    const started = Date.now();
    let result = null;
    try {
      const res = await ctx.api('/v1/inquiries/refine', { method: 'POST', body: { draft: wireDraft(clean) }, signal: controller.signal });
      result = parseRefined(res, clean);
    } catch (err) {
      if (controller.signal.aborted || err?.name === 'AbortError') return;
      if (![404, 405, 501].includes(err?.status)) {
        if (!alive || refineAbort !== controller) return;
        refineAbort = null;
        view = 'form';
        draw();
        toast(ctx, err?.message || 'Couldn’t refine the inquiry. Try again.', 'error');
        return;
      }
    }
    if (!result) result = heuristicRefine(clean);
    // Keep the spinner up briefly so the state change is readable.
    const wait = 700 - (Date.now() - started);
    if (wait > 0) await new Promise((r) => setTimeout(r, wait));
    if (!alive || refineAbort !== controller || controller.signal.aborted) return;
    refineAbort = null;
    draft = clean;
    refined = result;
    view = 'ready';
    draw();
  }

  function cancelRefine() {
    refineAbort?.abort();
    refineAbort = null;
    view = 'form';
    draw();
  }

  function drawRefining(page) {
    page.append(el('div', { class: 'page-head st-head' }, el('h1', { class: 'st-h1' }, 'New inquiry')));
    page.append(el('div', { class: 'st-card st-refining', role: 'status', 'aria-live': 'polite' },
      el('span', { class: 'st-spinner st-spinner--lg', 'aria-hidden': 'true' }),
      el('h2', { class: 'st-h2' }, 'Refining your inquiry…'),
      el('p', { class: 'st-muted' }, 'Rewriting the title and brief, turning your expectations into acceptance checks and suggesting a difficulty.'),
      el('button', { type: 'button', class: 'st-btn st-btn--outline', onclick: cancelRefine }, 'Cancel')));
  }

  function drawReady(page) {
    page.append(el('div', { class: 'page-head st-head' }, el('h1', { class: 'st-h1' }, 'Ready to post')));
    const r = refined;
    const wrote = el('section', { class: 'st-card st-wrote', 'aria-labelledby': 'ni-wrote' },
      el('h2', { class: 'st-h2', id: 'ni-wrote' }, 'What you wrote'),
      wroteItem('Title', draft.title), wroteItem('Explanation', draft.explanation), wroteItem('Expectation', draft.expectation || '—'));

    const forLabel = PROJECT_FOR[draft.projectFor].toUpperCase() + (draft.projectFor === 'ClassProject' && draft.classCode ? ` · ${draft.classCode}` : '');
    const chips = [
      ...r.types.map((k) => PROJECT_TYPES.find((t) => t.key === k)?.label || k),
      r.difficulty,
      r.minScore > 0 ? `Score ${r.minScore}+` : 'Any score',
      r.deadline ? `Due ${formatShortDate(r.deadline)}` : null,
    ].filter(Boolean);
    const reward = rewardLabel(draft);
    const card = el('section', { class: 'st-card st-refined', 'aria-labelledby': 'ni-refined' },
      el('div', { class: 'st-refined-top' },
        el('span', { class: 'st-mono-label st-mono-label--blue' }, icon('sparkle', 14), `${r.basic ? 'BASIC REWRITE' : 'REFINED BY AI'} · ${forLabel}`),
        reward !== '—' ? el('span', { class: 'st-refined-reward' }, reward) : null),
      r.basic ? el('span', { class: 'st-chip st-chip--refining st-chip--inline' }, BASIC_REWRITE_LABEL) : null,
      el('h2', { class: 'st-refined-title', id: 'ni-refined' }, r.title),
      el('p', { class: 'st-refined-brief' }, r.brief),
      r.doneLooksLike.length ? el('div', { class: 'st-done' }, el('span', { class: 'st-label' }, 'Done looks like'),
        r.doneLooksLike.map((d) => el('div', { class: 'st-done-item' }, el('span', { class: 'st-done-tick' }, icon('check', 14)), el('span', {}, d)))) : null,
      el('div', { class: 'st-pills' }, chips.map((c) => el('span', { class: 'st-pill' }, c))),
      r.whatAiChanged ? el('div', { class: 'st-changed' }, el('strong', {}, r.basic ? 'What changed: ' : 'What AI changed: '), r.whatAiChanged) : null);

    page.append(el('div', { class: 'st-ready' }, wrote, card));

    const who = r.notifiesOnDone || notify;
    const postBtn = el('button', { type: 'button', class: 'st-btn st-btn--yellow', onclick: () => post(postBtn) }, 'Post project');
    page.append(el('div', { class: 'st-footer' },
      el('span', { class: 'st-muted st-footer-note' }, who ? [icon('bell', 16), ` When it is done, ${who}, are notified.`] : 'A reviewer checks it before it goes live on the board.'),
      el('button', { type: 'button', class: 'st-btn st-btn--outline', onclick: () => { view = 'form'; draw(); } }, 'Edit'),
      el('button', { type: 'button', class: 'st-btn st-btn--outline', onclick: startRefine }, 'Refine again'),
      postBtn));
  }

  async function post(btn) {
    btn.disabled = true;
    btn.classList.add('is-busy');
    btn.textContent = 'Posting…';
    try {
      const department = String(me.department || ctx.user?.department || '').trim() || 'BAU';
      await ctx.api('/v1/inquiries', { method: 'POST', body: createBody(draft, refined, department) });
      if (draftId) deleteDraft(ctx, draftId);
      if (!alive) return;
      toast(ctx, 'Posted. A reviewer checks it before it goes live.', 'success');
      go(ctx, '#/inquiries');
    } catch (err) {
      if (!alive) return;
      btn.disabled = false;
      btn.classList.remove('is-busy');
      btn.textContent = 'Post project';
      toast(ctx, err?.message || 'Couldn’t post the inquiry. Try again.', 'error');
    }
  }

  draw();
  return () => {
    alive = false;
    refineAbort?.abort();
  };
}

function wroteItem(label, text) {
  return el('div', { class: 'st-wrote-item' }, el('span', { class: 'st-label' }, label), el('p', { class: 'st-pre' }, text));
}

/** Draft as sent to the (future) refine endpoint. */
function wireDraft(d) {
  return {
    title: d.title, explanation: d.explanation, expectation: d.expectation,
    types: d.types.map((k) => PROJECT_TYPES.find((t) => t.key === k)?.label || k),
    project_for: d.projectFor === 'ClassProject' ? 'class' : 'bau', class_code: d.classCode,
    rewards: d.rewards, price_usd: d.priceUsd, school_discount_percent: d.schoolDiscountPercent,
    priority: d.priority.toLowerCase(), deadline: d.deadline, difficulty: d.difficulty ? d.difficulty.toLowerCase() : null,
  };
}
