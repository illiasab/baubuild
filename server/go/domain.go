package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"
)

type apiError struct {
	Status  int
	Code    string
	Message string
	Extra   map[string]any
}

func (e *apiError) Error() string { return e.Message }

func fail(status int, code, message string) *apiError {
	return &apiError{Status: status, Code: code, Message: message}
}

const (
	accessTTL  = time.Hour
	refreshTTL = 30 * 24 * time.Hour
	scoreMax   = 500
	pageSize   = 20
	passMark   = 3
)

var tiers = []struct {
	Name string
	Min  int
}{
	{"rookie", 0},
	{"builder", 100},
	{"skilled", 200},
	{"expert", 300},
	{"honored", 400},
}

type examQ struct {
	ID, Prompt string
	Choices    []string
	Answer     int
}

var exam = []examQ{
	{"dates", "A booking form accepts past dates because it compares date strings. What should the check compare instead?", []string{"The length of the date string", "Date objects or timestamps", "The weekday name", "The user's score"}, 1},
	{"binary", "Binary search only works when the collection is…", []string{"Stored in a linked list", "Sorted by the search key", "Shorter than ten items", "Hosted on GitHub"}, 1},
	{"review", "After the AI review finishes, who decides whether the work merges?", []string{"The student who pushed", "A vetted dev lead, or the professor on a class project", "The bounty amount", "Whoever joined first"}, 1},
	{"score", "A project requires a score of 300 and yours is 180. What should the portal do if you request it?", []string{"Join you immediately", "Refuse the request and leave your memberships unchanged", "Lower the project's minimum score", "Mark the project approved"}, 1},
}

func nowISO() string { return time.Now().UTC().Format("2006-01-02T15:04:05Z") }

func parseISO(value string) (time.Time, error) {
	return time.Parse("2006-01-02T15:04:05Z", value)
}

func newID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func tokenURL() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func str(row map[string]any, key string) string {
	if row == nil || row[key] == nil {
		return ""
	}
	switch v := row[key].(type) {
	case string:
		return v
	case []byte:
		return string(v)
	default:
		return fmt.Sprint(v)
	}
}

func num(row map[string]any, key string) int {
	switch v := row[key].(type) {
	case int64:
		return int(v)
	case int:
		return v
	case float64:
		return int(v)
	default:
		return 0
	}
}

func one(tx *sql.Tx, query string, args ...any) (map[string]any, error) {
	rows, err := tx.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	if !rows.Next() {
		return nil, rows.Err()
	}
	return scan(rows)
}

func many(tx *sql.Tx, query string, args ...any) ([]map[string]any, error) {
	rows, err := tx.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []map[string]any
	for rows.Next() {
		row, err := scan(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, row)
	}
	return out, rows.Err()
}

func scan(rows *sql.Rows) (map[string]any, error) {
	cols, err := rows.Columns()
	if err != nil {
		return nil, err
	}
	vals := make([]any, len(cols))
	ptrs := make([]any, len(cols))
	for i := range vals {
		ptrs[i] = &vals[i]
	}
	if err := rows.Scan(ptrs...); err != nil {
		return nil, err
	}
	row := map[string]any{}
	for i, col := range cols {
		if b, ok := vals[i].([]byte); ok {
			row[col] = string(b)
			continue
		}
		row[col] = vals[i]
	}
	return row, nil
}

func classifyEmail(email string) (string, error) {
	email = strings.TrimSpace(strings.ToLower(email))
	at := strings.LastIndex(email, "@")
	domain := ""
	if at > 0 && at < len(email)-1 {
		domain = email[at+1:]
	}
	var role string
	switch domain {
	case "stu.bau.edu":
		role = "student"
	case "bau.edu":
		role = "staff"
	default:
		return "", fail(403, "domain_not_allowed", "Sign-in is limited to @stu.bau.edu and @bau.edu for now.")
	}
	for _, admin := range strings.Split(os.Getenv("BAU_ADMIN_EMAILS"), ",") {
		if strings.TrimSpace(strings.ToLower(admin)) == email {
			return "admin", nil
		}
	}
	return role, nil
}

func initialsOf(name string) string {
	parts := strings.Fields(name)
	if len(parts) == 0 {
		return "?"
	}
	if len(parts) == 1 {
		text := parts[0]
		if len(text) > 2 {
			text = text[:2]
		}
		return strings.ToUpper(text)
	}
	return strings.ToUpper(parts[0][:1] + parts[len(parts)-1][:1])
}

func statusName(score int) string {
	name := "rookie"
	for _, tier := range tiers {
		if score >= tier.Min {
			name = tier.Name
		}
	}
	return name
}

func minLabel(score int) string {
	if score <= 0 {
		return "Any score"
	}
	return fmt.Sprintf("Score %d+", score)
}

func publicUser(tx *sql.Tx, user map[string]any) (map[string]any, error) {
	return publicUserEmail(tx, user, true)
}

func publicUserEmail(tx *sql.Tx, user map[string]any, includeEmail bool) (map[string]any, error) {
	rows, err := many(tx, `SELECT c.code FROM enrollments e JOIN classes c ON c.id = e.class_id WHERE e.user_id = ? ORDER BY c.code`, str(user, "id"))
	if err != nil {
		return nil, err
	}
	classes := []any{}
	for _, row := range rows {
		classes = append(classes, str(row, "code"))
	}
	skills := []any{}
	var decoded []string
	_ = json.Unmarshal([]byte(str(user, "skills")), &decoded)
	for _, skill := range decoded {
		skills = append(skills, skill)
	}
	var dept any
	if user["department"] != nil {
		dept = str(user, "department")
	}
	out := map[string]any{
		"id": user["id"], "name": user["name"], "initials": user["initials"],
		"avatar": nil, "bio": user["bio"], "role": user["role"], "department": dept, "skills": skills,
		"classes": classes,
	}
	if includeEmail {
		out["email"] = user["email"]
	}
	return out, nil
}

func statsOf(user map[string]any) map[string]any {
	list := []map[string]any{}
	for _, tier := range tiers {
		list = append(list, map[string]any{"name": tier.Name, "min": tier.Min})
	}
	return map[string]any{
		"score": num(user, "score"), "max": scoreMax, "status": statusName(num(user, "score")),
		"tiers": list, "earnings_total": num(user, "earnings"), "approved_project_count": num(user, "approved_projects"),
	}
}

func entitlement(user map[string]any) map[string]any {
	tier := str(user, "tier")
	return map[string]any{"tier": tier, "expires_at": user["tier_expires_at"]}
}

func issueSession(tx *sql.Tx, user map[string]any, platform, version string) (map[string]any, error) {
	if platform == "" {
		platform = "web"
	}
	if version == "" {
		version = "0"
	}
	access, refresh := tokenURL(), tokenURL()
	moment := time.Now().UTC()
	id := newID()
	if _, err := tx.Exec(`INSERT INTO sessions (
		id, user_id, access_hash, refresh_hash, previous_refresh_hash, access_expires, refresh_expires, platform, app_version, created_at, revoked_at
	) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, NULL)`,
		id, str(user, "id"), hashToken(access), hashToken(refresh),
		moment.Add(accessTTL).Format("2006-01-02T15:04:05Z"),
		moment.Add(refreshTTL).Format("2006-01-02T15:04:05Z"),
		platform, version, moment.Format("2006-01-02T15:04:05Z")); err != nil {
		return nil, err
	}
	pub, err := publicUser(tx, user)
	if err != nil {
		return nil, err
	}
	return map[string]any{
		"access_token": access, "refresh_token": refresh,
		"expires_in": int(accessTTL.Seconds()), "user": pub, "entitlement": entitlement(user),
	}, nil
}

func upsertFromEmail(tx *sql.Tx, email, name string) (map[string]any, error) {
	email = strings.TrimSpace(strings.ToLower(email))
	role, err := classifyEmail(email)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(name) == "" {
		name = strings.Split(email, "@")[0]
	}
	row, err := one(tx, `SELECT * FROM users WHERE email = ?`, email)
	if err != nil {
		return nil, err
	}
	if row == nil {
		id := newID()
		if _, err := tx.Exec(`INSERT INTO users (id, email, name, initials, bio, role, skills, created_at) VALUES (?, ?, ?, ?, '', ?, '[]', ?)`,
			id, email, name, initialsOf(name), role, nowISO()); err != nil {
			return nil, err
		}
		return one(tx, `SELECT * FROM users WHERE id = ?`, id)
	}
	if str(row, "role") != "lead" && role == "admin" && str(row, "role") != "admin" {
		if _, err := tx.Exec(`UPDATE users SET role = 'admin' WHERE id = ?`, str(row, "id")); err != nil {
			return nil, err
		}
		return one(tx, `SELECT * FROM users WHERE id = ?`, str(row, "id"))
	}
	return row, nil
}

func userFromAccess(tx *sql.Tx, header string) (map[string]any, map[string]any, error) {
	if !strings.HasPrefix(header, "Bearer ") {
		return nil, nil, fail(401, "unauthorized", "Sign in again.")
	}
	token := strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
	session, err := one(tx, `SELECT * FROM sessions WHERE access_hash = ?`, hashToken(token))
	if err != nil {
		return nil, nil, err
	}
	if session == nil || session["revoked_at"] != nil {
		return nil, nil, fail(401, "unauthorized", "Sign in again.")
	}
	expires, err := parseISO(str(session, "access_expires"))
	if err != nil || !expires.After(time.Now().UTC()) {
		return nil, nil, fail(401, "unauthorized", "Sign in again.")
	}
	user, err := one(tx, `SELECT * FROM users WHERE id = ?`, str(session, "user_id"))
	if err != nil || user == nil {
		return nil, nil, fail(401, "unauthorized", "Sign in again.")
	}
	return user, session, nil
}

func refresh(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	token, _ := body["refresh_token"].(string)
	token = strings.TrimSpace(token)
	if token == "" {
		return nil, fail(400, "invalid_body", "refresh_token is required.")
	}
	sum := hashToken(token)
	session, err := one(tx, `SELECT * FROM sessions WHERE refresh_hash = ? OR previous_refresh_hash = ?`, sum, sum)
	if err != nil {
		return nil, err
	}
	if session == nil {
		return nil, fail(401, "unauthorized", "Sign in again.")
	}
	if session["revoked_at"] != nil || str(session, "previous_refresh_hash") == sum {
		if _, err := tx.Exec(`UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`, nowISO(), str(session, "id")); err != nil {
			return nil, err
		}
		return nil, fail(401, "refresh_reused", "That refresh token was already used. Sign in again.")
	}
	expires, err := parseISO(str(session, "refresh_expires"))
	if err != nil || !expires.After(time.Now().UTC()) {
		return nil, fail(401, "unauthorized", "Sign in again.")
	}
	user, err := one(tx, `SELECT * FROM users WHERE id = ?`, str(session, "user_id"))
	if err != nil || user == nil {
		return nil, fail(401, "unauthorized", "Sign in again.")
	}
	if _, err := classifyEmail(str(user, "email")); err != nil {
		return nil, err
	}
	access, next := tokenURL(), tokenURL()
	moment := time.Now().UTC()
	if _, err := tx.Exec(`UPDATE sessions SET previous_refresh_hash = refresh_hash, access_hash = ?, refresh_hash = ?, access_expires = ?, refresh_expires = ? WHERE id = ?`,
		hashToken(access), hashToken(next), moment.Add(accessTTL).Format("2006-01-02T15:04:05Z"), moment.Add(refreshTTL).Format("2006-01-02T15:04:05Z"), str(session, "id")); err != nil {
		return nil, err
	}
	pub, err := publicUser(tx, user)
	if err != nil {
		return nil, err
	}
	return map[string]any{
		"access_token": access, "refresh_token": next,
		"expires_in": int(accessTTL.Seconds()), "user": pub, "entitlement": entitlement(user),
	}, nil
}

func configDoc() map[string]any {
	min := map[string]any{}
	for _, key := range []string{"ios", "macos", "android", "windows", "linux"} {
		value := os.Getenv("MIN_" + strings.ToUpper(key))
		if value == "" {
			value = "1.0.0"
		}
		min[key] = value
	}
	var maint any
	if text := strings.TrimSpace(os.Getenv("BAU_MAINTENANCE")); text != "" {
		maint = map[string]any{"message": text}
	}
	return map[string]any{
		"min_supported": min,
		"features": map[string]any{
			"billing": false, "mentor": true, "mentor_chat": true, "mentor_reviews": true, "mentor_kickoff": true,
			"github": os.Getenv("GITHUB_CLIENT_ID") != "", "push": true,
		},
		"maintenance": maint,
	}
}

func requireProject(tx *sql.Tx, id string) (map[string]any, error) {
	row, err := one(tx, `SELECT * FROM projects WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That project is not on the board.")
	}
	return row, nil
}

func enrolled(tx *sql.Tx, userID, classID string) (bool, error) {
	if classID == "" {
		return true, nil
	}
	row, err := one(tx, `SELECT 1 AS ok FROM enrollments WHERE user_id = ? AND class_id = ?`, userID, classID)
	return row != nil, err
}

func eligibility(tx *sql.Tx, user, project map[string]any) (bool, string, error) {
	if str(user, "tier") == "payment_required" {
		return false, "payment_required", nil
	}
	if project["class_id"] != nil && num(project, "open_to_all") == 0 {
		ok, err := enrolled(tx, str(user, "id"), str(project, "class_id"))
		if err != nil || !ok {
			return false, "class_not_enrolled", err
		}
	}
	if num(user, "score") < num(project, "min_score") {
		return false, "score_too_low", nil
	}
	return true, "", nil
}

func membership(tx *sql.Tx, userID, projectID string) (map[string]any, error) {
	return one(tx, `SELECT * FROM memberships WHERE user_id = ? AND project_id = ?`, userID, projectID)
}

func bookmarked(tx *sql.Tx, userID, projectID string) (bool, error) {
	row, err := one(tx, `SELECT 1 AS ok FROM bookmarks WHERE user_id = ? AND project_id = ?`, userID, projectID)
	return row != nil, err
}

func projectCard(row map[string]any) map[string]any {
	return map[string]any{
		"id": row["id"], "department": row["department"], "title": row["title"], "bounty": num(row, "bounty"),
		"difficulty": row["difficulty"], "min_score": num(row, "min_score"), "min_score_label": minLabel(num(row, "min_score")),
		"due_at": row["due_at"], "urgent": num(row, "urgent") == 1, "art_key": row["art_key"], "status": row["status"],
	}
}

func projectDetail(tx *sql.Tx, user, row map[string]any) (map[string]any, error) {
	ok, reason, err := eligibility(tx, user, row)
	if err != nil {
		return nil, err
	}
	mem, err := membership(tx, str(user, "id"), str(row, "id"))
	if err != nil {
		return nil, err
	}
	var mine any
	if mem != nil {
		status := "pending_approval"
		if str(mem, "status") == "member" {
			status = "joined"
		}
		mine = map[string]any{"status": status}
	}
	marked, err := bookmarked(tx, str(user, "id"), str(row, "id"))
	if err != nil {
		return nil, err
	}
	var why any
	if reason != "" {
		why = reason
	}
	card := projectCard(row)
	card["description"] = row["description"]
	card["class_id"] = row["class_id"]
	card["repo"] = map[string]any{"provider": row["repo_provider"], "name": row["repo_name"], "connected": user["github_token"] != nil && str(user, "github_token") != ""}
	card["joining"] = row["joining"]
	card["reviewed_by"] = row["reviewed_by"]
	card["reviewer_label"] = row["reviewer_label"]
	card["is_bookmarked"] = marked
	card["my_membership"] = mine
	card["eligibility"] = map[string]any{"eligible": ok, "reason": why}
	return card, nil
}

func joinProject(tx *sql.Tx, user map[string]any, projectID string) (map[string]any, error) {
	project, err := requireProject(tx, projectID)
	if err != nil {
		return nil, err
	}
	if str(project, "status") != "open" {
		return nil, fail(409, "project_closed", "This project is not open.")
	}
	existing, err := membership(tx, str(user, "id"), projectID)
	if err != nil {
		return nil, err
	}
	if existing != nil && str(existing, "status") == "member" {
		return map[string]any{"result": "joined"}, nil
	}
	if existing != nil && str(existing, "status") == "pending" {
		return map[string]any{"result": "pending_approval"}, nil
	}
	ok, reason, err := eligibility(tx, user, project)
	if err != nil {
		return nil, err
	}
	if !ok {
		messages := map[string]string{
			"payment_required":  "A paid plan is required before joining.",
			"score_too_low":     fmt.Sprintf("This project needs a score of %d. Yours is %d.", num(project, "min_score"), num(user, "score")),
			"class_not_enrolled": "This project is for students enrolled in its class.",
		}
		return nil, fail(403, reason, messages[reason])
	}
	status := "pending"
	if str(project, "joining") == "everyone" {
		status = "member"
	}
	if _, err := tx.Exec(`INSERT INTO memberships (user_id, project_id, status, created_at) VALUES (?, ?, ?, ?)`, str(user, "id"), projectID, status, nowISO()); err != nil {
		return nil, err
	}
	if status == "member" {
		var tasks []map[string]string
		_ = json.Unmarshal([]byte(str(project, "template_tasks")), &tasks)
		for _, task := range tasks {
			if _, err := tx.Exec(`INSERT INTO tasks (id, project_id, user_id, title, status, lane, due_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
				newID(), projectID, str(user, "id"), task["title"], task["status"], task["lane"], project["due_at"]); err != nil {
				return nil, err
			}
		}
		notify(tx, str(user, "id"), fmt.Sprintf("You’re in “%s”.", str(project, "title")))
		return map[string]any{"result": "joined"}, nil
	}
	notify(tx, str(user, "id"), fmt.Sprintf("Request sent for “%s”. Waiting on %s.", str(project, "title"), str(project, "reviewer_label")))
	return map[string]any{"result": "pending_approval"}, nil
}

func notify(tx *sql.Tx, userID, text string) {
	id := newID()
	moment := nowISO()
	_, _ = tx.Exec(`INSERT INTO notifications (id, user_id, text, read_at, created_at) VALUES (?, ?, ?, NULL, ?)`, id, userID, text, moment)
	payload, _ := json.Marshal(map[string]any{"id": id, "text": text})
	_, _ = tx.Exec(`INSERT INTO events (user_id, kind, payload, created_at) VALUES (?, 'notification', ?, ?)`, userID, string(payload), moment)
}

func home(tx *sql.Tx, user map[string]any) (map[string]any, error) {
	urgent, err := many(tx, `SELECT * FROM projects WHERE status = 'open' AND urgent = 1 ORDER BY due_at`)
	if err != nil {
		return nil, err
	}
	counts := map[string]any{"easy": 0, "medium": 0, "hard": 0}
	rows, err := many(tx, `SELECT difficulty, COUNT(*) AS n FROM projects WHERE status = 'open' GROUP BY difficulty`)
	if err != nil {
		return nil, err
	}
	for _, row := range rows {
		counts[str(row, "difficulty")] = num(row, "n")
	}
	preview, err := many(tx, `SELECT * FROM projects WHERE status = 'open' ORDER BY urgent DESC, title LIMIT 6`)
	if err != nil {
		return nil, err
	}
	first := str(user, "email")
	if name := str(user, "name"); name != "" {
		first = strings.Fields(name)[0]
	}
	urgentCards := []map[string]any{}
	for _, row := range urgent {
		urgentCards = append(urgentCards, urgentCard(row))
	}
	open := []map[string]any{}
	for _, row := range preview {
		open = append(open, projectCard(row))
	}
	return map[string]any{"greeting_name": first, "urgent": urgentCards, "difficulty_counts": counts, "open_projects": open}, nil
}

func urgentCard(row map[string]any) map[string]any {
	return map[string]any{
		"id": row["id"], "title": row["title"], "bounty": num(row, "bounty"), "difficulty": row["difficulty"],
		"min_score_label": minLabel(num(row, "min_score")), "department": row["department"], "art_key": row["art_key"],
	}
}

func classPayload(tx *sql.Tx, row map[string]any) map[string]any {
	count := 0
	found, _ := one(tx, `SELECT COUNT(*) AS n FROM projects WHERE class_id = ? AND status = 'open'`, str(row, "id"))
	if found != nil {
		count = num(found, "n")
	}
	return map[string]any{
		"id": row["id"], "code": row["code"], "name": row["name"], "term": row["term"],
		"instructor": row["instructor"], "open_project_count": count,
	}
}

func searchAll(tx *sql.Tx, q string) (map[string]any, error) {
	q = strings.TrimSpace(q)
	if q == "" {
		return nil, fail(400, "invalid_query", "q is required.")
	}
	like := "%" + strings.ToLower(q) + "%"
	projects, err := many(tx, `SELECT * FROM projects WHERE status = 'open' AND (lower(title) LIKE ? OR lower(description) LIKE ? OR lower(department) LIKE ?) ORDER BY title LIMIT 20`, like, like, like)
	if err != nil {
		return nil, err
	}
	classes, err := many(tx, `SELECT * FROM classes WHERE lower(code) LIKE ? OR lower(name) LIKE ? OR lower(instructor) LIKE ? ORDER BY code LIMIT 20`, like, like, like)
	if err != nil {
		return nil, err
	}
	inquiries, err := many(tx, `SELECT * FROM inquiries WHERE status = 'published' AND (lower(title) LIKE ? OR lower(description) LIKE ? OR lower(department) LIKE ?) ORDER BY created_at DESC LIMIT 20`, like, like, like)
	if err != nil {
		return nil, err
	}
	pcards := []map[string]any{}
	for _, row := range projects {
		pcards = append(pcards, projectCard(row))
	}
	ccards := []map[string]any{}
	for _, row := range classes {
		ccards = append(ccards, classPayload(tx, row))
	}
	icards := []map[string]any{}
	for _, row := range inquiries {
		icards = append(icards, inquiryPayload(row))
	}
	return map[string]any{"projects": pcards, "classes": ccards, "inquiries": icards}, nil
}

func inquiryPayload(row map[string]any) map[string]any {
	return map[string]any{
		"id": row["id"], "title": row["title"], "description": row["description"], "department": row["department"],
		"difficulty": row["difficulty"], "bounty": num(row, "bounty"), "is_urgent": num(row, "is_urgent") == 1,
		"due_at": row["due_at"], "repo_url": row["repo_url"], "min_score": num(row, "min_score"),
		"joining": row["joining"], "reviewer_type": row["reviewer_type"], "status": row["status"],
		"project_id": row["project_id"], "created_at": row["created_at"],
	}
}

func staffOnly(user map[string]any) error {
	if role := str(user, "role"); role != "staff" && role != "admin" {
		return fail(403, "role_required", "Only @bau.edu staff can post inquiries.")
	}
	return nil
}

func adminOnly(user map[string]any) error {
	if str(user, "role") != "admin" {
		return fail(403, "role_required", "An admin has to do that.")
	}
	return nil
}

func whole(value any) (int, bool) {
	switch v := value.(type) {
	case float64:
		if v != float64(int(v)) {
			return 0, false
		}
		return int(v), true
	case int:
		return v, true
	case json.Number:
		n, err := v.Int64()
		return int(n), err == nil
	default:
		return 0, false
	}
}

func inquiryFields(body map[string]any, partial bool) (map[string]any, error) {
	required := []string{"title", "description", "department", "difficulty", "bounty", "is_urgent", "min_score", "joining", "reviewer_type"}
	if !partial {
		var missing []string
		for _, key := range required {
			if _, ok := body[key]; !ok {
				missing = append(missing, key)
			}
		}
		if len(missing) > 0 {
			return nil, fail(400, "invalid_body", "Missing "+strings.Join(missing, ", ")+".")
		}
	}
	data := map[string]any{}
	need := func(key string) bool { _, ok := body[key]; return ok || !partial }
	if need("title") {
		title := strings.TrimSpace(fmt.Sprint(body["title"]))
		if body["title"] == nil {
			title = ""
		}
		if title == "" || len(title) > 140 {
			return nil, fail(400, "invalid_body", "Title is required.")
		}
		data["title"] = title
	}
	if need("description") {
		description := strings.TrimSpace(asString(body["description"]))
		if description == "" {
			return nil, fail(400, "invalid_body", "Description is required.")
		}
		data["description"] = description
	}
	if need("department") {
		department := strings.TrimSpace(asString(body["department"]))
		if department == "" {
			return nil, fail(400, "invalid_body", "Department is required.")
		}
		data["department"] = department
	}
	if need("difficulty") {
		difficulty := strings.ToLower(asString(body["difficulty"]))
		if difficulty != "easy" && difficulty != "medium" && difficulty != "hard" {
			return nil, fail(400, "invalid_body", "difficulty must be easy, medium, or hard.")
		}
		data["difficulty"] = difficulty
	}
	if need("bounty") {
		bounty, ok := whole(body["bounty"])
		if !ok || bounty < 0 || bounty > 500 {
			return nil, fail(400, "invalid_body", "bounty must be a whole number from 0 to 500.")
		}
		data["bounty"] = bounty
	}
	if need("is_urgent") {
		flag, ok := body["is_urgent"].(bool)
		if !ok {
			return nil, fail(400, "invalid_body", "is_urgent must be true or false.")
		}
		urgent := 0
		if flag {
			urgent = 1
		}
		data["is_urgent"] = urgent
	}
	if _, ok := body["due_at"]; ok {
		data["due_at"] = body["due_at"]
	}
	if _, ok := body["repo_url"]; ok {
		text := strings.TrimSpace(asString(body["repo_url"]))
		if text == "" {
			data["repo_url"] = nil
		} else {
			data["repo_url"] = text
		}
	}
	if need("min_score") {
		minScore, ok := whole(body["min_score"])
		if !ok || minScore < 0 || minScore > scoreMax {
			return nil, fail(400, "invalid_body", "min_score must be a whole number from 0 to 500.")
		}
		data["min_score"] = minScore
	}
	if need("joining") {
		joining := asString(body["joining"])
		if joining != "everyone" && joining != "approval" {
			return nil, fail(400, "invalid_body", "joining must be everyone or approval.")
		}
		data["joining"] = joining
	}
	if need("reviewer_type") {
		reviewer := asString(body["reviewer_type"])
		if reviewer != "ai_then_lead" && reviewer != "professor" {
			return nil, fail(400, "invalid_body", "reviewer_type must be ai_then_lead or professor.")
		}
		data["reviewer_type"] = reviewer
	}
	return data, nil
}

func asString(value any) string {
	if value == nil {
		return ""
	}
	text, ok := value.(string)
	if ok {
		return text
	}
	return fmt.Sprint(value)
}

func createInquiry(tx *sql.Tx, user map[string]any, body map[string]any) (map[string]any, error) {
	if err := staffOnly(user); err != nil {
		return nil, err
	}
	fields, err := inquiryFields(body, false)
	if err != nil {
		return nil, err
	}
	id := newID()
	if _, err := tx.Exec(`INSERT INTO inquiries (
		id, author_id, title, description, department, difficulty, bounty, is_urgent, due_at, repo_url,
		min_score, joining, reviewer_type, status, project_id, created_at
	) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'submitted', NULL, ?)`,
		id, str(user, "id"), fields["title"], fields["description"], fields["department"], fields["difficulty"], fields["bounty"], fields["is_urgent"], fields["due_at"], fields["repo_url"], fields["min_score"], fields["joining"], fields["reviewer_type"], nowISO()); err != nil {
		return nil, err
	}
	row, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	return inquiryPayload(row), nil
}

func publishInquiry(tx *sql.Tx, user map[string]any, id string) (map[string]any, error) {
	if err := adminOnly(user); err != nil {
		return nil, err
	}
	row, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That inquiry does not exist.")
	}
	if str(row, "status") != "approved" {
		return nil, fail(409, "invalid_state", "Approve the inquiry before publishing it.")
	}
	projectID := newID()
	reviewer := "dev lead"
	if str(row, "reviewer_type") == "professor" {
		reviewer = "professor of the class"
	}
	if _, err := tx.Exec(`INSERT INTO projects (
		id, department, class_id, title, description, bounty, difficulty, min_score, due_at, urgent, open_to_all,
		joining, reviewed_by, reviewer_label, repo_provider, repo_name, art_key, status, inquiry_id, template_tasks
	) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'github', NULL, 'inquiry', 'open', ?, '[]')`,
		projectID, str(row, "department"), str(row, "title"), str(row, "description"), num(row, "bounty"), str(row, "difficulty"), num(row, "min_score"), row["due_at"], num(row, "is_urgent"), str(row, "joining"), str(row, "reviewer_type"), reviewer, str(row, "id")); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(`UPDATE inquiries SET status = 'published', project_id = ? WHERE id = ?`, projectID, id); err != nil {
		return nil, err
	}
	updated, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	return map[string]any{"inquiry": inquiryPayload(updated), "project_id": projectID}, nil
}

func approveInquiry(tx *sql.Tx, user map[string]any, id string) (map[string]any, error) {
	if err := adminOnly(user); err != nil {
		return nil, err
	}
	row, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That inquiry does not exist.")
	}
	if str(row, "status") != "submitted" {
		return nil, fail(409, "invalid_state", "Only a submitted inquiry can be approved.")
	}
	if _, err := tx.Exec(`UPDATE inquiries SET status = 'approved' WHERE id = ?`, id); err != nil {
		return nil, err
	}
	updated, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	return inquiryPayload(updated), nil
}

func requireMember(tx *sql.Tx, user map[string]any, projectID string) error {
	if _, err := requireProject(tx, projectID); err != nil {
		return err
	}
	mem, err := membership(tx, str(user, "id"), projectID)
	if err != nil {
		return err
	}
	if mem == nil || str(mem, "status") != "member" {
		return fail(403, "not_a_member", "Join the project before using its workspace.")
	}
	return nil
}

func aiFindings(branch string) []map[string]any {
	folded := strings.ToLower(branch)
	findings := []map[string]any{{"kind": "pass", "text": "The branch is attached to this project and ready for a dev lead."}}
	if strings.Contains(folded, "date") || strings.Contains(folded, "fix") || strings.Contains(folded, "valid") {
		findings = append(findings, map[string]any{"kind": "warn", "text": "Worth a look: announce the error to screen readers."})
	} else {
		findings = append(findings, map[string]any{"kind": "warn", "text": "Worth a look: add a test for the failure case."})
	}
	return findings
}

func submissionDetail(tx *sql.Tx, row map[string]any) (map[string]any, error) {
	var reviewer any
	if row["reviewer_id"] != nil {
		person, err := one(tx, `SELECT id, name, initials FROM users WHERE id = ?`, str(row, "reviewer_id"))
		if err != nil {
			return nil, err
		}
		if person != nil {
			reviewer = map[string]any{"id": person["id"], "name": person["name"], "initials": person["initials"]}
		}
	}
	var findings []any
	_ = json.Unmarshal([]byte(str(row, "ai_findings")), &findings)
	var comment any
	if row["comment"] != nil {
		comment = str(row, "comment")
	}
	return map[string]any{
		"id": row["id"], "project_id": row["project_id"], "diff_url": row["diff_url"], "created_at": row["created_at"],
		"steps": []any{
			map[string]any{"step": "pushed", "branch": row["branch"]},
			map[string]any{"step": "ai_review", "status": "complete", "findings": findings, "ai_review": latestReview(tx, str(row, "project_id"), str(row, "user_id"))},
			map[string]any{"step": "lead_decision", "status": row["lead_status"], "reviewer": reviewer, "comment": comment},
			map[string]any{"step": "bounty", "amount": num(row, "bounty_amount"), "paid_at": row["paid_at"]},
		},
	}, nil
}

func createSubmission(tx *sql.Tx, user map[string]any, projectID string, body map[string]any) (map[string]any, error) {
	project, err := requireProject(tx, projectID)
	if err != nil {
		return nil, err
	}
	if err := requireMember(tx, user, projectID); err != nil {
		return nil, err
	}
	branch := strings.TrimSpace(asString(body["branch"]))
	if branch == "" || len(branch) > 120 || strings.Contains(branch, " ") {
		return nil, fail(400, "invalid_body", "branch is required and cannot contain spaces.")
	}
	findings, _ := json.Marshal(aiFindings(branch))
	id := newID()
	repo := str(project, "repo_name")
	if repo == "" {
		repo = projectID
	}
	diff := "https://github.com/" + repo + "/compare/" + url.PathEscape(branch)
	if _, err := tx.Exec(`INSERT INTO submissions (
		id, project_id, user_id, branch, ai_findings, lead_status, reviewer_id, comment, diff_url, bounty_amount, paid_at, created_at
	) VALUES (?, ?, ?, ?, ?, 'pending', NULL, NULL, ?, ?, NULL, ?)`,
		id, projectID, str(user, "id"), branch, string(findings), diff, num(project, "bounty"), nowISO()); err != nil {
		return nil, err
	}
	notify(tx, str(user, "id"), "AI review finished on "+branch+". A dev lead has the summary.")
	row, err := one(tx, `SELECT * FROM submissions WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	return submissionDetail(tx, row)
}

func decideSubmission(tx *sql.Tx, user map[string]any, id string, body map[string]any) (map[string]any, error) {
	if role := str(user, "role"); role != "lead" && role != "admin" {
		return nil, fail(403, "role_required", "Only a lead can decide a review.")
	}
	action := asString(body["action"])
	if action != "approve" && action != "send_back" {
		return nil, fail(400, "invalid_body", "action must be approve or send_back.")
	}
	row, err := one(tx, `SELECT * FROM submissions WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That submission does not exist.")
	}
	if str(row, "lead_status") != "pending" {
		return nil, fail(409, "already_decided", "This submission already has a decision.")
	}
	if str(row, "user_id") == str(user, "id") {
		return nil, fail(403, "role_required", "A lead cannot decide their own submission.")
	}
	comment := strings.TrimSpace(asString(body["comment"]))
	var commentVal any
	if comment != "" {
		commentVal = comment
	}
	status := "approved"
	if action == "send_back" {
		status = "sent_back"
	}
	if _, err := tx.Exec(`UPDATE submissions SET lead_status = ?, reviewer_id = ?, comment = ? WHERE id = ?`, status, str(user, "id"), commentVal, id); err != nil {
		return nil, err
	}
	if action == "send_back" {
		notify(tx, str(row, "user_id"), "A dev lead sent the branch back with notes.")
	} else if err := payIfReady(tx, row); err != nil {
		return nil, err
	}
	updated, err := one(tx, `SELECT * FROM submissions WHERE id = ?`, id)
	if err != nil {
		return nil, err
	}
	return submissionDetail(tx, updated)
}

func payIfReady(tx *sql.Tx, submission map[string]any) error {
	waiting, err := one(tx, `SELECT 1 AS ok FROM submissions WHERE project_id = ? AND user_id = ? AND lead_status != 'approved'`, str(submission, "project_id"), str(submission, "user_id"))
	if err != nil || waiting != nil {
		return err
	}
	already, err := one(tx, `SELECT 1 AS ok FROM submissions WHERE project_id = ? AND user_id = ? AND paid_at IS NOT NULL`, str(submission, "project_id"), str(submission, "user_id"))
	if err != nil || already != nil {
		return err
	}
	project, err := one(tx, `SELECT * FROM projects WHERE id = ?`, str(submission, "project_id"))
	if err != nil || project == nil {
		return err
	}
	paid := nowISO()
	if _, err := tx.Exec(`UPDATE submissions SET paid_at = ?, bounty_amount = ? WHERE project_id = ? AND user_id = ? AND lead_status = 'approved' AND paid_at IS NULL`,
		paid, num(project, "bounty"), str(submission, "project_id"), str(submission, "user_id")); err != nil {
		return err
	}
	if _, err := tx.Exec(`UPDATE users SET earnings = earnings + ?, score = score + ?, approved_projects = approved_projects + 1 WHERE id = ?`,
		num(project, "bounty"), num(project, "bounty"), str(submission, "user_id")); err != nil {
		return err
	}
	notify(tx, str(submission, "user_id"), fmt.Sprintf("“%s” was approved. $%d was added to your record and your score.", str(project, "title"), num(project, "bounty")))
	return nil
}

func leadApplication(tx *sql.Tx, user map[string]any) (map[string]any, error) {
	row, err := one(tx, `SELECT * FROM lead_applications WHERE user_id = ?`, str(user, "id"))
	if err != nil {
		return nil, err
	}
	examDone := row != nil && num(row, "exam_passed") == 1
	inPerson := row != nil && num(row, "in_person") == 1
	isLead := str(user, "role") == "lead" || (examDone && inPerson)
	var current any
	switch {
	case isLead:
		current = "lead_status"
	case examDone:
		current = "in_person"
	case row != nil:
		current = "exam"
	}
	return map[string]any{
		"started": row != nil,
		"current": current,
		"stages": []any{
			map[string]any{"id": "exam", "done": examDone},
			map[string]any{"id": "in_person", "done": inPerson},
			map[string]any{"id": "lead_status", "done": isLead},
		},
	}, nil
}

func startExam(tx *sql.Tx, user map[string]any) (map[string]any, error) {
	if str(user, "role") != "lead" && str(user, "role") != "student" && str(user, "role") != "admin" {
		return nil, fail(403, "role_required", "Students apply to become a lead.")
	}
	if str(user, "role") != "lead" {
		existing, err := one(tx, `SELECT 1 AS ok FROM lead_applications WHERE user_id = ?`, str(user, "id"))
		if err != nil {
			return nil, err
		}
		if existing == nil {
			if _, err := tx.Exec(`INSERT INTO lead_applications (user_id, exam_passed, exam_attempts, in_person, started_at) VALUES (?, 0, 0, 0, ?)`, str(user, "id"), nowISO()); err != nil {
				return nil, err
			}
		}
	}
	row, err := one(tx, `SELECT * FROM lead_applications WHERE user_id = ?`, str(user, "id"))
	if err != nil {
		return nil, err
	}
	if row != nil && num(row, "exam_passed") == 1 {
		return nil, fail(409, "exam_passed", "You already passed the proctored exam.")
	}
	token := tokenURL()
	sessionID := newID()
	expires := time.Now().UTC().Add(2 * time.Hour).Format("2006-01-02T15:04:05Z")
	if _, err := tx.Exec(`INSERT INTO exam_sessions (id, user_id, token_hash, expires_at, submitted_at) VALUES (?, ?, ?, ?, NULL)`, sessionID, str(user, "id"), hashToken(token), expires); err != nil {
		return nil, err
	}
	questions := []map[string]any{}
	for _, question := range exam {
		questions = append(questions, map[string]any{"id": question.ID, "prompt": question.Prompt, "choices": question.Choices})
	}
	return map[string]any{
		"session_id": sessionID, "token": token, "expires_at": expires, "pass_mark": passMark,
		"questions": questions, "submit_path": "/v1/me/lead-application/exam/sessions/" + sessionID,
	}, nil
}

func submitExam(tx *sql.Tx, user map[string]any, sessionID string, body map[string]any) (map[string]any, error) {
	token := strings.TrimSpace(asString(body["token"]))
	row, err := one(tx, `SELECT * FROM exam_sessions WHERE id = ? AND user_id = ?`, sessionID, str(user, "id"))
	if err != nil {
		return nil, err
	}
	if row == nil || hashToken(token) != str(row, "token_hash") {
		return nil, fail(401, "unauthorized", "That exam session is not valid.")
	}
	expires, _ := parseISO(str(row, "expires_at"))
	if row["submitted_at"] != nil || !expires.After(time.Now().UTC()) {
		return nil, fail(409, "exam_closed", "That exam session is closed.")
	}
	raw, ok := body["answers"].([]any)
	if !ok || len(raw) != len(exam) {
		return nil, fail(400, "invalid_body", "Answer every question.")
	}
	correct := 0
	for i, question := range exam {
		choice, ok := whole(raw[i])
		if ok && choice == question.Answer {
			correct++
		}
	}
	passed := 0
	if correct >= passMark {
		passed = 1
	}
	if _, err := tx.Exec(`UPDATE exam_sessions SET submitted_at = ? WHERE id = ?`, nowISO(), sessionID); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(`UPDATE lead_applications SET exam_attempts = exam_attempts + 1, last_exam_score = ?, exam_passed = CASE WHEN ? THEN 1 ELSE exam_passed END WHERE user_id = ?`, correct, passed, str(user, "id")); err != nil {
		return nil, err
	}
	if passed == 1 {
		notify(tx, str(user, "id"), "You passed the proctored exam. The next stage is in person with Ylyas.")
	}
	app, err := leadApplication(tx, user)
	if err != nil {
		return nil, err
	}
	return map[string]any{"correct": correct, "passed": passed == 1, "pass_mark": passMark, "application": app}, nil
}

func confirmInPerson(tx *sql.Tx, admin map[string]any, userID string) (map[string]any, error) {
	if err := adminOnly(admin); err != nil {
		return nil, err
	}
	row, err := one(tx, `SELECT * FROM lead_applications WHERE user_id = ?`, userID)
	if err != nil {
		return nil, err
	}
	if row == nil || num(row, "exam_passed") != 1 {
		return nil, fail(409, "invalid_state", "The exam comes before the in-person stage.")
	}
	if _, err := tx.Exec(`UPDATE lead_applications SET in_person = 1 WHERE user_id = ?`, userID); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(`UPDATE users SET role = 'lead' WHERE id = ? AND role != 'admin'`, userID); err != nil {
		return nil, err
	}
	notify(tx, userID, "Ylyas confirmed the meeting. Your role is now Lead.")
	user, err := one(tx, `SELECT * FROM users WHERE id = ?`, userID)
	if err != nil {
		return nil, err
	}
	app, err := leadApplication(tx, user)
	if err != nil {
		return nil, err
	}
	return map[string]any{"role": user["role"], "application": app}, nil
}

func mentorReply(text string, project map[string]any, history []map[string]any, citations []map[string]any, hintLevel int, context any) (string, string) {
	if reply, model, ok := mentorAI(text, project, history, citations, hintLevel, context); ok {
		return guardMentor(reply), model
	}
	return mentorScript(text), ""
}

func mentorScript(text string) string {
	folded := strings.ToLower(text)
	switch {
	case strings.Contains(folded, "date") || strings.Contains(folded, "string") || strings.Contains(folded, "sort"):
		return "Compare Date objects, or timestamps from those objects. String order only matches calendar order when every part is zero-padded."
	case strings.Contains(folded, "test"):
		return "Add one case for yesterday, one for today, and one for a blank field. Assert the message the form shows, not only true or false."
	case strings.Contains(folded, "screen") || strings.Contains(folded, "reader") || strings.Contains(folded, "aria") || strings.Contains(folded, "announce"):
		return `Put the message in the form and give that node role="alert", or aria-live="assertive", so a screen reader speaks it when it appears.`
	case strings.Contains(folded, "csv") || strings.Contains(folded, "attendance") || strings.Contains(folded, "export"):
		return "Write one row of headers, then one row per person. Keep the club name in its own column so staff can filter it."
	default:
		return "Say what you expected and what the code did instead. You write the change, then push the branch."
	}
}

func postMentor(tx *sql.Tx, user map[string]any, projectID string, body map[string]any) (string, string, []map[string]any, error) {
	if err := requireMember(tx, user, projectID); err != nil {
		return "", "", nil, err
	}
	text := strings.TrimSpace(asString(body["text"]))
	if text == "" || len([]rune(text)) > 4000 {
		return "", "", nil, fail(400, "invalid_body", "Ask a question of 1 to 4000 characters.")
	}
	if err := mentorQuota(tx, str(user, "id")); err != nil {
		return "", "", nil, err
	}
	project, err := one(tx, `SELECT * FROM projects WHERE id = ?`, projectID)
	if err != nil {
		return "", "", nil, err
	}
	taskID := strings.TrimSpace(asString(body["task_id"]))
	history, err := many(tx, `SELECT role, body FROM mentor_messages WHERE project_id = ? AND user_id = ? ORDER BY created_at DESC LIMIT 8`, projectID, str(user, "id"))
	if err != nil {
		return "", "", nil, err
	}
	for i, j := 0, len(history)-1; i < j; i, j = i+1, j-1 {
		history[i], history[j] = history[j], history[i]
	}
	hints, err := one(tx, `SELECT COUNT(*) AS n FROM mentor_messages WHERE project_id = ? AND user_id = ? AND role = 'mentor'`, projectID, str(user, "id"))
	if err != nil {
		return "", "", nil, err
	}
	citations := kbCitations(tx, user, text, project)
	reply, model := mentorReply(text, project, history, citations, num(hints, "n")+1, body["context"])
	rawCitations, _ := json.Marshal(citations)
	moment := nowISO()
	studentID, replyID := newID(), newID()
	if _, err := tx.Exec(`INSERT INTO mentor_messages (id, project_id, user_id, role, body, created_at, task_id, citations, model) VALUES (?, ?, ?, 'student', ?, ?, ?, '[]', '')`, studentID, projectID, str(user, "id"), text, moment, taskID); err != nil {
		return "", "", nil, err
	}
	if _, err := tx.Exec(`INSERT INTO mentor_messages (id, project_id, user_id, role, body, created_at, task_id, citations, model) VALUES (?, ?, ?, 'mentor', ?, ?, ?, ?, ?)`, replyID, projectID, str(user, "id"), reply, moment, taskID, string(rawCitations), model); err != nil {
		return "", "", nil, err
	}
	_, _ = tx.Exec(`INSERT INTO mentor_audit (id, user_id, kind, model, kb_versions, created_at) VALUES (?, ?, 'chat', ?, ?, ?)`, newID(), str(user, "id"), model, string(rawCitations), moment)
	return replyID, reply, citations, nil
}

func updateMe(tx *sql.Tx, user map[string]any, body map[string]any) (map[string]any, error) {
	bio := str(user, "bio")
	var skills []string
	_ = json.Unmarshal([]byte(str(user, "skills")), &skills)
	if _, ok := body["bio"]; ok {
		bio = strings.TrimSpace(asString(body["bio"]))
		if bio == "" || len(bio) > 280 {
			return nil, fail(400, "invalid_body", "Bio must be 1 to 280 characters.")
		}
	}
	if raw, ok := body["skills"]; ok {
		list, ok := raw.([]any)
		if !ok || len(list) == 0 {
			return nil, fail(400, "invalid_body", "skills must be a non-empty list of strings.")
		}
		skills = nil
		for _, item := range list {
			text, ok := item.(string)
			if !ok || strings.TrimSpace(text) == "" {
				return nil, fail(400, "invalid_body", "skills must be a non-empty list of strings.")
			}
			skills = append(skills, strings.TrimSpace(text))
			if len(skills) == 20 {
				break
			}
		}
	}
	encoded, _ := json.Marshal(skills)
	if _, err := tx.Exec(`UPDATE users SET bio = ?, skills = ? WHERE id = ?`, bio, string(encoded), str(user, "id")); err != nil {
		return nil, err
	}
	fresh, err := one(tx, `SELECT * FROM users WHERE id = ?`, str(user, "id"))
	if err != nil {
		return nil, err
	}
	return publicUser(tx, fresh)
}

func registerDevice(tx *sql.Tx, user map[string]any, body map[string]any) (map[string]any, error) {
	platform := asString(body["platform"])
	provider := asString(body["provider"])
	token := strings.TrimSpace(asString(body["push_token"]))
	version := strings.TrimSpace(asString(body["app_version"]))
	platforms := map[string]bool{"ios": true, "macos": true, "android": true, "windows": true, "linux": true}
	providers := map[string]bool{"apns": true, "fcm": true, "wns": true}
	if !platforms[platform] || !providers[provider] || token == "" || version == "" {
		return nil, fail(400, "invalid_body", "platform, push_token, provider, and app_version are required.")
	}
	if provider == "apns" && platform != "ios" && platform != "macos" {
		return nil, fail(400, "invalid_body", "apns is for ios and macos.")
	}
	if provider == "fcm" && platform != "android" {
		return nil, fail(400, "invalid_body", "fcm is for android.")
	}
	if provider == "wns" && platform != "windows" {
		return nil, fail(400, "invalid_body", "wns is for windows. Linux uses the event stream.")
	}
	existing, err := one(tx, `SELECT * FROM devices WHERE user_id = ? AND push_token = ?`, str(user, "id"), token)
	if err != nil {
		return nil, err
	}
	id := newID()
	if existing != nil {
		id = str(existing, "id")
		if _, err := tx.Exec(`UPDATE devices SET platform = ?, provider = ?, app_version = ? WHERE id = ?`, platform, provider, version, id); err != nil {
			return nil, err
		}
	} else if _, err := tx.Exec(`INSERT INTO devices (id, user_id, platform, push_token, provider, app_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		id, str(user, "id"), platform, token, provider, version, nowISO()); err != nil {
		return nil, err
	}
	return map[string]any{"id": id, "platform": platform, "provider": provider}, nil
}

func markRead(tx *sql.Tx, user map[string]any, body map[string]any) (map[string]any, error) {
	moment := nowISO()
	if body["all"] == true {
		if _, err := tx.Exec(`UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL`, moment, str(user, "id")); err != nil {
			return nil, err
		}
	} else if ids, ok := body["ids"].([]any); ok {
		for _, id := range ids {
			if _, err := tx.Exec(`UPDATE notifications SET read_at = ? WHERE id = ? AND user_id = ? AND read_at IS NULL`, moment, asString(id), str(user, "id")); err != nil {
				return nil, err
			}
		}
	} else {
		return nil, fail(400, "invalid_body", `Send {"all": true} or {"ids": [...]}.`)
	}
	row, err := one(tx, `SELECT COUNT(*) AS n FROM notifications WHERE user_id = ? AND read_at IS NULL`, str(user, "id"))
	if err != nil {
		return nil, err
	}
	return map[string]any{"unread": num(row, "n")}, nil
}

func myWork(tx *sql.Tx, user map[string]any) (int, any, error) {
	members, err := many(tx, `SELECT p.*,
		(SELECT COUNT(*) FROM tasks t WHERE t.project_id = p.id AND t.user_id = ? AND t.lane = 'assigned' AND t.status != 'done') AS open_tasks,
		(SELECT COUNT(*) FROM tasks t WHERE t.project_id = p.id AND t.user_id = ?) AS task_count
		FROM memberships m JOIN projects p ON p.id = m.project_id
		WHERE m.user_id = ? AND m.status = 'member' ORDER BY p.title`, str(user, "id"), str(user, "id"), str(user, "id"))
	if err != nil {
		return 0, nil, err
	}
	pending, err := many(tx, `SELECT p.* FROM memberships m JOIN projects p ON p.id = m.project_id WHERE m.user_id = ? AND m.status = 'pending' ORDER BY p.title`, str(user, "id"))
	if err != nil {
		return 0, nil, err
	}
	inProgress := []any{}
	for _, row := range members {
		inProgress = append(inProgress, map[string]any{
			"id": row["id"], "title": row["title"], "department": row["department"], "bounty": num(row, "bounty"),
			"task_count": num(row, "task_count"), "open_task_count": num(row, "open_tasks"),
		})
	}
	waiting := []any{}
	for _, row := range pending {
		waiting = append(waiting, map[string]any{
			"id": row["id"], "title": row["title"], "status": "pending_approval",
			"reviewer_label": row["reviewer_label"], "bounty": num(row, "bounty"),
		})
	}
	return 200, map[string]any{"in_progress": inProgress, "awaiting_approval": waiting}, nil
}

func earnings(tx *sql.Tx, user map[string]any) (int, any, error) {
	rows, err := many(tx, `SELECT s.paid_at, s.bounty_amount, p.title, p.id AS project_id
		FROM submissions s JOIN projects p ON p.id = s.project_id
		WHERE s.user_id = ? AND s.paid_at IS NOT NULL ORDER BY s.paid_at DESC`, str(user, "id"))
	if err != nil {
		return 0, nil, err
	}
	seen := map[string]bool{}
	items := []any{}
	for _, row := range rows {
		id := str(row, "project_id")
		if seen[id] {
			continue
		}
		seen[id] = true
		items = append(items, map[string]any{"project_id": id, "title": row["title"], "amount": num(row, "bounty_amount"), "paid_at": row["paid_at"]})
	}
	return 200, map[string]any{"total": num(user, "earnings"), "items": items}, nil
}

func billingNotReady() error {
	return fail(503, "billing_not_enabled", "Paid plans are not on yet. BAU students stay on bau_free.")
}
