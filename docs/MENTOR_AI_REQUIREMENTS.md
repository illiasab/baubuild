# Mentor AI — backend requirements

Mentor AI is BAU BUILD's AI teaching assistant. It **reviews student code against BAU BUILD's own seeded knowledge
base** (coding standards, rubrics, project briefs, model answers, past reviews) and helps students finish tasks
**without writing the solution for them**. Same AI service also powers staff features (inquiry refine, daily progress
summaries) and Lead-exam grading (see `BACKEND_REQUIREMENTS.md`).

Provider-neutral: works with whichever LLM provider the backend uses. Language-agnostic HTTP contract.

---

## 1. What Mentor AI does

| # | Capability | Where the app shows it | Trigger |
|---|---|---|---|
| 1 | **Code review against the seeded knowledge base** — reviews a student's diff/branch: correctness vs. the task's acceptance criteria, BAU coding standards, security, accessibility, tests. Every finding cites the knowledge-base rule it comes from. | Review timeline "AI Review Complete" (mobile Review tab, desktop workspace), code editor | Automatically on every push to a `task/*` branch and on PR open/update (GitHub webhook); also on demand ("Ask for review") |
| 2 | **Mentor chat** per project/task — answers questions, explains errors, gives hints step by step (Socratic: asks guiding questions first), points to the exact file/line, links resources. | Mobile "Mentor AI" screen, desktop Mentor panel, task detail "Ask Mentor AI" | Student message |
| 3 | **Explain this code / error** — student selects code or pastes an error in the editor and asks. | Code editor | On demand |
| 4 | **Task kickoff guide** — when a task starts: where to look in the repo, what to change, which tests to add, which standards apply. | Task detail after "Start Task" | Branch created |
| 5 | **Test suggestions** — proposes test cases for the acceptance criteria (descriptions, not full test code). | Task detail / chat | On demand |
| 6 | **Readiness check before "Mark as Done"** — checklist of acceptance criteria met / not met with evidence from the diff. | Task detail before opening the PR | On demand + before PR |
| 7 | **Staff daily progress summary** — "Done today" written from the team's commits, PRs and notes; flags blockers. | Staff inquiry detail (Status) | Nightly + on new commits |
| 8 | **Inquiry refinement** for staff (same service, different prompt). | Staff New Inquiry → Ready to Post | On demand |
| 9 | **Lead exam grading** (same service, separate rubrics). | Exam result | After submit |

### Academic-integrity rules (must be enforced in the system prompt AND checked on output)
- Never write the complete solution for an assigned task or paste full replacement files. Max ~10 lines of example
  code, and only for a concept, not the task itself.
- Prefer questions and hints first; escalate to more concrete help only after the student tried (track hint level per
  task: 1 nudge → 2 pointer to file/line → 3 small example).
- Never reveal hidden tests, exam answers, rubrics' scoring details, or other students' code.
- Reviews comment and explain; they don't auto-fix.

---

## 2. Seeded knowledge base (the data the mentor reviews against)

The knowledge base (KB) is what makes reviews BAU-specific. The product owner seeds and maintains it.

### 2.1 Content types
- **Standards**: coding standards per language/platform (Kotlin, Swift, TS/JS, Go, Python…), security rules,
  accessibility rules, git/commit conventions. Each rule has a stable `rule_id` (e.g. `JS-DATE-001`), title, body,
  severity default, good/bad examples.
- **Rubrics**: what "done" means per project type and difficulty; review checklist.
- **Project briefs**: per project — goals, architecture notes, gotchas, acceptance criteria.
- **Model answers / reference reviews** (staff-only, never shown to students): ideal approaches, common mistakes.
- **Past reviews** approved by dev leads (optional, to learn the house style).

### 2.2 Admin API (🆕, roles `admin` / `lead`)
| Endpoint | Notes |
|---|---|
| `POST /v1/admin/kb/documents` | multipart (`.md`, `.txt`, `.pdf`, `.json`) or JSON `{title, type, body, tags[], language?, project_id?, visibility: student|staff_only}` |
| `GET /v1/admin/kb/documents?type&tag&project_id&cursor` | list |
| `GET/PATCH/DELETE /v1/admin/kb/documents/{id}` | versioned: edits create a new version; reviews store which versions they used |
| `POST /v1/admin/kb/rules` / `PATCH …/{rule_id}` | structured rules `{rule_id, title, body, severity, languages[], examples{good,bad}}` |
| `POST /v1/admin/kb/reindex` | rebuild the search index |
| `GET /v1/admin/kb/search?q=` | test retrieval (what the mentor would see) |

### 2.3 Retrieval
- Chunk documents (~500–1,000 tokens, keep rule boundaries intact); store chunks with tags, language, project,
  visibility, version.
- Index for search: start with **Postgres full-text search** (simple, good enough for rule lookup by keyword), add
  semantic search later with any embedding model + `pgvector`.
- At request time retrieve: always the project brief + the task's acceptance criteria + rules for the languages in the
  diff; plus top-k chunks for the question/diff. Filter by `visibility` (students never get `staff_only`).

---

## 3. Context the mentor needs (server-side tools)

The backend runs the model with **custom tools** it executes itself (the student's GitHub token / the org GitHub App
gives repo access — see `BACKEND_GITHUB_TOKEN.md`):

| Tool | Returns |
|---|---|
| `get_task()` | task title, description, acceptance criteria, due date, project brief |
| `list_files(path?)` | repo tree on the task branch |
| `read_file(path, ref?)` | file content (size-limited) |
| `search_code(query)` | matching lines in the repo |
| `get_diff(base, head)` | unified diff of the task branch vs. its base |
| `search_kb(query, tags?)` | KB chunks with `rule_id`/document id + version |
| `get_review_history()` | previous AI + lead reviews on this task |

Validate every tool input (paths stay inside the repo, size limits), and log tool calls.

---

## 4. HTTP API for the apps

### 4.1 Chat (✅ exists, extend)
- `GET /v1/projects/{id}/mentor/messages?task_id&cursor` → `{items:[{id, role: user|assistant, text, task_id,
  citations[], created_at}], next_cursor}`.
- `POST /v1/projects/{id}/mentor/messages {text, task_id?, context?: {file_path?, selection?, error_text?}}` →
  `text/event-stream`. Raise the 500-char limit to ~4,000 (students paste code/errors).
- **SSE events** (the app will render all of them):
  - `delta {text}` — streamed answer text
  - `status {label}` — tool progress, e.g. "Reading src/booking-form.js", "Checking BAU standards"
  - `citation {rule_id, title, document_id}` — KB rule used
  - `done {message_id}`
  - `error {code, message}` — e.g. `quota_exceeded`, `mentor_unavailable`
- Add the mentor routes to `docs/openapi.json` (they exist in the Go server but aren't documented).

### 4.2 Reviews (🆕)
- `POST /v1/tasks/{id}/reviews {ref?: branch|sha}` → `202 {review_id}` (on-demand review). Automatic reviews come
  from the GitHub webhook on push/PR.
- `GET /v1/reviews/{id}` →
  ```json
  {
    "id": "...", "task_id": "...", "ref": "abc1234", "status": "running|done|failed",
    "verdict": "looks_good|needs_changes|blocking",
    "summary": "Past dates are rejected and a test covers it.",
    "criteria": [{"criterion": "Choosing a date before today shows an error", "met": true, "evidence": "booking-form.js:42"}],
    "findings": [{"severity": "info|warning|blocking", "file": "src/booking-form.js", "line": 42,
                  "rule_id": "A11Y-ERR-002", "title": "Error not announced to screen readers",
                  "explanation": "...", "hint": "Link the message with aria-describedby."}],
    "kb_versions": {"A11Y-ERR-002": 3}, "model": "<provider-model-id>", "created_at": "..."
  }
  ```
- Include the latest review in `GET /v1/submissions/{id}` (`steps[].ai_review`) and emit `submission_updated` /
  `review_done` on the live stream + push notification.
- Dev leads can **override** a finding (`PATCH /v1/reviews/{id}/findings/{n} {status: dismissed|confirmed, note}`);
  confirmed/dismissed findings feed back into the KB as examples.

### 4.3 Other mentor endpoints (🆕)
- `POST /v1/tasks/{id}/mentor/kickoff` → kickoff guide (stream or JSON).
- `POST /v1/tasks/{id}/mentor/readiness` → `{criteria:[{criterion, met, evidence}], ready: bool}`.
- `POST /v1/tasks/{id}/mentor/tests` → suggested test cases (descriptions).
- `POST /v1/messages/{id}/feedback {helpful: bool, note?}` → thumbs up/down for quality tracking.

---

## 5. LLM usage (provider-neutral)

The provider must support: **streaming**, **tool / function calling**, **structured (JSON-schema) output**, and a
large context window (≥ 128K tokens) for diffs + KB chunks. Prompt caching and a batch API are strongly recommended
for cost.

- **Model tiers**: a strong model for reviews and exam grading; a cheaper/faster one is fine for chat hints and staff
  summaries. Keep the model name configurable per feature (env/config), store it with every response.
- **Streaming** for chat and reviews: stream tokens from the provider and forward them as SSE `delta` events.
- **Tool calling**: the server-side tools in §3 with strict JSON schemas; run the tool loop on the server; return tool
  errors to the model as error results instead of failing the request.
- **Structured output for reviews**: force the JSON schema of §4.2 so the review always parses; validate it server-side
  and retry once on invalid JSON.
- **Prompt caching** (if the provider supports it): stable content first — system prompt (mentor + integrity rules),
  tool definitions, project brief, core standards — and the student's question/diff last.
- **Batch API** (if available) for nightly staff summaries and bulk re-reviews after KB changes.
- **Embeddings** for semantic KB search (any embedding model + `pgvector`), or Postgres full-text search to start.
- **Resilience**: timeouts, retry with backoff on rate-limit/5xx, optional fallback model; on failure return
  `mentor_unavailable` to the app, never a raw provider error. Handle the provider's safety refusals gracefully.

---

## 6. Guardrails, quotas, privacy

- **Quotas** (configurable via `/v1/config` flags): e.g. 60 chat messages / student / day, 20 on-demand reviews /
  task / day; automatic reviews debounced (one per branch per 2 min). `429 quota_exceeded` with `retry_after_s`.
- **Output checks**: reject/regenerate answers that contain a full solution (heuristic: long code blocks touching the
  task's files) or staff-only KB text.
- **Prompt-injection hygiene**: treat repo files, commit messages and student text as data, never instructions.
- **Privacy (KVKK)**: send only what's needed (no emails/phone numbers); store conversations 12 months (configurable),
  deletable with the user's data export/delete; don't use student data to train models.
- **Audit**: store per response: model, effort, KB versions, tool calls, tokens used, cost.

---

## 7. Quality & operations

- **Eval set**: 50–100 real diffs with dev-lead-approved expected findings; run on every prompt/KB/model change;
  track precision of `blocking` findings and dev-lead agreement rate.
- **Dashboards**: cost per student/day, cache hit rate, review latency (target: review done < 2 min after push),
  thumbs-up rate, refusal rate.
- **Feature flags**: `mentor_chat`, `mentor_reviews`, `mentor_kickoff` in `/v1/config` (the app already reads
  `features.mentor`).

---

## 8. What the app does today vs. after this ships

| Today | After |
|---|---|
| Mentor chat shows "Mentor AI is coming soon" | Chat streams answers with file/line references and KB citations |
| Review tab shows the backend's `ai_findings` from submissions | Full structured review per push with criteria + findings + rule citations |
| — | Kickoff guide, readiness check, test suggestions on the task screen |
| Staff "Done today" empty | Daily AI summary + blocker flags |
