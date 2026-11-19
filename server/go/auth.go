package main

import (
	"database/sql"
	"net"
	"net/http"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

func migrateAuth(db *sql.DB) error {
	alters := []string{
		`ALTER TABLE users ADD COLUMN password_hash TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 0`,
	}
	for _, stmt := range alters {
		if _, err := db.Exec(stmt); err != nil && !strings.Contains(strings.ToLower(err.Error()), "duplicate column") {
			return err
		}
	}
	// No email step any more: every recorded account counts as verified.
	if _, err := db.Exec(`UPDATE users SET email_verified = 1 WHERE email_verified = 0`); err != nil {
		return err
	}
	_, err := db.Exec(`
CREATE TABLE IF NOT EXISTS auth_codes (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    purpose TEXT NOT NULL,
    code_hash TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS login_attempts (
    email TEXT PRIMARY KEY,
    failures INTEGER NOT NULL DEFAULT 0,
    locked_until TEXT
);
CREATE TABLE IF NOT EXISTS code_sends (
    id TEXT PRIMARY KEY,
    bucket TEXT NOT NULL,
    created_at TEXT NOT NULL
);
`)
	return err
}

func clientIP(r *http.Request) string {
	if fwd := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); fwd != "" {
		return strings.TrimSpace(strings.Split(fwd, ",")[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func deviceOf(body map[string]any) (string, string, error) {
	device, _ := body["device"].(map[string]any)
	if device == nil {
		return "", "", fail(400, "invalid_body", "device.platform and device.app_version are required.")
	}
	platform := asString(device["platform"])
	version := strings.TrimSpace(asString(device["app_version"]))
	allowed := map[string]bool{"ios": true, "macos": true, "android": true, "windows": true, "linux": true, "web": true}
	if !allowed[platform] {
		return "", "", fail(400, "invalid_body", "device.platform must be ios, macos, android, windows, linux, or web.")
	}
	if version == "" || len(version) > 40 {
		return "", "", fail(400, "invalid_body", "device.app_version is required.")
	}
	return platform, version, nil
}

func passwordOK(password string) error {
	letters, digits := false, false
	for _, r := range password {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z':
			letters = true
		case r >= '0' && r <= '9':
			digits = true
		}
	}
	if len(password) < 8 || len(password) > 72 || !letters || !digits {
		return fail(422, "weak_password", "Use at least 8 characters with a letter and a digit.")
	}
	return nil
}

// registerAccount creates a verified account and signs it in. There is no email step: the account is recorded
// in the database and the domain decides the role (@stu.bau.edu student, @bau.edu staff).
func registerAccount(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	email := strings.TrimSpace(strings.ToLower(asString(body["email"])))
	role, err := classifyEmail(email)
	if err != nil {
		return nil, err
	}
	name := strings.TrimSpace(asString(body["name"]))
	password := asString(body["password"])
	if name == "" || len(name) > 80 {
		return nil, fail(400, "invalid_body", "Name must be 1 to 80 characters.")
	}
	if err := passwordOK(password); err != nil {
		return nil, err
	}
	platform, version, err := deviceOf(body)
	if err != nil {
		return nil, err
	}
	row, err := one(tx, `SELECT * FROM users WHERE email = ?`, email)
	if err != nil {
		return nil, err
	}
	if row != nil && str(row, "password_hash") != "" {
		return nil, fail(409, "email_taken", "That email already has an account. Sign in.")
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), 12)
	if err != nil {
		return nil, err
	}
	// Seeded accounts that carry extra roles (senior engineers / interviewers) can't be claimed by registering:
	// without email verification anyone could take them over. An admin activates them with
	// POST /v1/admin/users/{id}/password.
	if row != nil && str(row, "roles") != "" && str(row, "roles") != "[]" {
		return nil, fail(409, "account_reserved", "This account is reserved. Ask a BAU BUILD admin to activate it.")
	}
	switch {
	case row == nil:
		if _, err := tx.Exec(`INSERT INTO users (id, email, name, initials, bio, role, skills, created_at, password_hash, email_verified) VALUES (?, ?, ?, ?, '', ?, '[]', ?, ?, 1)`,
			newID(), email, name, initialsOf(name), role, nowISO(), string(hash)); err != nil {
			return nil, err
		}
	case str(row, "role") == "lead" || str(row, "role") == "admin" || (role == "staff" && str(row, "role") == "staff"):
		// A pre-seeded account (no password yet) is claimed; keep its role and name.
		if _, err := tx.Exec(`UPDATE users SET password_hash = ?, email_verified = 1 WHERE email = ?`, string(hash), email); err != nil {
			return nil, err
		}
	default:
		if _, err := tx.Exec(`UPDATE users SET name = ?, initials = ?, role = ?, password_hash = ?, email_verified = 1 WHERE email = ?`, name, initialsOf(name), role, string(hash), email); err != nil {
			return nil, err
		}
	}
	user, err := one(tx, `SELECT * FROM users WHERE email = ?`, email)
	if err != nil {
		return nil, err
	}
	return issueSession(tx, user, platform, version)
}

// emailCodesGone answers the old code-by-email routes, kept so old clients get a clear error.
func emailCodesGone() error {
	return fail(501, "not_available", "Password reset by email isn't available. Ask BAU BUILD support.")
}

func loginAccount(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	email := strings.TrimSpace(strings.ToLower(asString(body["email"])))
	password := asString(body["password"])
	if _, err := classifyEmail(email); err != nil {
		return nil, err
	}
	platform, version, err := deviceOf(body)
	if err != nil {
		return nil, err
	}
	if err := loginLocked(tx, email); err != nil {
		return nil, err
	}
	user, err := one(tx, `SELECT * FROM users WHERE email = ?`, email)
	if err != nil {
		return nil, err
	}
	if user == nil || str(user, "password_hash") == "" || bcrypt.CompareHashAndPassword([]byte(str(user, "password_hash")), []byte(password)) != nil {
		return nil, noteLoginFailure(tx, email)
	}
	if _, err := tx.Exec(`DELETE FROM login_attempts WHERE email = ?`, email); err != nil {
		return nil, err
	}
	if num(user, "email_verified") != 1 {
		if _, err := tx.Exec(`UPDATE users SET email_verified = 1 WHERE id = ?`, str(user, "id")); err != nil {
			return nil, err
		}
	}
	return issueSession(tx, user, platform, version)
}

// adminSetPassword lets an admin set a user's password (there is no email reset) and signs that user out everywhere.
func adminSetPassword(tx *sql.Tx, admin map[string]any, userID string, body map[string]any) (map[string]any, error) {
	if err := adminOnly(admin); err != nil {
		return nil, err
	}
	password := asString(body["new_password"])
	if err := passwordOK(password); err != nil {
		return nil, err
	}
	user, err := one(tx, `SELECT * FROM users WHERE id = ?`, userID)
	if err != nil {
		return nil, err
	}
	if user == nil {
		return nil, fail(404, "not_found", "That user does not exist.")
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), 12)
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(`UPDATE users SET password_hash = ?, email_verified = 1 WHERE id = ?`, string(hash), userID); err != nil {
		return nil, err
	}
	result, err := tx.Exec(`UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`, nowISO(), userID)
	if err != nil {
		return nil, err
	}
	revoked, _ := result.RowsAffected()
	_, _ = tx.Exec(`DELETE FROM login_attempts WHERE email = ?`, str(user, "email"))
	return map[string]any{"user_id": userID, "sessions_revoked": revoked}, nil
}

func loginLocked(tx *sql.Tx, email string) error {
	row, err := one(tx, `SELECT * FROM login_attempts WHERE email = ?`, email)
	if err != nil || row == nil || row["locked_until"] == nil || str(row, "locked_until") == "" {
		return err
	}
	until, err := parseISO(str(row, "locked_until"))
	if err != nil || !until.After(time.Now().UTC()) {
		return nil
	}
	return &apiError{Status: 429, Code: "too_many_attempts", Message: "Too many sign-in attempts. Try again later.", Extra: map[string]any{"retry_after_s": int(time.Until(until).Seconds()) + 1}}
}

func noteLoginFailure(tx *sql.Tx, email string) error {
	row, err := one(tx, `SELECT failures FROM login_attempts WHERE email = ?`, email)
	if err != nil {
		return err
	}
	failures := 1
	if row != nil {
		failures = num(row, "failures") + 1
	}
	var locked any
	status := fail(401, "invalid_credentials", "The email or password is wrong.")
	if failures >= 5 {
		locked = time.Now().UTC().Add(15 * time.Minute).Format("2006-01-02T15:04:05Z")
		status = &apiError{Status: 429, Code: "too_many_attempts", Message: "Too many sign-in attempts. Try again later.", Extra: map[string]any{"retry_after_s": 900}}
	}
	if row == nil {
		_, err = tx.Exec(`INSERT INTO login_attempts (email, failures, locked_until) VALUES (?, ?, ?)`, email, failures, locked)
	} else {
		_, err = tx.Exec(`UPDATE login_attempts SET failures = ?, locked_until = ? WHERE email = ?`, failures, locked, email)
	}
	if err != nil {
		return err
	}
	return status
}
