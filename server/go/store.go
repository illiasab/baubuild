package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

const schemaSQL = `
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    initials TEXT NOT NULL,
    bio TEXT NOT NULL DEFAULT '',
    role TEXT NOT NULL,
    department TEXT,
    skills TEXT NOT NULL DEFAULT '[]',
    score INTEGER NOT NULL DEFAULT 0,
    earnings INTEGER NOT NULL DEFAULT 0,
    approved_projects INTEGER NOT NULL DEFAULT 0,
    tier TEXT NOT NULL DEFAULT 'bau_free',
    tier_expires_at TEXT,
    github_login TEXT,
    github_token TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    access_hash TEXT NOT NULL,
    refresh_hash TEXT NOT NULL,
    previous_refresh_hash TEXT,
    access_expires TEXT NOT NULL,
    refresh_expires TEXT NOT NULL,
    platform TEXT NOT NULL,
    app_version TEXT NOT NULL,
    created_at TEXT NOT NULL,
    revoked_at TEXT
);
CREATE TABLE IF NOT EXISTS classes (
    id TEXT PRIMARY KEY,
    code TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    term TEXT NOT NULL,
    instructor TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS enrollments (
    user_id TEXT NOT NULL,
    class_id TEXT NOT NULL,
    PRIMARY KEY (user_id, class_id)
);
CREATE TABLE IF NOT EXISTS projects (
    id TEXT PRIMARY KEY,
    department TEXT NOT NULL,
    class_id TEXT,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    bounty INTEGER NOT NULL,
    difficulty TEXT NOT NULL,
    min_score INTEGER NOT NULL,
    due_at TEXT,
    urgent INTEGER NOT NULL DEFAULT 0,
    open_to_all INTEGER NOT NULL DEFAULT 1,
    joining TEXT NOT NULL,
    reviewed_by TEXT NOT NULL,
    reviewer_label TEXT NOT NULL,
    repo_provider TEXT,
    repo_name TEXT,
    art_key TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    inquiry_id TEXT,
    template_tasks TEXT NOT NULL DEFAULT '[]'
);
CREATE TABLE IF NOT EXISTS memberships (
    user_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (user_id, project_id)
);
CREATE TABLE IF NOT EXISTS bookmarks (
    user_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    PRIMARY KEY (user_id, project_id)
);
CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    status TEXT NOT NULL,
    lane TEXT NOT NULL,
    due_at TEXT
);
CREATE TABLE IF NOT EXISTS inquiries (
    id TEXT PRIMARY KEY,
    author_id TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    department TEXT NOT NULL,
    difficulty TEXT NOT NULL,
    bounty INTEGER NOT NULL,
    is_urgent INTEGER NOT NULL,
    due_at TEXT,
    repo_url TEXT,
    min_score INTEGER NOT NULL,
    joining TEXT NOT NULL,
    reviewer_type TEXT NOT NULL,
    status TEXT NOT NULL,
    project_id TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS submissions (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    branch TEXT NOT NULL,
    ai_findings TEXT NOT NULL,
    lead_status TEXT NOT NULL,
    reviewer_id TEXT,
    comment TEXT,
    diff_url TEXT,
    bounty_amount INTEGER NOT NULL,
    paid_at TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS mentor_messages (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    role TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS notifications (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    text TEXT NOT NULL,
    read_at TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    platform TEXT NOT NULL,
    push_token TEXT NOT NULL,
    provider TEXT NOT NULL,
    app_version TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (user_id, push_token)
);
CREATE TABLE IF NOT EXISTS lead_applications (
    user_id TEXT PRIMARY KEY,
    exam_passed INTEGER NOT NULL DEFAULT 0,
    exam_attempts INTEGER NOT NULL DEFAULT 0,
    last_exam_score INTEGER,
    in_person INTEGER NOT NULL DEFAULT 0,
    started_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS exam_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    submitted_at TEXT
);
CREATE TABLE IF NOT EXISTS payout_methods (
    user_id TEXT PRIMARY KEY,
    payload TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS oauth_states (
    state TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    created_at TEXT NOT NULL
);
`

func openDB(path string) (*sql.DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schemaSQL); err != nil {
		db.Close()
		return nil, err
	}
	if err := seed(db); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func seed(db *sql.DB) error {
	var n int
	if err := db.QueryRow("SELECT COUNT(*) FROM classes").Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	now := time.Now().UTC().Truncate(time.Second)
	iso := func(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05Z") }
	if _, err := tx.Exec(`INSERT INTO classes (id, code, name, term, instructor) VALUES (?, ?, ?, ?, ?)`,
		"cmps433", "CMPS433", "Course search and ranking", "Fall 2026", "CMPS faculty"); err != nil {
		return err
	}
	due := map[string]string{
		"48h": iso(now.Add(48 * time.Hour)),
		"3d":  iso(now.Add(72 * time.Hour)),
		"5d":  iso(now.Add(5 * 24 * time.Hour)),
		"7d":  iso(now.Add(7 * 24 * time.Hour)),
		"4d":  iso(now.Add(4 * 24 * time.Hour)),
		"10d": iso(now.Add(10 * 24 * time.Hour)),
		"14d": iso(now.Add(14 * 24 * time.Hour)),
	}
	tasks := func(rows ...[3]string) string {
		out := make([]map[string]string, 0, len(rows))
		for _, row := range rows {
			out = append(out, map[string]string{"title": row[0], "lane": row[1], "status": row[2]})
		}
		b, _ := json.Marshal(out)
		return string(b)
	}
	type proj struct {
		id, dept, classID, title, desc, diff, joining, reviewed, reviewer, provider, repo, art, tasks string
		bounty, min, urgent, open                                                                     int
		due                                                                                           string
	}
	projects := []proj{
		{"shuttle", "Campus Ops", "", "Build a shuttle timetable widget for the student portal", "Campus Ops needs a timetable widget on the student portal this week. It is open even if you are not taking the class it would normally belong to.", "hard", "approval", "ai_then_lead", "dev lead", "github", "campus-shuttle", "shuttle", tasks([3]string{"Load the weekday shuttle stops", "assigned", "todo"}, [3]string{"Render the next three departures", "backlog", "todo"}), 35, 0, 1, 1, due["48h"]},
		{"library", "Library", "", "Fix form validation on the room-booking page", "The library’s booking form accepts dates in the past. Find the check, fix it, and add a test so it stays fixed.", "easy", "everyone", "ai_then_lead", "dev lead", "github", "library-booking", "library", tasks([3]string{"Reject past dates in the booking form", "assigned", "in_progress"}, [3]string{"Add a test for the date check", "backlog", "todo"}, [3]string{"Announce the error to screen readers", "backlog", "todo"}), 18, 0, 0, 1, due["3d"]},
		{"ranking", "CMPS433", "cmps433", "Rebuild course-search ranking", "Search results currently follow the course number. Rank them by how well they match the query. The professor of CMPS433 approves the work.", "hard", "approval", "professor", "professor of CMPS433", "github", "course-search", "ranking", tasks([3]string{"Rank matches by query relevance", "assigned", "todo"}, [3]string{"Break ties with the course title", "backlog", "todo"}), 60, 300, 0, 0, due["14d"]},
		{"attendance", "Student Affairs", "", "Export club attendance to CSV", "Staff copy attendance by hand each week. Write the script that does it in one click.", "easy", "everyone", "ai_then_lead", "dev lead", "github", "club-attendance", "attendance", tasks([3]string{"Export the attendance table to CSV", "assigned", "todo"}, [3]string{"Include the club name column", "assigned", "todo"}), 20, 0, 0, 1, due["5d"]},
		{"darkmode", "Events", "", "Add dark mode to the events calendar", "Add a theme switch on the events calendar and remember the visitor’s choice on the next visit.", "medium", "everyone", "ai_then_lead", "dev lead", "github", "events-calendar", "darkmode", tasks([3]string{"Add a theme switch to the calendar", "assigned", "todo"}, [3]string{"Remember the choice for the next visit", "backlog", "todo"}), 20, 0, 0, 1, due["7d"]},
		{"faq", "Admissions", "", "Repair broken links on the FAQ", "Find every dead link on the admissions FAQ and point it at a page that exists.", "easy", "everyone", "ai_then_lead", "dev lead", "github", "admissions-faq", "faq", tasks([3]string{"Replace each dead FAQ link", "assigned", "todo"}, [3]string{"Add a check that fails on a 404", "backlog", "todo"}), 18, 0, 0, 1, due["4d"]},
		{"transcript", "Registrar", "", "Speed up the transcript request form", "The transcript form reloads the page on every field. Find the cause and remove the reloads.", "hard", "approval", "ai_then_lead", "dev lead", "github", "transcript-form", "transcript", tasks([3]string{"Find which field triggers the reload", "assigned", "todo"}, [3]string{"Submit the form without reloading", "backlog", "todo"}), 45, 250, 0, 1, due["10d"]},
	}
	for _, p := range projects {
		var classID any
		if p.classID != "" {
			classID = p.classID
		}
		if _, err := tx.Exec(`INSERT INTO projects (
			id, department, class_id, title, description, bounty, difficulty, min_score, due_at,
			urgent, open_to_all, joining, reviewed_by, reviewer_label, repo_provider, repo_name,
			art_key, status, inquiry_id, template_tasks
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', NULL, ?)`,
			p.id, p.dept, classID, p.title, p.desc, p.bounty, p.diff, p.min, p.due, p.urgent, p.open, p.joining, p.reviewed, p.reviewer, p.provider, p.repo, p.art, p.tasks); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(`INSERT INTO inquiries (
		id, author_id, title, description, department, difficulty, bounty, is_urgent, due_at, repo_url,
		min_score, joining, reviewer_type, status, project_id, created_at
	) VALUES ('inq-shuttle', '', ?, ?, 'Campus Ops', 'hard', 35, 1, ?, 'https://github.com/bau/campus-shuttle', 0, 'approval', 'ai_then_lead', 'published', 'shuttle', ?)`,
		projects[0].title, projects[0].desc, due["48h"], iso(now)); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("seed: %w", err)
	}
	return nil
}
