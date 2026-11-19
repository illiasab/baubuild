"""SQLite storage for the /v1 API. One file, created on first use."""

from __future__ import annotations

import json
import os
import sqlite3
import threading
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

LOCK = threading.Lock()
DB_PATH = None

PLATFORMS = ("ios", "macos", "android", "windows", "linux")
DIFFICULTIES = ("easy", "medium", "hard")
JOINING = ("everyone", "approval")
REVIEWED = ("ai_then_lead", "professor")
TASK_STATUS = ("todo", "in_progress", "done")
PUSH_PROVIDERS = ("apns", "fcm", "wns")

SCHEMA = """
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
"""


def now():
    return datetime.now(timezone.utc).replace(microsecond=0)


def iso(moment):
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(value):
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def configure(path=None):
    global DB_PATH
    if path is None:
        path = os.environ.get("BAU_DB")
    if path is None:
        path = str(Path(__file__).resolve().parent.parent / "data" / "bau.sqlite")
    DB_PATH = path
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with LOCK:
        conn = _connect()
        conn.executescript(SCHEMA)
        _seed(conn)
        conn.commit()
        conn.close()


def _connect():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


class DB:
    def __enter__(self):
        LOCK.acquire()
        self.conn = _connect()
        self.conn.execute("BEGIN")
        return self.conn

    def __exit__(self, exc_type, exc, tb):
        from api.errors import APIError

        try:
            if exc_type and not issubclass(exc_type, APIError):
                self.conn.rollback()
            else:
                self.conn.commit()
        finally:
            self.conn.close()
            LOCK.release()


def new_id():
    return uuid.uuid4().hex


def _seed(conn):
    if conn.execute("SELECT 1 FROM classes LIMIT 1").fetchone():
        return
    created = iso(now())
    classes = [
        ("cmps433", "CMPS433", "Course search and ranking", "Fall 2026", "CMPS faculty"),
    ]
    conn.executemany(
        "INSERT INTO classes (id, code, name, term, instructor) VALUES (?, ?, ?, ?, ?)",
        classes,
    )
    due = {
        "48h": iso(now() + timedelta(hours=48)),
        "3d": iso(now() + timedelta(days=3)),
        "5d": iso(now() + timedelta(days=5)),
        "7d": iso(now() + timedelta(days=7)),
        "4d": iso(now() + timedelta(days=4)),
        "10d": iso(now() + timedelta(days=10)),
        "14d": iso(now() + timedelta(days=14)),
    }

    def tasks(*rows):
        return json.dumps(
            [{"title": title, "lane": lane, "status": status} for title, lane, status in rows]
        )

    projects = [
        (
            "shuttle", "Campus Ops", None,
            "Build a shuttle timetable widget for the student portal",
            "Campus Ops needs a timetable widget on the student portal this week. It is open even if you are not taking the class it would normally belong to.",
            35, "hard", 0, due["48h"], 1, 1, "approval", "ai_then_lead", "dev lead",
            "github", "campus-shuttle", "shuttle", "open", None,
            tasks(
                ("Load the weekday shuttle stops", "assigned", "todo"),
                ("Render the next three departures", "backlog", "todo"),
            ),
        ),
        (
            "library", "Library", None,
            "Fix form validation on the room-booking page",
            "The library’s booking form accepts dates in the past. Find the check, fix it, and add a test so it stays fixed.",
            18, "easy", 0, due["3d"], 0, 1, "everyone", "ai_then_lead", "dev lead",
            "github", "library-booking", "library", "open", None,
            tasks(
                ("Reject past dates in the booking form", "assigned", "in_progress"),
                ("Add a test for the date check", "backlog", "todo"),
                ("Announce the error to screen readers", "backlog", "todo"),
            ),
        ),
        (
            "ranking", "CMPS433", "cmps433",
            "Rebuild course-search ranking",
            "Search results currently follow the course number. Rank them by how well they match the query. The professor of CMPS433 approves the work.",
            60, "hard", 300, due["14d"], 0, 0, "approval", "professor", "professor of CMPS433",
            "github", "course-search", "ranking", "open", None,
            tasks(
                ("Rank matches by query relevance", "assigned", "todo"),
                ("Break ties with the course title", "backlog", "todo"),
            ),
        ),
        (
            "attendance", "Student Affairs", None,
            "Export club attendance to CSV",
            "Staff copy attendance by hand each week. Write the script that does it in one click.",
            20, "easy", 0, due["5d"], 0, 1, "everyone", "ai_then_lead", "dev lead",
            "github", "club-attendance", "attendance", "open", None,
            tasks(
                ("Export the attendance table to CSV", "assigned", "todo"),
                ("Include the club name column", "assigned", "todo"),
            ),
        ),
        (
            "darkmode", "Events", None,
            "Add dark mode to the events calendar",
            "Add a theme switch on the events calendar and remember the visitor’s choice on the next visit.",
            20, "medium", 0, due["7d"], 0, 1, "everyone", "ai_then_lead", "dev lead",
            "github", "events-calendar", "darkmode", "open", None,
            tasks(
                ("Add a theme switch to the calendar", "assigned", "todo"),
                ("Remember the choice for the next visit", "backlog", "todo"),
            ),
        ),
        (
            "faq", "Admissions", None,
            "Repair broken links on the FAQ",
            "Find every dead link on the admissions FAQ and point it at a page that exists.",
            18, "easy", 0, due["4d"], 0, 1, "everyone", "ai_then_lead", "dev lead",
            "github", "admissions-faq", "faq", "open", None,
            tasks(
                ("Replace each dead FAQ link", "assigned", "todo"),
                ("Add a check that fails on a 404", "backlog", "todo"),
            ),
        ),
        (
            "transcript", "Registrar", None,
            "Speed up the transcript request form",
            "The transcript form reloads the page on every field. Find the cause and remove the reloads.",
            45, "hard", 250, due["10d"], 0, 1, "approval", "ai_then_lead", "dev lead",
            "github", "transcript-form", "transcript", "open", None,
            tasks(
                ("Find which field triggers the reload", "assigned", "todo"),
                ("Submit the form without reloading", "backlog", "todo"),
            ),
        ),
    ]
    conn.executemany(
        """INSERT INTO projects (
            id, department, class_id, title, description, bounty, difficulty, min_score,
            due_at, urgent, open_to_all, joining, reviewed_by, reviewer_label,
            repo_provider, repo_name, art_key, status, inquiry_id, template_tasks
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        projects,
    )
    # A published urgent inquiry so the home widgets have a staff-origin record.
    conn.execute(
        """INSERT INTO inquiries (
            id, author_id, title, description, department, difficulty, bounty, is_urgent,
            due_at, repo_url, min_score, joining, reviewer_type, status, project_id, created_at
        ) VALUES (?, '', ?, ?, 'Campus Ops', 'hard', 35, 1, ?, ?, 0, 'approval', 'ai_then_lead',
                  'published', 'shuttle', ?)""",
        (
            "inq-shuttle",
            projects[0][3],
            projects[0][4],
            due["48h"],
            "https://github.com/bau/campus-shuttle",
            created,
        ),
    )
