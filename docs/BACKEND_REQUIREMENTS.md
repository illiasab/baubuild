# BAU BUILD — backend requirements (API v1, Go)

> Sign-in is email and password, recorded in the database — **no email, no code, no SMTP** (§1, changed 2026-11-19).
> `@stu.bau.edu` is a student, `@bau.edu` is staff, and anything else is `403 domain_not_allowed`. Register returns
> tokens straight away. Password resets are done by an admin (`POST /v1/admin/users/{id}/password`).
> `GET /healthz` and `GET /readyz` are public.

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

Plain email + password accounts, verified with a 6-digit code sent to the email. **Only `@stu.bau.edu` (students) and
`@bau.edu` (staff) may register and use the app.** All bodies are JSON; errors are `{"error":{"code","message"}}`
(rate-limit errors also carry `retry_after_s`, inside `error`, plus the `Retry-After` header);
`device = {platform: ios|macos|android|windows|linux|web, app_version}` — **`web` is required**: the web portal at
`/app` (same origin, `{platform:"web", app_version:"1.0.0"}`) uses these same endpoints.
Token responses are `{access_token, refresh_token, expires_in, user, entitlement}` (same shape as today's refresh).

| | Endpoint | Contract |
|---|---|---|
| ✅ | `POST /v1/auth/register {name, email, password, device}` | ✅ **Changed 2026-11-19: no email step.** → `200` tokens `{access_token, refresh_token, expires_in, user, entitlement}` (same as login); the account is created verified. `403 domain_not_allowed` is checked **first** (before any body validation), then `400 invalid_body`, `422 weak_password`, `409 email_taken` (any account with a password). A pre-seeded account without a password (e.g. Ilias/Arian) is claimed by registering. No SMTP needed. _Old contract:_ → `202 {verification_required: true, email, resend_after_s}` and emails a 6-digit code. Errors: `400 invalid_body`, `403 domain_not_allowed` (only stu.bau.edu / bau.edu), `409 email_taken` (a **verified** account exists; re-registering an unverified email replaces it and sends a new code), `422 weak_password` (min 8 chars, ≥1 letter + ≥1 digit; the server is authoritative). |
| ❌ | `POST /v1/auth/verify-email {email, code, device}` | ✅ Removed 2026-11-19: route kept for old clients, answers `501 not_available`. _Old contract:_ → `200` tokens (signs in). Errors: `400 invalid_code`, `410 code_expired` (codes valid 15 min), `429 too_many_attempts` (5 wrong tries → that code is dead, a new one is needed). |
| ❌ | `POST /v1/auth/resend-code {email, purpose: "verify"\|"reset"}` | ✅ Removed: `501 not_available`. _Old contract:_ → `202 {resend_after_s}` (invalidates the previous code). `429 too_many_requests` with `retry_after_s`. Same 202 whether or not the account exists. |
| ✅ | `POST /v1/auth/login {email, password, device}` | → `200` tokens. Never answers `email_not_verified` any more (all accounts count as verified; a migration sets `email_verified = 1`). Errors: `401 invalid_credentials` (also for an unknown email — no enumeration), `403 email_not_verified` (the server re-sends a code; include `resend_after_s` in `error`), `403 domain_not_allowed`, `429 too_many_attempts` (lockout, with `retry_after_s`). |
| ❌ | `POST /v1/auth/password/forgot {email}` | ✅ Removed: `501 not_available` ("Password reset by email isn't available. Ask BAU BUILD support."). _Old contract:_ → always `202 {resend_after_s}` (no enumeration); emails a reset code if the account exists. `429 too_many_requests` only for rate limiting. |
| ❌ | `POST /v1/auth/password/reset {email, code, new_password, device}` | ✅ Removed: `501 not_available`. _Old contract:_ → `200` tokens (signs in) and **revokes all other sessions**. Errors: `400 invalid_code`, `410 code_expired`, `422 weak_password`, `429 too_many_attempts`. |
| ✅ | `POST /v1/auth/refresh`, `POST /v1/auth/logout`, `GET /v1/me`, `GET/DELETE /v1/auth/sessions` | Unchanged. Refresh rotation; reuse → `401 refresh_reused`. Refresh also answers `403 domain_not_allowed` when the account's domain is no longer allowed (the app signs out and explains). |
| ✅ | `POST /v1/admin/users/{id}/password {new_password}` | Admin only. Sets the password (`422 weak_password`), revokes all of that user's sessions → `{user_id, sessions_revoked}`. |
| ❌ | `POST /v1/auth/microsoft/exchange` | No longer used by the apps. Microsoft sign-in can come back later as an extra option. |
| ✅ | `GET /v1/me/entitlement`, billing endpoints | Phase 2 (paid non-BAU users). |
| ✏️ | `GET /v1/me` | Add `roles[]`, `display_name`, `avatar_url`, `github_username`, `department`, `title`, `notification_preferences`. ✅ done except `notification_preferences`: `roles[]` (role + extra roles), `display_name` (= `name`), `avatar_url` (null until upload exists), `github_username` (typed one, else linked GitHub login), `department`, `title`. |
| ✅ | `PATCH /v1/me` | Done: all optional — `display_name` (or `name`, 1–80), `bio` (0–280, empty allowed), `skills`, `github_username` (GitHub rules, leading `@` stripped, empty clears), `department` (≤80), `title` (≤80, staff/admin only → else `403 role_required`). |
| 🆕 | `PUT /v1/me/avatar` | multipart `file` (jpeg/png ≤ 5 MB) → `{avatar_url}`; `DELETE` removes. |
| 🆕 | `GET/PATCH /v1/me/notification-preferences` | Staff: `project_done`, `student_joined`, `blocker_raised`; students: `join_approved`, `review_update`, `urgent_inquiry`. |
| ✅ | `POST/DELETE /v1/devices` | APNs (iOS/macOS) + FCM (Android) tokens; desktop Windows/Linux via in-app SSE only. |
| 🆕 | `GET /v1/me/export`, `DELETE /v1/me` | KVKK/GDPR. |

**Server requirements (🆕):**
- **Passwords** hashed with **argon2id** (or bcrypt cost ≥ 12); never logged, never returned, never in error messages
  or traces (scrub request bodies of `password` / `new_password` / `code` in logs).
- **Codes**: 6 random digits from a CSPRNG, stored **hashed**, **single-use**, **15-minute** expiry, max 5 wrong tries per
  code; a new code invalidates the previous one. Separate purposes (`verify`, `reset`).
- **Rate limits per email and per IP** on register, login, resend-code, forgot, verify and reset (e.g. login: 5 failures
  / 15 min per email → `429 too_many_attempts` with `retry_after_s`; code e-mails: 1 per 60 s, 5 per hour per email).
- **Role from the domain**: `stu.bau.edu` → `student`, `bau.edu` → `staff` (compare the lower-cased domain exactly;
  no subdomains). Emails are case-insensitive and trimmed.
- **`domain_not_allowed` is enforced on usage, not only registration**: on login **and on every token refresh** (so a
  rule change takes effect within one access-token lifetime).
- **No enumeration**: login answers `invalid_credentials` for unknown emails; forgot-password and resend-code always 202.
- **Transactional email** sender (e.g. Amazon SES / SendGrid / Microsoft 365 SMTP) on a domain with **SPF + DKIM**
  (and DMARC) so BAU's mail servers accept it; plain-text + HTML templates ("Your BAU BUILD code is 123456. It expires
  in 15 minutes."); the code in the subject line helps iOS / Android one-time-code autofill.
- Password reset revokes every other session (refresh tokens) of that user.
- Optional later: Microsoft sign-in can come back as an additional option for the same accounts.

---

## 2. Students — projects, work, review

| | Endpoint | Needed change |
|---|---|---|
| ✅ | `GET /v1/home`, `GET /v1/inquiries/urgent`, `GET /v1/projects`, `GET /v1/search` | — |
| ✅ | `GET /v1/projects/{id}` | Done (also on every project list): `membership_status`, `repo_full_name` (+ `repo.full_name`, `repo.viewer_github_linked`), `summary` (first sentence), `class_code`. Org = env `GITHUB_ORG` (default `bau-build`); a repo name with an owner is kept as is; an inquiry's GitHub `repo_url` becomes the project's repo. Was: add `membership_status` (`none`, `member`, `pending_approval`) so "I'm In" isn't offered twice; add `repo_full_name` (`bau-build/<repo>`). |
| ✏️ | `GET /v1/projects` | Exclude (or flag) projects the caller already joined. |
| ✅ | `POST /v1/projects/{id}/join`, `DELETE …/membership` | Emit `membership_changed` on the live stream. |
| ✅ | `GET /v1/me/classes`, `GET /v1/classes/{id}/projects` | — |
| ✅ | `GET /v1/me/work` | — |
| ✅ | `GET /v1/projects/{id}/tasks` | Done: `description`, `acceptance_criteria[]`, `priority`, `resources[{label,url}]`, `due_date`, `criteria_done[]` (seeded for all seed tasks; old rows back-filled by migration). `branch` still to come with §4. Was: each task needs `description`, `acceptance_criteria[]`, `due_date`, `priority` (`low`,`medium`,`high`), `resources[{label,url}]`, `branch` (once started). |
| ✅ | `PATCH /v1/tasks/{id}` | Done: `status` and/or `criteria_done[]` (indexes into `acceptance_criteria`, de-duplicated and sorted; out of range → `400`). Returns the full task. |
| ✅ | `GET /v1/projects/{id}/changes` | Done for submitted branches: each commit has `sha`, `message`, `branch`, `status`, `url` (+ old `state`, `diff_url`); top level adds `repo_full_name`. `POST …/submissions` accepts optional `message` + `sha`; without them `message` falls back to the branch name. `github_commits` stays `[]` — reading commits from GitHub needs the stored token (§4). Was: per commit: `sha`, `message`, `branch`, `status` (`approved`,`with_dev_lead`,`changes_requested`), `url` (diff). |
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
| ✅ | `PATCH /v1/inquiries/{id}` | Done: editable while `submitted`/`approved`/`published` (title, description, department, difficulty, bounty, is_urgent, due_at, min_score, joining, reviewer_type, repo_url). Changes are mirrored onto the project in the same transaction and members get an `inquiry_updated` event. `cancelled` → `409 inquiry_locked`. Was: today only while `submitted` (`409 inquiry_locked`). Allow editing title, explanation, expectation, types, priority, deadline, reward while `posted`/`in_progress` — "the team sees changes right away" (emit event to members). |
| ✅ | `POST /v1/inquiries/{id}/cancel` | Done: author or admin → status `cancelled` + `cancelled_at`, project `closed` (no new joins), members notified; `wasted_usd` = bounty − paid. Already cancelled or delivered → `409 inquiry_locked`. `DELETE` only before publishing. Inquiries also carry `stage` (`draft|open|in_progress|delivered|cancelled`) and `updated_at`. |
| 🆕 | `GET /v1/inquiries/{id}/progress` | `{today, day_of, total_days, percent_done, done_today[] (AI summary of commits/notes), blocker {id, text, raised_by, raised_at, resolved}, log[{date, summary, blockers}], students[]}`. |
| 🆕 | `POST /v1/inquiries/{id}/blockers/{blocker_id}/resolve` | Students raise blockers via 🆕 `POST /v1/projects/{id}/blockers {text}`. |
| ✅ | `GET /v1/staff/overview?period=semester` | Done (no `period` filter yet; all of the caller's inquiries): `{raised_usd, wasted_usd, open, in_progress, delivered, needs_attention: null}`. Mapping via `stage`: delivered = project `done` or an approved submission was paid (raised = its bounty); in_progress = ≥1 member; open = published with no member, or not yet published; wasted = `wasted_usd` of cancelled. |
| ✅ | `GET /v1/world-projects` | Done: `{items:[{id, title, summary, organization, project_types[], url}]}` from the seeded `world_projects` table (6 ideas). |
| 🆕 | `GET /v1/staff/profile` (or fields on `/v1/me`) | `department, title, classes_taught[]` (empty → app shows "No classes"), `projects_posted, delivered, raised_usd, senior_engineers[]`. |
| — | Notifications | When done → notify author **and senior engineers (Ilias, Arian)**; when a student joins → author. |
| ✅ | `GET /v1/senior-engineers` | Done: users whose `roles` include `senior_engineer`/`interviewer`; seeded `ilias@bau.edu` (Tech Lead Engineer, `illiasab`) and `arian@bau.edu` (Senior Engineer) as staff with roles `[staff, interviewer, senior_engineer]`. Spec: `{items:[{id, name, title, bio, avatar_url, skills[], github_username, email, roles[]}]}` — Ilias (Tech Lead Engineer) and Arian (Senior Engineer). Shown on the staff Profile; tapping one opens their bio. Same people are the Lead interviewers (§5.7). |
| ✅ | `GET /v1/users/{id}` | Done: `title`, `bio`, `skills`, `github_username`, `roles[]`; `email` only for staff/lead/admin viewers. Was: public profile used for the bio sheet — add `title`, `email` (for staff viewers), `github_username`, `roles[]`. |

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
  body for `/v1/auth/refresh`. Please publish full schemas, including the new §1 auth routes.
- **`403 domain_not_allowed`** should include the refused `email` (and `access`: `not_yet_available | requires_payment`).
- **Refresh errors**: an expired/unknown refresh token returns `401 unauthorized`, a reused one `401 refresh_reused` —
  document both (the app signs out on either).
- **`/v1/me`**: no `score`, `earnings`, `approved_projects` (only in `/v1/me/stats` — embed or document), no
  `github_username`, `avatar` always null.
- ✅ **Projects** (fixed 2026-11-19: `repo_full_name`, `repo.full_name`, `repo.viewer_github_linked`, `summary`, `class_code`, `membership_status`; `task_count` still missing): `repo.name` is a bare name — send `repo.full_name` (`bau-build/<repo>`); `repo.connected` means "this
  user linked GitHub", not "repo connected" — rename (`viewer_github_linked`); no `summary`, `task_count`,
  `class_code` (only `class_id`).
- **My Work**: projects in `/v1/me/work` are slim (no difficulty, due date, description); no in-review/approved
  membership status.
- ✅ **Changes** (fixed: real `message` when the client sends it with the submission, plus `sha`, `branch`, `status`, `url`; compare URLs now use the full repo name, also for old rows): a branch commit's `message` is the branch name; GitHub commits don't carry their branch.
- **Notifications**: only `text` — add `type`, `title`, `project_id`.
- **Urgent inquiries**: payload is a project (id = project id), no requester; `min_score` only as a label string —
  send the number.
- **Inquiry create** requires `difficulty`, `min_score`, `joining`, `reviewer_type` (the staff app lets AI suggest
  them — see §3); bounty limited to 0–500.
- **Home** (`/v1/home`) cards don't include the viewer's membership, so joined projects can't be hidden there.
- **Security**: the current server stores `github_token` in plain text — must be encrypted at rest (§4).

## 9. Found in the Go server (2026-11-19)

- **Auth routes**: email + password registration and login are in the Go server, with no email step (see §1).
- **GitHub token route missing**: the app calls `PUT /v1/integrations/github/token` (§4, `docs/BACKEND_GITHUB_TOKEN.md`);
  the Go server only has the OAuth connect → callback flow. Add the token route (the app keeps working and retries until
  it exists). `features.github` in `/v1/config` is `false` today; the app skips linking while it's false.
- **Mentor AI**: `GET/POST /v1/projects/{id}/mentor/messages` exists (POST `{text}` ≤ 500 chars → SSE `delta` … `done`)
  but isn't in `docs/openapi.json` — document it and the app will wire the chat.
- **Notifications**: returns at most 21 items and ignores `cursor` — implement paging.
- ✅ **Not yet in the server**: `GET /v1/senior-engineers`, `GET /v1/world-projects` (app hides those sections on 404). — Both exist now.
- ✅ **Register without email**: `register` returns tokens (200), no SMTP; `domain_not_allowed` is checked first
  (previously a gmail address got `503 email_not_configured`). Code routes answer `501 not_available`.
- **`/healthz`** must be public (currently 401).
