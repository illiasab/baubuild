package main

import (
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"regexp"
	"strings"
	"sync"
)

type app struct {
	db *sql.DB
	mu sync.Mutex
}

func (a *app) withTx(fn func(*sql.Tx) (int, any, error)) (int, any, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	tx, err := a.db.Begin()
	if err != nil {
		return 0, nil, err
	}
	status, body, err := fn(tx)
	if err != nil {
		if _, ok := err.(*apiError); ok {
			_ = tx.Commit()
			return status, body, err
		}
		_ = tx.Rollback()
		return 0, nil, err
	}
	if err := tx.Commit(); err != nil {
		return 0, nil, err
	}
	return status, body, nil
}

func (a *app) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, Last-Event-ID")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, PUT, DELETE, OPTIONS")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	status, body, stream, err := a.route(r)
	if err != nil {
		if api, ok := err.(*apiError); ok {
			writeJSON(w, api.Status, map[string]any{"error": map[string]any{"code": api.Code, "message": api.Message}})
			return
		}
		writeJSON(w, 500, map[string]any{"error": map[string]any{"code": "server_error", "message": "The server could not finish that."}})
		return
	}
	if stream != "" {
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, stream)
		return
	}
	if status == http.StatusNoContent {
		w.WriteHeader(status)
		return
	}
	writeJSON(w, status, body)
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(body)
}

func readBody(r *http.Request) map[string]any {
	if r.Body == nil {
		return map[string]any{}
	}
	defer r.Body.Close()
	var body map[string]any
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body == nil {
		return map[string]any{}
	}
	return body
}

func (a *app) route(r *http.Request) (int, any, string, error) {
	path := r.URL.Path
	if path == "/v1" && r.Method == http.MethodGet {
		return 200, map[string]any{"service": "bau-build", "version": "v1", "documentation": "/docs", "openapi": "/v1/openapi.json"}, "", nil
	}
	if path == "/v1/config" && r.Method == http.MethodGet {
		return 200, configDoc(), "", nil
	}
	if path == "/v1/billing/plans" && r.Method == http.MethodGet {
		return 200, map[string]any{"items": []any{map[string]any{
			"id": "outside_builder", "name": "Outside builder", "tier": "paid", "interval": "month",
			"stores": []string{"apple", "google", "stripe"}, "active": false,
		}}}, "", nil
	}
	if r.Method == http.MethodPost && (path == "/v1/billing/checkout" || path == "/v1/billing/apple/verify" || path == "/v1/billing/google/verify" || strings.HasPrefix(path, "/v1/billing/webhooks/")) {
		return 0, nil, "", billingNotReady()
	}

	var status int
	var body any
	var stream string
	var err error
	status, body, stream, err = a.withAuth(r, path)
	return status, body, stream, err
}

func (a *app) withAuth(r *http.Request, path string) (int, any, string, error) {
	public := path == "/v1/auth/microsoft/exchange" || path == "/v1/auth/refresh"
	if public {
		status, body, err := a.withTx(func(tx *sql.Tx) (int, any, error) {
			body := readBody(r)
			if path == "/v1/auth/microsoft/exchange" {
				out, err := exchange(tx, body)
				return 200, out, err
			}
			out, err := refresh(tx, body)
			return 200, out, err
		})
		return status, body, "", err
	}
	var user, session map[string]any
	status, result, err := a.withTx(func(tx *sql.Tx) (int, any, error) {
		var err error
		user, session, err = userFromAccess(tx, r.Header.Get("Authorization"))
		if err != nil {
			return 0, nil, err
		}
		return a.handle(tx, r, path, user, session)
	})
	if err != nil {
		return 0, nil, "", err
	}
	if text, ok := result.(string); ok && strings.HasPrefix(text, "event:") {
		return status, nil, text, nil
	}
	return status, result, "", nil
}

var (
	projectAction = regexp.MustCompile(`^/v1/projects/([A-Za-z0-9]+)/(join|leave|bookmark)$`)
	projectSub    = regexp.MustCompile(`^/v1/projects/([A-Za-z0-9]+)/(tasks|changes|submissions|mentor/messages|mentor/stream)$`)
	inquiryID     = regexp.MustCompile(`^/v1/inquiries/([A-Za-z0-9]+)$`)
	adminInquiry  = regexp.MustCompile(`^/v1/admin/inquiries/([A-Za-z0-9]+)/(approve|publish)$`)
	examSubmit    = regexp.MustCompile(`^/v1/me/lead-application/exam/sessions/([A-Za-z0-9]+)$`)
	inPerson      = regexp.MustCompile(`^/v1/admin/lead-applications/([A-Za-z0-9]+)/in-person$`)
	submissionID  = regexp.MustCompile(`^/v1/submissions/([A-Za-z0-9]+)(/decision)?$`)
)

func (a *app) handle(tx *sql.Tx, r *http.Request, path string, user, session map[string]any) (int, any, error) {
	body := map[string]any{}
	if r.Method == http.MethodPost || r.Method == http.MethodPatch || r.Method == http.MethodPut {
		body = readBody(r)
	}
	switch {
	case r.Method == http.MethodGet && path == "/v1/auth/sessions":
		rows, err := many(tx, `SELECT * FROM sessions WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at DESC`, str(user, "id"))
		if err != nil {
			return 0, nil, err
		}
		items := []any{}
		for _, row := range rows {
			items = append(items, map[string]any{
				"id": row["id"], "platform": row["platform"], "app_version": row["app_version"],
				"created_at": row["created_at"], "current": str(row, "id") == str(session, "id"),
			})
		}
		if items == nil {
			items = []any{}
		}
		return 200, map[string]any{"sessions": items}, nil
	case r.Method == http.MethodPost && path == "/v1/auth/logout":
		_, err := tx.Exec(`UPDATE sessions SET revoked_at = ? WHERE id = ?`, nowISO(), str(session, "id"))
		return 204, nil, err
	case r.Method == http.MethodGet && path == "/v1/me":
		pub, err := publicUser(tx, user)
		return 200, pub, err
	case r.Method == http.MethodPatch && path == "/v1/me":
		out, err := updateMe(tx, user, body)
		return 200, out, err
	case r.Method == http.MethodGet && path == "/v1/me/stats":
		fresh, err := one(tx, `SELECT * FROM users WHERE id = ?`, str(user, "id"))
		if err != nil {
			return 0, nil, err
		}
		return 200, statsOf(fresh), nil
	case r.Method == http.MethodGet && path == "/v1/home":
		out, err := home(tx, user)
		return 200, out, err
	case r.Method == http.MethodGet && path == "/v1/me/work":
		return myWork(tx, user)
	case r.Method == http.MethodGet && path == "/v1/me/earnings":
		return earnings(tx, user)
	case r.Method == http.MethodGet && path == "/v1/me/bookmarks":
		rows, err := many(tx, `SELECT p.* FROM bookmarks b JOIN projects p ON p.id = b.project_id WHERE b.user_id = ? ORDER BY p.title`, str(user, "id"))
		if err != nil {
			return 0, nil, err
		}
		items := []any{}
		for _, row := range rows {
			item, err := projectDetail(tx, user, row)
			if err != nil {
				return 0, nil, err
			}
			items = append(items, item)
		}
		return 200, map[string]any{"items": items}, nil
	case r.Method == http.MethodGet && path == "/v1/notifications":
		rows, err := many(tx, `SELECT * FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT 21`, str(user, "id"))
		if err != nil {
			return 0, nil, err
		}
		items := []any{}
		for _, row := range rows {
			items = append(items, map[string]any{"id": row["id"], "text": row["text"], "read": row["read_at"] != nil, "created_at": row["created_at"]})
		}
		return 200, map[string]any{"items": items, "next_cursor": nil}, nil
	case r.Method == http.MethodGet && path == "/v1/search":
		out, err := searchAll(tx, r.URL.Query().Get("q"))
		return 200, out, err
	case r.Method == http.MethodPost && path == "/v1/inquiries":
		out, err := createInquiry(tx, user, body)
		return 201, out, err
	case r.Method == http.MethodPost && adminInquiry.MatchString(path):
		parts := adminInquiry.FindStringSubmatch(path)
		if parts[2] == "approve" {
			out, err := approveInquiry(tx, user, parts[1])
			return 200, out, err
		}
		out, err := publishInquiry(tx, user, parts[1])
		return 201, out, err
	case r.Method == http.MethodPut && strings.HasSuffix(path, "/bookmark"):
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/v1/projects/"), "/bookmark")
		if _, err := requireProject(tx, id); err != nil {
			return 0, nil, err
		}
		_, err := tx.Exec(`INSERT OR IGNORE INTO bookmarks (user_id, project_id) VALUES (?, ?)`, str(user, "id"), id)
		return 204, nil, err
	case r.Method == http.MethodGet && strings.HasSuffix(path, "/tasks") && strings.HasPrefix(path, "/v1/projects/"):
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/v1/projects/"), "/tasks")
		if err := requireMember(tx, user, id); err != nil {
			return 0, nil, err
		}
		rows, err := many(tx, `SELECT * FROM tasks WHERE project_id = ? AND user_id = ? ORDER BY lane, title`, id, str(user, "id"))
		if err != nil {
			return 0, nil, err
		}
		items := []any{}
		for _, row := range rows {
			items = append(items, map[string]any{
				"id": row["id"], "project_id": row["project_id"], "title": row["title"],
				"status": row["status"], "lane": row["lane"], "due_at": row["due_at"],
			})
		}
		return 200, map[string]any{"items": items}, nil
	case r.Method == http.MethodPost && projectAction.MatchString(path):
		parts := projectAction.FindStringSubmatch(path)
		if parts[2] == "join" {
			out, err := joinProject(tx, user, parts[1])
			return 200, out, err
		}
		if parts[2] == "bookmark" {
			if _, err := requireProject(tx, parts[1]); err != nil {
				return 0, nil, err
			}
			_, err := tx.Exec(`INSERT OR IGNORE INTO bookmarks (user_id, project_id) VALUES (?, ?)`, str(user, "id"), parts[1])
			return 204, nil, err
		}
		return 404, nil, fail(404, "not_found", "No route matches that path.")
	case r.Method == http.MethodDelete && projectAction.MatchString(path) && strings.HasSuffix(path, "/bookmark"):
		parts := projectAction.FindStringSubmatch(path)
		_, err := tx.Exec(`DELETE FROM bookmarks WHERE user_id = ? AND project_id = ?`, str(user, "id"), parts[1])
		return 204, nil, err
	case r.Method == http.MethodPost && projectSub.MatchString(path) && strings.HasSuffix(path, "/submissions"):
		parts := projectSub.FindStringSubmatch(path)
		out, err := createSubmission(tx, user, parts[1], body)
		return 201, out, err
	case r.Method == http.MethodPost && strings.HasSuffix(path, "/decision") && submissionID.MatchString(path):
		parts := submissionID.FindStringSubmatch(path)
		out, err := decideSubmission(tx, user, parts[1], body)
		return 200, out, err
	case r.Method == http.MethodGet && path == "/v1/lead/queue":
		if role := str(user, "role"); role != "lead" && role != "admin" {
			return 0, nil, fail(403, "role_required", "Only a lead can open the review queue.")
		}
		rows, err := many(tx, `SELECT * FROM submissions WHERE lead_status = 'pending' ORDER BY created_at`)
		if err != nil {
			return 0, nil, err
		}
		items := []any{}
		for _, row := range rows {
			item, err := submissionDetail(tx, row)
			if err != nil {
				return 0, nil, err
			}
			items = append(items, item)
		}
		return 200, map[string]any{"items": items}, nil
	case r.Method == http.MethodPost && path == "/v1/me/lead-application/exam/session":
		out, err := startExam(tx, user)
		return 201, out, err
	case r.Method == http.MethodPost && examSubmit.MatchString(path):
		parts := examSubmit.FindStringSubmatch(path)
		out, err := submitExam(tx, user, parts[1], body)
		return 200, out, err
	case r.Method == http.MethodPost && inPerson.MatchString(path):
		parts := inPerson.FindStringSubmatch(path)
		out, err := confirmInPerson(tx, user, parts[1])
		return 200, out, err
	case r.Method == http.MethodPost && strings.HasSuffix(path, "/mentor/messages") && projectSub.MatchString(path):
		parts := projectSub.FindStringSubmatch(path)
		replyID, reply, err := postMentor(tx, user, parts[1], asString(body["text"]))
		if err != nil {
			return 0, nil, err
		}
		words := strings.Split(reply, " ")
		var b strings.Builder
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
		done, _ := json.Marshal(map[string]string{"id": replyID, "text": reply})
		b.WriteString("event: done\ndata: ")
		b.Write(done)
		b.WriteString("\n\n")
		return 200, b.String(), nil
	case r.Method == http.MethodGet && path == "/v1/notifications/unread-count":
		row, err := one(tx, `SELECT COUNT(*) AS n FROM notifications WHERE user_id = ? AND read_at IS NULL`, str(user, "id"))
		return 200, map[string]any{"unread": num(row, "n")}, err
	case r.Method == http.MethodPost && path == "/v1/notifications/read":
		out, err := markRead(tx, user, body)
		return 200, out, err
	case r.Method == http.MethodGet && path == "/v1/events/stream":
		last := 0
		if raw := r.Header.Get("Last-Event-ID"); raw != "" {
			for _, c := range raw {
				if c < '0' || c > '9' {
					last = 0
					break
				}
				last = last*10 + int(c-'0')
			}
		}
		rows, err := many(tx, `SELECT * FROM events WHERE user_id = ? AND id > ? ORDER BY id LIMIT 50`, str(user, "id"), last)
		if err != nil {
			return 0, nil, err
		}
		var b strings.Builder
		b.WriteString("event: heartbeat\ndata: {}\n\n")
		for _, row := range rows {
			b.WriteString("id: ")
			b.WriteString(asString(row["id"]))
			b.WriteString("\nevent: ")
			b.WriteString(str(row, "kind"))
			b.WriteString("\ndata: ")
			b.WriteString(str(row, "payload"))
			b.WriteString("\n\n")
		}
		return 200, b.String(), nil
	case r.Method == http.MethodPost && path == "/v1/devices":
		out, err := registerDevice(tx, user, body)
		return 201, out, err
	case r.Method == http.MethodGet && path == "/v1/projects/bookmarks":
		rows, err := many(tx, `SELECT p.* FROM bookmarks b JOIN projects p ON p.id = b.project_id WHERE b.user_id = ? ORDER BY p.title`, str(user, "id"))
		if err != nil {
			return 0, nil, err
		}
		items := []any{}
		for _, row := range rows {
			item, err := projectDetail(tx, user, row)
			if err != nil {
				return 0, nil, err
			}
			items = append(items, item)
		}
		return 200, map[string]any{"items": items}, nil
	default:
		return 0, nil, fail(404, "not_found", "No route matches that path.")
	}
}
