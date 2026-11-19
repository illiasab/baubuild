package main

import (
	"database/sql"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func gapServer(t *testing.T) (*sql.DB, *httptest.Server, func(email, name string) string) {
	t.Helper()
	db, err := openDB(filepath.Join(t.TempDir(), "bau.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	os.Setenv("BAU_ADMIN_EMAILS", "lead.admin@bau.edu")
	os.Unsetenv("SMTP_HOST")
	os.Unsetenv("MENTOR_API_KEY")
	os.Unsetenv("GITHUB_ORG")
	server := httptest.NewServer(&app{db: db})
	t.Cleanup(server.Close)
	signUp := func(email, name string) string {
		t.Helper()
		status, body := call(t, server, "POST", "/v1/auth/register", "", map[string]any{
			"name": name, "email": email, "password": "Password1", "device": map[string]any{"platform": "web", "app_version": "1.0.0"},
		})
		if status != 200 {
			t.Fatalf("register %s: %d %v", email, status, body)
		}
		return body["access_token"].(string)
	}
	return db, server, signUp
}

func errCode(body map[string]any) any {
	if e, ok := body["error"].(map[string]any); ok {
		return e["code"]
	}
	return nil
}

func TestRegisterWithoutEmail(t *testing.T) {
	_, server, signUp := gapServer(t)
	device := map[string]any{"platform": "ios", "app_version": "1.0.0"}
	// Domain check comes first, and no SMTP is needed for anything.
	status, body := call(t, server, "POST", "/v1/auth/register", "", map[string]any{"name": "Out", "email": "person@gmail.com", "password": "Password1", "device": device})
	if status != 403 || errCode(body) != "domain_not_allowed" {
		t.Fatalf("gmail %d %v", status, body)
	}
	status, body = call(t, server, "POST", "/v1/auth/register", "", map[string]any{"email": "person@gmail.com"})
	if status != 403 || errCode(body) != "domain_not_allowed" {
		t.Fatalf("gmail before body checks %d %v", status, body)
	}
	student := signUp("ada@stu.bau.edu", "Ada Lovelace")
	admin := signUp("lead.admin@bau.edu", "Admin User")
	_, me, _ := callRaw(t, server, "GET", "/v1/me", student, nil)
	id := me["id"].(string)

	if status, body, _ := callRaw(t, server, "POST", "/v1/admin/users/"+id+"/password", student, map[string]any{"new_password": "Another123"}); status != 403 {
		t.Fatalf("student reset %d %v", status, body)
	}
	if status, body, _ := callRaw(t, server, "POST", "/v1/admin/users/"+id+"/password", admin, map[string]any{"new_password": "weak"}); status != 422 {
		t.Fatalf("weak reset %d %v", status, body)
	}
	status, reset, _ := callRaw(t, server, "POST", "/v1/admin/users/"+id+"/password", admin, map[string]any{"new_password": "Another123"})
	if status != 200 || reset["sessions_revoked"].(float64) < 1 {
		t.Fatalf("reset %d %v", status, reset)
	}
	if status, _, _ := callRaw(t, server, "GET", "/v1/me", student, nil); status != 401 {
		t.Fatalf("old session still valid %d", status)
	}
	status, body = call(t, server, "POST", "/v1/auth/login", "", map[string]any{"email": "ada@stu.bau.edu", "password": "Another123", "device": device})
	if status != 200 {
		t.Fatalf("login with new password %d %v", status, body)
	}
}

func TestStaffInquiryLifecycle(t *testing.T) {
	_, server, signUp := gapServer(t)
	staff := signUp("sam@bau.edu", "Sam Staff")
	other := signUp("pat@bau.edu", "Pat Staff")
	student := signUp("ada@stu.bau.edu", "Ada Lovelace")
	admin := signUp("lead.admin@bau.edu", "Admin User")
	post := func(title string, bounty int) map[string]any {
		t.Helper()
		status, created, _ := callRaw(t, server, "POST", "/v1/inquiries", staff, map[string]any{
			"title": title, "description": "The queue drops jobs. Find out why.", "department": "Labs",
			"difficulty": "easy", "bounty": bounty, "is_urgent": false, "min_score": 0, "joining": "everyone", "reviewer_type": "ai_then_lead",
			"repo_url": "https://github.com/bau-build/lab-printer",
		})
		if status != 201 || created["status"] != "published" || created["stage"] != "open" {
			t.Fatalf("create %d %v", status, created)
		}
		return created
	}
	first := post("Fix the lab printer", 40)
	inqID, projectID := first["id"].(string), first["project_id"].(string)
	if _, joined, _ := callRaw(t, server, "POST", "/v1/projects/"+projectID+"/join", student, nil); joined["result"] != "joined" {
		t.Fatal(joined)
	}

	// Edit while published: the project changes with it.
	status, edited, _ := callRaw(t, server, "PATCH", "/v1/inquiries/"+inqID, staff, map[string]any{
		"title": "Fix the lab printer queue", "bounty": 55, "is_urgent": true, "difficulty": "medium", "min_score": 10, "due_at": "2026-12-01T12:00:00Z",
	})
	if status != 200 || edited["title"] != "Fix the lab printer queue" || edited["stage"] != "in_progress" || edited["updated_at"] == nil {
		t.Fatalf("edit %d %v", status, edited)
	}
	_, project, _ := callRaw(t, server, "GET", "/v1/projects/"+projectID, student, nil)
	if project["title"] != "Fix the lab printer queue" || project["bounty"].(float64) != 55 || project["urgent"] != true ||
		project["difficulty"] != "medium" || project["due_at"] != "2026-12-01T12:00:00Z" || project["min_score"].(float64) != 10 {
		t.Fatalf("project not mirrored %v", project)
	}
	if project["repo_full_name"] != "bau-build/lab-printer" || project["membership_status"] != "member" || project["summary"] != "The queue drops jobs." {
		t.Fatalf("project fields %v", project)
	}
	if _, _, live := callRaw(t, server, "GET", "/v1/events/stream", student, nil); !strings.Contains(live, "inquiry_updated") {
		t.Fatalf("no inquiry_updated event: %s", live)
	}
	if status, body, _ := callRaw(t, server, "PATCH", "/v1/inquiries/"+inqID, staff, map[string]any{"bounty": 9000}); status != 400 {
		t.Fatalf("bad bounty %d %v", status, body)
	}
	if status, _, _ := callRaw(t, server, "DELETE", "/v1/inquiries/"+inqID, staff, nil); status != 409 {
		t.Fatalf("delete published %d", status)
	}

	_, overview, _ := callRaw(t, server, "GET", "/v1/staff/overview", staff, nil)
	if overview["in_progress"].(float64) != 1 || overview["open"].(float64) != 0 || overview["needs_attention"] != nil {
		t.Fatalf("overview %v", overview)
	}
	if status, _, _ := callRaw(t, server, "GET", "/v1/staff/overview", student, nil); status != 403 {
		t.Fatalf("student overview %d", status)
	}

	// Cancel: only the author (or an admin); the project closes and the bounty counts as wasted.
	if status, _, _ := callRaw(t, server, "POST", "/v1/inquiries/"+inqID+"/cancel", other, nil); status != 403 {
		t.Fatalf("other staff cancel %d", status)
	}
	status, cancelled, _ := callRaw(t, server, "POST", "/v1/inquiries/"+inqID+"/cancel", staff, nil)
	if status != 200 || cancelled["status"] != "cancelled" || cancelled["cancelled_at"] == nil || cancelled["wasted_usd"].(float64) != 55 {
		t.Fatalf("cancel %d %v", status, cancelled)
	}
	_, project, _ = callRaw(t, server, "GET", "/v1/projects/"+projectID, student, nil)
	if project["status"] != "closed" {
		t.Fatalf("project still open %v", project)
	}
	late := signUp("bea@stu.bau.edu", "Bea Student")
	if _, body, _ := callRaw(t, server, "POST", "/v1/projects/"+projectID+"/join", late, nil); errCode(body) != "project_closed" {
		t.Fatalf("join closed %v", body)
	}
	if status, _, _ := callRaw(t, server, "PATCH", "/v1/inquiries/"+inqID, staff, map[string]any{"title": "x"}); status != 409 {
		t.Fatalf("edit cancelled %d", status)
	}
	if status, _, _ := callRaw(t, server, "POST", "/v1/inquiries/"+inqID+"/cancel", staff, nil); status != 409 {
		t.Fatalf("cancel twice %d", status)
	}

	// Deliver a second one: an approved, paid submission.
	second := post("Label the lab shelves", 25)
	secondProject := second["project_id"].(string)
	callRaw(t, server, "POST", "/v1/projects/"+secondProject+"/join", student, nil)
	_, sub, _ := callRaw(t, server, "POST", "/v1/projects/"+secondProject+"/submissions", student, map[string]any{"branch": "task/labels", "message": "Add shelf labels", "sha": "ABCDEF1234"})
	if status, body, _ := callRaw(t, server, "POST", "/v1/submissions/"+sub["id"].(string)+"/decision", admin, map[string]any{"action": "approve"}); status != 200 {
		t.Fatalf("approve %d %v", status, body)
	}
	if status, body, _ := callRaw(t, server, "POST", "/v1/inquiries/"+second["id"].(string)+"/cancel", staff, nil); status != 409 {
		t.Fatalf("cancel delivered %d %v", status, body)
	}
	post("Fix the lab door sensor", 10)
	_, overview, _ = callRaw(t, server, "GET", "/v1/staff/overview", staff, nil)
	want := map[string]float64{"raised_usd": 25, "wasted_usd": 55, "open": 1, "in_progress": 0, "delivered": 1}
	for key, value := range want {
		if overview[key].(float64) != value {
			t.Fatalf("overview %s = %v, want %v (%v)", key, overview[key], value, overview)
		}
	}
	_, mine, _ := callRaw(t, server, "GET", "/v1/inquiries/mine", staff, nil)
	stages := map[string]bool{}
	for _, item := range mine["items"].([]any) {
		stages[item.(map[string]any)["stage"].(string)] = true
	}
	if !stages["cancelled"] || !stages["delivered"] || !stages["open"] {
		t.Fatalf("stages %v", stages)
	}
}

func TestProfilesProjectsTasksAndChanges(t *testing.T) {
	_, server, signUp := gapServer(t)
	staff := signUp("sam@bau.edu", "Sam Staff")
	student := signUp("ada@stu.bau.edu", "Ada Lovelace")

	status, me, _ := callRaw(t, server, "PATCH", "/v1/me", staff, map[string]any{
		"display_name": "Dr. Sam Staff", "title": "Professor", "department": "Computer Engineering", "github_username": "@sam-staff", "bio": "",
	})
	if status != 200 || me["title"] != "Professor" || me["department"] != "Computer Engineering" || me["display_name"] != "Dr. Sam Staff" ||
		me["name"] != "Dr. Sam Staff" || me["github_username"] != "sam-staff" || me["bio"] != "" || me["roles"].([]any)[0] != "staff" {
		t.Fatalf("staff patch %d %v", status, me)
	}
	if status, body, _ := callRaw(t, server, "PATCH", "/v1/me", student, map[string]any{"title": "Professor"}); status != 403 {
		t.Fatalf("student title %d %v", status, body)
	}
	if status, _, _ := callRaw(t, server, "PATCH", "/v1/me", student, map[string]any{"github_username": "not valid!"}); status != 400 {
		t.Fatalf("bad github %d", status)
	}
	if status, body, _ := callRaw(t, server, "PATCH", "/v1/me", student, map[string]any{"name": ""}); status != 400 {
		t.Fatalf("empty name %d %v", status, body)
	}
	_, public, _ := callRaw(t, server, "GET", "/v1/users/"+me["id"].(string), student, nil)
	if public["title"] != "Professor" || public["github_username"] != "sam-staff" || public["email"] != nil {
		t.Fatalf("public profile %v", public)
	}

	// Senior engineers and world projects are seeded.
	_, seniors, _ := callRaw(t, server, "GET", "/v1/senior-engineers", student, nil)
	people := map[string]map[string]any{}
	for _, item := range seniors["items"].([]any) {
		person := item.(map[string]any)
		people[person["name"].(string)] = person
	}
	if people["Ilias"]["title"] != "Tech Lead Engineer" || people["Ilias"]["github_username"] != "illiasab" || people["Arian"]["title"] != "Senior Engineer" {
		t.Fatalf("seniors %v", seniors)
	}
	if !strings.Contains(mustJSON(people["Arian"]["roles"]), "senior_engineer") || !strings.Contains(mustJSON(people["Ilias"]["roles"]), "interviewer") {
		t.Fatalf("roles %v", people)
	}
	_, ilias, _ := callRaw(t, server, "GET", "/v1/users/"+people["Ilias"]["id"].(string), staff, nil)
	if ilias["title"] != "Tech Lead Engineer" || ilias["email"] != "ilias@bau.edu" || len(ilias["skills"].([]any)) == 0 {
		t.Fatalf("ilias profile %v", ilias)
	}
	_, world, _ := callRaw(t, server, "GET", "/v1/world-projects", student, nil)
	items := world["items"].([]any)
	if len(items) < 4 || items[0].(map[string]any)["organization"] == nil || len(items[0].(map[string]any)["project_types"].([]any)) == 0 {
		t.Fatalf("world %v", world)
	}

	// Projects: full repo name, summary, class code, membership status.
	_, library, _ := callRaw(t, server, "GET", "/v1/projects/library", student, nil)
	if library["repo_full_name"] != "bau-build/library-booking" || library["repo"].(map[string]any)["full_name"] != "bau-build/library-booking" ||
		library["membership_status"] != "none" || library["summary"] != "The library’s booking form accepts dates in the past." {
		t.Fatalf("library %v", library)
	}
	_, ranking, _ := callRaw(t, server, "GET", "/v1/projects/ranking", student, nil)
	if ranking["class_code"] != "CMPS433" {
		t.Fatalf("class code %v", ranking)
	}
	callRaw(t, server, "POST", "/v1/projects/shuttle/join", student, nil)
	_, shuttle, _ := callRaw(t, server, "GET", "/v1/projects/shuttle", student, nil)
	if shuttle["membership_status"] != "pending_approval" {
		t.Fatalf("pending %v", shuttle)
	}
	os.Setenv("GITHUB_ORG", "bau-labs")
	_, list, _ := callRaw(t, server, "GET", "/v1/projects?q=library", student, nil)
	os.Unsetenv("GITHUB_ORG")
	if list["items"].([]any)[0].(map[string]any)["repo_full_name"] != "bau-labs/library-booking" {
		t.Fatalf("org override %v", list)
	}

	// Tasks carry a brief; criteria can be ticked.
	callRaw(t, server, "POST", "/v1/projects/library/join", student, nil)
	_, library, _ = callRaw(t, server, "GET", "/v1/projects/library", student, nil)
	if library["membership_status"] != "member" {
		t.Fatalf("member %v", library)
	}
	_, tasks, _ := callRaw(t, server, "GET", "/v1/projects/library/tasks", student, nil)
	var reject map[string]any
	for _, item := range tasks["items"].([]any) {
		task := item.(map[string]any)
		if task["title"] == "Reject past dates in the booking form" {
			reject = task
		}
	}
	if reject == nil || reject["priority"] != "high" || len(reject["acceptance_criteria"].([]any)) != 4 || len(reject["resources"].([]any)) == 0 ||
		reject["description"] == "" || reject["due_date"] == nil || len(reject["criteria_done"].([]any)) != 0 {
		t.Fatalf("task %v", reject)
	}
	taskID := reject["id"].(string)
	status, ticked, _ := callRaw(t, server, "PATCH", "/v1/tasks/"+taskID, student, map[string]any{"criteria_done": []any{2, 0, 0}})
	if status != 200 || mustJSON(ticked["criteria_done"]) != "[0,2]" || ticked["status"] != "in_progress" {
		t.Fatalf("tick %d %v", status, ticked)
	}
	status, ticked, _ = callRaw(t, server, "PATCH", "/v1/tasks/"+taskID, student, map[string]any{"status": "done", "criteria_done": []any{0, 1, 2, 3}})
	if status != 200 || ticked["status"] != "done" || len(ticked["criteria_done"].([]any)) != 4 {
		t.Fatalf("done %d %v", status, ticked)
	}
	if status, _, _ := callRaw(t, server, "PATCH", "/v1/tasks/"+taskID, student, map[string]any{"criteria_done": []any{4}}); status != 400 {
		t.Fatalf("out of range %d", status)
	}
	if status, _, _ := callRaw(t, server, "PATCH", "/v1/tasks/"+taskID, student, map[string]any{}); status != 400 {
		t.Fatalf("empty patch %d", status)
	}

	// Changes: the commit message the client sent, and a compare URL with the owner.
	if status, body, _ := callRaw(t, server, "POST", "/v1/projects/library/submissions", student, map[string]any{"branch": "task/fix-dates", "sha": "xyz"}); status != 400 {
		t.Fatalf("bad sha %d %v", status, body)
	}
	_, sub, _ := callRaw(t, server, "POST", "/v1/projects/library/submissions", student, map[string]any{"branch": "task/fix-dates", "message": "Reject past booking dates", "sha": "1a2b3c4d"})
	if sub["diff_url"] != "https://github.com/bau-build/library-booking/compare/task/fix-dates" || sub["message"] != "Reject past booking dates" {
		t.Fatalf("submission %v", sub)
	}
	callRaw(t, server, "POST", "/v1/projects/library/submissions", student, map[string]any{"branch": "older-branch"})
	_, changes, _ := callRaw(t, server, "GET", "/v1/projects/library/changes", student, nil)
	if changes["repo_full_name"] != "bau-build/library-booking" {
		t.Fatalf("changes repo %v", changes)
	}
	commits := map[string]map[string]any{}
	for _, branch := range changes["branches"].([]any) {
		b := branch.(map[string]any)
		commits[b["name"].(string)] = b["commits"].([]any)[0].(map[string]any)
	}
	fix := commits["task/fix-dates"]
	if fix["message"] != "Reject past booking dates" || fix["sha"] != "1a2b3c4d" || fix["status"] != "with_dev_lead" ||
		fix["url"] != "https://github.com/bau-build/library-booking/compare/task/fix-dates" || fix["branch"] != "task/fix-dates" {
		t.Fatalf("commit %v", fix)
	}
	if commits["older-branch"]["message"] != "older-branch" || commits["older-branch"]["sha"] != nil {
		t.Fatalf("fallback %v", commits["older-branch"])
	}
}

// TestMigratesOldDatabase opens a database made with the previous schema (no new columns, an unverified user, a
// task without a brief) and checks the migrations add everything without losing data, twice in a row.
func TestMigratesOldDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.sqlite")
	old, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := old.Exec(schemaSQL); err != nil {
		t.Fatal(err)
	}
	if _, err := old.Exec(`ALTER TABLE users ADD COLUMN password_hash TEXT NOT NULL DEFAULT ''`); err != nil {
		t.Fatal(err)
	}
	if _, err := old.Exec(`ALTER TABLE users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 0`); err != nil {
		t.Fatal(err)
	}
	stmts := []string{
		`INSERT INTO users (id, email, name, initials, role, created_at, password_hash, email_verified) VALUES ('u1', 'old@stu.bau.edu', 'Old', 'OL', 'student', '2026-01-01T00:00:00Z', 'x', 0)`,
		`INSERT INTO tasks (id, project_id, user_id, title, status, lane) VALUES ('t1', 'library', 'u1', 'Reject past dates in the booking form', 'todo', 'assigned')`,
		`INSERT INTO submissions (id, project_id, user_id, branch, ai_findings, lead_status, diff_url, bounty_amount, created_at) VALUES ('s1', 'library', 'u1', 'fix', '[]', 'pending', 'https://github.com/library-booking/compare/fix', 18, '2026-01-01T00:00:00Z')`,
	}
	for _, stmt := range stmts {
		if _, err := old.Exec(stmt); err != nil {
			t.Fatal(err)
		}
	}
	old.Close()
	for i := 0; i < 2; i++ {
		db, err := openDB(path)
		if err != nil {
			t.Fatalf("open %d: %v", i, err)
		}
		var description, priority, roles string
		var verified int
		if err := db.QueryRow(`SELECT description, priority FROM tasks WHERE id = 't1'`).Scan(&description, &priority); err != nil || description == "" || priority != "high" {
			t.Fatalf("task backfill %q %q %v", description, priority, err)
		}
		if err := db.QueryRow(`SELECT roles, email_verified FROM users WHERE id = 'u1'`).Scan(&roles, &verified); err != nil || roles != "[]" || verified != 1 {
			t.Fatalf("user migrate %q %d %v", roles, verified, err)
		}
		var seniors, world int
		_ = db.QueryRow(`SELECT COUNT(*) FROM users WHERE roles LIKE '%senior_engineer%'`).Scan(&seniors)
		_ = db.QueryRow(`SELECT COUNT(*) FROM world_projects`).Scan(&world)
		if seniors != 2 || world < 4 {
			t.Fatalf("seeds %d %d", seniors, world)
		}
		tx, _ := db.Begin()
		row, _ := one(tx, `SELECT * FROM submissions WHERE id = 's1'`)
		detail, err := submissionDetail(tx, row)
		_ = tx.Rollback()
		if err != nil || detail["diff_url"] != "https://github.com/bau-build/library-booking/compare/fix" {
			t.Fatalf("old diff url %v %v", detail["diff_url"], err)
		}
		db.Close()
	}
}

// Seeded senior-engineer accounts must not be claimable by registering (no email verification).
func TestReservedAccountsCannotBeClaimed(t *testing.T) {
	_, server, _ := gapServer(t)
	status, body := call(t, server, "POST", "/v1/auth/register", "", map[string]any{
		"name": "Not Ilias", "email": "ilias@bau.edu", "password": "Takeover123",
		"device": map[string]any{"platform": "web", "app_version": "1.0.0"},
	})
	if status != 409 || errCode(body) != "account_reserved" {
		t.Fatalf("expected 409 account_reserved, got %d %v", status, body)
	}
}
