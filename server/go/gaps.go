package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"sort"
	"strings"
)

// migrateGaps adds the columns and tables the apps need. Every step is additive and safe to run on an
// existing database on every start: columns are only added, never dropped or renamed.
func migrateGaps(db *sql.DB) error {
	alters := []string{
		`ALTER TABLE users ADD COLUMN title TEXT`,
		`ALTER TABLE users ADD COLUMN github_username TEXT`,
		`ALTER TABLE users ADD COLUMN roles TEXT NOT NULL DEFAULT '[]'`,
		`ALTER TABLE inquiries ADD COLUMN cancelled_at TEXT`,
		`ALTER TABLE inquiries ADD COLUMN wasted_usd INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE inquiries ADD COLUMN updated_at TEXT`,
		`ALTER TABLE tasks ADD COLUMN description TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE tasks ADD COLUMN acceptance_criteria TEXT NOT NULL DEFAULT '[]'`,
		`ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'medium'`,
		`ALTER TABLE tasks ADD COLUMN resources TEXT NOT NULL DEFAULT '[]'`,
		`ALTER TABLE tasks ADD COLUMN criteria_done TEXT NOT NULL DEFAULT '[]'`,
		`ALTER TABLE submissions ADD COLUMN commit_message TEXT`,
		`ALTER TABLE submissions ADD COLUMN commit_sha TEXT`,
	}
	for _, stmt := range alters {
		if _, err := db.Exec(stmt); err != nil && !strings.Contains(strings.ToLower(err.Error()), "duplicate column") {
			return err
		}
	}
	if _, err := db.Exec(`
CREATE TABLE IF NOT EXISTS world_projects (
    id TEXT PRIMARY KEY,
    position INTEGER NOT NULL DEFAULT 0,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    organization TEXT NOT NULL,
    project_types TEXT NOT NULL DEFAULT '[]',
    url TEXT
);`); err != nil {
		return err
	}
	return seedGaps(db)
}

type taskDetail struct {
	Description string
	Criteria    []string
	Priority    string
	Resources   []map[string]string
}

// taskDetails holds the brief for each seeded task, keyed by the task title. joinProject copies it into new
// task rows, and seedGaps back-fills rows created before these columns existed.
var taskDetails = map[string]taskDetail{
	"Reject past dates in the booking form": {
		Description: "The room-booking form in the library portal accepts a date in the past because it compares the date strings from the input. Parse the chosen date and compare it with today's date (in the library's time zone) before the form submits. Today stays valid; yesterday does not.",
		Criteria: []string{
			"Choosing yesterday shows \"Choose today or a later date\" and the booking is not sent",
			"Choosing today or any later date still submits the booking",
			"The check compares Date values (or timestamps), not strings",
			"A blank date shows \"Choose a date\" instead of crashing",
		},
		Priority: "high",
		Resources: []map[string]string{
			{"label": "MDN: Date", "url": "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date"},
			{"label": "MDN: Client-side form validation", "url": "https://developer.mozilla.org/en-US/docs/Learn/Forms/Form_validation"},
		},
	},
	"Add a test for the date check": {
		Description: "Cover the date rule with automated tests so the bug cannot come back. Freeze the clock in the test so the result does not depend on the day the suite runs.",
		Criteria: []string{
			"One test each for yesterday, today, tomorrow, and a blank field",
			"Tests assert the message the form shows, not only true or false",
			"The clock is fixed in the tests (no dependence on the real date)",
			"The tests run in CI and pass on the branch",
		},
		Priority: "medium",
		Resources: []map[string]string{
			{"label": "Jest: Timer mocks and fake dates", "url": "https://jestjs.io/docs/timer-mocks"},
			{"label": "Testing Library: Queries", "url": "https://testing-library.com/docs/queries/about"},
		},
	},
	"Announce the error to screen readers": {
		Description: "When the date is refused, the message only turns red today. Put the message in the form and make assistive technology read it out when it appears.",
		Criteria: []string{
			"The error node has role=\"alert\" (or aria-live=\"assertive\")",
			"The date input points at the message with aria-describedby",
			"VoiceOver or NVDA reads the message when it appears",
			"The message is not conveyed by color alone",
		},
		Priority: "low",
		Resources: []map[string]string{
			{"label": "MDN: ARIA alert role", "url": "https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Roles/alert_role"},
			{"label": "WCAG 2.2: Error identification", "url": "https://www.w3.org/WAI/WCAG22/Understanding/error-identification.html"},
		},
	},
	"Load the weekday shuttle stops": {
		Description: "Read the weekday shuttle timetable from the Campus Ops JSON feed and keep it in a typed model the widget can use.",
		Criteria:    []string{"Stops and departure times load from the feed", "A failed request shows a retry message", "Weekend days show \"No service today\""},
		Priority:    "high",
	},
	"Render the next three departures": {
		Description: "Show the next three departures from the student's chosen stop, updating every minute.",
		Criteria:    []string{"Exactly the next three departures are shown", "The list refreshes every minute without a reload", "After the last bus the widget says so"},
		Priority:    "medium",
	},
	"Rank matches by query relevance": {
		Description: "Order course-search results by how well they match the query instead of by course number.",
		Criteria:    []string{"An exact title match ranks first", "Matches in the title rank above matches in the description", "Ranking is covered by tests"},
		Priority:    "high",
	},
	"Break ties with the course title": {
		Description: "When two results score the same, order them alphabetically by course title so results are stable.",
		Criteria:    []string{"Equal scores are ordered by title A–Z", "The order is the same on every request"},
		Priority:    "low",
	},
	"Export the attendance table to CSV": {
		Description: "Add a one-click export of the attendance table so staff stop copying it by hand.",
		Criteria:    []string{"The file has one header row and one row per person", "Commas and quotes in names are escaped", "The file opens correctly in Excel"},
		Priority:    "high",
	},
	"Include the club name column": {
		Description: "Add the club name as its own column so staff can filter the export.",
		Criteria:    []string{"Every row has the club name in its own column", "The header reads \"club\""},
		Priority:    "medium",
	},
	"Add a theme switch to the calendar": {
		Description: "Add a light/dark switch to the events calendar header.",
		Criteria:    []string{"The switch toggles the whole calendar", "Text keeps WCAG AA contrast in both themes"},
		Priority:    "medium",
	},
	"Remember the choice for the next visit": {
		Description: "Store the visitor's theme choice and apply it before the first paint on the next visit.",
		Criteria:    []string{"The choice survives a reload", "No flash of the wrong theme on load", "Without a saved choice the system setting is used"},
		Priority:    "low",
	},
	"Replace each dead FAQ link": {
		Description: "Find every link on the admissions FAQ that returns an error and point it at a page that exists.",
		Criteria:    []string{"Every FAQ link returns 200", "Replaced links go to the page the text describes"},
		Priority:    "high",
	},
	"Add a check that fails on a 404": {
		Description: "Add an automated link check that fails the build when an FAQ link breaks.",
		Criteria:    []string{"The check runs in CI", "A broken link fails the build with the URL in the log"},
		Priority:    "medium",
	},
	"Find which field triggers the reload": {
		Description: "Profile the transcript request form and find the field handler that reloads the page.",
		Criteria:    []string{"The cause is written up in the pull request", "Steps to reproduce are included"},
		Priority:    "high",
	},
	"Submit the form without reloading": {
		Description: "Remove the reloads so the form only submits once, when the student presses Send.",
		Criteria:    []string{"Typing in any field never reloads the page", "Validation errors appear inline", "The request is sent once on submit"},
		Priority:    "medium",
	},
}

func (d taskDetail) encoded() (string, string, string) {
	criteria := d.Criteria
	if criteria == nil {
		criteria = []string{}
	}
	resources := d.Resources
	if resources == nil {
		resources = []map[string]string{}
	}
	c, _ := json.Marshal(criteria)
	r, _ := json.Marshal(resources)
	priority := d.Priority
	if priority == "" {
		priority = "medium"
	}
	return string(c), priority, string(r)
}

var worldProjects = []struct {
	id, title, summary, organization, url string
	types                                 []string
}{
	{"accessible-campus-map", "Accessible campus map", "A campus map that routes around stairs, shows step-free entrances, lifts and accessible toilets, and lets people report a broken lift.", "OpenStreetMap / Wheelmap", "https://wheelmap.org", []string{"web_app", "ios_app", "android_app"}},
	{"flood-early-warning", "Flood early-warning dashboard", "Pull river-level and rainfall readings into one dashboard that flags rising water and sends an SMS alert to the people downstream.", "Copernicus Emergency Management Service", "https://www.globalfloods.eu", []string{"web_app", "backend_api", "data_script"}},
	{"food-bank-stock", "Food bank stock tracker", "Volunteers scan donations in and parcels out; the tracker shows what is running low and which items expire this week.", "Global FoodBanking Network", "https://www.foodbanking.org", []string{"web_app", "android_app", "backend_api"}},
	{"air-quality-sensors", "Low-cost air-quality sensors", "Read PM2.5 and NO2 from cheap sensors around campus, publish open data, and show a live map with health advice.", "OpenAQ", "https://openaq.org", []string{"data_script", "backend_api", "website"}},
	{"blood-donor-match", "Blood donor matching", "Match hospitals that need a blood type with nearby registered donors and let donors confirm with one tap.", "Turkish Red Crescent (Kızılay)", "https://www.kizilay.org.tr", []string{"ios_app", "android_app", "backend_api"}},
	{"earthquake-checkin", "Earthquake safety check-in", "After a quake, students and staff mark themselves safe; coordinators see who has not checked in, per building.", "AFAD", "https://www.afad.gov.tr", []string{"ios_app", "android_app", "web_app"}},
}

var seniorEngineers = []struct {
	id, email, name, title, bio, github string
	skills                              []string
}{
	{"ilias", "ilias@bau.edu", "Ilias", "Tech Lead Engineer", "Tech lead for BAU BUILD. Reviews architecture and the Go API, runs the Lead interviews, and helps teams ship to production.", "illiasab", []string{"Go", "Swift", "Kotlin", "System design", "PostgreSQL"}},
	{"arian", "arian@bau.edu", "Arian", "Senior Engineer", "Senior engineer on BAU BUILD. Reviews pull requests, mentors student leads, and co-runs the Lead interviews.", "", []string{"TypeScript", "React", "Node.js", "Testing", "CI/CD"}},
}

func seedGaps(db *sql.DB) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for i, p := range worldProjects {
		types, _ := json.Marshal(p.types)
		if _, err := tx.Exec(`INSERT OR IGNORE INTO world_projects (id, position, title, summary, organization, project_types, url) VALUES (?, ?, ?, ?, ?, ?, ?)`,
			p.id, i, p.title, p.summary, p.organization, string(types), p.url); err != nil {
			return err
		}
	}
	roles := `["staff","interviewer","senior_engineer"]`
	for _, e := range seniorEngineers {
		skills, _ := json.Marshal(e.skills)
		var github any
		if e.github != "" {
			github = e.github
		}
		var existing int
		if err := tx.QueryRow(`SELECT COUNT(*) FROM users WHERE email = ?`, e.email).Scan(&existing); err != nil {
			return err
		}
		if existing == 0 {
			if _, err := tx.Exec(`INSERT OR IGNORE INTO users (id, email, name, initials, bio, role, department, skills, created_at, title, github_username, roles, email_verified)
				VALUES (?, ?, ?, ?, ?, 'staff', 'BAU BUILD', ?, ?, ?, ?, ?, 1)`,
				e.id, e.email, e.name, initialsOf(e.name), e.bio, string(skills), nowISO(), e.title, github, roles); err != nil {
				return err
			}
			continue
		}
		if _, err := tx.Exec(`UPDATE users SET roles = ?, title = COALESCE(title, ?), github_username = COALESCE(github_username, ?) WHERE email = ? AND roles = '[]'`,
			roles, e.title, github, e.email); err != nil {
			return err
		}
	}
	for title, detail := range taskDetails {
		criteria, priority, resources := detail.encoded()
		if _, err := tx.Exec(`UPDATE tasks SET description = ?, acceptance_criteria = ?, priority = ?, resources = ? WHERE title = ? AND description = ''`,
			detail.Description, criteria, priority, resources, title); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// githubOrg is the GitHub organization project repos live in (GITHUB_ORG, default bau-build).
func githubOrg() string {
	if org := strings.TrimSpace(os.Getenv("GITHUB_ORG")); org != "" {
		return org
	}
	return "bau-build"
}

// repoFullName returns owner/repo for a project: repo_name as stored when it has an owner, otherwise the org.
func repoFullName(project map[string]any) any {
	name := strings.Trim(strings.TrimSpace(str(project, "repo_name")), "/")
	if name == "" {
		return nil
	}
	if strings.Contains(name, "/") {
		return name
	}
	return githubOrg() + "/" + name
}

var githubRepoURL = regexp.MustCompile(`^(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\.git)?/?$`)

// repoFromURL turns https://github.com/owner/repo into owner/repo (nil when it is not a GitHub repo URL).
func repoFromURL(raw string) any {
	parts := githubRepoURL.FindStringSubmatch(strings.TrimSpace(raw))
	if parts == nil {
		return nil
	}
	return parts[1] + "/" + parts[2]
}

func compareURL(project map[string]any, branch string) string {
	full, _ := repoFullName(project).(string)
	if full == "" {
		full = githubOrg() + "/" + str(project, "id")
	}
	return "https://github.com/" + full + "/compare/" + escapeGitHubPath(branch)
}

// firstSentence is a one-line summary of a description: up to the first sentence end, at most 200 characters.
func firstSentence(text string) string {
	text = strings.TrimSpace(text)
	for i, r := range text {
		if r == '.' || r == '!' || r == '?' {
			next := i + 1
			if next >= len(text) || text[next] == ' ' || text[next] == '\n' {
				text = text[:next]
				break
			}
		}
	}
	return clip(text, 200)
}

func jsonList(raw string) []any {
	var out []any
	_ = json.Unmarshal([]byte(raw), &out)
	if out == nil {
		out = []any{}
	}
	return out
}

func taskJSON(row map[string]any) map[string]any {
	var dueDate any
	if due := str(row, "due_at"); len(due) >= 10 {
		dueDate = due[:10]
	}
	priority := str(row, "priority")
	if priority == "" {
		priority = "medium"
	}
	return map[string]any{
		"id": row["id"], "project_id": row["project_id"], "title": row["title"],
		"status": row["status"], "lane": row["lane"], "due_at": row["due_at"], "due_date": dueDate,
		"description": str(row, "description"), "acceptance_criteria": jsonList(str(row, "acceptance_criteria")),
		"priority": priority, "resources": jsonList(str(row, "resources")), "criteria_done": jsonList(str(row, "criteria_done")),
	}
}

// patchTask updates a task's status and/or ticked acceptance checks (criteria_done: indexes into acceptance_criteria).
func patchTask(tx *sql.Tx, user map[string]any, taskID string, body map[string]any) (map[string]any, error) {
	row, err := one(tx, `SELECT * FROM tasks WHERE id = ? AND user_id = ?`, taskID, str(user, "id"))
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That task is not yours.")
	}
	_, hasStatus := body["status"]
	raw, hasCriteria := body["criteria_done"]
	if !hasStatus && !hasCriteria {
		return nil, fail(400, "invalid_body", "Send status and/or criteria_done.")
	}
	if hasStatus {
		status := asString(body["status"])
		if status != "todo" && status != "in_progress" && status != "done" {
			return nil, fail(400, "invalid_body", "status must be todo, in_progress, or done.")
		}
		if _, err := tx.Exec(`UPDATE tasks SET status = ? WHERE id = ?`, status, taskID); err != nil {
			return nil, err
		}
	}
	if hasCriteria {
		list, ok := raw.([]any)
		if !ok {
			return nil, fail(400, "invalid_body", "criteria_done must be a list of criterion indexes.")
		}
		total := len(jsonList(str(row, "acceptance_criteria")))
		seen := map[int]bool{}
		done := []int{}
		for _, item := range list {
			n, ok := whole(item)
			if !ok || n < 0 || n >= total {
				return nil, fail(400, "invalid_body", fmt.Sprintf("criteria_done indexes must be whole numbers from 0 to %d.", total-1))
			}
			if !seen[n] {
				seen[n] = true
				done = append(done, n)
			}
		}
		sort.Ints(done)
		encoded, _ := json.Marshal(done)
		if _, err := tx.Exec(`UPDATE tasks SET criteria_done = ? WHERE id = ?`, string(encoded), taskID); err != nil {
			return nil, err
		}
	}
	fresh, err := one(tx, `SELECT * FROM tasks WHERE id = ?`, taskID)
	if err != nil {
		return nil, err
	}
	return taskJSON(fresh), nil
}

// rolesOf is the user's primary role plus any extra roles (interviewer, senior_engineer), without duplicates.
func rolesOf(user map[string]any) []any {
	out := []any{}
	seen := map[string]bool{}
	add := func(role string) {
		if role != "" && !seen[role] {
			seen[role] = true
			out = append(out, role)
		}
	}
	add(str(user, "role"))
	for _, role := range jsonList(str(user, "roles")) {
		add(asString(role))
	}
	return out
}

func nullable(row map[string]any, key string) any {
	if row[key] == nil || str(row, key) == "" {
		return nil
	}
	return str(row, key)
}

// inquiryStage says where an inquiry is in its life, derived from the inquiry and its project:
//
//	draft        submitted/approved, not published yet
//	open         published, project open, nobody joined yet
//	in_progress  published and at least one student is a member, nothing paid out yet
//	delivered    project closed as done, or at least one approved submission was paid
//	cancelled    the author cancelled it
func inquiryStage(tx *sql.Tx, row map[string]any) (string, error) {
	switch str(row, "status") {
	case "cancelled":
		return "cancelled", nil
	case "submitted", "approved":
		return "draft", nil
	}
	projectID := str(row, "project_id")
	if projectID == "" {
		return "open", nil
	}
	project, err := one(tx, `SELECT status FROM projects WHERE id = ?`, projectID)
	if err != nil {
		return "", err
	}
	if project != nil && str(project, "status") == "done" {
		return "delivered", nil
	}
	paid, err := one(tx, `SELECT 1 AS ok FROM submissions WHERE project_id = ? AND lead_status = 'approved' AND paid_at IS NOT NULL`, projectID)
	if err != nil {
		return "", err
	}
	if paid != nil {
		return "delivered", nil
	}
	members, err := one(tx, `SELECT 1 AS ok FROM memberships WHERE project_id = ? AND status = 'member'`, projectID)
	if err != nil {
		return "", err
	}
	if members != nil {
		return "in_progress", nil
	}
	return "open", nil
}

// inquiryView is inquiryPayload plus the lifecycle fields (stage, cancelled_at, wasted_usd, updated_at).
func inquiryView(tx *sql.Tx, row map[string]any) (map[string]any, error) {
	out := inquiryPayload(row)
	stage, err := inquiryStage(tx, row)
	if err != nil {
		return nil, err
	}
	out["stage"] = stage
	out["cancelled_at"] = row["cancelled_at"]
	out["wasted_usd"] = num(row, "wasted_usd")
	out["updated_at"] = row["updated_at"]
	return out, nil
}

func inquiryViews(tx *sql.Tx, rows []map[string]any) ([]any, error) {
	items := []any{}
	for _, row := range rows {
		item, err := inquiryView(tx, row)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, nil
}

// editInquiry applies a PATCH. A published inquiry stays editable; the linked project is updated in the same
// transaction so students see the change right away, and members get an inquiry_updated event.
func editInquiry(tx *sql.Tx, row map[string]any, body map[string]any) (map[string]any, error) {
	switch str(row, "status") {
	case "submitted", "approved", "published":
	default:
		return nil, fail(409, "inquiry_locked", "A cancelled inquiry can't be edited.")
	}
	fields, err := inquiryFields(body, true)
	if err != nil {
		return nil, err
	}
	id := str(row, "id")
	if len(fields) == 0 {
		return inquiryView(tx, row)
	}
	sets := []string{"updated_at = ?"}
	args := []any{nowISO()}
	for key, value := range fields {
		sets = append(sets, key+" = ?")
		args = append(args, value)
	}
	args = append(args, id)
	if _, err := tx.Exec(`UPDATE inquiries SET `+strings.Join(sets, ", ")+` WHERE id = ?`, args...); err != nil {
		return nil, err
	}
	if projectID := str(row, "project_id"); projectID != "" && str(row, "status") == "published" {
		columns := map[string]string{
			"title": "title", "description": "description", "department": "department", "difficulty": "difficulty",
			"bounty": "bounty", "is_urgent": "urgent", "due_at": "due_at", "min_score": "min_score", "joining": "joining",
		}
		psets := []string{}
		pargs := []any{}
		for key, column := range columns {
			if value, ok := fields[key]; ok {
				psets = append(psets, column+" = ?")
				pargs = append(pargs, value)
			}
		}
		if reviewer, ok := fields["reviewer_type"]; ok {
			label := "dev lead"
			if reviewer == "professor" {
				label = "professor of the class"
			}
			psets = append(psets, "reviewed_by = ?", "reviewer_label = ?")
			pargs = append(pargs, reviewer, label)
		}
		if repo, ok := fields["repo_url"]; ok {
			if full := repoFromURL(asString(repo)); full != nil {
				psets = append(psets, "repo_name = ?")
				pargs = append(pargs, full)
			}
		}
		if len(psets) > 0 {
			pargs = append(pargs, projectID)
			if _, err := tx.Exec(`UPDATE projects SET `+strings.Join(psets, ", ")+` WHERE id = ?`, pargs...); err != nil {
				return nil, err
			}
		}
		memberEvent(tx, projectID, "inquiry_updated", map[string]any{"inquiry_id": id, "project_id": projectID})
	}
	fresh, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	return inquiryView(tx, fresh)
}

// memberEvent puts an event on the live stream of every member of a project.
func memberEvent(tx *sql.Tx, projectID, kind string, payload map[string]any) {
	rows, err := many(tx, `SELECT user_id FROM memberships WHERE project_id = ?`, projectID)
	if err != nil {
		return
	}
	raw, _ := json.Marshal(payload)
	for _, member := range rows {
		_, _ = tx.Exec(`INSERT INTO events (user_id, kind, payload, created_at) VALUES (?, ?, ?, ?)`, str(member, "user_id"), kind, string(raw), nowISO())
	}
}

// cancelInquiry closes the inquiry and its project. Bounty that was not paid out counts as wasted.
func cancelInquiry(tx *sql.Tx, user map[string]any, id string) (map[string]any, error) {
	row, err := inquiryOwned(tx, user, id)
	if err != nil {
		return nil, err
	}
	if str(row, "status") == "cancelled" {
		return nil, fail(409, "inquiry_locked", "That inquiry is already cancelled.")
	}
	stage, err := inquiryStage(tx, row)
	if err != nil {
		return nil, err
	}
	if stage == "delivered" {
		return nil, fail(409, "inquiry_locked", "A delivered inquiry can't be cancelled.")
	}
	wasted := num(row, "bounty")
	projectID := str(row, "project_id")
	if projectID != "" {
		paid, err := one(tx, `SELECT COALESCE(SUM(bounty_amount), 0) AS n FROM submissions WHERE project_id = ? AND paid_at IS NOT NULL`, projectID)
		if err != nil {
			return nil, err
		}
		wasted -= num(paid, "n")
		if wasted < 0 {
			wasted = 0
		}
		project, err := one(tx, `SELECT title FROM projects WHERE id = ?`, projectID)
		if err != nil {
			return nil, err
		}
		if _, err := tx.Exec(`UPDATE projects SET status = 'closed' WHERE id = ?`, projectID); err != nil {
			return nil, err
		}
		members, err := many(tx, `SELECT user_id FROM memberships WHERE project_id = ?`, projectID)
		if err != nil {
			return nil, err
		}
		for _, member := range members {
			notify(tx, str(member, "user_id"), fmt.Sprintf("“%s” was cancelled by the staff member who posted it.", str(project, "title")))
		}
		memberEvent(tx, projectID, "inquiry_updated", map[string]any{"inquiry_id": id, "project_id": projectID, "status": "cancelled"})
	}
	moment := nowISO()
	if _, err := tx.Exec(`UPDATE inquiries SET status = 'cancelled', cancelled_at = ?, updated_at = ?, wasted_usd = ? WHERE id = ?`, moment, moment, wasted, id); err != nil {
		return nil, err
	}
	fresh, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	return inquiryView(tx, fresh)
}

func inquiryOwned(tx *sql.Tx, user map[string]any, id string) (map[string]any, error) {
	if err := staffOnly(user); err != nil {
		return nil, err
	}
	row, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That inquiry does not exist.")
	}
	if str(user, "role") != "admin" && str(row, "author_id") != str(user, "id") {
		return nil, fail(403, "role_required", "That inquiry belongs to someone else.")
	}
	return row, nil
}

// staffOverview sums the caller's inquiries. raised_usd is the bounty of every delivered inquiry, wasted_usd the
// unspent bounty of cancelled ones; open / in_progress / delivered follow inquiryStage (drafts count as open).
func staffOverview(tx *sql.Tx, user map[string]any) (map[string]any, error) {
	if err := staffOnly(user); err != nil {
		return nil, err
	}
	rows, err := many(tx, `SELECT i.*, p.bounty AS project_bounty FROM inquiries i LEFT JOIN projects p ON p.id = i.project_id WHERE i.author_id = ?`, str(user, "id"))
	if err != nil {
		return nil, err
	}
	raised, wasted, open, progress, delivered := 0, 0, 0, 0, 0
	for _, row := range rows {
		stage, err := inquiryStage(tx, row)
		if err != nil {
			return nil, err
		}
		switch stage {
		case "cancelled":
			wasted += num(row, "wasted_usd")
		case "delivered":
			delivered++
			bounty := num(row, "bounty")
			if row["project_bounty"] != nil {
				bounty = num(row, "project_bounty")
			}
			raised += bounty
		case "in_progress":
			progress++
		default:
			open++
		}
	}
	return map[string]any{
		"raised_usd": raised, "wasted_usd": wasted, "open": open, "in_progress": progress,
		"delivered": delivered, "needs_attention": nil,
	}, nil
}

func listWorldProjects(tx *sql.Tx) (map[string]any, error) {
	rows, err := many(tx, `SELECT * FROM world_projects ORDER BY position, title`)
	if err != nil {
		return nil, err
	}
	items := []any{}
	for _, row := range rows {
		items = append(items, map[string]any{
			"id": row["id"], "title": row["title"], "summary": row["summary"], "organization": row["organization"],
			"project_types": jsonList(str(row, "project_types")), "url": nullable(row, "url"),
		})
	}
	return map[string]any{"items": items}, nil
}

func listSeniorEngineers(tx *sql.Tx) (map[string]any, error) {
	rows, err := many(tx, `SELECT * FROM users WHERE roles LIKE '%"senior_engineer"%' OR roles LIKE '%"interviewer"%' ORDER BY CASE id WHEN 'ilias' THEN 0 WHEN 'arian' THEN 1 ELSE 2 END, name`)
	if err != nil {
		return nil, err
	}
	items := []any{}
	for _, row := range rows {
		items = append(items, map[string]any{
			"id": row["id"], "name": row["name"], "title": nullable(row, "title"), "bio": str(row, "bio"),
			"avatar_url": nil, "skills": jsonList(str(row, "skills")), "github_username": githubName(row),
			"email": row["email"], "roles": rolesOf(row),
		})
	}
	return map[string]any{"items": items}, nil
}

// githubName is the username the person typed on their profile, else the login from a linked GitHub account.
func githubName(user map[string]any) any {
	if name := nullable(user, "github_username"); name != nil {
		return name
	}
	return nullable(user, "github_login")
}

var inquiryCancel = regexp.MustCompile(`^/v1/inquiries/([A-Za-z0-9-]+)/cancel$`)
var adminPassword = regexp.MustCompile(`^/v1/admin/users/([A-Za-z0-9-]+)/password$`)

func gapRoutes(tx *sql.Tx, r *http.Request, path string, user, body map[string]any) (int, any, bool, error) {
	switch {
	case r.Method == http.MethodGet && path == "/v1/staff/overview":
		out, err := staffOverview(tx, user)
		return 200, out, true, err
	case r.Method == http.MethodGet && path == "/v1/world-projects":
		out, err := listWorldProjects(tx)
		return 200, out, true, err
	case r.Method == http.MethodGet && path == "/v1/senior-engineers":
		out, err := listSeniorEngineers(tx)
		return 200, out, true, err
	case r.Method == http.MethodPost && inquiryCancel.MatchString(path):
		out, err := cancelInquiry(tx, user, inquiryCancel.FindStringSubmatch(path)[1])
		return 200, out, true, err
	case r.Method == http.MethodPost && adminPassword.MatchString(path):
		out, err := adminSetPassword(tx, user, adminPassword.FindStringSubmatch(path)[1], body)
		return 200, out, true, err
	case r.Method == http.MethodPatch && strings.HasPrefix(path, "/v1/tasks/") && !strings.Contains(strings.TrimPrefix(path, "/v1/tasks/"), "/"):
		out, err := patchTask(tx, user, strings.TrimPrefix(path, "/v1/tasks/"), body)
		return 200, out, true, err
	}
	return 0, nil, false, nil
}
