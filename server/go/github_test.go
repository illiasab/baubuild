package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

const testKey = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=" // base64 of 32 ASCII bytes

// fakeGitHub answers the few GitHub REST calls the server makes. Tokens: "good" (octo, org member), "outsider"
// (stranger, not a member), "other" (octo again: same GitHub id), anything else is 401.
type fakeGitHub struct {
	server   *httptest.Server
	commits  atomic.Int32
	failRepo atomic.Bool
	mu       sync.Mutex
	branches []string
	auths    []string
}

func newFakeGitHub(t *testing.T) *fakeGitHub {
	f := &fakeGitHub{}
	f.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		f.mu.Lock()
		f.auths = append(f.auths, auth)
		f.mu.Unlock()
		if r.Header.Get("X-GitHub-Api-Version") != "2022-11-28" {
			w.WriteHeader(400)
			return
		}
		reply := func(status int, body any) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(status)
			_ = json.NewEncoder(w).Encode(body)
		}
		switch {
		case r.URL.Path == "/user":
			switch auth {
			case "good", "other", "service":
				reply(200, map[string]any{"login": "octo", "id": 42})
			case "outsider":
				reply(200, map[string]any{"login": "stranger", "id": 7})
			default:
				reply(401, map[string]any{"message": "Bad credentials"})
			}
		case r.URL.Path == "/user/memberships/orgs/bau-build":
			if auth == "good" || auth == "other" {
				reply(200, map[string]any{"state": "active"})
				return
			}
			reply(404, map[string]any{"message": "Not Found"})
		case strings.HasPrefix(r.URL.Path, "/orgs/bau-build/members/"):
			w.WriteHeader(404)
		case r.URL.Path == "/repos/bau-build/library-booking/commits":
			f.commits.Add(1)
			if f.failRepo.Load() {
				reply(500, map[string]any{"message": "boom"})
				return
			}
			branch := r.URL.Query().Get("sha")
			if r.URL.Query().Get("per_page") != "20" {
				reply(400, nil)
				return
			}
			reply(200, []any{map[string]any{
				"sha": "abc123" + branch[len(branch)-1:], "html_url": "https://github.com/bau-build/library-booking/commit/abc123",
				"author": map[string]any{"login": "octo"},
				"commit": map[string]any{"message": "Work on " + branch, "committer": map[string]any{"date": "2026-11-19T10:00:00Z"}},
			}})
		case r.URL.Path == "/repos/bau-build/library-booking/branches":
			f.mu.Lock()
			list := []any{map[string]any{"name": "main"}}
			for _, name := range f.branches {
				list = append(list, map[string]any{"name": name})
			}
			f.mu.Unlock()
			reply(200, list)
		default:
			reply(404, map[string]any{"message": "Not Found"})
		}
	}))
	t.Cleanup(f.server.Close)
	return f
}

func githubEnv(t *testing.T, f *fakeGitHub) {
	t.Setenv("GITHUB_API_URL", f.server.URL)
	t.Setenv("GITHUB_TOKEN_KEY", testKey)
	t.Setenv("GITHUB_ORG", "")
	t.Setenv("GITHUB_ORG_CHECK", "")
	t.Setenv("GITHUB_SERVICE_TOKEN", "")
	t.Setenv("GITHUB_CLIENT_ID", "")
	t.Setenv("GITHUB_WEBHOOK_SECRET", "")
}

func TestTokenSealing(t *testing.T) {
	t.Setenv("GITHUB_TOKEN_KEY", testKey)
	sealed, err := sealToken("u1", "github_pat_secret")
	if err != nil || !strings.HasPrefix(sealed, "v1:") || strings.Contains(sealed, "secret") {
		t.Fatalf("seal %q %v", sealed, err)
	}
	again, _ := sealToken("u1", "github_pat_secret")
	if again == sealed {
		t.Fatal("nonce must differ per value")
	}
	if plain, err := openToken("u1", sealed); err != nil || plain != "github_pat_secret" {
		t.Fatalf("open %q %v", plain, err)
	}
	if _, err := openToken("u2", sealed); err == nil {
		t.Fatal("a ciphertext copied to another user must not open")
	}
	if plain, err := openToken("u1", "gho_legacy"); err != nil || plain != "gho_legacy" {
		t.Fatalf("legacy plain %q %v", plain, err)
	}
	t.Setenv("GITHUB_TOKEN_KEY", "")
	if _, err := openToken("u1", sealed); err == nil {
		t.Fatal("sealed value without key must fail")
	}
	if out, err := sealToken("u1", "dev-token"); err != nil || out != "dev-token" {
		t.Fatalf("no key keeps plain %q %v", out, err)
	}
	t.Setenv("GITHUB_TOKEN_KEY", base64.StdEncoding.EncodeToString([]byte("short")))
	if _, ok := tokenKey(); ok {
		t.Fatal("a key that is not 32 bytes is ignored")
	}
}

func TestPlainTokenMigration(t *testing.T) {
	t.Setenv("GITHUB_TOKEN_KEY", "")
	path := filepath.Join(t.TempDir(), "bau.sqlite")
	db, err := openDB(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO users (id, email, name, initials, role, created_at, github_login, github_token) VALUES ('u9', 'nine@stu.bau.edu', 'Nine', 'NI', 'student', '2026-01-01T00:00:00Z', 'nine', 'gho_plain')`); err != nil {
		t.Fatal(err)
	}
	db.Close()
	t.Setenv("GITHUB_TOKEN_KEY", testKey)
	for i := 0; i < 2; i++ {
		db, err := openDB(path)
		if err != nil {
			t.Fatal(err)
		}
		var stored, source string
		if err := db.QueryRow(`SELECT github_token, github_token_source FROM users WHERE id = 'u9'`).Scan(&stored, &source); err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(stored, "v1:") || source != "oauth" {
			t.Fatalf("run %d: %q %q", i, stored, source)
		}
		if plain, err := openToken("u9", stored); err != nil || plain != "gho_plain" {
			t.Fatalf("run %d open %q %v", i, plain, err)
		}
		db.Close()
	}
}

func TestGitHubPATLink(t *testing.T) {
	f := newFakeGitHub(t)
	githubEnv(t, f)
	db, server, signUp := gapServer(t)
	student := signUp("ada@stu.bau.edu", "Ada Lovelace")
	second := signUp("bo@stu.bau.edu", "Bo Second")

	_, config, _ := callRaw(t, server, "GET", "/v1/config", "", nil)
	if config["features"].(map[string]any)["github"] != true {
		t.Fatalf("features.github with key %v", config)
	}
	if status, body, _ := callRaw(t, server, "PUT", "/v1/integrations/github/token", "", map[string]any{"token": "good"}); status != 401 {
		t.Fatalf("no session %d %v", status, body)
	}
	if status, body, _ := callRaw(t, server, "PUT", "/v1/integrations/github/token", student, map[string]any{"token": ""}); status != 400 || errCode(body) != "invalid_body" {
		t.Fatalf("blank %d %v", status, body)
	}
	if status, body, _ := callRaw(t, server, "PUT", "/v1/integrations/github/token", student, map[string]any{"token": "bad"}); status != 400 || errCode(body) != "invalid_token" {
		t.Fatalf("invalid %d %v", status, body)
	}
	if status, body, _ := callRaw(t, server, "PUT", "/v1/integrations/github/token", student, map[string]any{"token": "outsider"}); status != 403 || errCode(body) != "not_org_member" {
		t.Fatalf("outsider %d %v", status, body)
	}
	status, linked, raw := callRaw(t, server, "PUT", "/v1/integrations/github/token", student, map[string]any{"token": "good", "login": "someone-else"})
	if status != 200 || linked["connected"] != true || linked["login"] != "octo" || linked["source"] != "pat" || linked["linked_at"] == nil || linked["org_member"] != true {
		t.Fatalf("link %d %v", status, linked)
	}
	if strings.Contains(raw, "good") {
		t.Fatal("the token must never be returned")
	}
	var stored, username string
	if err := db.QueryRow(`SELECT github_token, github_username FROM users WHERE email = 'ada@stu.bau.edu'`).Scan(&stored, &username); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(stored, "v1:") || strings.Contains(stored, "good") || username != "octo" {
		t.Fatalf("stored %q %q", stored, username)
	}
	_, gh, _ := callRaw(t, server, "GET", "/v1/integrations/github", student, nil)
	if gh["connected"] != true || gh["source"] != "pat" || gh["login"] != "octo" || gh["linked_at"] == nil {
		t.Fatalf("status %v", gh)
	}
	// The same GitHub account can't be linked to a second BAU BUILD user.
	if status, body, _ := callRaw(t, server, "PUT", "/v1/integrations/github/token", second, map[string]any{"token": "other"}); status != 409 || errCode(body) != "github_account_in_use" {
		t.Fatalf("in use %d %v", status, body)
	}
	// With the org check off a non-member can link.
	t.Setenv("GITHUB_ORG_CHECK", "false")
	if status, body, _ := callRaw(t, server, "PUT", "/v1/integrations/github/token", second, map[string]any{"token": "outsider"}); status != 200 || body["login"] != "stranger" || body["org_member"] != nil {
		t.Fatalf("check off %d %v", status, body)
	}

	// An OAuth link (web) reports source oauth; an old row without a source counts as oauth.
	sealed, _ := sealToken(idOf(t, server, second), "gho_web")
	if _, err := db.Exec(`UPDATE users SET github_token = ?, github_token_source = 'oauth', github_login = 'webby' WHERE email = 'bo@stu.bau.edu'`, sealed); err != nil {
		t.Fatal(err)
	}
	if _, gh, _ := callRaw(t, server, "GET", "/v1/integrations/github", second, nil); gh["source"] != "oauth" || gh["login"] != "webby" {
		t.Fatalf("oauth status %v", gh)
	}
	if _, err := db.Exec(`UPDATE users SET github_token_source = NULL WHERE email = 'bo@stu.bau.edu'`); err != nil {
		t.Fatal(err)
	}
	if _, gh, _ := callRaw(t, server, "GET", "/v1/integrations/github", second, nil); gh["source"] != "oauth" {
		t.Fatalf("legacy status %v", gh)
	}

	status, gone, _ := callRaw(t, server, "DELETE", "/v1/integrations/github", student, nil)
	if status != 200 || gone["connected"] != false || gone["source"] != nil {
		t.Fatalf("delete %d %v", status, gone)
	}
	var token sql.NullString
	_ = db.QueryRow(`SELECT github_token FROM users WHERE email = 'ada@stu.bau.edu'`).Scan(&token)
	if token.Valid {
		t.Fatal("delete must clear the token")
	}
	if _, gh, _ := callRaw(t, server, "GET", "/v1/integrations/github", student, nil); gh["connected"] != false || gh["login"] != nil || gh["source"] != nil {
		t.Fatalf("after delete %v", gh)
	}

	t.Setenv("GITHUB_TOKEN_KEY", "")
	if _, config, _ := callRaw(t, server, "GET", "/v1/config", "", nil); config["features"].(map[string]any)["github"] != false {
		t.Fatalf("features.github without key or OAuth %v", config)
	}
	t.Setenv("GITHUB_CLIENT_ID", "client")
	if _, config, _ := callRaw(t, server, "GET", "/v1/config", "", nil); config["features"].(map[string]any)["github"] != true {
		t.Fatalf("features.github with OAuth %v", config)
	}
}

func idOf(t *testing.T, server *httptest.Server, token string) string {
	t.Helper()
	_, me, _ := callRaw(t, server, "GET", "/v1/me", token, nil)
	return me["id"].(string)
}

func libraryTask(t *testing.T, server *httptest.Server, token string) map[string]any {
	t.Helper()
	callRaw(t, server, "POST", "/v1/projects/library/join", token, nil)
	_, tasks, _ := callRaw(t, server, "GET", "/v1/projects/library/tasks", token, nil)
	for _, item := range tasks["items"].([]any) {
		task := item.(map[string]any)
		if task["title"] == "Reject past dates in the booking form" {
			return task
		}
	}
	t.Fatalf("no task in %v", tasks)
	return nil
}

func TestChangesWithGitHubCommits(t *testing.T) {
	f := newFakeGitHub(t)
	githubEnv(t, f)
	_, server, signUp := gapServer(t)
	student := signUp("ada@stu.bau.edu", "Ada Lovelace")
	other := signUp("bo@stu.bau.edu", "Bo Other")
	task := libraryTask(t, server, student)
	taskID := task["id"].(string)
	if task["branch"] != nil || task["pr_number"] != nil {
		t.Fatalf("no branch yet %v", task)
	}

	// Record the branch the app created.
	branch := "task/" + taskID + "-reject-past-dates"
	if status, body, _ := callRaw(t, server, "POST", "/v1/tasks/"+taskID+"/branch", student, map[string]any{"name": "has space"}); status != 400 {
		t.Fatalf("bad name %d %v", status, body)
	}
	if status, body, _ := callRaw(t, server, "POST", "/v1/tasks/"+taskID+"/branch", other, map[string]any{"name": branch}); status != 403 {
		t.Fatalf("not yours %d %v", status, body)
	}
	if status, body, _ := callRaw(t, server, "POST", "/v1/tasks/nope/branch", student, map[string]any{"name": branch}); status != 404 {
		t.Fatalf("unknown %d %v", status, body)
	}
	status, recorded, _ := callRaw(t, server, "POST", "/v1/tasks/"+taskID+"/branch", student, map[string]any{"name": branch})
	if status != 200 || recorded["branch"] != branch || recorded["id"] != taskID {
		t.Fatalf("record %d %v", status, recorded)
	}
	if again := libraryTask(t, server, student); again["branch"] != branch {
		t.Fatalf("tasks branch %v", again)
	}

	// Not linked and no service token: changes still work, with a code.
	_, changes, _ := callRaw(t, server, "GET", "/v1/projects/library/changes", student, nil)
	if changes["github_error"] != "github_not_linked" || len(changes["github_commits"].([]any)) != 0 || changes["connected"] != false {
		t.Fatalf("unlinked %v", changes)
	}
	if status, body, _ := callRaw(t, server, "PUT", "/v1/integrations/github/token", student, map[string]any{"token": "good"}); status != 200 {
		t.Fatalf("link %d %v", status, body)
	}
	_, changes, _ = callRaw(t, server, "GET", "/v1/projects/library/changes", student, nil)
	commits := changes["github_commits"].([]any)
	if changes["github_error"] != nil || len(commits) != 1 || changes["connected"] != true {
		t.Fatalf("commits %v", changes)
	}
	commit := commits[0].(map[string]any)
	if commit["branch"] != branch || commit["author_login"] != "octo" || commit["message"] != "Work on "+branch ||
		commit["committed_at"] != "2026-11-19T10:00:00Z" || commit["url"] == "" || commit["sha"] == "" {
		t.Fatalf("commit %v", commit)
	}
	f.mu.Lock()
	usedUserToken := false
	for _, auth := range f.auths {
		if auth == "good" {
			usedUserToken = true
		}
	}
	f.mu.Unlock()
	if !usedUserToken {
		t.Fatal("commits should use the caller's token")
	}
	calls := f.commits.Load()
	callRaw(t, server, "GET", "/v1/projects/library/changes", student, nil)
	if f.commits.Load() != calls {
		t.Fatal("commits for a branch are cached for 60s")
	}

	// A GitHub error never fails the request.
	f.failRepo.Store(true)
	callRaw(t, server, "POST", "/v1/tasks/"+taskID+"/branch", student, map[string]any{"name": branch}) // clears the cache
	status, changes, _ = callRaw(t, server, "GET", "/v1/projects/library/changes", student, nil)
	if status != 200 || changes["github_error"] != "github_unavailable" || len(changes["github_commits"].([]any)) != 0 || changes["repo_full_name"] != "bau-build/library-booking" {
		t.Fatalf("error fallback %d %v", status, changes)
	}
	f.failRepo.Store(false)

	// No recorded branch: the server lists task/ branches for the caller's tasks, with the service token.
	t.Setenv("GITHUB_SERVICE_TOKEN", "service")
	otherTask := libraryTask(t, server, other)
	otherBranch := "task/" + otherTask["id"].(string) + "-x"
	f.mu.Lock()
	f.branches = []string{otherBranch, branch, "task/unrelated-y"}
	f.mu.Unlock()
	_, changes, _ = callRaw(t, server, "GET", "/v1/projects/library/changes", other, nil)
	commits = changes["github_commits"].([]any)
	if changes["github_error"] != nil || len(commits) != 1 || commits[0].(map[string]any)["branch"] != otherBranch {
		t.Fatalf("listed branches %v", changes)
	}
}

func signedPost(t *testing.T, server *httptest.Server, secret, event string, payload any) (int, map[string]any) {
	t.Helper()
	raw, _ := json.Marshal(payload)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(raw)
	req, _ := http.NewRequest("POST", server.URL+"/v1/integrations/github/webhook", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-GitHub-Event", event)
	req.Header.Set("X-Hub-Signature-256", "sha256="+hex.EncodeToString(mac.Sum(nil)))
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	var out map[string]any
	_ = json.Unmarshal(body, &out)
	return res.StatusCode, out
}

func TestGitHubWebhook(t *testing.T) {
	f := newFakeGitHub(t)
	githubEnv(t, f)
	db, server, signUp := gapServer(t)
	student := signUp("ada@stu.bau.edu", "Ada Lovelace")
	task := libraryTask(t, server, student)
	taskID := task["id"].(string)
	branch := "task/" + taskID + "-reject-past-dates"
	repo := map[string]any{"full_name": "bau-build/library-booking"}

	if status, body := signedPost(t, server, "s3cret", "ping", map[string]any{}); status != 503 || errCode(body) != "github_not_configured" {
		t.Fatalf("unset secret %d %v", status, body)
	}
	t.Setenv("GITHUB_WEBHOOK_SECRET", "s3cret")
	if status, body := signedPost(t, server, "wrong", "ping", map[string]any{}); status != 401 || errCode(body) != "invalid_signature" {
		t.Fatalf("bad signature %d %v", status, body)
	}
	if status, _ := signedPost(t, server, "s3cret", "ping", map[string]any{"zen": "hi"}); status != 200 {
		t.Fatalf("ping %d", status)
	}

	status, body := signedPost(t, server, "s3cret", "push", map[string]any{
		"ref": "refs/heads/" + branch, "repository": repo, "after": "deadbeef",
		"head_commit": map[string]any{"id": "deadbeef", "message": "Reject past dates"},
	})
	if status != 200 || body["task_id"] != taskID {
		t.Fatalf("push %d %v", status, body)
	}
	if status, body := signedPost(t, server, "s3cret", "push", map[string]any{"ref": "refs/heads/main", "repository": repo}); status != 200 || body["ignored"] != "not_a_task_branch" {
		t.Fatalf("main push %d %v", status, body)
	}
	if status, body := signedPost(t, server, "s3cret", "push", map[string]any{"ref": "refs/heads/" + branch, "repository": map[string]any{"full_name": "evil/repo"}}); status != 200 || body["ignored"] != "other_repo" {
		t.Fatalf("other repo %d %v", status, body)
	}
	pushed := libraryTask(t, server, student)
	if pushed["branch"] != branch || pushed["last_commit_sha"] != "deadbeef" || pushed["last_commit_message"] != "Reject past dates" || pushed["pr_number"] != nil {
		t.Fatalf("after push %v", pushed)
	}
	var events int
	_ = db.QueryRow(`SELECT COUNT(*) FROM events WHERE kind = 'task_updated' AND payload LIKE ?`, "%"+taskID+"%").Scan(&events)
	if events != 1 {
		t.Fatalf("task_updated events %d", events)
	}

	pr := func(action string, merged bool, state string) (int, map[string]any) {
		return signedPost(t, server, "s3cret", "pull_request", map[string]any{
			"action": action, "repository": repo,
			"pull_request": map[string]any{"number": 5, "html_url": "https://github.com/bau-build/library-booking/pull/5", "state": state, "merged": merged, "head": map[string]any{"ref": branch}},
		})
	}
	if status, body := pr("opened", false, "open"); status != 200 || body["task_id"] != taskID {
		t.Fatalf("opened %d %v", status, body)
	}
	opened := libraryTask(t, server, student)
	if opened["pr_number"] != float64(5) || opened["pr_state"] != "open" || opened["pr_url"] != "https://github.com/bau-build/library-booking/pull/5" || opened["status"] == "done" {
		t.Fatalf("after open %v", opened)
	}
	if status, body := pr("closed", true, "closed"); status != 200 {
		t.Fatalf("merged %d %v", status, body)
	}
	merged := libraryTask(t, server, student)
	if merged["status"] != "done" || merged["pr_state"] != "merged" || merged["last_commit_sha"] != "deadbeef" {
		t.Fatalf("after merge %v", merged)
	}
	var notes int
	_ = db.QueryRow(`SELECT COUNT(*) FROM notifications WHERE text LIKE '%#5 was merged%'`).Scan(&notes)
	if notes != 1 {
		t.Fatalf("merge notification %d", notes)
	}
	if status, body := signedPost(t, server, "s3cret", "push", map[string]any{"ref": "refs/heads/task/ffff0000-x", "repository": repo}); status != 200 || body["ignored"] != "unknown_task" {
		t.Fatalf("unknown task %d %v", status, body)
	}
}

// TestNoGitHubConfig boots the way production runs today: no GITHUB_TOKEN_KEY, webhook secret, service token or
// OAuth app. Startup works (even with an old plain token row) and every app/web route keeps working.
func TestNoGitHubConfig(t *testing.T) {
	for _, key := range []string{"GITHUB_TOKEN_KEY", "GITHUB_WEBHOOK_SECRET", "GITHUB_SERVICE_TOKEN", "GITHUB_CLIENT_ID", "GITHUB_CLIENT_SECRET", "GITHUB_ORG", "GITHUB_ORG_CHECK", "GITHUB_API_URL"} {
		t.Setenv(key, "")
	}
	path := filepath.Join(t.TempDir(), "bau.sqlite")
	db, err := openDB(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO users (id, email, name, initials, role, created_at, github_login, github_token) VALUES ('old', 'old@stu.bau.edu', 'Old', 'OL', 'student', '2026-01-01T00:00:00Z', 'old', 'gho_plain')`); err != nil {
		t.Fatal(err)
	}
	db.Close()
	db, err = openDB(path)
	if err != nil {
		t.Fatalf("restart without GitHub env: %v", err)
	}
	var stored string
	_ = db.QueryRow(`SELECT github_token FROM users WHERE id = 'old'`).Scan(&stored)
	if stored != "gho_plain" {
		t.Fatalf("no key: tokens stay as they are, got %q", stored)
	}
	server := httptest.NewServer(&app{db: db})
	t.Cleanup(func() { server.Close(); db.Close() })
	register := func(email, name string) string {
		status, body := call(t, server, "POST", "/v1/auth/register", "", map[string]any{"name": name, "email": email, "password": "Password1", "device": map[string]any{"platform": "web", "app_version": "1.0.0"}})
		if status != 200 {
			t.Fatalf("register %d %v", status, body)
		}
		return body["access_token"].(string)
	}
	student := register("ada@stu.bau.edu", "Ada Lovelace")
	staff := register("sam@bau.edu", "Sam Staff")

	if _, config, _ := callRaw(t, server, "GET", "/v1/config", "", nil); config["features"].(map[string]any)["github"] != false {
		t.Fatalf("features.github %v", config)
	}
	task := libraryTask(t, server, student)
	if _, ok := task["branch"]; !ok {
		t.Fatalf("tasks carry branch fields %v", task)
	}
	for _, route := range []struct{ method, path, token string }{
		{"GET", "/v1/me", student}, {"GET", "/v1/projects", student}, {"GET", "/v1/projects/library", student},
		{"GET", "/v1/projects/library/tasks", student}, {"GET", "/v1/me/submissions", student},
		{"GET", "/v1/projects/library/mentor/messages", student}, {"GET", "/v1/staff/overview", staff},
		{"GET", "/v1/integrations/github", student}, {"GET", "/v1/home", student},
	} {
		if status, body, _ := callRaw(t, server, route.method, route.path, route.token, nil); status != 200 {
			t.Fatalf("%s %s: %d %v", route.method, route.path, status, body)
		}
	}
	if status, body, _ := callRaw(t, server, "POST", "/v1/projects/library/submissions", student, map[string]any{"branch": "task/" + task["id"].(string) + "-x"}); status != 201 {
		t.Fatalf("submission %d %v", status, body)
	}
	if status, body, _ := callRaw(t, server, "POST", "/v1/tasks/"+task["id"].(string)+"/branch", student, map[string]any{"name": "task/" + task["id"].(string) + "-x"}); status != 200 {
		t.Fatalf("branch %d %v", status, body)
	}
	status, changes, _ := callRaw(t, server, "GET", "/v1/projects/library/changes", student, nil)
	if status != 200 || changes["github_error"] != "not_configured" || len(changes["github_commits"].([]any)) != 0 || len(changes["branches"].([]any)) != 1 {
		t.Fatalf("changes %d %v", status, changes)
	}
	if status, body := signedPost(t, server, "anything", "push", map[string]any{}); status != 503 || errCode(body) != "github_not_configured" {
		t.Fatalf("webhook %d %v", status, body)
	}
	if status, body, _ := callRaw(t, server, "POST", "/v1/integrations/github/connect", student, nil); status != 503 || errCode(body) != "github_not_configured" {
		t.Fatalf("connect %d %v", status, body)
	}
	// PUT token still works without a key: stored without encryption (a warning is logged once).
	f := newFakeGitHub(t)
	t.Setenv("GITHUB_API_URL", f.server.URL)
	if status, body, _ := callRaw(t, server, "PUT", "/v1/integrations/github/token", student, map[string]any{"token": "good"}); status != 200 || body["source"] != "pat" {
		t.Fatalf("token without key %d %v", status, body)
	}
	_ = db.QueryRow(`SELECT github_token FROM users WHERE email = 'ada@stu.bau.edu'`).Scan(&stored)
	if stored != "good" {
		t.Fatalf("stored without key %q", stored)
	}
	if _, gh, _ := callRaw(t, server, "GET", "/v1/integrations/github", student, nil); gh["connected"] != true || gh["source"] != "pat" {
		t.Fatalf("status without key %v", gh)
	}
}
