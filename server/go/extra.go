package main

import (
	"database/sql"
	"encoding/json"
	"html"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
)

func shareHTML(project map[string]any) string {
	title := html.EscapeString(str(project, "title"))
	department := html.EscapeString(str(project, "department"))
	description := html.EscapeString(str(project, "description"))
	return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + title + " · BAU BUILD</title>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>\n<body><p>" + department + "</p><h1>" + title + "</h1><p>" + description + "</p>\n<p><a href=\"https://baubuild.vesperconnect.org/\">Open BAU BUILD</a></p></body></html>"
}

func (a *app) share(id string) (int, string, error) {
	status, body, err := a.withTx(func(tx *sql.Tx) (int, any, error) {
		row, err := one(tx, `SELECT * FROM projects WHERE id = ?`, id)
		if err != nil {
			return 0, nil, err
		}
		if row == nil {
			return 0, nil, fail(404, "not_found", "That project is not on the board.")
		}
		return 200, shareHTML(row), nil
	})
	if err != nil {
		return 0, "", err
	}
	return status, body.(string), nil
}

func (a *app) githubCallback(query url.Values) (int, any, error) {
	return a.withTx(func(tx *sql.Tx) (int, any, error) {
		state := query.Get("state")
		code := query.Get("code")
		row, err := one(tx, `SELECT * FROM oauth_states WHERE state = ?`, state)
		if err != nil {
			return 0, nil, err
		}
		if row == nil || code == "" {
			return 0, nil, fail(400, "invalid_oauth", "The GitHub callback is missing a valid state.")
		}
		clientID := strings.TrimSpace(os.Getenv("GITHUB_CLIENT_ID"))
		secret := strings.TrimSpace(os.Getenv("GITHUB_CLIENT_SECRET"))
		if clientID == "" || secret == "" {
			return 0, nil, fail(503, "github_not_configured", "GitHub sign-in is not configured on this server.")
		}
		form := url.Values{
			"client_id": {clientID}, "client_secret": {secret}, "code": {code},
			"redirect_uri": {githubRedirect()},
		}
		req, err := http.NewRequest(http.MethodPost, "https://github.com/login/oauth/access_token", strings.NewReader(form.Encode()))
		if err != nil {
			return 0, nil, fail(401, "github_rejected", "GitHub did not accept that connection.")
		}
		req.Header.Set("Accept", "application/json")
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			return 0, nil, fail(401, "github_rejected", "GitHub did not accept that connection.")
		}
		defer res.Body.Close()
		raw, _ := io.ReadAll(res.Body)
		var token map[string]any
		if json.Unmarshal(raw, &token) != nil || asString(token["access_token"]) == "" {
			return 0, nil, fail(401, "github_rejected", "GitHub did not accept that connection.")
		}
		access := asString(token["access_token"])
		profileReq, _ := http.NewRequest(http.MethodGet, "https://api.github.com/user", nil)
		profileReq.Header.Set("Authorization", "Bearer "+access)
		profileReq.Header.Set("User-Agent", "bau-build")
		profileRes, err := http.DefaultClient.Do(profileReq)
		if err != nil {
			return 0, nil, fail(401, "github_rejected", "GitHub did not accept that connection.")
		}
		defer profileRes.Body.Close()
		raw, _ = io.ReadAll(profileRes.Body)
		var profile map[string]any
		if json.Unmarshal(raw, &profile) != nil {
			return 0, nil, fail(401, "github_rejected", "GitHub did not accept that connection.")
		}
		if _, err := tx.Exec(`DELETE FROM oauth_states WHERE state = ?`, state); err != nil {
			return 0, nil, err
		}
		if _, err := tx.Exec(`UPDATE users SET github_login = ?, github_token = ? WHERE id = ?`, profile["login"], access, str(row, "user_id")); err != nil {
			return 0, nil, err
		}
		return 200, map[string]any{"connected": true, "login": profile["login"]}, nil
	})
}

func githubRedirect() string {
	if value := strings.TrimSpace(os.Getenv("GITHUB_REDIRECT_URI")); value != "" {
		return value
	}
	return "https://api.baubuild.vesperconnect.org/v1/integrations/github/callback"
}

func listProjects(tx *sql.Tx, user map[string]any, query url.Values) (map[string]any, error) {
	clauses := []string{"status = 'open'"}
	args := []any{}
	if difficulty := strings.ToLower(query.Get("difficulty")); difficulty != "" {
		if difficulty != "easy" && difficulty != "medium" && difficulty != "hard" {
			return nil, fail(400, "invalid_query", "difficulty must be easy, medium, or hard.")
		}
		clauses = append(clauses, "difficulty = ?")
		args = append(args, difficulty)
	}
	if department := query.Get("department"); department != "" {
		clauses = append(clauses, "lower(department) = lower(?)")
		args = append(args, department)
	}
	if classID := query.Get("class_id"); classID != "" {
		clauses = append(clauses, "class_id = ?")
		args = append(args, classID)
	}
	if status := query.Get("status"); status != "" && status != "open" {
		return nil, fail(400, "invalid_query", "status=open is the supported filter.")
	}
	if q := strings.TrimSpace(query.Get("q")); q != "" {
		like := "%" + strings.ToLower(q) + "%"
		clauses = append(clauses, "(lower(title) LIKE ? OR lower(description) LIKE ? OR lower(department) LIKE ?)")
		args = append(args, like, like, like)
	}
	offset := 0
	if cursor := query.Get("cursor"); cursor != "" {
		for _, c := range cursor {
			if c < '0' || c > '9' {
				return nil, fail(400, "invalid_cursor", "That cursor is not valid.")
			}
			offset = offset*10 + int(c-'0')
		}
	}
	args = append(args, pageSize+1, offset)
	rows, err := many(tx, `SELECT * FROM projects WHERE `+strings.Join(clauses, " AND ")+` ORDER BY urgent DESC, title LIMIT ? OFFSET ?`, args...)
	if err != nil {
		return nil, err
	}
	limit := pageSize
	if len(rows) < limit {
		limit = len(rows)
	}
	items := []any{}
	for _, row := range rows[:limit] {
		item, err := projectDetail(tx, user, row)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	var next any
	if len(rows) > pageSize {
		next = intString(offset + pageSize)
	}
	return map[string]any{"items": items, "next_cursor": next}, nil
}

func intString(n int) string {
	if n == 0 {
		return "0"
	}
	digits := []byte{}
	for n > 0 {
		digits = append([]byte{byte('0' + n%10)}, digits...)
		n /= 10
	}
	return string(digits)
}

func classByID(tx *sql.Tx, classID string) (map[string]any, error) {
	row, err := one(tx, `SELECT * FROM classes WHERE id = ? OR lower(code) = lower(?)`, classID, classID)
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "not_found", "That class is not listed.")
	}
	return row, nil
}

func extra(tx *sql.Tx, r *http.Request, path string, user, session map[string]any, body map[string]any) (int, any, bool, error) {
	_ = session
	id := func(prefix, suffix string) string {
		return strings.TrimSuffix(strings.TrimPrefix(path, prefix), suffix)
	}
	if status, payload, ok, err := mentorRoutes(tx, r, path, user, body); ok {
		return status, payload, true, err
	}
	switch {
	case r.Method == http.MethodDelete && strings.HasPrefix(path, "/v1/auth/sessions/"):
		sessionID := strings.TrimPrefix(path, "/v1/auth/sessions/")
		row, err := one(tx, `SELECT * FROM sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL`, sessionID, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		if row == nil {
			return 0, nil, true, fail(404, "not_found", "That session is not active.")
		}
		_, err = tx.Exec(`UPDATE sessions SET revoked_at = ? WHERE id = ?`, nowISO(), sessionID)
		return 204, nil, true, err
	case r.Method == http.MethodGet && path == "/v1/me/entitlement":
		return 200, entitlement(user), true, nil
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/v1/users/"):
		row, err := one(tx, `SELECT * FROM users WHERE id = ?`, strings.TrimPrefix(path, "/v1/users/"))
		if err != nil {
			return 0, nil, true, err
		}
		if row == nil {
			return 0, nil, true, fail(404, "not_found", "That profile does not exist.")
		}
		pub, err := publicUserEmail(tx, row, false)
		return 200, pub, true, err
	case r.Method == http.MethodGet && path == "/v1/inquiries/urgent":
		rows, err := many(tx, `SELECT * FROM projects WHERE status = 'open' AND urgent = 1 ORDER BY due_at`)
		if err != nil {
			return 0, nil, true, err
		}
		items := []any{}
		for _, row := range rows {
			items = append(items, urgentCard(row))
		}
		return 200, map[string]any{"items": items}, true, nil
	case r.Method == http.MethodGet && path == "/v1/projects":
		out, err := listProjects(tx, user, r.URL.Query())
		return 200, out, true, err
	case r.Method == http.MethodGet && path == "/v1/me/classes":
		rows, err := many(tx, `SELECT c.* FROM enrollments e JOIN classes c ON c.id = e.class_id WHERE e.user_id = ? ORDER BY c.code`, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		items := []any{}
		for _, row := range rows {
			items = append(items, classPayload(tx, row))
		}
		return 200, map[string]any{"items": items}, true, nil
	case r.Method == http.MethodGet && path == "/v1/me/submissions":
		rows, err := many(tx, `SELECT * FROM submissions WHERE user_id = ? ORDER BY created_at DESC`, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		items := []any{}
		for _, row := range rows {
			item, err := submissionDetail(tx, row)
			if err != nil {
				return 0, nil, true, err
			}
			items = append(items, item)
		}
		return 200, map[string]any{"items": items}, true, nil
	case r.Method == http.MethodGet && path == "/v1/me/payout-method":
		row, err := one(tx, `SELECT * FROM payout_methods WHERE user_id = ?`, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		if row == nil {
			return 200, map[string]any{"active": false, "method": nil}, true, nil
		}
		var payload any
		_ = json.Unmarshal([]byte(str(row, "payload")), &payload)
		return 200, map[string]any{"active": false, "method": payload, "updated_at": row["updated_at"]}, true, nil
	case r.Method == http.MethodPut && path == "/v1/me/payout-method":
		kind := strings.TrimSpace(asString(body["type"]))
		if kind == "" {
			return 0, nil, true, fail(400, "invalid_body", "type is required. Payouts are not sent yet.")
		}
		payload, _ := json.Marshal(map[string]any{"type": kind, "label": strings.TrimSpace(asString(body["label"]))})
		_, err := tx.Exec(`INSERT INTO payout_methods (user_id, payload, updated_at) VALUES (?, ?, ?)
			ON CONFLICT(user_id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at`, str(user, "id"), string(payload), nowISO())
		if err != nil {
			return 0, nil, true, err
		}
		var stored any
		_ = json.Unmarshal(payload, &stored)
		return 200, map[string]any{"active": false, "method": stored, "updated_at": nowISO()}, true, nil
	case r.Method == http.MethodGet && path == "/v1/me/lead-application":
		out, err := leadApplication(tx, user)
		return 200, out, true, err
	case r.Method == http.MethodPost && path == "/v1/me/lead-application":
		if str(user, "role") == "lead" {
			out, err := leadApplication(tx, user)
			return 200, out, true, err
		}
		if str(user, "role") != "student" && str(user, "role") != "admin" {
			return 0, nil, true, fail(403, "role_required", "Students apply to become a lead.")
		}
		existing, err := one(tx, `SELECT 1 AS ok FROM lead_applications WHERE user_id = ?`, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		if existing == nil {
			if _, err := tx.Exec(`INSERT INTO lead_applications (user_id, exam_passed, exam_attempts, in_person, started_at) VALUES (?, 0, 0, 0, ?)`, str(user, "id"), nowISO()); err != nil {
				return 0, nil, true, err
			}
		}
		out, err := leadApplication(tx, user)
		return 200, out, true, err
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/v1/classes/") && strings.HasSuffix(path, "/projects"):
		course, err := classByID(tx, strings.TrimSuffix(strings.TrimPrefix(path, "/v1/classes/"), "/projects"))
		if err != nil {
			return 0, nil, true, err
		}
		rows, err := many(tx, `SELECT * FROM projects WHERE class_id = ? AND status = 'open' ORDER BY title`, str(course, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		items := []any{}
		for _, row := range rows {
			item, err := projectDetail(tx, user, row)
			if err != nil {
				return 0, nil, true, err
			}
			items = append(items, item)
		}
		return 200, map[string]any{"class": classPayload(tx, course), "items": items}, true, nil
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/v1/classes/"):
		course, err := classByID(tx, strings.TrimPrefix(path, "/v1/classes/"))
		if err != nil {
			return 0, nil, true, err
		}
		return 200, classPayload(tx, course), true, nil
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/v1/projects/") && !strings.Contains(strings.TrimPrefix(path, "/v1/projects/"), "/"):
		row, err := requireProject(tx, strings.TrimPrefix(path, "/v1/projects/"))
		if err != nil {
			return 0, nil, true, err
		}
		out, err := projectDetail(tx, user, row)
		return 200, out, true, err
	case r.Method == http.MethodDelete && strings.HasSuffix(path, "/membership"):
		projectID := id("/v1/projects/", "/membership")
		if _, err := requireProject(tx, projectID); err != nil {
			return 0, nil, true, err
		}
		mem, err := membership(tx, str(user, "id"), projectID)
		if err != nil {
			return 0, nil, true, err
		}
		if mem == nil {
			return 0, nil, true, fail(404, "not_found", "You are not in this project.")
		}
		if _, err := tx.Exec(`DELETE FROM tasks WHERE user_id = ? AND project_id = ?`, str(user, "id"), projectID); err != nil {
			return 0, nil, true, err
		}
		_, err = tx.Exec(`DELETE FROM memberships WHERE user_id = ? AND project_id = ?`, str(user, "id"), projectID)
		return 204, nil, true, err
	case r.Method == http.MethodDelete && strings.HasSuffix(path, "/bookmark") && strings.HasPrefix(path, "/v1/projects/"):
		projectID := id("/v1/projects/", "/bookmark")
		if _, err := requireProject(tx, projectID); err != nil {
			return 0, nil, true, err
		}
		_, err := tx.Exec(`DELETE FROM bookmarks WHERE user_id = ? AND project_id = ?`, str(user, "id"), projectID)
		return 204, nil, true, err
	case r.Method == http.MethodPatch && strings.HasPrefix(path, "/v1/tasks/"):
		taskID := strings.TrimPrefix(path, "/v1/tasks/")
		row, err := one(tx, `SELECT * FROM tasks WHERE id = ? AND user_id = ?`, taskID, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		if row == nil {
			return 0, nil, true, fail(404, "not_found", "That task is not yours.")
		}
		status := asString(body["status"])
		if status != "todo" && status != "in_progress" && status != "done" {
			return 0, nil, true, fail(400, "invalid_body", "status must be todo, in_progress, or done.")
		}
		if _, err := tx.Exec(`UPDATE tasks SET status = ? WHERE id = ?`, status, taskID); err != nil {
			return 0, nil, true, err
		}
		fresh, err := one(tx, `SELECT * FROM tasks WHERE id = ?`, taskID)
		if err != nil {
			return 0, nil, true, err
		}
		return 200, map[string]any{"id": fresh["id"], "project_id": fresh["project_id"], "title": fresh["title"], "status": fresh["status"], "lane": fresh["lane"], "due_at": fresh["due_at"]}, true, nil
	case r.Method == http.MethodGet && strings.HasSuffix(path, "/changes"):
		projectID := id("/v1/projects/", "/changes")
		project, err := requireProject(tx, projectID)
		if err != nil {
			return 0, nil, true, err
		}
		if err := requireMember(tx, user, projectID); err != nil {
			return 0, nil, true, err
		}
		rows, err := many(tx, `SELECT * FROM submissions WHERE project_id = ? AND user_id = ? ORDER BY created_at DESC`, projectID, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		grouped := []any{}
		seen := map[string][]any{}
		order := []string{}
		for _, row := range rows {
			name := str(row, "branch")
			if _, ok := seen[name]; !ok {
				order = append(order, name)
			}
			seen[name] = append(seen[name], map[string]any{"id": row["id"], "message": row["branch"], "state": row["lead_status"], "diff_url": row["diff_url"], "created_at": row["created_at"]})
		}
		for _, name := range order {
			grouped = append(grouped, map[string]any{"name": name, "commits": seen[name]})
		}
		return 200, map[string]any{"connected": user["github_token"] != nil && str(user, "github_token") != "", "repo": project["repo_name"], "branches": grouped, "github_commits": []any{}}, true, nil
	case r.Method == http.MethodGet && strings.HasSuffix(path, "/mentor/messages"):
		projectID := id("/v1/projects/", "/mentor/messages")
		if err := requireMember(tx, user, projectID); err != nil {
			return 0, nil, true, err
		}
		rows, err := many(tx, `SELECT * FROM mentor_messages WHERE project_id = ? AND user_id = ? ORDER BY created_at`, projectID, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		items := []any{}
		for _, row := range rows {
			if taskFilter := r.URL.Query().Get("task_id"); taskFilter != "" && str(row, "task_id") != taskFilter {
				continue
			}
			role := str(row, "role")
			if role == "student" {
				role = "user"
			}
			if role == "mentor" {
				role = "assistant"
			}
			var citations []any
			_ = json.Unmarshal([]byte(str(row, "citations")), &citations)
			if citations == nil {
				citations = []any{}
			}
			items = append(items, map[string]any{
				"id": row["id"], "role": role, "text": row["body"], "task_id": row["task_id"],
				"citations": citations, "created_at": row["created_at"],
			})
		}
		return 200, map[string]any{"items": items, "next_cursor": nil}, true, nil
	case r.Method == http.MethodGet && path == "/v1/integrations/github":
		return 200, map[string]any{"connected": user["github_token"] != nil && str(user, "github_token") != "", "login": user["github_login"]}, true, nil
	case r.Method == http.MethodPost && path == "/v1/integrations/github/connect":
		clientID := strings.TrimSpace(os.Getenv("GITHUB_CLIENT_ID"))
		if clientID == "" {
			return 0, nil, true, fail(503, "github_not_configured", "GitHub sign-in is not configured on this server.")
		}
		state := tokenURL()
		if _, err := tx.Exec(`INSERT INTO oauth_states (state, user_id, created_at) VALUES (?, ?, ?)`, state, str(user, "id"), nowISO()); err != nil {
			return 0, nil, true, err
		}
		q := url.Values{"client_id": {clientID}, "redirect_uri": {githubRedirect()}, "scope": {"read:user repo"}, "state": {state}}
		return 200, map[string]any{"authorize_url": "https://github.com/login/oauth/authorize?" + q.Encode()}, true, nil
	case r.Method == http.MethodDelete && path == "/v1/integrations/github":
		_, err := tx.Exec(`UPDATE users SET github_login = NULL, github_token = NULL WHERE id = ?`, str(user, "id"))
		return 200, map[string]any{"connected": false, "login": nil}, true, err
	case r.Method == http.MethodGet && path == "/v1/inquiries/mine":
		if err := staffOnly(user); err != nil {
			return 0, nil, true, err
		}
		rows, err := many(tx, `SELECT * FROM inquiries WHERE author_id = ? ORDER BY created_at DESC`, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		items := []any{}
		for _, row := range rows {
			items = append(items, inquiryPayload(row))
		}
		return 200, map[string]any{"items": items}, true, nil
	case strings.HasPrefix(path, "/v1/inquiries/") && !strings.Contains(strings.TrimPrefix(path, "/v1/inquiries/"), "/"):
		return inquiryOne(tx, r.Method, strings.TrimPrefix(path, "/v1/inquiries/"), user, body)
	case r.Method == http.MethodPost && path == "/v1/admin/enrollments":
		if err := adminOnly(user); err != nil {
			return 0, nil, true, err
		}
		person, err := one(tx, `SELECT * FROM users WHERE id = ?`, asString(body["user_id"]))
		if err != nil {
			return 0, nil, true, err
		}
		if person == nil {
			return 0, nil, true, fail(404, "not_found", "That user does not exist.")
		}
		course, err := classByID(tx, asString(body["class_id"]))
		if err != nil {
			return 0, nil, true, err
		}
		if _, err := tx.Exec(`INSERT OR IGNORE INTO enrollments (user_id, class_id) VALUES (?, ?)`, str(person, "id"), str(course, "id")); err != nil {
			return 0, nil, true, err
		}
		return 201, map[string]any{"user_id": person["id"], "class_id": course["id"], "code": course["code"]}, true, nil
	case r.Method == http.MethodDelete && strings.HasPrefix(path, "/v1/devices/"):
		deviceID := strings.TrimPrefix(path, "/v1/devices/")
		row, err := one(tx, `SELECT * FROM devices WHERE id = ? AND user_id = ?`, deviceID, str(user, "id"))
		if err != nil {
			return 0, nil, true, err
		}
		if row == nil {
			return 0, nil, true, fail(404, "not_found", "That device is not registered.")
		}
		_, err = tx.Exec(`DELETE FROM devices WHERE id = ?`, deviceID)
		return 204, nil, true, err
	case r.Method == http.MethodGet && submissionID.MatchString(path) && !strings.HasSuffix(path, "/decision"):
		parts := submissionID.FindStringSubmatch(path)
		row, err := one(tx, `SELECT * FROM submissions WHERE id = ?`, parts[1])
		if err != nil {
			return 0, nil, true, err
		}
		if row == nil {
			return 0, nil, true, fail(404, "not_found", "That submission does not exist.")
		}
		if str(row, "user_id") != str(user, "id") && str(user, "role") != "lead" && str(user, "role") != "admin" {
			return 0, nil, true, fail(403, "role_required", "That submission is not yours.")
		}
		out, err := submissionDetail(tx, row)
		return 200, out, true, err
	default:
		return 0, nil, false, nil
	}
}

func inquiryOne(tx *sql.Tx, method, inquiryID string, user, body map[string]any) (int, any, bool, error) {
	row, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, inquiryID)
	if err != nil {
		return 0, nil, true, err
	}
	if row == nil {
		return 0, nil, true, fail(404, "not_found", "That inquiry does not exist.")
	}
	if str(user, "role") != "admin" && str(row, "author_id") != str(user, "id") {
		return 0, nil, true, fail(403, "role_required", "That inquiry belongs to someone else.")
	}
	if str(user, "role") != "staff" && str(user, "role") != "admin" {
		return 0, nil, true, fail(403, "role_required", "Only @bau.edu staff can edit inquiries.")
	}
	if method == http.MethodGet {
		return 200, inquiryPayload(row), true, nil
	}
	if str(row, "status") != "submitted" && method != http.MethodGet {
		return 0, nil, true, fail(409, "inquiry_locked", "Only a submitted inquiry can be edited.")
	}
	if method == http.MethodDelete {
		if str(row, "status") != "submitted" {
			return 0, nil, true, fail(409, "inquiry_locked", "Only a submitted inquiry can be deleted.")
		}
		_, err := tx.Exec(`DELETE FROM inquiries WHERE id = ?`, inquiryID)
		return 204, nil, true, err
	}
	if method == http.MethodPatch {
		fields, err := inquiryFields(body, true)
		if err != nil {
			return 0, nil, true, err
		}
		if len(fields) == 0 {
			return 200, inquiryPayload(row), true, nil
		}
		sets := []string{}
		args := []any{}
		for key, value := range fields {
			sets = append(sets, key+" = ?")
			args = append(args, value)
		}
		args = append(args, inquiryID)
		if _, err := tx.Exec(`UPDATE inquiries SET `+strings.Join(sets, ", ")+` WHERE id = ?`, args...); err != nil {
			return 0, nil, true, err
		}
		fresh, err := one(tx, `SELECT * FROM inquiries WHERE id = ?`, inquiryID)
		return 200, inquiryPayload(fresh), true, err
	}
	return 0, nil, false, nil
}
