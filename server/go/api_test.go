package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAPI(t *testing.T) {
	dir := t.TempDir()
	db, err := openDB(filepath.Join(dir, "bau.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	os.Setenv("BAU_ADMIN_EMAILS", "lead.admin@bau.edu")
	os.Setenv("SMTP_HOST", "test")
	os.Unsetenv("MENTOR_API_KEY")
	var mailed string
	originalMail := deliverCode
	deliverCode = func(to, subject, body string) error {
		mailed = body
		return nil
	}
	t.Cleanup(func() { deliverCode = originalMail })
	server := httptest.NewServer(&app{db: db})
	t.Cleanup(server.Close)
	device := map[string]any{"platform": "ios", "app_version": "1.0.0"}
	codeOf := func() string {
		t.Helper()
		for _, line := range strings.Split(mailed, "\n") {
			line = strings.TrimSpace(line)
			if len(line) == 6 {
				return line
			}
		}
		t.Fatalf("no code in %q", mailed)
		return ""
	}
	signIn := func(email, name string) map[string]any {
		t.Helper()
		status, body := call(t, server, "POST", "/v1/auth/register", "", map[string]any{
			"name": name, "email": email, "password": "Password1", "device": device,
		})
		if status == 409 {
			status, body = call(t, server, "POST", "/v1/auth/login", "", map[string]any{
				"email": email, "password": "Password1", "device": device,
			})
			if status != 200 {
				t.Fatalf("login %s: %d %v", email, status, body)
			}
			return body
		}
		if status != 202 {
			t.Fatalf("register %s: %d %v", email, status, body)
		}
		status, body = call(t, server, "POST", "/v1/auth/verify-email", "", map[string]any{
			"email": email, "code": codeOf(), "device": device,
		})
		if status != 200 {
			t.Fatalf("verify %s: %d %v", email, status, body)
		}
		return body
	}
	authed := func(method, path, token string, payload any) (int, map[string]any, string) {
		t.Helper()
		return callRaw(t, server, method, path, token, payload)
	}

	status, body := call(t, server, "POST", "/v1/auth/register", "", map[string]any{
		"name": "Outsider", "email": "person@gmail.com", "password": "Password1", "device": device,
	})
	if status != 403 || body["error"].(map[string]any)["code"] != "domain_not_allowed" {
		t.Fatalf("gmail: %d %v", status, body)
	}
	status, pending := call(t, server, "POST", "/v1/auth/register", "", map[string]any{
		"name": "Bea Early", "email": "early@stu.bau.edu", "password": "Password1", "device": device,
	})
	if status != 202 || pending["verification_required"] != true {
		t.Fatalf("pending %d %v", status, pending)
	}
	status, denied := call(t, server, "POST", "/v1/auth/login", "", map[string]any{
		"email": "early@stu.bau.edu", "password": "Password1", "device": device,
	})
	if status != 403 || denied["error"].(map[string]any)["code"] != "email_not_verified" {
		t.Fatalf("unverified %d %v", status, denied)
	}
	status, wrong := call(t, server, "POST", "/v1/auth/login", "", map[string]any{
		"email": "early@stu.bau.edu", "password": "nope-nope", "device": device,
	})
	if status != 401 || wrong["error"].(map[string]any)["code"] != "invalid_credentials" {
		t.Fatalf("credentials %d %v", status, wrong)
	}

	student := signIn("ada@stu.bau.edu", "Ada Lovelace")
	if student["user"].(map[string]any)["role"] != "student" || student["entitlement"].(map[string]any)["tier"] != "bau_free" {
		t.Fatalf("student session %#v", student)
	}
	if signIn("sam@bau.edu", "Sam Staff")["user"].(map[string]any)["role"] != "staff" {
		t.Fatal("staff role")
	}
	admin := signIn("lead.admin@bau.edu", "Admin User")
	if admin["user"].(map[string]any)["role"] != "admin" {
		t.Fatal("admin role")
	}
	status, refreshed := call(t, server, "POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": student["refresh_token"]})
	if status != 200 || refreshed["refresh_token"] == student["refresh_token"] {
		t.Fatalf("refresh %#v", refreshed)
	}
	status, reused := call(t, server, "POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": student["refresh_token"]})
	if status != 401 || reused["error"].(map[string]any)["code"] != "refresh_reused" {
		t.Fatalf("reuse %d %v", status, reused)
	}
	if code, _, _ := authed("GET", "/v1/me", refreshed["access_token"].(string), nil); code != 401 {
		t.Fatalf("revoked access %d", code)
	}
	again := signIn("ada@stu.bau.edu", "Ada Lovelace")
	code, sessions, _ := authed("GET", "/v1/auth/sessions", again["access_token"].(string), nil)
	if code != 200 || len(sessions["sessions"].([]any)) < 1 {
		t.Fatalf("sessions %d %v", code, sessions)
	}
	if code, _, _ := authed("POST", "/v1/auth/logout", again["access_token"].(string), nil); code != 204 {
		t.Fatal("logout")
	}
	if code, _, _ := authed("GET", "/v1/me", again["access_token"].(string), nil); code != 401 {
		t.Fatal("logged out token still works")
	}

	if code, cfg, _ := callRaw(t, server, "GET", "/v1/config", "", nil); code != 200 || cfg["min_supported"].(map[string]any)["linux"] == nil {
		t.Fatalf("config %d %v", code, cfg)
	}
	ada := signIn("ada@stu.bau.edu", "Ada Lovelace")
	token := ada["access_token"].(string)
	code, me, _ := authed("PATCH", "/v1/me", token, map[string]any{"bio": "Shipping real work.", "skills": []any{"Python"}})
	if code != 200 || me["skills"].([]any)[0] != "Python" || len(me["classes"].([]any)) != 0 {
		t.Fatalf("profile %d %v", code, me)
	}
	code, stats, _ := authed("GET", "/v1/me/stats", token, nil)
	if stats["status"] != "rookie" || stats["max"].(float64) != 500 {
		t.Fatalf("stats %v", stats)
	}
	if code, _, _ = authed("PATCH", "/v1/me", token, map[string]any{"bio": "  "}); code != 400 {
		t.Fatal("blank bio")
	}

	code, low, _ := authed("POST", "/v1/projects/transcript/join", token, nil)
	if code != 403 || low["error"].(map[string]any)["code"] != "score_too_low" {
		t.Fatalf("transcript %d %v", code, low)
	}
	_, work, _ := authed("GET", "/v1/me/work", token, nil)
	if len(work["in_progress"].([]any)) != 0 {
		t.Fatal("score gate wrote work")
	}
	_, joined, _ := authed("POST", "/v1/projects/library/join", token, nil)
	if joined["result"] != "joined" {
		t.Fatal(joined)
	}
	_, tasks, _ := authed("GET", "/v1/projects/library/tasks?assignee=me", token, nil)
	if len(tasks["items"].([]any)) < 1 {
		t.Fatal("tasks")
	}
	_, pending, _ = authed("POST", "/v1/projects/shuttle/join", token, nil)
	if pending["result"] != "pending_approval" {
		t.Fatal(pending)
	}
	_, work, _ = authed("GET", "/v1/me/work", token, nil)
	if len(work["in_progress"].([]any)) != 1 || work["awaiting_approval"].([]any)[0].(map[string]any)["reviewer_label"] != "dev lead" {
		t.Fatalf("work %#v", work)
	}
	_, closed, _ := authed("POST", "/v1/projects/ranking/join", token, nil)
	if closed["error"].(map[string]any)["code"] != "class_not_enrolled" {
		t.Fatal(closed)
	}
	if _, err := db.Exec(`UPDATE users SET tier = 'payment_required' WHERE email = ?`, "ada@stu.bau.edu"); err != nil {
		t.Fatal(err)
	}
	_, blocked, _ := authed("POST", "/v1/projects/faq/join", token, nil)
	if blocked["error"].(map[string]any)["code"] != "payment_required" {
		t.Fatal(blocked)
	}
	if _, err := db.Exec(`UPDATE users SET tier = 'bau_free' WHERE email = ?`, "ada@stu.bau.edu"); err != nil {
		t.Fatal(err)
	}

	code, denied, _ = authed("POST", "/v1/inquiries", token, map[string]any{"title": "x"})
	if denied["error"].(map[string]any)["code"] != "role_required" {
		t.Fatal(denied)
	}
	staff := signIn("sam@bau.edu", "Sam Staff")
	code, created, _ := authed("POST", "/v1/inquiries", staff["access_token"].(string), map[string]any{
		"title": "Fix the lab printer", "description": "The queue drops jobs.", "department": "Labs",
		"difficulty": "easy", "bounty": 18, "is_urgent": false, "min_score": 0, "joining": "everyone", "reviewer_type": "ai_then_lead",
	})
	// Staff posts go live immediately: no admin approval step.
	if code != 201 || created["status"] != "published" || created["project_id"] == nil {
		t.Fatalf("inquiry %d %v", code, created)
	}
	inquiryID := created["id"].(string)
	admin = signIn("lead.admin@bau.edu", "Admin User")
	code, _, _ = authed("POST", "/v1/admin/inquiries/"+inquiryID+"/publish", admin["access_token"].(string), nil)
	if code != 409 {
		t.Fatal(code)
	}
	_, found, _ := authed("GET", "/v1/search?q=printer", token, nil)
	if found["projects"].([]any)[0].(map[string]any)["title"] != "Fix the lab printer" {
		t.Fatal(found)
	}
	_, home, _ := authed("GET", "/v1/home", token, nil)
	if home["greeting_name"] != "Ada" || len(home["urgent"].([]any)) == 0 {
		t.Fatal(home)
	}

	_, submission, _ := authed("POST", "/v1/projects/library/submissions", token, map[string]any{"branch": "fix-date-validation"})
	steps := map[string]map[string]any{}
	for _, item := range submission["steps"].([]any) {
		step := item.(map[string]any)
		steps[step["step"].(string)] = step
	}
	if steps["ai_review"]["status"] != "complete" || steps["lead_decision"]["status"] != "pending" {
		t.Fatal(submission)
	}
	code, _, _ = authed("POST", "/v1/submissions/"+submission["id"].(string)+"/decision", token, map[string]any{"action": "approve"})
	if code != 403 {
		t.Fatal(code)
	}
	code, _, _ = authed("GET", "/v1/lead/queue", token, nil)
	if code != 403 {
		t.Fatal(code)
	}
	code, decided, _ := authed("POST", "/v1/submissions/"+submission["id"].(string)+"/decision", admin["access_token"].(string), map[string]any{"action": "approve", "comment": "Looks right."})
	if code != 200 {
		t.Fatal(decided)
	}
	for _, item := range decided["steps"].([]any) {
		step := item.(map[string]any)
		if step["step"] == "bounty" && step["paid_at"] == nil {
			t.Fatal("unpaid")
		}
	}
	_, stats, _ = authed("GET", "/v1/me/stats", token, nil)
	if stats["score"].(float64) != 18 || stats["earnings_total"].(float64) != 18 || stats["status"] != "rookie" {
		t.Fatal(stats)
	}
	code, _, _ = authed("POST", "/v1/submissions/"+submission["id"].(string)+"/decision", admin["access_token"].(string), map[string]any{"action": "approve"})
	if code != 409 {
		t.Fatal(code)
	}
	_, earned, _ := authed("GET", "/v1/me/earnings", token, nil)
	if earned["total"].(float64) != 18 || len(earned["items"].([]any)) != 1 {
		t.Fatal(earned)
	}
	code, started, _ := authed("POST", "/v1/me/lead-application/exam/session", token, nil)
	if code != 201 || bytes.Contains([]byte(mustJSON(started["questions"])), []byte("answer")) {
		t.Fatal(started)
	}
	_, passed, _ := authed("POST", started["submit_path"].(string), token, map[string]any{"token": started["token"], "answers": []any{1, 1, 1, 1}})
	if passed["passed"] != true {
		t.Fatal(passed)
	}
	_, me, _ = authed("GET", "/v1/me", token, nil)
	if me["role"] != "student" {
		t.Fatal(me)
	}
	_, promoted, _ := authed("POST", "/v1/admin/lead-applications/"+ada["user"].(map[string]any)["id"].(string)+"/in-person", admin["access_token"].(string), nil)
	if promoted["role"] != "lead" {
		t.Fatal(promoted)
	}

	if code, _, _ = authed("PUT", "/v1/projects/library/bookmark", token, nil); code != 204 {
		t.Fatal(code)
	}
	_, saved, _ := authed("GET", "/v1/me/bookmarks", token, nil)
	if saved["items"].([]any)[0].(map[string]any)["id"] != "library" {
		t.Fatal(saved)
	}
	_, _, stream := authed("POST", "/v1/projects/library/mentor/messages", token, map[string]any{"text": "Are the dates sorted as strings?"})
	if !bytes.Contains([]byte(stream), []byte("Date objects")) || !bytes.Contains([]byte(stream), []byte("JS-DATE-001")) {
		t.Fatal(stream)
	}
	taskID := tasks["items"].([]any)[0].(map[string]any)["id"].(string)
	code, review, _ := authed("POST", "/v1/tasks/"+taskID+"/reviews", token, map[string]any{"ref": "task/dates"})
	if code != 202 || review["review_id"] == "" {
		t.Fatalf("review %d %v", code, review)
	}
	other := signIn("bea@stu.bau.edu", "Bea Student")
	if code, _, _ = authed("GET", "/v1/admin/kb/search?q=date", other["access_token"].(string), nil); code != 403 {
		t.Fatalf("student search %d", code)
	}
	var hits map[string]any
	code, hits, _ = authed("GET", "/v1/admin/kb/search?q=date", admin["access_token"].(string), nil)
	if code != 200 || !bytes.Contains([]byte(mustJSON(hits)), []byte("JS-DATE-001")) {
		t.Fatalf("kb search %d %v", code, hits)
	}
	_, notes, _ := authed("GET", "/v1/notifications", token, nil)
	if len(notes["items"].([]any)[0].(map[string]any)["text"].(string)) < 1 {
		t.Fatal(notes)
	}
	_, unread, _ := authed("GET", "/v1/notifications/unread-count", token, nil)
	if unread["unread"].(float64) < 1 {
		t.Fatal(unread)
	}
	_, cleared, _ := authed("POST", "/v1/notifications/read", token, map[string]any{"all": true})
	if cleared["unread"].(float64) != 0 {
		t.Fatal(cleared)
	}
	_, _, live := authed("GET", "/v1/events/stream", token, nil)
	if !bytes.HasPrefix([]byte(live), []byte("event: heartbeat")) {
		t.Fatal(live)
	}
	code, _, _ = authed("POST", "/v1/devices", token, map[string]any{"platform": "ios", "push_token": "abc", "provider": "apns", "app_version": "1.0.0"})
	if code != 201 {
		t.Fatal(code)
	}
	code, _, _ = authed("POST", "/v1/devices", token, map[string]any{"platform": "linux", "push_token": "nope", "provider": "fcm", "app_version": "1.0.0"})
	if code != 400 {
		t.Fatal(code)
	}
	_, billing, _ := authed("POST", "/v1/billing/checkout", token, map[string]any{"plan": "outside_builder"})
	if billing["error"].(map[string]any)["code"] != "billing_not_enabled" {
		t.Fatal(billing)
	}
	if code, _, _ = callRaw(t, server, "GET", "/v1/billing/plans", "", nil); code != 200 {
		t.Fatal(code)
	}
}

func call(t *testing.T, server *httptest.Server, method, path, token string, payload any) (int, map[string]any) {
	t.Helper()
	status, body, _ := callRaw(t, server, method, path, token, payload)
	return status, body
}

func callRaw(t *testing.T, server *httptest.Server, method, path, token string, payload any) (int, map[string]any, string) {
	t.Helper()
	var reader io.Reader
	if payload != nil {
		raw, _ := json.Marshal(payload)
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, server.URL+path, reader)
	if err != nil {
		t.Fatal(err)
	}
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	if res.Header.Get("Content-Type") == "text/event-stream" {
		return res.StatusCode, nil, string(raw)
	}
	if res.StatusCode == 204 || len(raw) == 0 {
		return res.StatusCode, nil, ""
	}
	var body map[string]any
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("%s %s: %s", method, path, raw)
	}
	return res.StatusCode, body, string(raw)
}

func mustJSON(value any) string {
	raw, _ := json.Marshal(value)
	return string(raw)
}
