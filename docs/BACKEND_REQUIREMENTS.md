# BAU BUILD — backend requirements (API v1, Go)

> **Blocking right now (checked 2026-11-19):** `https://api.vesperconnect.org/healthz` answers `ok`, but every
> `/v1/...` route (e.g. `GET /v1/config`, `POST /v1/auth/microsoft/exchange`) returns **404** — the documented API
> isn't deployed there. Real sign-in also needs the Entra app registration: client ID + tenant ID shared with the
> app team, redirect URIs `com.vesper.baubuild://auth` and `http://127.0.0.1` under "Mobile and desktop
> applications", "Allow public client flows" on, `MICROSOFT_CLIENT_ID` set on the server, **no client secret**.

Everything the apps (iOS, Android, macOS, Windows, Linux) need from the backend. Built on the current API
(`docs/openapi.json`, `docs/index.html`); items marked **✅ exists** are already there, **🆕** are new, **✏️** change
an existing endpoint. Language-agnostic — written for the Go rewrite.

---

## 0. Cross-cutting rules

- **Errors**: always `{"error":{"code","message"}}`. Apps branch on `code` only, never on `message`.
- **Zero downtime**: rolling deploys behind a load balancer with `/healthz` + `/readyz`; DB migrations
  expand → migrate → contract (never drop/rename a column in the same release that stops using it); API changes
  are additive (new optional fields, new endpoints) — removing/renaming fields needs a `/v2` route.
  Apps ignore unknown fields and unknown enum values.
- **`GET /v1/config`** ✅ exists — keep `min_version` per platform (`ios`, `android`, `macos`, `windows`, `linux`),
  `maintenance` banner and feature `flags`. Add flags for every feature below so they can be dark-launched
  (`staff_ai_refine`, `lead_exam_v2`, `interview_booking`, `in_app_code`, …).
- **Idempotency**: all POSTs that create things accept `Idempotency-Key` (apps retry on network failure).
- **Pagination**: cursor-based (`?cursor=` → `next_cursor`) on every list.
- **Time**: ISO-8601 UTC everywhere; dates as `YYYY-MM-DD`; the server is the clock for exams and deadlines.
- **Roles**: `student`, `lead`, `staff`, `admin`, 🆕 `interviewer` (Ilias, Arian). A user can have several
  (e.g. interviewer + senior engineer). Expose `roles[]` on `GET /v1/me`.
- **Privacy (KVKK/GDPR)**: consent records, data export/delete endpoints, retention policies (see exam snapshots).
- **Audit log** for admin/interviewer actions (approve, publish, grade overrides, interview outcomes).

---

## 1. Auth, session, account

| | Endpoint | Notes |
|---|---|---|
| ✅ | `POST /v1/auth/microsoft/exchange` | PKCE code → `{access_token, refresh_token, expires_in, user, entitlement}`; `403 domain_not_allowed`; set `MICROSOFT_CLIENT_ID` (today `503 microsoft_not_configured`). |
| ✅ | `POST /v1/auth/refresh`, `POST /v1/auth/logout`, `GET/DELETE /v1/auth/sessions` | Refresh rotation; reuse → `401 refresh_reused`. |
| — | Email + password login | **Not needed** — decided Microsoft only; apps pass the email as `login_hint`. |
| ✅ | `GET /v1/me/entitlement`, billing endpoints | Phase 2 (paid non-BAU users). |
| ✏️ | `GET /v1/me` | Add `roles[]`, `display_name`, `avatar_url`, `github_username`, `department`, `title`, `notification_preferences`. |
| ✏️ | `PATCH /v1/me` | Today only `bio` + `skills` (bio can't be empty). Accept `display_name`, `bio` (empty allowed), `skills`, `github_username`. |
| 🆕 | `PUT /v1/me/avatar` | multipart `file` (jpeg/png ≤ 5 MB) → `{avatar_url}`; `DELETE` removes. |
| 🆕 | `GET/PATCH /v1/me/notification-preferences` | Staff: `project_done`, `student_joined`, `blocker_raised`; students: `join_approved`, `review_update`, `urgent_inquiry`. |
| ✅ | `POST/DELETE /v1/devices` | APNs (iOS/macOS) + FCM (Android) tokens; desktop Windows/Linux via in-app SSE only. |
| 🆕 | `GET /v1/me/export`, `DELETE /v1/me` | KVKK/GDPR. |

---

## 2. Students — projects, work, review

| | Endpoint | Needed change |
|---|---|---|
| ✅ | `GET /v1/home`, `GET /v1/inquiries/urgent`, `GET /v1/projects`, `GET /v1/search` | — |
| ✏️ | `GET /v1/projects/{id}` | Add `membership_status` (`none`, `member`, `pending_approval`) so "I'm In" isn't offered twice; add `repo_full_name` (`bau-build/<repo>`). |
| ✏️ | `GET /v1/projects` | Exclude (or flag) projects the caller already joined. |
| ✅ | `POST /v1/projects/{id}/join`, `DELETE …/membership` | Emit `membership_changed` on the live stream. |
| ✅ | `GET /v1/me/classes`, `GET /v1/classes/{id}/projects` | — |
| ✅ | `GET /v1/me/work` | — |
| ✏️ | `GET /v1/projects/{id}/tasks` | Each task needs `description`, `acceptance_criteria[]`, `due_date`, `priority` (`low`,`medium`,`high`), `resources[{label,url}]`, `branch` (once started). |
| ✏️ | `PATCH /v1/tasks/{id}` | Accept `status` (✅) and `criteria_done[]` (ticked acceptance checks). |
| ✏️ | `GET /v1/projects/{id}/changes` | Per commit: `sha`, `message`, `branch`, `status` (`approved`,`with_dev_lead`,`changes_requested`), `url` (diff). |
| ✅ | submissions + timeline, lead queue, decisions | Link a submission to the task's **pull request** (see §4). |
| ✅ | notifications, unread count, mark read | Types: `join_approved`, `review_update`, `urgent_inquiry`, `exam_graded`, `interview_booked`, `general`. |
| ✅ | `GET /v1/events/stream` (SSE) | Events the apps react to: `membership_changed`, `task_updated`, `submission_updated`, `inquiry_updated`, `notification`, `exam_graded`. |
| ✅ | Mentor AI (`/v1/projects/{id}/mentor/messages`, SSE) | App will switch from its placeholder to this. |

---

## 3. Staff (@bau.edu) — inquiries

Today an inquiry is a short form moved `submitted → approved → published` by **admins**. The staff app needs a
staff-driven flow with AI refinement and live progress.

### 3.1 Inquiry model (✏️ extend `Inquiry`)
`id, author_id, title, explanation, expectation, project_types[]` (`ios_app, android_app, website, web_app,
desktop_app, backend_api, data_script, other`), `project_for` (`class_project | bau_project`), `class_code`,
`rewards[]` (`price, ssn, job_at_bau, school_discount, volunteer`), `price_usd`, `school_discount_percent`,
`priority` (`normal | urgent`), `deadline`, `difficulty` (nullable = "let AI suggest"), `status`, `latest`
(one-line summary: "Day 4 of 7 · 1 blocker", "Ilias & Arian notified", "$20 budget wasted"), `refined`
(see 3.2), `original` (what the staff member wrote), `project_id`, `reward_label`, timestamps.

**Statuses**: `draft → ai_refining → ready_to_post → posted → in_progress → done` and `cancelled`.
Admin approval may stay as an internal step but must be visible to the author as a status.

### 3.2 Endpoints
| | Endpoint | Notes |
|---|---|---|
| 🆕 | `POST /v1/inquiries/refine {draft}` | AI rewrite → `{title, brief, done_looks_like[], project_types[], difficulty, min_score, reward_summary, deadline, what_ai_changed, notifies_on_done}`. May take seconds: either synchronous ≤ 15 s or `202` + `inquiry_updated` event. "Refine again" calls it again. |
| ✏️ | `POST /v1/inquiries {draft, refined}` | Post directly as a project (status `posted`), keeping both versions. |
| 🆕 | `POST /v1/inquiries/drafts` | Save draft; drafts appear in `/mine`. |
| ✅ | `GET /v1/inquiries/mine`, `GET /v1/inquiries/{id}` | Return the extended model; filterable `?status=open|closed`. |
| ✏️ | `PATCH /v1/inquiries/{id}` | Today only while `submitted` (`409 inquiry_locked`). Allow editing title, explanation, expectation, types, priority, deadline, reward while `posted`/`in_progress` — "the team sees changes right away" (emit event to members). |
| 🆕 | `POST /v1/inquiries/{id}/cancel` | Any open status → `cancelled`; unspent budget counts as **wasted**. (`DELETE` stays for drafts.) |
| 🆕 | `GET /v1/inquiries/{id}/progress` | `{today, day_of, total_days, percent_done, done_today[] (AI summary of commits/notes), blocker {id, text, raised_by, raised_at, resolved}, log[{date, summary, blockers}], students[]}`. |
| 🆕 | `POST /v1/inquiries/{id}/blockers/{blocker_id}/resolve` | Students raise blockers via 🆕 `POST /v1/projects/{id}/blockers {text}`. |
| 🆕 | `GET /v1/staff/overview?period=semester` | `{raised_usd, wasted_usd, open, in_progress, delivered, needs_attention}`. |
| 🆕 | `GET /v1/world-projects` | Ideas shown when a staff member has no inquiries: `[{id, title, summary, organization, project_types[], url}]` (planned). |
| 🆕 | `GET /v1/staff/profile` (or fields on `/v1/me`) | `department, title, classes_taught[]` (empty → app shows "No classes"), `projects_posted, delivered, raised_usd, senior_engineers[]`. |
| — | Notifications | When done → notify author **and senior engineers (Ilias, Arian)**; when a student joins → author. |
| 🆕 | `GET /v1/senior-engineers` | `{items:[{id, name, title, bio, avatar_url, skills[], github_username, email, roles[]}]}` — Ilias (Tech Lead Engineer) and Arian (Senior Engineer). Shown on the staff Profile; tapping one opens their bio. Same people are the Lead interviewers (§5.7). |
| ✏️ | `GET /v1/users/{id}` | Public profile used for the bio sheet — add `title`, `email` (for staff viewers), `github_username`, `roles[]`. |

---

## 4. GitHub & in-app coding

Decision: students paste a **fine-grained personal access token** for the **bau-build** organization. The app keeps
it in Keychain/Keystore and talks to GitHub directly to read files, create the task branch, commit, and open the PR.
The backend also holds it so the org's push-control agent can work with it. Full spec:
`docs/BACKEND_GITHUB_TOKEN.md`.

| | Endpoint / component | Notes |
|---|---|---|
| 🆕 | `PUT /v1/integrations/github/token {token, login}` | Validate via GitHub `GET /user`; check `bau-build` org membership; store **encrypted at rest** (KMS/sealed key — not hashed: it must be usable); link login ↔ user. |
| ✅ | `GET/DELETE /v1/integrations/github` | Status + disconnect (delete stored token). |
| 🆕 | GitHub App / webhook on the org | Receives `push` + `pull_request` events: maps `task/<task_id>-<slug>` branches → task, records commits (feeds `/changes` and staff daily progress), runs the AI review on PRs, enforces branch protection on `main`. |
| 🆕 | `POST /v1/tasks/{id}/branch {name}` (optional) | App announces the branch it created (webhook can also infer it). |
| ✏️ | `POST /v1/projects/{id}/submissions {branch, pr_number}` | Link "Mark as Done" PR to the submission/review timeline. |
| — | Repos | Projects must carry `repo_full_name`; students need repo access (org team per project) for their token to work. |

---

## 5. Become a Lead — proctored exam + interview (🆕 v2)

### 5.1 What exists today
`GET/POST /v1/me/lead-application`, `POST …/exam/session` (2-hour session, a fixed multiple-choice set, pass mark 3),
`POST …/exam/sessions/{id}` (submit answers), `POST /v1/admin/lead-applications/{user_id}/in-person`. No proctoring,
no coding, no AI grading, no interview booking.

### 5.2 New exam format
1. **Algorithm problem (1, default)** — the student picks a language (Kotlin, Swift, Python, JavaScript/TypeScript,
   Go, Java, C++ — configurable), writes code in the app's editor. **Run** executes visible sample tests;
   **Submit** runs hidden tests. **Coding score** = 70 % hidden tests passed + 30 % AI quality review
   (correctness reasoning, complexity, readability, edge cases). **Pass: coding score ≥ 80 %.**
2. **Hard questions (5)** — each from a different sphere (backend, frontend, iOS, Android, + systems/security)
   but written so any strong engineer can answer regardless of specialty (concurrency, caching & consistency,
   state management, networking & offline, security). Free-text answers, AI-graded against a rubric
   (pass/fail per question with rationale). **Pass: ≥ 4 of 5.** (If a pool round uses 3–4 questions, the bar is
   the same ratio — configurable `questions_required`.)
3. **Result** is computed automatically: both bars met → status `exam_passed` → the app shows
   **"Pick a date"** for the interview. Otherwise `exam_failed` with per-part feedback and a cooldown
   (e.g. 30 days; configurable `retry_after`).

### 5.3 Exam endpoints
| | Endpoint | Notes |
|---|---|---|
| ✏️ | `POST /v1/me/lead-application/exam/session` | Body: `{consent: {camera: true, rules_version}}`. Returns `{session_id, token, started_at, expires_at (server clock, e.g. 90 min), languages[], algorithm: {id, title, statement, constraints, samples[], starter_code{lang: code}}, questions[{id, sphere, prompt}], proctoring: {snapshot_interval_s, max_focus_losses}}`. Questions drawn randomly per session from a pool; never the same set twice for a user. |
| 🆕 | `POST …/exam/sessions/{id}/run {language, code}` | Runs **sample** tests in the sandbox → `{results[{input, expected, actual, passed, time_ms, stderr}]}`. Rate-limited (e.g. 30 runs/session). |
| 🆕 | `PUT …/exam/sessions/{id}/answers {algorithm: {language, code}, answers: [{question_id, text}]}` | Autosave every ~15 s (resume after crash, same session, same deadline). |
| 🆕 | `POST …/exam/sessions/{id}/events [{type, at, meta}]` | Proctoring events (batch). See 5.4. |
| 🆕 | `POST …/exam/sessions/{id}/snapshots` | multipart JPEG (front camera), every `snapshot_interval_s` and on suspicious events. |
| ✏️ | `POST …/exam/sessions/{id}/submit` | Final submit (also auto-submitted at `expires_at` server-side). → `202`; grading is async. |
| 🆕 | `GET …/exam/sessions/{id}/result` | `{status: grading|passed|failed, coding: {score, tests_passed, tests_total, ai_review}, questions: [{id, passed, feedback}], integrity: {flags[], needs_review}, retry_after}`. Also pushed as `exam_graded` event + notification. |
| ✏️ | `GET /v1/me/lead-application` | Stages: `exam` (`not_started | in_progress | grading | passed | failed`), `interview` (`locked | pick_date | booked | passed | failed`), `lead` (`pending | done`), plus `booking` when booked. |

### 5.4 Proctoring — custom is feasible (no third-party required)
**App side (we build):** exam mode is full-screen; paste disabled in the exam editor; front-camera snapshots with
on-device face detection (iOS Vision / Android ML Kit / desktop webcam); detects and reports:
`app_backgrounded`, `focus_lost` (desktop window blur), `screenshot_taken` (iOS), `screen_captured` /
`screen_mirroring` (iOS `isCaptured`), `paste_blocked`, `face_missing`, `multiple_faces`, `network_lost`.
Android blocks screenshots/recording with `FLAG_SECURE`. Desktop needs a webcam; if none, the exam can't start.

**Backend side:**
- Server-authoritative timer; session token bound to the device; one active session per user.
- Store events + snapshots **encrypted**, access only for interviewers/admins, **retention 30 days** after the
  decision, explicit consent stored with `rules_version` (KVKK).
- **Integrity scoring**: thresholds (e.g. > 3 focus losses, face missing > 60 s, multiple faces) set
  `needs_review = true` — it does **not** auto-fail; an interviewer reviews flagged sessions (🆕
  `GET /v1/interviewer/exam-reviews`, `POST …/{session_id}/review {decision, note}`).
- Optional similarity check of submitted code against other submissions + known solutions.

### 5.5 Code execution sandbox (🆕 service)
Self-hosted **Judge0** (quickest) or a custom Go runner using containers isolated with **gVisor/Firecracker**:
no network, read-only FS, per-run CPU/memory/time limits (e.g. 2 s, 256 MB), per-language images, a queue
with autoscaling workers. Hidden test cases stored server-side only.

### 5.6 AI grading (🆕 service)
LLM grading with versioned prompts + rubrics per question, structured JSON output (`score`, `passed`,
`feedback`), temperature 0, stored with the model/prompt version for audits; interviewer/admin can **override**
(`POST /v1/admin/exam-sessions/{id}/override`). Same AI service powers inquiry refinement (§3), staff daily
summaries (§3) and the code review on PRs (§4).

### 5.7 Interviewers & booking (🆕)
- **Interviewers**: Ilias and Arian as users with role `interviewer` (and `senior_engineer` for staff
  notifications). Admin endpoints: `POST /v1/admin/interviewers {user_id}`, `DELETE …/{user_id}`.
- **Availability**: `PUT /v1/interviewer/availability` — weekly recurring slots (e.g. Tue/Thu 14:00–17:00,
  30-min interviews, campus room) + date exceptions; `GET /v1/interviewer/bookings`.
- **Student booking**: `GET /v1/me/lead-application/interview/slots?from&to` → free slots across both interviewers
  `[{slot_id, starts_at, ends_at, interviewer {id, name}, location}]`; `POST …/interview/booking {slot_id}`
  (transactional — no double booking; `409 slot_taken`); `DELETE …/interview/booking` (cancel/reschedule allowed
  until e.g. 24 h before). Calendar invite (.ics / Outlook via Microsoft Graph) + reminders 24 h and 1 h before.
- **Outcome**: `POST /v1/interviewer/bookings/{id}/outcome {result: passed|failed, note}` → passed sets role
  `lead` automatically (replaces today's admin `in-person` confirm) and notifies the student.

---

## 6. Notifications & live updates

Push (APNs/FCM) + in-app list + SSE for: join approved/request, task updated, review updates, inquiry
posted/done/blocker raised (staff + senior engineers), student joined (staff), exam graded, interview booked /
reminder / outcome, urgent inquiry. Respect `notification-preferences`.

---

## 7. Already in the API, app work pending (not backend gaps)

Mentor AI chat (SSE), live events stream, bookmarks, earnings + payout method, sessions list, billing (phase 2),
device registration for push. The app will adopt these next.

---

## 8. Contract mismatches found while wiring the app (fix in the Go rewrite)

- **Response schemas**: `docs/openapi.json` has no response schemas (user, token pair, config, projects…) and no request
  body for `/v1/auth/refresh`; `401 microsoft_rejected` (exchange) isn't documented. Please publish full schemas.
- **`403 domain_not_allowed`** should include the refused `email` (and `access`: `not_yet_available | requires_payment`).
- **Refresh errors**: an expired/unknown refresh token returns `401 unauthorized`, a reused one `401 refresh_reused` —
  document both (the app signs out on either).
- **Microsoft**: the server redeems the code with scope `User.Read` and calls Graph; **don't send `client_secret`**
  for public-client (mobile/desktop) redirect URIs — Entra rejects it (AADSTS700025).
- **`/v1/me`**: no `score`, `earnings`, `approved_projects` (only in `/v1/me/stats` — embed or document), no
  `github_username`, `avatar` always null.
- **Projects**: `repo.name` is a bare name — send `repo.full_name` (`bau-build/<repo>`); `repo.connected` means "this
  user linked GitHub", not "repo connected" — rename (`viewer_github_linked`); no `summary`, `task_count`,
  `class_code` (only `class_id`).
- **My Work**: projects in `/v1/me/work` are slim (no difficulty, due date, description); no in-review/approved
  membership status.
- **Changes**: a branch commit's `message` is the branch name; GitHub commits don't carry their branch.
- **Notifications**: only `text` — add `type`, `title`, `project_id`.
- **Urgent inquiries**: payload is a project (id = project id), no requester; `min_score` only as a label string —
  send the number.
- **Inquiry create** requires `difficulty`, `min_score`, `joining`, `reviewer_type` (the staff app lets AI suggest
  them — see §3); bounty limited to 0–500.
- **Home** (`/v1/home`) cards don't include the viewer's membership, so joined projects can't be hidden there.
- **Security**: the current server stores `github_token` in plain text — must be encrypted at rest (§4).
