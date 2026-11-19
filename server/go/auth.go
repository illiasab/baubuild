package main

import (
	"crypto/rand"
	"database/sql"
	"fmt"
	"net/smtp"
	"os"
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
`)
	return err
}

var deliverCode = func(to, subject, body string) error {
	host := strings.TrimSpace(os.Getenv("SMTP_HOST"))
	if host == "" || host == "test" {
		return nil
	}
	port := strings.TrimSpace(os.Getenv("SMTP_PORT"))
	if port == "" {
		port = "587"
	}
	from := strings.TrimSpace(os.Getenv("SMTP_FROM"))
	if from == "" {
		from = "BAU BUILD <noreply@baubuild.vesperconnect.org>"
	}
	addr := host + ":" + port
	var auth smtp.Auth
	user := os.Getenv("SMTP_USER")
	if user != "" {
		auth = smtp.PlainAuth("", user, os.Getenv("SMTP_PASSWORD"), host)
	}
	msg := "From: " + from + "\r\nTo: " + to + "\r\nSubject: " + subject + "\r\n\r\n" + body + "\r\n"
	return smtp.SendMail(addr, auth, mailbox(from), []string{to}, []byte(msg))
}

func mailbox(from string) string {
	if start := strings.Index(from, "<"); start >= 0 {
		end := strings.Index(from, ">")
		if end > start {
			return from[start+1 : end]
		}
	}
	return from
}

func mailReady() error {
	if strings.TrimSpace(os.Getenv("SMTP_HOST")) == "" {
		return fail(503, "email_not_configured", "Email is not configured on this server.")
	}
	return nil
}

func newDigits() string {
	var raw [4]byte
	_, _ = rand.Read(raw[:])
	n := int(raw[0])<<16 | int(raw[1])<<8 | int(raw[2])
	return fmt.Sprintf("%06d", n%1000000)
}

func deviceOf(body map[string]any) (string, string, error) {
	device, _ := body["device"].(map[string]any)
	if device == nil {
		return "", "", fail(400, "invalid_body", "device.platform and device.app_version are required.")
	}
	platform := asString(device["platform"])
	version := strings.TrimSpace(asString(device["app_version"]))
	allowed := map[string]bool{"ios": true, "macos": true, "android": true, "windows": true, "linux": true}
	if !allowed[platform] {
		return "", "", fail(400, "invalid_body", "device.platform must be ios, macos, android, windows, or linux.")
	}
	if version == "" || len(version) > 40 {
		return "", "", fail(400, "invalid_body", "device.app_version is required.")
	}
	return platform, version, nil
}

func passwordOK(password string) error {
	if len(password) < 8 || len(password) > 72 {
		return fail(400, "invalid_body", "Use a password of 8 to 72 characters.")
	}
	return nil
}

func registerAccount(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	if err := mailReady(); err != nil {
		return nil, err
	}
	name := strings.TrimSpace(asString(body["name"]))
	email := strings.TrimSpace(strings.ToLower(asString(body["email"])))
	password := asString(body["password"])
	if name == "" || len(name) > 80 {
		return nil, fail(400, "invalid_body", "Name must be 1 to 80 characters.")
	}
	if err := passwordOK(password); err != nil {
		return nil, err
	}
	if _, _, err := deviceOf(body); err != nil {
		return nil, err
	}
	role, err := classifyEmail(email)
	if err != nil {
		return nil, err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, err
	}
	row, err := one(tx, `SELECT * FROM users WHERE email = ?`, email)
	if err != nil {
		return nil, err
	}
	if row != nil && num(row, "email_verified") == 1 {
		return nil, fail(409, "email_taken", "That email already has an account. Sign in.")
	}
	if row == nil {
		if _, err := tx.Exec(`INSERT INTO users (id, email, name, initials, bio, role, skills, created_at, password_hash, email_verified) VALUES (?, ?, ?, ?, '', ?, '[]', ?, ?, 0)`,
			newID(), email, name, initialsOf(name), role, nowISO(), string(hash)); err != nil {
			return nil, err
		}
	} else if str(row, "role") == "lead" {
		if _, err := tx.Exec(`UPDATE users SET name = ?, initials = ?, password_hash = ? WHERE email = ?`, name, initialsOf(name), string(hash), email); err != nil {
			return nil, err
		}
	} else if _, err := tx.Exec(`UPDATE users SET name = ?, initials = ?, role = ?, password_hash = ? WHERE email = ?`, name, initialsOf(name), role, string(hash), email); err != nil {
		return nil, err
	}
	if err := issueCode(tx, email, "verify", "Your BAU BUILD code", "Your BAU BUILD verification code is:\n%s\nIt expires in 15 minutes."); err != nil {
		return nil, err
	}
	return map[string]any{"email": email, "verification_required": true}, nil
}

func verifyEmail(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	email := strings.TrimSpace(strings.ToLower(asString(body["email"])))
	code := strings.TrimSpace(asString(body["code"]))
	platform, version, err := deviceOf(body)
	if err != nil {
		return nil, err
	}
	if _, err := classifyEmail(email); err != nil {
		return nil, err
	}
	if err := checkCode(tx, email, "verify", code); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(`UPDATE users SET email_verified = 1 WHERE email = ?`, email); err != nil {
		return nil, err
	}
	user, err := one(tx, `SELECT * FROM users WHERE email = ?`, email)
	if err != nil || user == nil {
		return nil, fail(400, "invalid_code", "That code is not valid.")
	}
	return issueSession(tx, user, platform, version)
}

func resendCode(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	if err := mailReady(); err != nil {
		return nil, err
	}
	email := strings.TrimSpace(strings.ToLower(asString(body["email"])))
	if _, err := classifyEmail(email); err != nil {
		return nil, err
	}
	row, err := one(tx, `SELECT * FROM users WHERE email = ?`, email)
	if err != nil {
		return nil, err
	}
	if row != nil && num(row, "email_verified") == 0 {
		if err := issueCode(tx, email, "verify", "Your BAU BUILD code", "Your BAU BUILD verification code is:\n%s\nIt expires in 15 minutes."); err != nil {
			return nil, err
		}
	}
	return map[string]any{"ok": true}, nil
}

func loginAccount(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	email := strings.TrimSpace(strings.ToLower(asString(body["email"])))
	password := asString(body["password"])
	platform, version, err := deviceOf(body)
	if err != nil {
		return nil, err
	}
	if _, err := classifyEmail(email); err != nil {
		return nil, err
	}
	if err := loginLocked(tx, email); err != nil {
		return nil, err
	}
	user, err := one(tx, `SELECT * FROM users WHERE email = ?`, email)
	if err != nil {
		return nil, err
	}
	if user == nil || bcrypt.CompareHashAndPassword([]byte(str(user, "password_hash")), []byte(password)) != nil {
		return nil, noteLoginFailure(tx, email)
	}
	if num(user, "email_verified") != 1 {
		return nil, fail(403, "email_not_verified", "Verify the email before signing in.")
	}
	if _, err := tx.Exec(`DELETE FROM login_attempts WHERE email = ?`, email); err != nil {
		return nil, err
	}
	return issueSession(tx, user, platform, version)
}

func forgotPassword(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	if err := mailReady(); err != nil {
		return nil, err
	}
	email := strings.TrimSpace(strings.ToLower(asString(body["email"])))
	if _, err := classifyEmail(email); err != nil {
		return nil, err
	}
	row, err := one(tx, `SELECT id FROM users WHERE email = ?`, email)
	if err != nil {
		return nil, err
	}
	if row != nil {
		if err := issueCode(tx, email, "reset", "Reset your BAU BUILD password", "Your BAU BUILD password reset code is:\n%s\nIt expires in 15 minutes."); err != nil {
			return nil, err
		}
	}
	return map[string]any{"ok": true}, nil
}

func resetPassword(tx *sql.Tx, body map[string]any) (map[string]any, error) {
	email := strings.TrimSpace(strings.ToLower(asString(body["email"])))
	password := asString(body["password"])
	if err := passwordOK(password); err != nil {
		return nil, err
	}
	if _, err := classifyEmail(email); err != nil {
		return nil, err
	}
	if err := checkCode(tx, email, "reset", asString(body["code"])); err != nil {
		return nil, err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, err
	}
	result, err := tx.Exec(`UPDATE users SET password_hash = ?, email_verified = 1 WHERE email = ?`, string(hash), email)
	if err != nil {
		return nil, err
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return nil, fail(400, "invalid_code", "That code is not valid.")
	}
	_, _ = tx.Exec(`DELETE FROM login_attempts WHERE email = ?`, email)
	return map[string]any{"ok": true}, nil
}

func issueCode(tx *sql.Tx, email, purpose, subject, template string) error {
	recent, err := one(tx, `SELECT created_at FROM auth_codes WHERE email = ? AND purpose = ? ORDER BY created_at DESC LIMIT 1`, email, purpose)
	if err != nil {
		return err
	}
	if recent != nil {
		created, parseErr := parseISO(str(recent, "created_at"))
		if parseErr == nil && time.Since(created) < time.Minute {
			return &apiError{Status: 429, Code: "too_many_attempts", Message: "Wait a minute before asking for another code.", Extra: map[string]any{"retry_after_s": 60}}
		}
	}
	code := newDigits()
	if _, err := tx.Exec(`DELETE FROM auth_codes WHERE email = ? AND purpose = ?`, email, purpose); err != nil {
		return err
	}
	expires := time.Now().UTC().Add(15 * time.Minute).Format("2006-01-02T15:04:05Z")
	if _, err := tx.Exec(`INSERT INTO auth_codes (id, email, purpose, code_hash, expires_at, attempts, created_at) VALUES (?, ?, ?, ?, ?, 0, ?)`,
		newID(), email, purpose, hashToken(code), expires, nowISO()); err != nil {
		return err
	}
	if err := deliverCode(email, subject, fmt.Sprintf(template, code)); err != nil {
		return fail(503, "email_not_configured", "The verification email could not be sent.")
	}
	return nil
}

func checkCode(tx *sql.Tx, email, purpose, code string) error {
	code = strings.TrimSpace(code)
	row, err := one(tx, `SELECT * FROM auth_codes WHERE email = ? AND purpose = ? ORDER BY created_at DESC LIMIT 1`, email, purpose)
	if err != nil {
		return err
	}
	if row == nil || len(code) != 6 {
		return fail(400, "invalid_code", "That code is not valid.")
	}
	expires, err := parseISO(str(row, "expires_at"))
	if err != nil || !expires.After(time.Now().UTC()) {
		return fail(400, "invalid_code", "That code has expired.")
	}
	if num(row, "attempts") >= 5 {
		_, _ = tx.Exec(`DELETE FROM auth_codes WHERE id = ?`, str(row, "id"))
		return &apiError{Status: 429, Code: "too_many_attempts", Message: "Too many attempts. Ask for a new code.", Extra: map[string]any{"retry_after_s": 60}}
	}
	if hashToken(code) != str(row, "code_hash") {
		_, _ = tx.Exec(`UPDATE auth_codes SET attempts = attempts + 1 WHERE id = ?`, str(row, "id"))
		return fail(400, "invalid_code", "That code is not valid.")
	}
	_, err = tx.Exec(`DELETE FROM auth_codes WHERE email = ? AND purpose = ?`, email, purpose)
	return err
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
