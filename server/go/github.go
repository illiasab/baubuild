package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

// GitHub integration: one connection per user, whether it came from the web OAuth flow (source "oauth") or a
// fine-grained PAT the app registers (source "pat"). Tokens are sealed with AES-256-GCM (GITHUB_TOKEN_KEY) and
// never logged or returned.

// migrateGitHub adds the connection columns and the task_branches table, then seals any plain stored tokens when
// GITHUB_TOKEN_KEY is set. Additive and safe to run on every start.
func migrateGitHub(db *sql.DB) error {
	alters := []string{
		`ALTER TABLE users ADD COLUMN github_token_source TEXT`,
		`ALTER TABLE users ADD COLUMN github_linked_at TEXT`,
		`ALTER TABLE users ADD COLUMN github_user_id TEXT`,
	}
	for _, stmt := range alters {
		if _, err := db.Exec(stmt); err != nil && !strings.Contains(strings.ToLower(err.Error()), "duplicate column") {
			return err
		}
	}
	if _, err := db.Exec(`
CREATE TABLE IF NOT EXISTS task_branches (
    task_id TEXT NOT NULL,
    repo_full_name TEXT NOT NULL DEFAULT '',
    branch TEXT NOT NULL,
    last_sha TEXT,
    last_message TEXT,
    pr_number INTEGER,
    pr_url TEXT,
    pr_state TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (task_id, branch)
);
CREATE INDEX IF NOT EXISTS task_branches_branch ON task_branches (repo_full_name, branch);`); err != nil {
		return err
	}
	// Tokens stored before the source column existed came from the OAuth flow.
	if _, err := db.Exec(`UPDATE users SET github_token_source = 'oauth' WHERE github_token IS NOT NULL AND github_token != '' AND github_token_source IS NULL`); err != nil {
		return err
	}
	// Sealing is best effort: a bad row is logged and left as it is, startup never fails on it.
	sealPlainTokens(db)
	return nil
}

// sealPlainTokens encrypts every github_token that is still plain text. Without a key it does nothing. Errors are
// logged (never the token) and skipped.
func sealPlainTokens(db *sql.DB) {
	if _, ok := tokenKey(); !ok {
		return
	}
	rows, err := db.Query(`SELECT id, github_token FROM users WHERE github_token IS NOT NULL AND github_token != '' AND github_token NOT LIKE 'v1:%'`)
	if err != nil {
		log.Printf("github: could not read stored tokens to encrypt: %v", err)
		return
	}
	type pending struct{ id, token string }
	var plain []pending
	for rows.Next() {
		var id, token sql.NullString
		if err := rows.Scan(&id, &token); err != nil || !id.Valid || !token.Valid {
			continue
		}
		plain = append(plain, pending{id.String, token.String})
	}
	rows.Close()
	sealed := 0
	for _, p := range plain {
		value, err := sealToken(p.id, p.token)
		if err != nil {
			log.Printf("github: could not encrypt the token of user %s; left as is", p.id)
			continue
		}
		if _, err := db.Exec(`UPDATE users SET github_token = ? WHERE id = ? AND github_token = ?`, value, p.id, p.token); err != nil {
			log.Printf("github: could not store the encrypted token of user %s; left as is", p.id)
			continue
		}
		sealed++
	}
	if sealed > 0 {
		log.Printf("github: encrypted %d stored token(s) at rest", sealed)
	}
}

const tokenPrefix = "v1:"

var warnKeyOnce sync.Once

// tokenKey reads GITHUB_TOKEN_KEY (base64, 32 bytes). A missing or malformed key means "no encryption".
func tokenKey() ([]byte, bool) {
	raw := strings.TrimSpace(os.Getenv("GITHUB_TOKEN_KEY"))
	if raw == "" {
		return nil, false
	}
	key, err := base64.StdEncoding.DecodeString(raw)
	if err != nil {
		key, err = base64.RawStdEncoding.DecodeString(raw)
	}
	if err != nil || len(key) != 32 {
		return nil, false
	}
	return key, true
}

func warnNoKey() {
	warnKeyOnce.Do(func() {
		if strings.TrimSpace(os.Getenv("GITHUB_TOKEN_KEY")) != "" {
			log.Printf("github: GITHUB_TOKEN_KEY is not valid base64 for 32 bytes; GitHub tokens are stored WITHOUT encryption")
			return
		}
		log.Printf("github: GITHUB_TOKEN_KEY is not set; GitHub tokens are stored WITHOUT encryption (dev only)")
	})
}

// sealToken encrypts a token for one user (the user id is bound as associated data). Without a key the token is
// returned as is and a warning is logged once.
func sealToken(userID, token string) (string, error) {
	key, ok := tokenKey()
	if !ok {
		warnNoKey()
		return token, nil
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	return tokenPrefix + base64.StdEncoding.EncodeToString(gcm.Seal(nonce, nonce, []byte(token), []byte(userID))), nil
}

var errNoKey = errors.New("github token is encrypted but GITHUB_TOKEN_KEY is not set")

// openToken returns the plain token. Values without the v1: prefix are legacy plain text and returned as is.
func openToken(userID, stored string) (string, error) {
	if !strings.HasPrefix(stored, tokenPrefix) {
		return stored, nil
	}
	key, ok := tokenKey()
	if !ok {
		return "", errNoKey
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(stored, tokenPrefix))
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	if len(raw) < gcm.NonceSize() {
		return "", errors.New("github token ciphertext is too short")
	}
	plain, err := gcm.Open(nil, raw[:gcm.NonceSize()], raw[gcm.NonceSize():], []byte(userID))
	if err != nil {
		return "", err
	}
	return string(plain), nil
}

// githubUsable is features.github: the OAuth app is configured or tokens can be sealed.
func githubUsable() bool {
	_, ok := tokenKey()
	return strings.TrimSpace(os.Getenv("GITHUB_CLIENT_ID")) != "" || ok
}

func githubAPI() string {
	if base := strings.TrimSpace(os.Getenv("GITHUB_API_URL")); base != "" {
		return strings.TrimRight(base, "/")
	}
	return "https://api.github.com"
}

var githubClient = &http.Client{Timeout: 10 * time.Second}

type ghResponse struct {
	status int
	body   []byte
}

// githubCall makes one GitHub REST call. path starts with "/". The token is only put in the header.
func githubCall(method, path, token string) (ghResponse, error) {
	req, err := http.NewRequest(method, githubAPI()+path, nil)
	if err != nil {
		return ghResponse{}, err
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	req.Header.Set("User-Agent", "bau-build")
	res, err := githubClient.Do(req)
	if err != nil {
		return ghResponse{}, err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	return ghResponse{status: res.StatusCode, body: raw}, nil
}

func githubOrgCheck() bool {
	return !strings.EqualFold(strings.TrimSpace(os.Getenv("GITHUB_ORG_CHECK")), "false")
}

func serviceToken() string { return strings.TrimSpace(os.Getenv("GITHUB_SERVICE_TOKEN")) }

// orgMember says whether login is in the org: true / false, or nil when GitHub would not tell.
func orgMember(token, login string) (any, error) {
	org := url.PathEscape(githubOrg())
	res, err := githubCall(http.MethodGet, "/user/memberships/orgs/"+org, token)
	if err != nil {
		return nil, err
	}
	if res.status == http.StatusOK {
		var m map[string]any
		_ = json.Unmarshal(res.body, &m)
		return asString(m["state"]) == "active", nil
	}
	if res.status >= 500 {
		return nil, fmt.Errorf("github status %d", res.status)
	}
	checker := serviceToken()
	if checker == "" {
		checker = token
	}
	res, err = githubCall(http.MethodGet, "/orgs/"+org+"/members/"+url.PathEscape(login), checker)
	if err != nil {
		return nil, err
	}
	switch {
	case res.status == http.StatusNoContent:
		return true, nil
	case res.status == http.StatusNotFound:
		return false, nil
	case res.status >= 500:
		return nil, fmt.Errorf("github status %d", res.status)
	}
	return nil, nil
}

// currentUser authenticates the request in a short transaction (so GitHub calls never hold the DB lock).
func (a *app) currentUser(r *http.Request) (map[string]any, error) {
	var user map[string]any
	_, _, err := a.withTx(func(tx *sql.Tx) (int, any, error) {
		u, _, err := userFromAccess(tx, r.Header.Get("Authorization"))
		user = u
		return 0, nil, err
	})
	return user, err
}

var (
	projectChangesPath = regexp.MustCompile(`^/v1/projects/([A-Za-z0-9_-]+)/changes$`)
	taskBranchPath     = regexp.MustCompile(`^/v1/tasks/([A-Za-z0-9_-]+)/branch$`)
	taskBranchName     = regexp.MustCompile(`^task/([A-Za-z0-9]+)(?:-|$)`)
	branchName         = regexp.MustCompile(`^[A-Za-z0-9._/-]+$`)
)

// githubRoutes are the GitHub routes that call GitHub (or verify a webhook) outside the request transaction.
func (a *app) githubRoutes(r *http.Request, path string) (int, any, bool, error) {
	switch {
	case r.Method == http.MethodPost && path == "/v1/integrations/github/webhook":
		status, body, err := a.githubWebhook(r)
		return status, body, true, err
	case r.Method == http.MethodPut && path == "/v1/integrations/github/token":
		status, body, err := a.linkToken(r)
		return status, body, true, err
	case r.Method == http.MethodGet && projectChangesPath.MatchString(path):
		status, body, err := a.changes(r, projectChangesPath.FindStringSubmatch(path)[1])
		return status, body, true, err
	}
	return 0, nil, false, nil
}

// githubTxRoutes run inside the normal authenticated transaction.
func githubTxRoutes(tx *sql.Tx, r *http.Request, path string, user, body map[string]any) (int, any, bool, error) {
	switch {
	case r.Method == http.MethodGet && path == "/v1/integrations/github":
		return 200, githubStatus(user), true, nil
	case r.Method == http.MethodDelete && path == "/v1/integrations/github":
		_, err := tx.Exec(`UPDATE users SET github_login = NULL, github_token = NULL, github_token_source = NULL, github_linked_at = NULL, github_user_id = NULL WHERE id = ?`, str(user, "id"))
		return 200, map[string]any{"connected": false, "login": nil, "source": nil, "linked_at": nil}, true, err
	case r.Method == http.MethodPost && taskBranchPath.MatchString(path):
		out, err := recordTaskBranch(tx, user, taskBranchPath.FindStringSubmatch(path)[1], body)
		return 200, out, true, err
	}
	return 0, nil, false, nil
}

func githubConnected(user map[string]any) bool {
	return str(user, "github_token") != ""
}

func githubStatus(user map[string]any) map[string]any {
	if !githubConnected(user) {
		return map[string]any{"connected": false, "login": nil, "source": nil, "linked_at": nil}
	}
	source := str(user, "github_token_source")
	if source == "" {
		source = "oauth"
	}
	return map[string]any{"connected": true, "login": nullable(user, "github_login"), "source": source, "linked_at": nullable(user, "github_linked_at")}
}

// storeGitHubLink writes the one connection for a user, replacing any earlier one (OAuth or PAT).
func storeGitHubLink(tx *sql.Tx, userID, token, login, githubID, source string) (string, error) {
	sealed, err := sealToken(userID, token)
	if err != nil {
		return "", err
	}
	moment := nowISO()
	var gid any
	if githubID != "" {
		gid = githubID
	}
	_, err = tx.Exec(`UPDATE users SET github_login = ?, github_token = ?, github_token_source = ?, github_linked_at = ?, github_user_id = ?,
		github_username = CASE WHEN github_username IS NULL OR github_username = '' THEN ? ELSE github_username END WHERE id = ?`,
		login, sealed, source, moment, gid, login, userID)
	return moment, err
}

// linkToken is PUT /v1/integrations/github/token {token, login?}. login is only a hint; GitHub's answer wins.
func (a *app) linkToken(r *http.Request) (int, any, error) {
	user, err := a.currentUser(r)
	if err != nil {
		return 0, nil, err
	}
	body := readBody(r)
	token := strings.TrimSpace(asString(body["token"]))
	if token == "" || len(token) > 512 || strings.ContainsAny(token, " \r\n\t") {
		return 0, nil, fail(400, "invalid_body", "token is required.")
	}
	unavailable := fail(502, "github_unavailable", "GitHub could not be reached. Try again later.")
	res, err := githubCall(http.MethodGet, "/user", token)
	if err != nil {
		return 0, nil, unavailable
	}
	if res.status == http.StatusUnauthorized {
		return 0, nil, fail(400, "invalid_token", "GitHub did not accept that token.")
	}
	if res.status != http.StatusOK {
		return 0, nil, unavailable
	}
	var profile map[string]any
	if json.Unmarshal(res.body, &profile) != nil || asString(profile["login"]) == "" {
		return 0, nil, unavailable
	}
	login := asString(profile["login"])
	githubID := ""
	if id, ok := profile["id"].(float64); ok {
		githubID = fmt.Sprintf("%.0f", id)
	}
	var member any
	if githubOrgCheck() {
		member, err = orgMember(token, login)
		if err != nil {
			return 0, nil, unavailable
		}
		if member == false {
			return 0, nil, fail(403, "not_org_member", "Accept the invite to the "+githubOrg()+" organization on GitHub, then connect again.")
		}
	}
	var linkedAt string
	_, _, err = a.withTx(func(tx *sql.Tx) (int, any, error) {
		if githubID != "" {
			other, err := one(tx, `SELECT id FROM users WHERE github_user_id = ? AND id != ? AND github_token IS NOT NULL`, githubID, str(user, "id"))
			if err != nil {
				return 0, nil, err
			}
			if other != nil {
				return 0, nil, fail(409, "github_account_in_use", "That GitHub account is linked to another BAU BUILD account.")
			}
		}
		linkedAt, err = storeGitHubLink(tx, str(user, "id"), token, login, githubID, "pat")
		return 0, nil, err
	})
	if err != nil {
		return 0, nil, err
	}
	return 200, map[string]any{"connected": true, "login": login, "source": "pat", "linked_at": linkedAt, "org_member": member}, nil
}

// ---- commits for /changes -------------------------------------------------------------------------------------

type commitCacheEntry struct {
	at      time.Time
	commits []map[string]any
}

var commitCache = struct {
	sync.Mutex
	entries map[string]commitCacheEntry
}{entries: map[string]commitCacheEntry{}}

const commitCacheTTL = 60 * time.Second

func cacheKey(repo, branch string) string { return strings.ToLower(repo) + "\x00" + branch }

func forgetCommits(repo, branch string) {
	commitCache.Lock()
	delete(commitCache.entries, cacheKey(repo, branch))
	commitCache.Unlock()
}

// ghError maps a GitHub status to the github_error code on /changes.
func ghError(status int) string {
	switch {
	case status == http.StatusUnauthorized:
		return "github_unauthorized"
	case status == http.StatusForbidden || status == http.StatusTooManyRequests:
		return "github_forbidden"
	case status == http.StatusNotFound:
		return "github_not_found"
	}
	return "github_unavailable"
}

// branchCommits lists the last 20 commits on a branch (cached for 60s). A missing branch is an empty list.
func branchCommits(repo, branch, token string) ([]map[string]any, string) {
	key := cacheKey(repo, branch)
	commitCache.Lock()
	entry, ok := commitCache.entries[key]
	commitCache.Unlock()
	if ok && time.Since(entry.at) < commitCacheTTL {
		return entry.commits, ""
	}
	res, err := githubCall(http.MethodGet, "/repos/"+escapeGitHubPath(repo)+"/commits?sha="+url.QueryEscape(branch)+"&per_page=20", token)
	if err != nil {
		return nil, "github_unavailable"
	}
	commits := []map[string]any{}
	switch {
	case res.status == http.StatusOK:
		var list []map[string]any
		if json.Unmarshal(res.body, &list) != nil {
			return nil, "github_unavailable"
		}
		for _, item := range list {
			commit, _ := item["commit"].(map[string]any)
			author, _ := item["author"].(map[string]any)
			committer, _ := commit["committer"].(map[string]any)
			if committer == nil {
				committer, _ = commit["author"].(map[string]any)
			}
			var login any
			if name := asString(author["login"]); name != "" {
				login = name
			}
			var when any
			if date := asString(committer["date"]); date != "" {
				when = date
			}
			commits = append(commits, map[string]any{
				"sha": asString(item["sha"]), "message": asString(commit["message"]), "branch": branch,
				"author_login": login, "url": asString(item["html_url"]), "committed_at": when,
			})
		}
	case res.status == http.StatusNotFound || res.status == http.StatusUnprocessableEntity || res.status == http.StatusConflict:
		// Branch deleted (for example after a merge) or an empty repo: nothing to show for it.
	default:
		return nil, ghError(res.status)
	}
	commitCache.Lock()
	commitCache.entries[key] = commitCacheEntry{at: time.Now(), commits: commits}
	commitCache.Unlock()
	return commits, ""
}

// listTaskBranches finds task/<taskId>-… branches in the repo for the given task ids.
func listTaskBranches(repo, token string, taskIDs map[string]bool) ([]string, string) {
	res, err := githubCall(http.MethodGet, "/repos/"+escapeGitHubPath(repo)+"/branches?per_page=100", token)
	if err != nil {
		return nil, "github_unavailable"
	}
	if res.status != http.StatusOK {
		return nil, ghError(res.status)
	}
	var list []map[string]any
	if json.Unmarshal(res.body, &list) != nil {
		return nil, "github_unavailable"
	}
	out := []string{}
	for _, item := range list {
		name := asString(item["name"])
		if parts := taskBranchName.FindStringSubmatch(name); parts != nil && taskIDs[parts[1]] {
			out = append(out, name)
		}
	}
	return out, ""
}

// projectChanges is the database part of GET /v1/projects/{id}/changes: submissions grouped by branch.
func projectChanges(tx *sql.Tx, user map[string]any, projectID string) (map[string]any, map[string]any, error) {
	project, err := requireProject(tx, projectID)
	if err != nil {
		return nil, nil, err
	}
	if err := requireMember(tx, user, projectID); err != nil {
		return nil, nil, err
	}
	rows, err := many(tx, `SELECT * FROM submissions WHERE project_id = ? AND user_id = ? ORDER BY created_at DESC`, projectID, str(user, "id"))
	if err != nil {
		return nil, nil, err
	}
	grouped := []any{}
	seen := map[string][]any{}
	order := []string{}
	for _, row := range rows {
		name := str(row, "branch")
		if _, ok := seen[name]; !ok {
			order = append(order, name)
		}
		seen[name] = append(seen[name], changeCommit(project, row))
	}
	for _, name := range order {
		grouped = append(grouped, map[string]any{"name": name, "commits": seen[name]})
	}
	return map[string]any{
		"connected": githubConnected(user), "repo": project["repo_name"], "repo_full_name": repoFullName(project),
		"branches": grouped, "github_commits": []any{}, "github_error": nil,
	}, project, nil
}

// changes is GET /v1/projects/{id}/changes with github_commits filled from GitHub for the caller's task branches.
// GitHub problems never fail the request: github_commits is [] and github_error carries a code.
func (a *app) changes(r *http.Request, projectID string) (int, any, error) {
	var out map[string]any
	var repo, token string
	var branches []string
	taskIDs := map[string]bool{}
	_, _, err := a.withTx(func(tx *sql.Tx) (int, any, error) {
		user, _, err := userFromAccess(tx, r.Header.Get("Authorization"))
		if err != nil {
			return 0, nil, err
		}
		var project map[string]any
		out, project, err = projectChanges(tx, user, projectID)
		if err != nil {
			return 0, nil, err
		}
		repo, _ = repoFullName(project).(string)
		tasks, err := many(tx, `SELECT id FROM tasks WHERE project_id = ? AND user_id = ?`, projectID, str(user, "id"))
		if err != nil {
			return 0, nil, err
		}
		for _, task := range tasks {
			taskIDs[str(task, "id")] = true
		}
		recorded, err := many(tx, `SELECT DISTINCT b.branch FROM task_branches b JOIN tasks t ON t.id = b.task_id
			WHERE t.project_id = ? AND t.user_id = ? ORDER BY b.updated_at DESC`, projectID, str(user, "id"))
		if err != nil {
			return 0, nil, err
		}
		seen := map[string]bool{}
		for _, row := range recorded {
			if name := str(row, "branch"); !seen[name] {
				seen[name] = true
				branches = append(branches, name)
			}
		}
		for _, row := range out["branches"].([]any) {
			name := asString(row.(map[string]any)["name"])
			if strings.HasPrefix(name, "task/") && !seen[name] {
				seen[name] = true
				branches = append(branches, name)
			}
		}
		if githubConnected(user) {
			if plain, err := openToken(str(user, "id"), str(user, "github_token")); err == nil {
				token = plain
			}
		}
		return 0, nil, nil
	})
	if err != nil {
		return 0, nil, err
	}
	if token == "" {
		token = serviceToken()
	}
	if repo == "" {
		return 200, out, nil
	}
	if token == "" {
		if githubUsable() {
			out["github_error"] = "github_not_linked"
		} else {
			out["github_error"] = "not_configured"
		}
		return 200, out, nil
	}
	if len(branches) == 0 && len(taskIDs) > 0 {
		found, code := listTaskBranches(repo, token, taskIDs)
		if code != "" {
			out["github_error"] = code
			return 200, out, nil
		}
		branches = found
	}
	all := []map[string]any{}
	for _, branch := range branches {
		commits, code := branchCommits(repo, branch, token)
		if code != "" {
			out["github_error"] = code
			return 200, out, nil
		}
		all = append(all, commits...)
	}
	sort.SliceStable(all, func(i, j int) bool { return asString(all[i]["committed_at"]) > asString(all[j]["committed_at"]) })
	list := make([]any, 0, len(all))
	for _, commit := range all {
		list = append(list, commit)
	}
	out["github_commits"] = list
	return 200, out, nil
}

// ---- task branches ---------------------------------------------------------------------------------------------

// upsertTaskBranch records a branch for a task; nil arguments leave the stored value as it is.
func upsertTaskBranch(tx *sql.Tx, taskID, repo, branch string, sha, message, prNumber, prURL, prState any) error {
	_, err := tx.Exec(`INSERT INTO task_branches (task_id, repo_full_name, branch, last_sha, last_message, pr_number, pr_url, pr_state, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(task_id, branch) DO UPDATE SET
			repo_full_name = CASE WHEN excluded.repo_full_name != '' THEN excluded.repo_full_name ELSE task_branches.repo_full_name END,
			last_sha = COALESCE(excluded.last_sha, task_branches.last_sha),
			last_message = COALESCE(excluded.last_message, task_branches.last_message),
			pr_number = COALESCE(excluded.pr_number, task_branches.pr_number),
			pr_url = COALESCE(excluded.pr_url, task_branches.pr_url),
			pr_state = COALESCE(excluded.pr_state, task_branches.pr_state),
			updated_at = excluded.updated_at`,
		taskID, repo, branch, sha, message, prNumber, prURL, prState, nowISO())
	return err
}

// withBranch adds branch, pr_number, pr_url, pr_state (and the last pushed commit) to a task payload.
func withBranch(tx *sql.Tx, task map[string]any) (map[string]any, error) {
	row, err := one(tx, `SELECT * FROM task_branches WHERE task_id = ? ORDER BY updated_at DESC, rowid DESC LIMIT 1`, task["id"])
	if err != nil {
		return nil, err
	}
	task["branch"], task["pr_number"], task["pr_url"], task["pr_state"] = nil, nil, nil, nil
	task["last_commit_sha"], task["last_commit_message"] = nil, nil
	if row != nil {
		task["branch"] = row["branch"]
		task["pr_url"] = nullable(row, "pr_url")
		task["pr_state"] = nullable(row, "pr_state")
		task["last_commit_sha"] = nullable(row, "last_sha")
		task["last_commit_message"] = nullable(row, "last_message")
		if row["pr_number"] != nil {
			task["pr_number"] = num(row, "pr_number")
		}
	}
	return task, nil
}

// recordTaskBranch is POST /v1/tasks/{id}/branch {name}: the app announces the branch it created.
func recordTaskBranch(tx *sql.Tx, user map[string]any, taskID string, body map[string]any) (map[string]any, error) {
	task, err := one(tx, `SELECT * FROM tasks WHERE id = ?`, taskID)
	if err != nil {
		return nil, err
	}
	if task == nil {
		return nil, fail(404, "not_found", "That task is not on the board.")
	}
	if str(task, "user_id") != str(user, "id") {
		return nil, fail(403, "role_required", "That task belongs to another student.")
	}
	if err := requireMember(tx, user, str(task, "project_id")); err != nil {
		return nil, err
	}
	name := strings.TrimSpace(asString(body["name"]))
	if name == "" {
		name = strings.TrimSpace(asString(body["branch"]))
	}
	if name == "" || len(name) > 200 || !branchName.MatchString(name) || strings.Contains(name, "..") || strings.HasPrefix(name, "/") || strings.HasSuffix(name, "/") {
		return nil, fail(400, "invalid_body", "name must be a branch name such as task/<task_id>-short-title.")
	}
	project, err := requireProject(tx, str(task, "project_id"))
	if err != nil {
		return nil, err
	}
	repo, _ := repoFullName(project).(string)
	if err := upsertTaskBranch(tx, taskID, repo, name, nil, nil, nil, nil, nil); err != nil {
		return nil, err
	}
	forgetCommits(repo, name)
	return withBranch(tx, taskJSON(task))
}

// ---- webhook ---------------------------------------------------------------------------------------------------

func validSignature(secret string, body []byte, header string) bool {
	if !strings.HasPrefix(header, "sha256=") {
		return false
	}
	given, err := hex.DecodeString(strings.TrimPrefix(header, "sha256="))
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return hmac.Equal(given, mac.Sum(nil))
}

// githubWebhook is POST /v1/integrations/github/webhook (org webhook: push + pull_request, JSON).
func (a *app) githubWebhook(r *http.Request) (int, any, error) {
	secret := strings.TrimSpace(os.Getenv("GITHUB_WEBHOOK_SECRET"))
	if secret == "" {
		return 0, nil, fail(503, "github_not_configured", "The GitHub webhook is not configured on this server.")
	}
	defer r.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(r.Body, 25<<20))
	if err != nil {
		return 0, nil, fail(400, "invalid_body", "The webhook body could not be read.")
	}
	if !validSignature(secret, raw, r.Header.Get("X-Hub-Signature-256")) {
		return 0, nil, fail(401, "invalid_signature", "The webhook signature does not match.")
	}
	var payload map[string]any
	if err := json.Unmarshal(raw, &payload); err != nil {
		return 0, nil, fail(400, "invalid_body", "The webhook body is not JSON.")
	}
	event := r.Header.Get("X-GitHub-Event")
	repository, _ := payload["repository"].(map[string]any)
	repo := asString(repository["full_name"])
	switch event {
	case "ping":
		return 200, map[string]any{"ok": true}, nil
	case "push":
		ref := asString(payload["ref"])
		if !strings.HasPrefix(ref, "refs/heads/") || payload["deleted"] == true {
			return 200, map[string]any{"ok": true, "ignored": "not_a_branch_push"}, nil
		}
		branch := strings.TrimPrefix(ref, "refs/heads/")
		head, _ := payload["head_commit"].(map[string]any)
		if head == nil {
			if commits, _ := payload["commits"].([]any); len(commits) > 0 {
				head, _ = commits[len(commits)-1].(map[string]any)
			}
		}
		var sha, message any
		if head != nil {
			sha, message = asString(head["id"]), clip(asString(head["message"]), 500)
		} else if after := asString(payload["after"]); after != "" {
			sha = after
		}
		return a.webhookTask(repo, branch, func(tx *sql.Tx, task map[string]any) (map[string]any, error) {
			if err := upsertTaskBranch(tx, str(task, "id"), repo, branch, sha, message, nil, nil, nil); err != nil {
				return nil, err
			}
			return map[string]any{"sha": sha, "message": message}, nil
		})
	case "pull_request":
		action := asString(payload["action"])
		pr, _ := payload["pull_request"].(map[string]any)
		headRef, _ := pr["head"].(map[string]any)
		branch := asString(headRef["ref"])
		if pr == nil || branch == "" {
			return 200, map[string]any{"ok": true, "ignored": "no_pull_request"}, nil
		}
		number, _ := whole(pr["number"])
		state := asString(pr["state"])
		merged := pr["merged"] == true
		if merged {
			state = "merged"
		}
		if state == "" {
			state = "open"
		}
		prURL := asString(pr["html_url"])
		return a.webhookTask(repo, branch, func(tx *sql.Tx, task map[string]any) (map[string]any, error) {
			if err := upsertTaskBranch(tx, str(task, "id"), repo, branch, nil, nil, number, prURL, state); err != nil {
				return nil, err
			}
			extra := map[string]any{"pr_number": number, "pr_url": prURL, "pr_state": state, "action": action}
			title := str(task, "title")
			switch {
			case action == "closed" && merged:
				if str(task, "status") != "done" {
					if _, err := tx.Exec(`UPDATE tasks SET status = 'done' WHERE id = ?`, str(task, "id")); err != nil {
						return nil, err
					}
					extra["status"] = "done"
				}
				notify(tx, str(task, "user_id"), fmt.Sprintf("Pull request #%d was merged. “%s” is done.", number, title))
			case action == "opened" || action == "reopened":
				notify(tx, str(task, "user_id"), fmt.Sprintf("Pull request #%d is open for “%s”.", number, title))
			case action == "closed":
				notify(tx, str(task, "user_id"), fmt.Sprintf("Pull request #%d for “%s” was closed without merging.", number, title))
			}
			return extra, nil
		})
	}
	return 200, map[string]any{"ok": true, "ignored": "event"}, nil
}

// webhookTask finds the task a task/<taskId>-… branch belongs to, applies fn, and sends task_updated to the task's
// student. Branches that are not task branches, unknown tasks and other repos are acknowledged and ignored.
func (a *app) webhookTask(repo, branch string, fn func(*sql.Tx, map[string]any) (map[string]any, error)) (int, any, error) {
	parts := taskBranchName.FindStringSubmatch(branch)
	if parts == nil {
		return 200, map[string]any{"ok": true, "ignored": "not_a_task_branch"}, nil
	}
	forgetCommits(repo, branch)
	return a.withTx(func(tx *sql.Tx) (int, any, error) {
		task, err := one(tx, `SELECT * FROM tasks WHERE id = ?`, parts[1])
		if err != nil {
			return 0, nil, err
		}
		if task == nil {
			return 200, map[string]any{"ok": true, "ignored": "unknown_task"}, nil
		}
		project, err := one(tx, `SELECT * FROM projects WHERE id = ?`, str(task, "project_id"))
		if err != nil {
			return 0, nil, err
		}
		if want, _ := repoFullName(project).(string); want != "" && repo != "" && !strings.EqualFold(want, repo) {
			return 200, map[string]any{"ok": true, "ignored": "other_repo"}, nil
		}
		extra, err := fn(tx, task)
		if err != nil {
			return 0, nil, err
		}
		payload := map[string]any{"task_id": task["id"], "project_id": task["project_id"], "branch": branch}
		for key, value := range extra {
			payload[key] = value
		}
		encoded, _ := json.Marshal(payload)
		if _, err := tx.Exec(`INSERT INTO events (user_id, kind, payload, created_at) VALUES (?, 'task_updated', ?, ?)`, str(task, "user_id"), string(encoded), nowISO()); err != nil {
			return 0, nil, err
		}
		return 200, map[string]any{"ok": true, "task_id": task["id"]}, nil
	})
}
