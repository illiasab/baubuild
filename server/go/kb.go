package main

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

func migrateKB(db *sql.DB) error {
	alters := []string{
		`ALTER TABLE mentor_messages ADD COLUMN task_id TEXT`,
		`ALTER TABLE mentor_messages ADD COLUMN citations TEXT NOT NULL DEFAULT '[]'`,
		`ALTER TABLE mentor_messages ADD COLUMN model TEXT NOT NULL DEFAULT ''`,
	}
	for _, stmt := range alters {
		if _, err := db.Exec(stmt); err != nil && !strings.Contains(strings.ToLower(err.Error()), "duplicate column") {
			return err
		}
	}
	if _, err := db.Exec(`
CREATE TABLE IF NOT EXISTS kb_documents (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    type TEXT NOT NULL,
    body TEXT NOT NULL,
    tags TEXT NOT NULL DEFAULT '[]',
    language TEXT,
    project_id TEXT,
    visibility TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    source_url TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS kb_document_versions (
    id TEXT NOT NULL,
    version INTEGER NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (id, version)
);
CREATE TABLE IF NOT EXISTS kb_rules (
    rule_id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    severity TEXT NOT NULL,
    languages TEXT NOT NULL DEFAULT '[]',
    example_good TEXT,
    example_bad TEXT,
    version INTEGER NOT NULL DEFAULT 1,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS reviews (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    ref TEXT,
    status TEXT NOT NULL,
    verdict TEXT,
    summary TEXT,
    criteria TEXT NOT NULL DEFAULT '[]',
    findings TEXT NOT NULL DEFAULT '[]',
    kb_versions TEXT NOT NULL DEFAULT '{}',
    model TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS mentor_feedback (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    helpful INTEGER NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS mentor_audit (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    kind TEXT NOT NULL,
    model TEXT,
    kb_versions TEXT,
    created_at TEXT NOT NULL
);
`); err != nil {
		return err
	}
	if _, err := db.Exec(`CREATE VIRTUAL TABLE IF NOT EXISTS kb_fts USING fts5(doc_id UNINDEXED, title, body, tags)`); err != nil {
		kbFTS = false
	}
	return seedStandards(db)
}

var kbFTS = true

func seedStandards(db *sql.DB) error {
	now := time.Now().UTC().Format("2006-01-02T15:04:05Z")
	rules := []struct {
		id, title, body, severity, languages, good, bad string
	}{
		{"JS-DATE-001", "Compare dates as dates", "Do not sort or compare calendar dates as strings. Parse them into Date objects, or compare timestamps from those objects. String order matches calendar order only when every part is zero-padded.", "warning", `["javascript","typescript"]`, "dateA.getTime() < dateB.getTime()", `a < b where a is "2026-1-5"`},
		{"TEST-CASE-001", "Cover the edges", "Add one case for yesterday, one for today, and one for a blank field. Assert the message the form shows, not only true or false.", "warning", `["javascript","typescript","kotlin","swift","go","python"]`, "expect(message).toBe('Choose a future date')", "expect(valid).toBe(false)"},
		{"A11Y-ERR-002", "Announce form errors", "Put the error in the form and give that node role=\"alert\", or aria-live=\"assertive\", so a screen reader speaks it when it appears.", "blocking", `["html","javascript","typescript"]`, `role="alert"`, "a color change only"},
		{"CSV-COL-001", "One column per fact", "Write one row of headers, then one row per person. Keep the club name in its own column so staff can filter it.", "info", `["python","go","javascript"]`, "club,name,present", "one cell with the club and the name"},
		{"GIT-MSG-001", "Say what changed", "A commit message names the behavior that changed. It does not say 'fix' or 'wip' alone.", "info", `["git"]`, "Reject past dates on the booking form", "fix"},
		{"SEC-SECRET-001", "No secrets in the repo", "Do not commit tokens, passwords, or private keys. Read them from the environment.", "blocking", `["go","kotlin","swift","javascript","python"]`, "os.Getenv(\"API_KEY\")", "token = \"ghp_...\""},
	}
	for _, rule := range rules {
		if _, err := db.Exec(`INSERT OR IGNORE INTO kb_rules (rule_id, title, body, severity, languages, example_good, example_bad, version, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)`,
			rule.id, rule.title, rule.body, rule.severity, rule.languages, rule.good, rule.bad, now); err != nil {
			return err
		}
	}
	rows, err := db.Query(`SELECT id, title, description FROM projects`)
	if err != nil {
		return err
	}
	type brief struct{ id, title, description string }
	briefs := []brief{}
	for rows.Next() {
		var item brief
		if err := rows.Scan(&item.id, &item.title, &item.description); err != nil {
			rows.Close()
			return err
		}
		briefs = append(briefs, item)
	}
	rows.Close()
	for _, item := range briefs {
		if _, err := db.Exec(`INSERT OR IGNORE INTO kb_documents (id, title, type, body, tags, language, project_id, visibility, version, source_url, created_at) VALUES (?, ?, 'brief', ?, '["project"]', '', ?, 'student', 1, '', ?)`,
			"brief-"+item.id, item.title, item.description, item.id, now); err != nil {
			return err
		}
	}
	return reindexKB(db)
}

func reindexKB(db *sql.DB) error {
	if !kbFTS {
		return nil
	}
	if _, err := db.Exec(`DELETE FROM kb_fts`); err != nil {
		kbFTS = false
		return nil
	}
	type indexed struct{ id, title, body, tags string }
	load := func(query string) ([]indexed, error) {
		rows, err := db.Query(query)
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		out := []indexed{}
		for rows.Next() {
			var item indexed
			if err := rows.Scan(&item.id, &item.title, &item.body, &item.tags); err != nil {
				return nil, err
			}
			out = append(out, item)
		}
		return out, rows.Err()
	}
	docs, err := load(`SELECT id, title, body, tags FROM kb_documents`)
	if err != nil {
		return err
	}
	rules, err := load(`SELECT rule_id, title, body, languages FROM kb_rules`)
	if err != nil {
		return err
	}
	for _, item := range rules {
		item.id = "rule:" + item.id
		docs = append(docs, item)
	}
	for _, item := range docs {
		if _, err := db.Exec(`INSERT INTO kb_fts (doc_id, title, body, tags) VALUES (?, ?, ?, ?)`, item.id, item.title, item.body, item.tags); err != nil {
			kbFTS = false
			return nil
		}
	}
	return nil
}

func kbCitations(tx *sql.Tx, user map[string]any, query string, project map[string]any) []map[string]any {
	hits := searchKB(tx, user, query, str(project, "id"), false)
	out := []map[string]any{}
	for _, hit := range hits {
		if str(hit, "visibility") == "staff_only" {
			continue
		}
		item := map[string]any{"title": str(hit, "title"), "document_id": str(hit, "id")}
		if strings.HasPrefix(str(hit, "id"), "rule:") {
			item["rule_id"] = strings.TrimPrefix(str(hit, "id"), "rule:")
			item["document_id"] = ""
		}
		out = append(out, item)
		if len(out) == 4 {
			break
		}
	}
	if out == nil {
		out = []map[string]any{}
	}
	return out
}

func searchKB(tx *sql.Tx, user map[string]any, query, projectID string, includeStaff bool) []map[string]any {
	role := ""
	if user != nil {
		role = str(user, "role")
	}
	staff := includeStaff || role == "lead" || role == "admin" || role == "staff"
	words := kbWords(query)
	if len(words) == 0 {
		words = []string{"date"}
	}
	seen := map[string]bool{}
	out := []map[string]any{}
	add := func(row map[string]any) {
		id := str(row, "id")
		if id == "" || seen[id] {
			return
		}
		if str(row, "visibility") == "staff_only" && !staff {
			return
		}
		if projectID != "" && str(row, "project_id") != "" && str(row, "project_id") != projectID && str(row, "type") == "brief" {
			return
		}
		seen[id] = true
		out = append(out, row)
	}
	if kbFTS {
		match := strings.Join(words, " OR ")
		rows, err := many(tx, `SELECT doc_id FROM kb_fts WHERE kb_fts MATCH ? LIMIT 20`, match)
		if err == nil {
			for _, row := range rows {
				add(kbHit(tx, str(row, "doc_id")))
			}
		}
	}
	like := "%" + words[0] + "%"
	rules, err := many(tx, `SELECT rule_id, title, body, severity, version FROM kb_rules WHERE title LIKE ? OR body LIKE ? LIMIT 8`, like, like)
	if err == nil {
		for _, row := range rules {
			add(map[string]any{"id": "rule:" + str(row, "rule_id"), "title": row["title"], "body": row["body"], "visibility": "student", "type": "rule", "version": row["version"], "severity": row["severity"]})
		}
	}
	docs, err := many(tx, `SELECT * FROM kb_documents WHERE title LIKE ? OR body LIKE ? LIMIT 8`, like, like)
	if err == nil {
		for _, row := range docs {
			add(row)
		}
	}
	if projectID != "" {
		brief, err := one(tx, `SELECT * FROM kb_documents WHERE id = ?`, "brief-"+projectID)
		if err == nil && brief != nil {
			add(brief)
		}
	}
	return out
}

func kbHit(tx *sql.Tx, id string) map[string]any {
	if strings.HasPrefix(id, "rule:") {
		row, err := one(tx, `SELECT * FROM kb_rules WHERE rule_id = ?`, strings.TrimPrefix(id, "rule:"))
		if err != nil || row == nil {
			return nil
		}
		return map[string]any{"id": id, "title": row["title"], "body": row["body"], "visibility": "student", "type": "rule", "version": row["version"], "severity": row["severity"]}
	}
	row, err := one(tx, `SELECT * FROM kb_documents WHERE id = ?`, id)
	if err != nil {
		return nil
	}
	return row
}

func kbWords(query string) []string {
	parts := regexp.MustCompile(`[a-zA-Z0-9]{3,}`).FindAllString(strings.ToLower(query), 8)
	out := []string{}
	skip := map[string]bool{"the": true, "and": true, "for": true, "are": true, "you": true, "what": true, "how": true}
	for _, part := range parts {
		if skip[part] {
			continue
		}
		out = append(out, part)
	}
	return out
}

func mentorQuota(tx *sql.Tx, userID string) error {
	start := time.Now().UTC().Format("2006-01-02")
	row, err := one(tx, `SELECT COUNT(*) AS n FROM mentor_messages WHERE user_id = ? AND role = 'student' AND created_at >= ?`, userID, start)
	if err != nil {
		return err
	}
	if num(row, "n") >= 60 {
		return &apiError{Status: 429, Code: "quota_exceeded", Message: "The mentor limit for today is 60 messages.", Extra: map[string]any{"retry_after_s": 3600}}
	}
	return nil
}

func mentorStream(replyID, reply string, citations []map[string]any) string {
	var b strings.Builder
	status, _ := json.Marshal(map[string]string{"label": "Checking BAU standards"})
	b.WriteString("event: status\ndata: ")
	b.Write(status)
	b.WriteString("\n\n")
	for _, citation := range citations {
		payload, _ := json.Marshal(citation)
		b.WriteString("event: citation\ndata: ")
		b.Write(payload)
		b.WriteString("\n\n")
	}
	words := strings.Split(reply, " ")
	for i, word := range words {
		piece := word
		if i != len(words)-1 {
			piece += " "
		}
		payload, _ := json.Marshal(map[string]string{"text": piece})
		b.WriteString("event: delta\ndata: ")
		b.Write(payload)
		b.WriteString("\n\n")
	}
	done, _ := json.Marshal(map[string]string{"message_id": replyID, "id": replyID, "text": reply})
	b.WriteString("event: done\ndata: ")
	b.Write(done)
	b.WriteString("\n\n")
	return b.String()
}

func guardMentor(reply string) string {
	parts := strings.Split(reply, "```")
	if len(parts) < 3 {
		return clip(reply, 2000)
	}
	var b strings.Builder
	for i, part := range parts {
		if i%2 == 1 {
			lines := strings.Split(part, "\n")
			if len(lines) > 11 {
				b.WriteString("Try the change yourself. I can explain the idea, not the whole file.")
				continue
			}
			b.WriteString("```")
			b.WriteString(part)
			b.WriteString("```")
			continue
		}
		b.WriteString(part)
	}
	return clip(b.String(), 2000)
}

func clip(text string, max int) string {
	if utf8.RuneCountInString(text) <= max {
		return strings.TrimSpace(text)
	}
	runes := []rune(text)
	return strings.TrimSpace(string(runes[:max]))
}

func latestReview(tx *sql.Tx, projectID, userID string) any {
	row, err := one(tx, `SELECT r.* FROM reviews r JOIN tasks t ON t.id = r.task_id WHERE t.project_id = ? AND r.user_id = ? ORDER BY r.created_at DESC LIMIT 1`, projectID, userID)
	if err != nil || row == nil {
		return nil
	}
	return reviewJSON(row)
}

func reviewJSON(row map[string]any) map[string]any {
	var criteria, findings []any
	var versions map[string]any
	_ = json.Unmarshal([]byte(str(row, "criteria")), &criteria)
	_ = json.Unmarshal([]byte(str(row, "findings")), &findings)
	_ = json.Unmarshal([]byte(str(row, "kb_versions")), &versions)
	if criteria == nil {
		criteria = []any{}
	}
	if findings == nil {
		findings = []any{}
	}
	if versions == nil {
		versions = map[string]any{}
	}
	return map[string]any{
		"id": row["id"], "task_id": row["task_id"], "ref": row["ref"], "status": row["status"],
		"verdict": row["verdict"], "summary": row["summary"], "criteria": criteria, "findings": findings,
		"kb_versions": versions, "model": row["model"], "created_at": row["created_at"],
	}
}

func requireStaff(user map[string]any) error {
	switch str(user, "role") {
	case "admin", "lead", "staff":
		return nil
	default:
		return fail(403, "role_required", "Only staff can do that.")
	}
}

func mentorRoutes(tx *sql.Tx, r *http.Request, path string, user map[string]any, body map[string]any) (int, any, bool, error) {
	switch {
	case r.Method == http.MethodPost && path == "/v1/admin/kb/documents":
		if err := requireStaff(user); err != nil {
			return 0, nil, true, err
		}
		out, err := createKBDocument(tx, body)
		return 201, out, true, err
	case r.Method == http.MethodGet && path == "/v1/admin/kb/documents":
		if err := requireStaff(user); err != nil {
			return 0, nil, true, err
		}
		out, err := listKBDocuments(tx, r.URL.Query())
		return 200, out, true, err
	case r.Method == http.MethodGet && path == "/v1/admin/kb/search":
		if err := requireStaff(user); err != nil {
			return 0, nil, true, err
		}
		hits := searchKB(tx, user, r.URL.Query().Get("q"), r.URL.Query().Get("project_id"), true)
		items := []any{}
		for _, hit := range hits {
			items = append(items, map[string]any{"id": hit["id"], "title": hit["title"], "type": hit["type"], "visibility": hit["visibility"]})
		}
		return 200, map[string]any{"items": items}, true, nil
	case r.Method == http.MethodPost && path == "/v1/admin/kb/reindex":
		if err := requireStaff(user); err != nil {
			return 0, nil, true, err
		}
		return 202, map[string]any{"ok": true}, true, nil
	case r.Method == http.MethodPost && path == "/v1/admin/kb/rules":
		if err := requireStaff(user); err != nil {
			return 0, nil, true, err
		}
		out, err := saveRule(tx, "", body)
		return 201, out, true, err
	case strings.HasPrefix(path, "/v1/admin/kb/rules/") && (r.Method == http.MethodPatch || r.Method == http.MethodPost):
		if err := requireStaff(user); err != nil {
			return 0, nil, true, err
		}
		out, err := saveRule(tx, strings.TrimPrefix(path, "/v1/admin/kb/rules/"), body)
		return 200, out, true, err
	case strings.HasPrefix(path, "/v1/admin/kb/documents/"):
		id := strings.TrimPrefix(path, "/v1/admin/kb/documents/")
		if err := requireStaff(user); err != nil {
			return 0, nil, true, err
		}
		switch r.Method {
		case http.MethodGet:
			row, err := one(tx, `SELECT * FROM kb_documents WHERE id = ?`, id)
			if err != nil {
				return 0, nil, true, err
			}
			if row == nil {
				return 0, nil, true, fail(404, "not_found", "That document is not in the knowledge base.")
			}
			return 200, row, true, nil
		case http.MethodPatch:
			out, err := patchKBDocument(tx, id, body)
			return 200, out, true, err
		case http.MethodDelete:
			if _, err := tx.Exec(`DELETE FROM kb_documents WHERE id = ?`, id); err != nil {
				return 0, nil, true, err
			}
			return 204, nil, true, nil
		}
	case r.Method == http.MethodPost && strings.HasPrefix(path, "/v1/tasks/") && strings.HasSuffix(path, "/reviews"):
		taskID := strings.TrimSuffix(strings.TrimPrefix(path, "/v1/tasks/"), "/reviews")
		out, err := createReview(tx, user, taskID, body)
		return 202, out, true, err
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/v1/reviews/"):
		id := strings.TrimPrefix(path, "/v1/reviews/")
		if i := strings.Index(id, "/"); i >= 0 {
			return 0, nil, false, nil
		}
		row, err := one(tx, `SELECT * FROM reviews WHERE id = ?`, id)
		if err != nil {
			return 0, nil, true, err
		}
		if row == nil || (str(row, "user_id") != str(user, "id") && requireStaff(user) != nil) {
			return 0, nil, true, fail(404, "not_found", "That review is not available.")
		}
		return 200, reviewJSON(row), true, nil
	case r.Method == http.MethodPatch && strings.Contains(path, "/findings/"):
		out, err := overrideFinding(tx, user, path, body)
		return 200, out, true, err
	case r.Method == http.MethodPost && strings.HasPrefix(path, "/v1/tasks/") && strings.Contains(path, "/mentor/"):
		taskID := strings.TrimPrefix(path, "/v1/tasks/")
		taskID = taskID[:strings.Index(taskID, "/")]
		kind := path[strings.LastIndex(path, "/")+1:]
		out, err := taskMentor(tx, user, taskID, kind)
		return 200, out, true, err
	case r.Method == http.MethodPost && strings.HasPrefix(path, "/v1/messages/") && strings.HasSuffix(path, "/feedback"):
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/v1/messages/"), "/feedback")
		out, err := saveFeedback(tx, user, id, body)
		return 200, out, true, err
	}
	return 0, nil, false, nil
}

func createKBDocument(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	title := strings.TrimSpace(asString(body["title"]))
	text := strings.TrimSpace(asString(body["body"]))
	kind := strings.TrimSpace(asString(body["type"]))
	if kind == "" {
		kind = "standard"
	}
	visibility := strings.TrimSpace(asString(body["visibility"]))
	if visibility == "" {
		visibility = "student"
	}
	if title == "" || text == "" || (visibility != "student" && visibility != "staff_only") {
		return nil, fail(400, "invalid_body", "A document needs a title, a body, and visibility student or staff_only.")
	}
	tags, _ := json.Marshal(body["tags"])
	if body["tags"] == nil {
		tags = []byte("[]")
	}
	id := newID()
	now := nowISO()
	if _, err := tx.Exec(`INSERT INTO kb_documents (id, title, type, body, tags, language, project_id, visibility, version, source_url, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, '', ?)`,
		id, title, kind, text, string(tags), asString(body["language"]), asString(body["project_id"]), visibility, now); err != nil {
		return nil, err
	}
	return one(tx, `SELECT * FROM kb_documents WHERE id = ?`, id)
}

func patchKBDocument(tx *sql.Tx, id string, body map[string]any) (map[string]any, error) {
	row, err := one(tx, `SELECT * FROM kb_documents WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That document is not in the knowledge base.")
	}
	if _, err := tx.Exec(`INSERT INTO kb_document_versions (id, version, title, body, created_at) VALUES (?, ?, ?, ?, ?)`, id, num(row, "version"), str(row, "title"), str(row, "body"), nowISO()); err != nil {
		return nil, err
	}
	title, text := str(row, "title"), str(row, "body")
	if asString(body["title"]) != "" {
		title = asString(body["title"])
	}
	if asString(body["body"]) != "" {
		text = asString(body["body"])
	}
	if _, err := tx.Exec(`UPDATE kb_documents SET title = ?, body = ?, version = version + 1 WHERE id = ?`, title, text, id); err != nil {
		return nil, err
	}
	return one(tx, `SELECT * FROM kb_documents WHERE id = ?`, id)
}

func listKBDocuments(tx *sql.Tx, q url.Values) (map[string]any, error) {
	rows, err := many(tx, `SELECT * FROM kb_documents ORDER BY created_at DESC LIMIT 50`)
	if err != nil {
		return nil, err
	}
	items := []any{}
	for _, row := range rows {
		if kind := q.Get("type"); kind != "" && str(row, "type") != kind {
			continue
		}
		if projectID := q.Get("project_id"); projectID != "" && str(row, "project_id") != projectID {
			continue
		}
		items = append(items, row)
	}
	return map[string]any{"items": items, "next_cursor": nil}, nil
}

func saveRule(tx *sql.Tx, ruleID string, body map[string]any) (map[string]any, error) {
	if ruleID == "" {
		ruleID = strings.TrimSpace(asString(body["rule_id"]))
	}
	title := strings.TrimSpace(asString(body["title"]))
	text := strings.TrimSpace(asString(body["body"]))
	if ruleID == "" || title == "" || text == "" {
		return nil, fail(400, "invalid_body", "A rule needs a rule_id, a title, and a body.")
	}
	severity := asString(body["severity"])
	if severity == "" {
		severity = "warning"
	}
	languages, _ := json.Marshal(body["languages"])
	if body["languages"] == nil {
		languages = []byte("[]")
	}
	examples, _ := body["examples"].(map[string]any)
	if _, err := tx.Exec(`INSERT INTO kb_rules (rule_id, title, body, severity, languages, example_good, example_bad, version, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
		ON CONFLICT(rule_id) DO UPDATE SET title = excluded.title, body = excluded.body, severity = excluded.severity, languages = excluded.languages, example_good = excluded.example_good, example_bad = excluded.example_bad, version = kb_rules.version + 1, updated_at = excluded.updated_at`,
		ruleID, title, text, severity, string(languages), asString(examples["good"]), asString(examples["bad"]), nowISO()); err != nil {
		return nil, err
	}
	return one(tx, `SELECT * FROM kb_rules WHERE rule_id = ?`, ruleID)
}

func taskRow(tx *sql.Tx, user map[string]any, taskID string) (map[string]any, error) {
	row, err := one(tx, `SELECT * FROM tasks WHERE id = ?`, taskID)
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That task is not on the board.")
	}
	if str(row, "user_id") != str(user, "id") && requireStaff(user) != nil {
		return nil, fail(403, "role_required", "That task belongs to another student.")
	}
	return row, nil
}

func createReview(tx *sql.Tx, user map[string]any, taskID string, body map[string]any) (map[string]any, error) {
	task, err := taskRow(tx, user, taskID)
	if err != nil {
		return nil, err
	}
	start := time.Now().UTC().Format("2006-01-02")
	count, err := one(tx, `SELECT COUNT(*) AS n FROM reviews WHERE task_id = ? AND user_id = ? AND created_at >= ?`, taskID, str(user, "id"), start)
	if err != nil {
		return nil, err
	}
	if num(count, "n") >= 20 {
		return nil, &apiError{Status: 429, Code: "quota_exceeded", Message: "This task has used its 20 reviews for today.", Extra: map[string]any{"retry_after_s": 3600}}
	}
	project, err := one(tx, `SELECT * FROM projects WHERE id = ?`, str(task, "project_id"))
	if err != nil {
		return nil, err
	}
	hits := searchKB(tx, user, str(task, "title")+" "+str(project, "description"), str(project, "id"), false)
	criteria, findings, versions := reviewFromKB(str(task, "title"), hits)
	summary := "Checked " + str(task, "title") + " against the BAU standards."
	verdict := "needs_changes"
	model := ""
	if text, used, ok := mentorJSON(reviewPrompt(task, project, hits)); ok {
		var parsed struct {
			Verdict  string           `json:"verdict"`
			Summary  string           `json:"summary"`
			Criteria []map[string]any `json:"criteria"`
			Findings []map[string]any `json:"findings"`
		}
		if json.Unmarshal([]byte(jsonObject(text)), &parsed) == nil && parsed.Summary != "" {
			summary = guardMentor(parsed.Summary)
			if parsed.Verdict != "" {
				verdict = parsed.Verdict
			}
			if len(parsed.Criteria) > 0 {
				raw, _ := json.Marshal(parsed.Criteria)
				_ = json.Unmarshal(raw, &criteria)
			}
			if len(parsed.Findings) > 0 {
				raw, _ := json.Marshal(parsed.Findings)
				_ = json.Unmarshal(raw, &findings)
			}
			model = used
		}
	}
	rawCriteria, _ := json.Marshal(criteria)
	rawFindings, _ := json.Marshal(findings)
	rawVersions, _ := json.Marshal(versions)
	id := newID()
	now := nowISO()
	ref := asString(body["ref"])
	if _, err := tx.Exec(`INSERT INTO reviews (id, task_id, user_id, ref, status, verdict, summary, criteria, findings, kb_versions, model, created_at) VALUES (?, ?, ?, ?, 'done', ?, ?, ?, ?, ?, ?, ?)`,
		id, taskID, str(user, "id"), ref, verdict, summary, string(rawCriteria), string(rawFindings), string(rawVersions), model, now); err != nil {
		return nil, err
	}
	_, _ = tx.Exec(`INSERT INTO mentor_audit (id, user_id, kind, model, kb_versions, created_at) VALUES (?, ?, 'review', ?, ?, ?)`, newID(), str(user, "id"), model, string(rawVersions), now)
	row, err := one(tx, `SELECT * FROM reviews WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	out := reviewJSON(row)
	return map[string]any{"review_id": id, "review": out}, nil
}

func reviewFromKB(title string, hits []map[string]any) ([]any, []any, map[string]any) {
	criteria := []any{map[string]any{"criterion": title, "met": false, "evidence": ""}}
	findings := []any{}
	versions := map[string]any{}
	for _, hit := range hits {
		if str(hit, "type") != "rule" {
			continue
		}
		ruleID := strings.TrimPrefix(str(hit, "id"), "rule:")
		versions[ruleID] = num(hit, "version")
		if versions[ruleID] == 0 {
			versions[ruleID] = 1
		}
		findings = append(findings, map[string]any{
			"severity": str(hit, "severity"), "file": "", "line": 0, "rule_id": ruleID,
			"title": str(hit, "title"), "explanation": str(hit, "body"), "hint": "Apply this rule in the task, then ask for review again.",
		})
		if len(findings) == 3 {
			break
		}
	}
	return criteria, findings, versions
}

func reviewPrompt(task, project map[string]any, hits []map[string]any) string {
	var b strings.Builder
	b.WriteString("Write a code review as JSON with keys verdict (looks_good, needs_changes, or blocking), summary, criteria (list of criterion, met, evidence), findings (list of severity, file, line, rule_id, title, explanation, hint). ")
	b.WriteString("Do not write the solution. Cite only these rules. Task: ")
	b.WriteString(str(task, "title"))
	b.WriteString(". Project: ")
	b.WriteString(str(project, "title"))
	for _, hit := range hits {
		if str(hit, "type") != "rule" {
			continue
		}
		b.WriteString("\n- ")
		b.WriteString(strings.TrimPrefix(str(hit, "id"), "rule:"))
		b.WriteString(": ")
		b.WriteString(str(hit, "title"))
	}
	return b.String()
}

func taskMentor(tx *sql.Tx, user map[string]any, taskID, kind string) (map[string]any, error) {
	task, err := taskRow(tx, user, taskID)
	if err != nil {
		return nil, err
	}
	project, err := one(tx, `SELECT * FROM projects WHERE id = ?`, str(task, "project_id"))
	if err != nil {
		return nil, err
	}
	hits := searchKB(tx, user, str(task, "title"), str(project, "id"), false)
	switch kind {
	case "kickoff":
		guide := "Start in the files that match “" + str(task, "title") + "”. Change the check, then add a test. Standards: "
		for _, hit := range hits {
			if str(hit, "type") == "rule" {
				guide += str(hit, "title") + ". "
			}
		}
		if text, _, ok := mentorJSON("Give a kickoff guide for this task in under 80 words. Do not write the solution. Task: " + str(task, "title")); ok {
			guide = guardMentor(text)
		}
		return map[string]any{"task_id": taskID, "guide": guide}, nil
	case "readiness":
		criteria, _, _ := reviewFromKB(str(task, "title"), hits)
		return map[string]any{"criteria": criteria, "ready": false}, nil
	case "tests":
		items := []any{}
		for _, hit := range hits {
			if str(hit, "type") == "rule" {
				items = append(items, map[string]any{"case": str(hit, "title"), "expect": str(hit, "body")})
			}
		}
		if len(items) == 0 {
			items = append(items, map[string]any{"case": "The happy path", "expect": "The form accepts a valid future date."})
		}
		return map[string]any{"cases": items}, nil
	default:
		return nil, fail(404, "not_found", "That mentor action is not available.")
	}
}

func saveFeedback(tx *sql.Tx, user map[string]any, messageID string, body map[string]any) (map[string]any, error) {
	row, err := one(tx, `SELECT id FROM mentor_messages WHERE id = ? AND user_id = ?`, messageID, str(user, "id"))
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That mentor message is not available.")
	}
	helpful := 0
	if body["helpful"] == true {
		helpful = 1
	}
	if _, err := tx.Exec(`INSERT INTO mentor_feedback (id, message_id, user_id, helpful, note, created_at) VALUES (?, ?, ?, ?, ?, ?)`, newID(), messageID, str(user, "id"), helpful, asString(body["note"]), nowISO()); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

func overrideFinding(tx *sql.Tx, user map[string]any, path string, body map[string]any) (map[string]any, error) {
	if err := requireStaff(user); err != nil {
		return nil, err
	}
	rest := strings.TrimPrefix(path, "/v1/reviews/")
	parts := strings.Split(rest, "/findings/")
	if len(parts) != 2 {
		return nil, fail(404, "not_found", "That finding is not on the review.")
	}
	row, err := one(tx, `SELECT * FROM reviews WHERE id = ?`, parts[0])
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That review is not available.")
	}
	var findings []map[string]any
	_ = json.Unmarshal([]byte(str(row, "findings")), &findings)
	index := 0
	for _, c := range parts[1] {
		if c < '0' || c > '9' {
			return nil, fail(400, "invalid_body", "The finding number is not valid.")
		}
		index = index*10 + int(c-'0')
	}
	if index < 0 || index >= len(findings) {
		return nil, fail(404, "not_found", "That finding is not on the review.")
	}
	findings[index]["status"] = asString(body["status"])
	findings[index]["note"] = asString(body["note"])
	raw, _ := json.Marshal(findings)
	if _, err := tx.Exec(`UPDATE reviews SET findings = ? WHERE id = ?`, string(raw), parts[0]); err != nil {
		return nil, err
	}
	updated, err := one(tx, `SELECT * FROM reviews WHERE id = ?`, parts[0])
	if err != nil {
		return nil, err
	}
	return reviewJSON(updated), nil
}

func jsonObject(text string) string {
	start := strings.Index(text, "{")
	end := strings.LastIndex(text, "}")
	if start >= 0 && end > start {
		return text[start : end+1]
	}
	return text
}

func seedGitHub(db *sql.DB) {
	token := strings.TrimSpace(os.Getenv("GITHUB_SEED_TOKEN"))
	if token == "" {
		return
	}
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM kb_documents WHERE type = 'code'`).Scan(&n)
	if n >= 40 {
		return
	}
	client := &http.Client{Timeout: 20 * time.Second}
	repos := githubJSON(client, token, "https://api.github.com/user/repos?per_page=100&affiliation=owner&sort=updated")
	list, _ := repos.([]any)
	stored := n
	for _, item := range list {
		if stored >= 60 {
			break
		}
		repo, _ := item.(map[string]any)
		name, _ := repo["full_name"].(string)
		size, _ := repo["size"].(float64)
		if name == "" || size > 8000 {
			continue
		}
		branch, _ := repo["default_branch"].(string)
		if branch == "" {
			branch = "main"
		}
		tree := githubJSON(client, token, "https://api.github.com/repos/"+name+"/git/trees/"+url.PathEscape(branch)+"?recursive=1")
		root, _ := tree.(map[string]any)
		entries, _ := root["tree"].([]any)
		taken := 0
		for _, entry := range entries {
			if taken == 3 || stored >= 60 {
				break
			}
			file, _ := entry.(map[string]any)
			path, _ := file["path"].(string)
			if file["type"] != "blob" || !sourceFile(path) {
				continue
			}
			if fileSize, _ := file["size"].(float64); fileSize < 80 || fileSize > 12000 {
				continue
			}
			id := "gh-" + name + "-" + strings.ReplaceAll(path, "/", "-")
			if len(id) > 180 {
				id = id[:180]
			}
			body := githubFile(client, token, name, escapeGitHubPath(path), branch)
			if strings.TrimSpace(body) == "" {
				continue
			}
			now := time.Now().UTC().Format("2006-01-02T15:04:05Z")
			lang := path[strings.LastIndex(path, ".")+1:]
			title := name + " " + path
			res, err := db.Exec(`INSERT OR IGNORE INTO kb_documents (id, title, type, body, tags, language, project_id, visibility, version, source_url, created_at) VALUES (?, ?, 'code', ?, ?, ?, '', 'staff_only', 1, ?, ?)`,
				id, title, clip(body, 6000), `["github","`+lang+`"]`, lang, "https://github.com/"+name+"/blob/"+branch+"/"+path, now)
			if err != nil {
				continue
			}
			if added, _ := res.RowsAffected(); added > 0 {
				taken++
				stored++
			}
		}
	}
	_ = reindexKB(db)
}

func sourceFile(path string) bool {
	lower := strings.ToLower(path)
	for _, skip := range []string{"vendor/", "node_modules/", "dist/", "build/", ".gradle/", "pods/", "generated/", ".min.js", "wire_gen"} {
		if strings.Contains(lower, skip) {
			return false
		}
	}
	for _, ext := range []string{".go", ".kt", ".kts", ".swift", ".ts", ".tsx", ".js", ".jsx", ".py", ".java"} {
		if strings.HasSuffix(lower, ext) {
			return true
		}
	}
	return false
}

func escapeGitHubPath(path string) string {
	parts := strings.Split(path, "/")
	for i, part := range parts {
		parts[i] = url.PathEscape(part)
	}
	return strings.Join(parts, "/")
}

func githubJSON(client *http.Client, token, rawURL string) any {
	req, err := http.NewRequest(http.MethodGet, rawURL, nil)
	if err != nil {
		return nil
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "baubuild")
	res, err := client.Do(req)
	if err != nil {
		return nil
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 2<<20))
	if res.StatusCode >= 400 {
		return nil
	}
	var parsed any
	if json.Unmarshal(raw, &parsed) != nil {
		return nil
	}
	return parsed
}

func githubFile(client *http.Client, token, repo, path, branch string) string {
	rawURL := "https://api.github.com/repos/" + repo + "/contents/" + path + "?ref=" + url.QueryEscape(branch)
	parsed, _ := githubJSON(client, token, rawURL).(map[string]any)
	content, _ := parsed["content"].(string)
	if content == "" {
		return ""
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.ReplaceAll(content, "\n", ""))
	if err != nil {
		return ""
	}
	return string(decoded)
}
