"""Domain rules for /v1. Routes call these functions inside a database transaction."""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import urllib.parse
import urllib.request
from datetime import timedelta

from api.db import (
    DIFFICULTIES,
    JOINING,
    PLATFORMS,
    PUSH_PROVIDERS,
    REVIEWED,
    TASK_STATUS,
    iso,
    new_id,
    now,
    parse_iso,
)
from api.errors import APIError
from logic import EXAM, PASS_MARK

ACCESS_TTL = 3600
REFRESH_TTL = 30 * 24 * 3600
SCORE_MAX = 500
TIERS = (
    ("rookie", 0),
    ("builder", 100),
    ("skilled", 200),
    ("expert", 300),
    ("honored", 400),
)
PAGE = 20


def hash_token(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def classify_email(email):
    email = (email or "").strip().lower()
    if email.endswith("@stu.bau.edu"):
        return "student"
    if email.endswith("@bau.edu"):
        return "staff"
    return None


def admin_emails():
    raw = os.environ.get("BAU_ADMIN_EMAILS", "")
    return {part.strip().lower() for part in raw.split(",") if part.strip()}


def initials_for(name):
    parts = [part for part in (name or "").split() if part]
    if not parts:
        return "?"
    if len(parts) == 1:
        return parts[0][:2].upper()
    return (parts[0][0] + parts[-1][0]).upper()


def status_name(score):
    current = TIERS[0][0]
    for name, minimum in TIERS:
        if score >= minimum:
            current = name
    return current


def _json_list(value):
    if not value:
        return []
    return json.loads(value)


def public_user(conn, row, include_email=True):
    classes = [
        item["code"]
        for item in conn.execute(
            """SELECT c.code FROM enrollments e
               JOIN classes c ON c.id = e.class_id
               WHERE e.user_id = ? ORDER BY c.code""",
            (row["id"],),
        )
    ]
    payload = {
        "id": row["id"],
        "name": row["name"],
        "initials": row["initials"],
        "avatar": None,
        "bio": row["bio"],
        "role": row["role"],
        "department": row["department"],
        "skills": _json_list(row["skills"]),
        "classes": classes,
    }
    if include_email:
        payload["email"] = row["email"]
    return payload


def entitlement_of(row):
    return {"tier": row["tier"], "expires_at": row["tier_expires_at"]}


def config_payload():
    banner = os.environ.get("BAU_MAINTENANCE", "").strip()
    return {
        "min_supported": {
            "ios": os.environ.get("MIN_IOS", "1.0.0"),
            "macos": os.environ.get("MIN_MACOS", "1.0.0"),
            "android": os.environ.get("MIN_ANDROID", "1.0.0"),
            "windows": os.environ.get("MIN_WINDOWS", "1.0.0"),
            "linux": os.environ.get("MIN_LINUX", "1.0.0"),
        },
        "features": {
            "billing": False,
            "mentor": True,
            "github": bool(os.environ.get("GITHUB_CLIENT_ID")),
            "push": True,
        },
        "maintenance": {"message": banner} if banner else None,
    }


def _device(body):
    device = body.get("device") if isinstance(body, dict) else None
    if not isinstance(device, dict):
        raise APIError(400, "invalid_body", "device.platform and device.app_version are required.")
    platform = device.get("platform")
    version = (device.get("app_version") or "").strip()
    if platform not in PLATFORMS:
        raise APIError(400, "invalid_body", "device.platform must be ios, macos, android, windows, or linux.")
    if not version or len(version) > 40:
        raise APIError(400, "invalid_body", "device.app_version is required.")
    return platform, version


def issue_session(conn, user, platform, app_version):
    access = secrets.token_urlsafe(32)
    refresh = secrets.token_urlsafe(32)
    moment = now()
    conn.execute(
        """INSERT INTO sessions (
            id, user_id, access_hash, refresh_hash, previous_refresh_hash,
            access_expires, refresh_expires, platform, app_version, created_at, revoked_at
        ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, NULL)""",
        (
            new_id(),
            user["id"],
            hash_token(access),
            hash_token(refresh),
            iso(moment + timedelta(seconds=ACCESS_TTL)),
            iso(moment + timedelta(seconds=REFRESH_TTL)),
            platform,
            app_version,
            iso(moment),
        ),
    )
    return {
        "access_token": access,
        "refresh_token": refresh,
        "expires_in": ACCESS_TTL,
        "user": public_user(conn, user),
        "entitlement": entitlement_of(user),
    }


def upsert_from_email(conn, email, name):
    email = email.strip().lower()
    role = classify_email(email)
    if role is None:
        raise APIError(
            403,
            "domain_not_allowed",
            "Sign-in is limited to @stu.bau.edu and @bau.edu for now.",
        )
    if email in admin_emails():
        role = "admin"
    row = conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
    if row:
        if row["role"] != "lead" and email in admin_emails() and row["role"] != "admin":
            conn.execute("UPDATE users SET role = 'admin' WHERE id = ?", (row["id"],))
            row = conn.execute("SELECT * FROM users WHERE id = ?", (row["id"],)).fetchone()
        return row
    user_id = new_id()
    conn.execute(
        """INSERT INTO users (
            id, email, name, initials, bio, role, department, skills, score, earnings,
            approved_projects, tier, tier_expires_at, github_login, github_token, created_at
        ) VALUES (?, ?, ?, ?, '', ?, NULL, '[]', 0, 0, 0, 'bau_free', NULL, NULL, NULL, ?)""",
        (user_id, email, name.strip() or email, initials_for(name or email), role, iso(now())),
    )
    return conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()


def microsoft_identity(code, code_verifier, redirect_uri):
    client_id = os.environ.get("MICROSOFT_CLIENT_ID", "").strip()
    if not client_id:
        raise APIError(503, "microsoft_not_configured", "Microsoft sign-in is not configured on this server.")
    tenant = os.environ.get("MICROSOFT_TENANT", "common").strip() or "common"
    form = {
        "client_id": client_id,
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": redirect_uri,
        "code_verifier": code_verifier,
        "scope": "openid profile email User.Read",
    }
    secret = os.environ.get("MICROSOFT_CLIENT_SECRET", "").strip()
    if secret:
        form["client_secret"] = secret
    token_url = f"https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token"
    try:
        request = urllib.request.Request(
            token_url,
            data=urllib.parse.urlencode(form).encode(),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        with urllib.request.urlopen(request, timeout=15) as response:
            token = json.loads(response.read().decode())
        graph = urllib.request.Request(
            "https://graph.microsoft.com/v1.0/me",
            headers={"Authorization": f"Bearer {token['access_token']}"},
        )
        with urllib.request.urlopen(graph, timeout=15) as response:
            profile = json.loads(response.read().decode())
    except APIError:
        raise
    except Exception as exc:
        raise APIError(401, "microsoft_rejected", "Microsoft did not accept that sign-in code.") from exc
    email = (profile.get("mail") or profile.get("userPrincipalName") or "").strip()
    name = profile.get("displayName") or email
    if not email:
        raise APIError(401, "microsoft_rejected", "Microsoft did not return an email address.")
    return email, name


def exchange(conn, body, identity=None):
    if not isinstance(body, dict):
        raise APIError(400, "invalid_body", "Send a JSON object.")
    code = (body.get("code") or "").strip()
    verifier = (body.get("code_verifier") or "").strip()
    redirect_uri = (body.get("redirect_uri") or "").strip()
    if not code or not verifier or not redirect_uri:
        raise APIError(400, "invalid_body", "code, code_verifier, and redirect_uri are required.")
    platform, version = _device(body)
    if identity is None:
        email, name = microsoft_identity(code, verifier, redirect_uri)
    else:
        email, name = identity
    user = upsert_from_email(conn, email, name)
    return issue_session(conn, user, platform, version)


def user_from_access(conn, header):
    if not header or not header.lower().startswith("bearer "):
        raise APIError(401, "unauthorized", "Sign in again.")
    token = header.split(" ", 1)[1].strip()
    row = conn.execute(
        """SELECT s.*, u.email FROM sessions s
           JOIN users u ON u.id = s.user_id
           WHERE s.access_hash = ? AND s.revoked_at IS NULL""",
        (hash_token(token),),
    ).fetchone()
    if not row or parse_iso(row["access_expires"]) <= now():
        raise APIError(401, "unauthorized", "Sign in again.")
    return conn.execute("SELECT * FROM users WHERE id = ?", (row["user_id"],)).fetchone(), row


def refresh(conn, body):
    token = (body or {}).get("refresh_token") if isinstance(body, dict) else None
    if not token:
        raise APIError(400, "invalid_body", "refresh_token is required.")
    digest = hash_token(token)
    current = conn.execute(
        "SELECT * FROM sessions WHERE refresh_hash = ?",
        (digest,),
    ).fetchone()
    reused = conn.execute(
        "SELECT * FROM sessions WHERE previous_refresh_hash = ?",
        (digest,),
    ).fetchone()
    if reused:
        conn.execute(
            "UPDATE sessions SET revoked_at = ? WHERE id = ?",
            (iso(now()), reused["id"]),
        )
        raise APIError(401, "refresh_reused", "That refresh token was already used. Sign in again.")
    if not current or current["revoked_at"] or parse_iso(current["refresh_expires"]) <= now():
        raise APIError(401, "unauthorized", "Sign in again.")
    access = secrets.token_urlsafe(32)
    refresh_token = secrets.token_urlsafe(32)
    moment = now()
    conn.execute(
        """UPDATE sessions
           SET previous_refresh_hash = refresh_hash,
               access_hash = ?, refresh_hash = ?, access_expires = ?, refresh_expires = ?
           WHERE id = ?""",
        (
            hash_token(access),
            hash_token(refresh_token),
            iso(moment + timedelta(seconds=ACCESS_TTL)),
            iso(moment + timedelta(seconds=REFRESH_TTL)),
            current["id"],
        ),
    )
    user = conn.execute("SELECT * FROM users WHERE id = ?", (current["user_id"],)).fetchone()
    return {
        "access_token": access,
        "refresh_token": refresh_token,
        "expires_in": ACCESS_TTL,
        "user": public_user(conn, user),
        "entitlement": entitlement_of(user),
    }


def logout(conn, session):
    conn.execute("UPDATE sessions SET revoked_at = ? WHERE id = ?", (iso(now()), session["id"]))
    return None


def list_sessions(conn, user, current_id):
    rows = conn.execute(
        """SELECT * FROM sessions WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at DESC""",
        (user["id"],),
    ).fetchall()
    return {
        "sessions": [
            {
                "id": row["id"],
                "platform": row["platform"],
                "app_version": row["app_version"],
                "created_at": row["created_at"],
                "current": row["id"] == current_id,
            }
            for row in rows
        ]
    }


def delete_session(conn, user, session_id):
    row = conn.execute(
        "SELECT * FROM sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
        (session_id, user["id"]),
    ).fetchone()
    if not row:
        raise APIError(404, "not_found", "That session is not active.")
    conn.execute("UPDATE sessions SET revoked_at = ? WHERE id = ?", (iso(now()), session_id))
    return None


def update_me(conn, user, body):
    if not isinstance(body, dict):
        raise APIError(400, "invalid_body", "Send a JSON object.")
    bio = user["bio"]
    skills = _json_list(user["skills"])
    if "bio" in body:
        bio = (body.get("bio") or "").strip()
        if not bio or len(bio) > 280:
            raise APIError(400, "invalid_body", "Bio must be 1 to 280 characters.")
    if "skills" in body:
        raw = body.get("skills")
        if not isinstance(raw, list) or not raw or not all(isinstance(item, str) and item.strip() for item in raw):
            raise APIError(400, "invalid_body", "skills must be a non-empty list of strings.")
        skills = [item.strip() for item in raw][:20]
    conn.execute(
        "UPDATE users SET bio = ?, skills = ? WHERE id = ?",
        (bio, json.dumps(skills), user["id"]),
    )
    return public_user(conn, conn.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone())


def stats_of(user):
    return {
        "score": user["score"],
        "max": SCORE_MAX,
        "status": status_name(user["score"]),
        "tiers": [{"name": name, "min": minimum} for name, minimum in TIERS],
        "earnings_total": user["earnings"],
        "approved_project_count": user["approved_projects"],
    }


def _require_project(conn, project_id):
    row = conn.execute("SELECT * FROM projects WHERE id = ?", (project_id,)).fetchone()
    if not row:
        raise APIError(404, "not_found", "That project is not on the board.")
    return row


def _enrolled(conn, user_id, class_id):
    if not class_id:
        return True
    return (
        conn.execute(
            "SELECT 1 FROM enrollments WHERE user_id = ? AND class_id = ?",
            (user_id, class_id),
        ).fetchone()
        is not None
    )


def eligibility(conn, user, project):
    if user["tier"] == "payment_required":
        return False, "payment_required"
    if project["class_id"] and not project["open_to_all"] and not _enrolled(conn, user["id"], project["class_id"]):
        return False, "class_not_enrolled"
    if user["score"] < project["min_score"]:
        return False, "score_too_low"
    return True, None


def _membership(conn, user_id, project_id):
    return conn.execute(
        "SELECT * FROM memberships WHERE user_id = ? AND project_id = ?",
        (user_id, project_id),
    ).fetchone()


def _bookmarked(conn, user_id, project_id):
    return (
        conn.execute(
            "SELECT 1 FROM bookmarks WHERE user_id = ? AND project_id = ?",
            (user_id, project_id),
        ).fetchone()
        is not None
    )


def min_score_label(score):
    return "Any score" if score <= 0 else f"Score {score}+"


def project_card(row):
    return {
        "id": row["id"],
        "department": row["department"],
        "title": row["title"],
        "bounty": row["bounty"],
        "difficulty": row["difficulty"],
        "min_score": row["min_score"],
        "min_score_label": min_score_label(row["min_score"]),
        "due_at": row["due_at"],
        "urgent": bool(row["urgent"]),
        "art_key": row["art_key"],
        "status": row["status"],
    }


def project_detail(conn, user, row):
    allowed, reason = eligibility(conn, user, row)
    membership = _membership(conn, user["id"], row["id"])
    status = None
    if membership:
        status = "joined" if membership["status"] == "member" else "pending_approval"
    return {
        **project_card(row),
        "description": row["description"],
        "class_id": row["class_id"],
        "repo": {
            "provider": row["repo_provider"],
            "name": row["repo_name"],
            "connected": bool(user["github_token"]),
        },
        "joining": row["joining"],
        "reviewed_by": row["reviewed_by"],
        "reviewer_label": row["reviewer_label"],
        "is_bookmarked": _bookmarked(conn, user["id"], row["id"]),
        "my_membership": None if status is None else {"status": status},
        "eligibility": {"eligible": allowed, "reason": reason},
    }


def _cursor_offset(cursor):
    if not cursor:
        return 0
    try:
        offset = int(cursor)
    except (TypeError, ValueError):
        raise APIError(400, "invalid_cursor", "That cursor is not valid.")
    if offset < 0:
        raise APIError(400, "invalid_cursor", "That cursor is not valid.")
    return offset


def list_projects(conn, user, query):
    clauses = ["status = 'open'"]
    params = []
    difficulty = (query.get("difficulty") or "").lower()
    if difficulty:
        if difficulty not in DIFFICULTIES:
            raise APIError(400, "invalid_query", "difficulty must be easy, medium, or hard.")
        clauses.append("difficulty = ?")
        params.append(difficulty)
    if query.get("department"):
        clauses.append("lower(department) = lower(?)")
        params.append(query["department"])
    if query.get("class_id"):
        clauses.append("class_id = ?")
        params.append(query["class_id"])
    status = query.get("status") or "open"
    if status != "open":
        raise APIError(400, "invalid_query", "status=open is the supported filter.")
    if query.get("q"):
        clauses.append("(lower(title) LIKE ? OR lower(description) LIKE ? OR lower(department) LIKE ?)")
        like = f"%{query['q'].strip().lower()}%"
        params.extend([like, like, like])
    offset = _cursor_offset(query.get("cursor"))
    where = " AND ".join(clauses)
    rows = conn.execute(
        f"SELECT * FROM projects WHERE {where} ORDER BY urgent DESC, title LIMIT ? OFFSET ?",
        (*params, PAGE + 1, offset),
    ).fetchall()
    items = rows[:PAGE]
    next_cursor = str(offset + PAGE) if len(rows) > PAGE else None
    return {"items": [project_detail(conn, user, row) for row in items], "next_cursor": next_cursor}


def home(conn, user):
    urgent = conn.execute(
        "SELECT * FROM projects WHERE status = 'open' AND urgent = 1 ORDER BY due_at"
    ).fetchall()
    counts = {"easy": 0, "medium": 0, "hard": 0}
    for row in conn.execute(
        "SELECT difficulty, COUNT(*) AS n FROM projects WHERE status = 'open' GROUP BY difficulty"
    ):
        counts[row["difficulty"]] = row["n"]
    preview = conn.execute(
        "SELECT * FROM projects WHERE status = 'open' ORDER BY urgent DESC, title LIMIT 6"
    ).fetchall()
    first = user["name"].split()[0] if user["name"] else user["email"]
    return {
        "greeting_name": first,
        "urgent": [urgent_card(row) for row in urgent],
        "difficulty_counts": counts,
        "open_projects": [project_card(row) for row in preview],
    }


def urgent_card(row):
    return {
        "id": row["id"],
        "title": row["title"],
        "bounty": row["bounty"],
        "difficulty": row["difficulty"],
        "min_score_label": min_score_label(row["min_score"]),
        "department": row["department"],
        "art_key": row["art_key"],
    }


def urgent_inquiries(conn):
    rows = conn.execute(
        """SELECT p.* FROM projects p
           WHERE p.status = 'open' AND p.urgent = 1
           ORDER BY p.due_at"""
    ).fetchall()
    return {"items": [urgent_card(row) for row in rows]}


def join_project(conn, user, project_id):
    project = _require_project(conn, project_id)
    if project["status"] != "open":
        raise APIError(409, "project_closed", "This project is not open.")
    existing = _membership(conn, user["id"], project_id)
    if existing and existing["status"] == "member":
        return {"result": "joined"}
    if existing and existing["status"] == "pending":
        return {"result": "pending_approval"}
    allowed, reason = eligibility(conn, user, project)
    if not allowed:
        message = {
            "payment_required": "A paid plan is required before joining.",
            "score_too_low": f"This project needs a score of {project['min_score']}. Yours is {user['score']}.",
            "class_not_enrolled": "This project is for students enrolled in its class.",
        }[reason]
        raise APIError(403, reason, message)
    status = "member" if project["joining"] == "everyone" else "pending"
    conn.execute(
        "INSERT INTO memberships (user_id, project_id, status, created_at) VALUES (?, ?, ?, ?)",
        (user["id"], project_id, status, iso(now())),
    )
    if status == "member":
        for task in json.loads(project["template_tasks"]):
            conn.execute(
                """INSERT INTO tasks (id, project_id, user_id, title, status, lane, due_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (new_id(), project_id, user["id"], task["title"], task["status"], task["lane"], project["due_at"]),
            )
        _notify(conn, user["id"], f"You’re in “{project['title']}”.")
        return {"result": "joined"}
    _notify(conn, user["id"], f"Request sent for “{project['title']}”. Waiting on {project['reviewer_label']}.")
    return {"result": "pending_approval"}


def leave_project(conn, user, project_id):
    _require_project(conn, project_id)
    membership = _membership(conn, user["id"], project_id)
    if not membership:
        raise APIError(404, "not_found", "You are not in this project.")
    conn.execute("DELETE FROM tasks WHERE user_id = ? AND project_id = ?", (user["id"], project_id))
    conn.execute("DELETE FROM memberships WHERE user_id = ? AND project_id = ?", (user["id"], project_id))
    return None


def bookmark(conn, user, project_id, on):
    _require_project(conn, project_id)
    if on:
        conn.execute(
            "INSERT OR IGNORE INTO bookmarks (user_id, project_id) VALUES (?, ?)",
            (user["id"], project_id),
        )
    else:
        conn.execute(
            "DELETE FROM bookmarks WHERE user_id = ? AND project_id = ?",
            (user["id"], project_id),
        )
    return None


def my_bookmarks(conn, user):
    rows = conn.execute(
        """SELECT p.* FROM bookmarks b JOIN projects p ON p.id = b.project_id
           WHERE b.user_id = ? ORDER BY p.title""",
        (user["id"],),
    ).fetchall()
    return {"items": [project_detail(conn, user, row) for row in rows]}


def search(conn, user, q):
    q = (q or "").strip()
    if not q:
        raise APIError(400, "invalid_query", "q is required.")
    like = f"%{q.lower()}%"
    projects = conn.execute(
        """SELECT * FROM projects
           WHERE status = 'open' AND (lower(title) LIKE ? OR lower(description) LIKE ? OR lower(department) LIKE ?)
           ORDER BY title LIMIT 20""",
        (like, like, like),
    ).fetchall()
    classes = conn.execute(
        """SELECT * FROM classes
           WHERE lower(code) LIKE ? OR lower(name) LIKE ? OR lower(instructor) LIKE ?
           ORDER BY code LIMIT 20""",
        (like, like, like),
    ).fetchall()
    inquiries = conn.execute(
        """SELECT * FROM inquiries
           WHERE status = 'published' AND (lower(title) LIKE ? OR lower(description) LIKE ? OR lower(department) LIKE ?)
           ORDER BY created_at DESC LIMIT 20""",
        (like, like, like),
    ).fetchall()
    return {
        "projects": [project_card(row) for row in projects],
        "classes": [_class_payload(conn, row) for row in classes],
        "inquiries": [_inquiry_payload(row) for row in inquiries],
    }


def _class_payload(conn, row):
    count = conn.execute(
        "SELECT COUNT(*) AS n FROM projects WHERE class_id = ? AND status = 'open'",
        (row["id"],),
    ).fetchone()["n"]
    return {
        "id": row["id"],
        "code": row["code"],
        "name": row["name"],
        "term": row["term"],
        "instructor": row["instructor"],
        "open_project_count": count,
    }


def my_classes(conn, user):
    rows = conn.execute(
        """SELECT c.* FROM enrollments e JOIN classes c ON c.id = e.class_id
           WHERE e.user_id = ? ORDER BY c.code""",
        (user["id"],),
    ).fetchall()
    return {"items": [_class_payload(conn, row) for row in rows]}


def class_by_id(conn, class_id):
    row = conn.execute("SELECT * FROM classes WHERE id = ? OR lower(code) = lower(?)", (class_id, class_id)).fetchone()
    if not row:
        raise APIError(404, "not_found", "That class is not listed.")
    return row


def class_projects(conn, user, class_id):
    row = class_by_id(conn, class_id)
    projects = conn.execute(
        "SELECT * FROM projects WHERE class_id = ? AND status = 'open' ORDER BY title",
        (row["id"],),
    ).fetchall()
    return {"class": _class_payload(conn, row), "items": [project_detail(conn, user, item) for item in projects]}


def _staff(user):
    if user["role"] not in ("staff", "admin"):
        raise APIError(403, "role_required", "Only @bau.edu staff can post inquiries.")


def _admin(user):
    if user["role"] != "admin":
        raise APIError(403, "role_required", "An admin has to do that.")


def _inquiry_fields(body, partial=False):
    if not isinstance(body, dict):
        raise APIError(400, "invalid_body", "Send a JSON object.")
    required = (
        "title", "description", "department", "difficulty", "bounty", "is_urgent",
        "min_score", "joining", "reviewer_type",
    )
    if not partial:
        missing = [key for key in required if key not in body]
        if missing:
            raise APIError(400, "invalid_body", f"Missing {', '.join(missing)}.")
    data = {}
    if "title" in body or not partial:
        title = (body.get("title") or "").strip()
        if not title or len(title) > 140:
            raise APIError(400, "invalid_body", "Title is required.")
        data["title"] = title
    if "description" in body or not partial:
        description = (body.get("description") or "").strip()
        if not description:
            raise APIError(400, "invalid_body", "Description is required.")
        data["description"] = description
    if "department" in body or not partial:
        department = (body.get("department") or "").strip()
        if not department:
            raise APIError(400, "invalid_body", "Department is required.")
        data["department"] = department
    if "difficulty" in body or not partial:
        difficulty = (body.get("difficulty") or "").lower()
        if difficulty not in DIFFICULTIES:
            raise APIError(400, "invalid_body", "difficulty must be easy, medium, or hard.")
        data["difficulty"] = difficulty
    if "bounty" in body or not partial:
        bounty = body.get("bounty")
        if not isinstance(bounty, int) or isinstance(bounty, bool) or bounty < 0 or bounty > 500:
            raise APIError(400, "invalid_body", "bounty must be a whole number from 0 to 500.")
        data["bounty"] = bounty
    if "is_urgent" in body or not partial:
        if not isinstance(body.get("is_urgent"), bool):
            raise APIError(400, "invalid_body", "is_urgent must be true or false.")
        data["is_urgent"] = 1 if body["is_urgent"] else 0
    if "due_at" in body:
        data["due_at"] = body.get("due_at")
    if "repo_url" in body:
        data["repo_url"] = (body.get("repo_url") or "").strip() or None
    if "min_score" in body or not partial:
        min_score = body.get("min_score")
        if not isinstance(min_score, int) or isinstance(min_score, bool) or min_score < 0 or min_score > SCORE_MAX:
            raise APIError(400, "invalid_body", "min_score must be a whole number from 0 to 500.")
        data["min_score"] = min_score
    if "joining" in body or not partial:
        joining = body.get("joining")
        if joining not in JOINING:
            raise APIError(400, "invalid_body", "joining must be everyone or approval.")
        data["joining"] = joining
    if "reviewer_type" in body or not partial:
        reviewer = body.get("reviewer_type")
        if reviewer not in REVIEWED:
            raise APIError(400, "invalid_body", "reviewer_type must be ai_then_lead or professor.")
        data["reviewer_type"] = reviewer
    return data


def _inquiry_payload(row):
    return {
        "id": row["id"],
        "title": row["title"],
        "description": row["description"],
        "department": row["department"],
        "difficulty": row["difficulty"],
        "bounty": row["bounty"],
        "is_urgent": bool(row["is_urgent"]),
        "due_at": row["due_at"],
        "repo_url": row["repo_url"],
        "min_score": row["min_score"],
        "joining": row["joining"],
        "reviewer_type": row["reviewer_type"],
        "status": row["status"],
        "project_id": row["project_id"],
        "created_at": row["created_at"],
    }


def create_inquiry(conn, user, body):
    _staff(user)
    fields = _inquiry_fields(body)
    inquiry_id = new_id()
    conn.execute(
        """INSERT INTO inquiries (
            id, author_id, title, description, department, difficulty, bounty, is_urgent,
            due_at, repo_url, min_score, joining, reviewer_type, status, project_id, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'submitted', NULL, ?)""",
        (
            inquiry_id, user["id"], fields["title"], fields["description"], fields["department"],
            fields["difficulty"], fields["bounty"], fields["is_urgent"], fields.get("due_at"),
            fields.get("repo_url"), fields["min_score"], fields["joining"], fields["reviewer_type"],
            iso(now()),
        ),
    )
    return _inquiry_payload(conn.execute("SELECT * FROM inquiries WHERE id = ?", (inquiry_id,)).fetchone())


def my_inquiries(conn, user):
    _staff(user)
    rows = conn.execute(
        "SELECT * FROM inquiries WHERE author_id = ? ORDER BY created_at DESC",
        (user["id"],),
    ).fetchall()
    return {"items": [_inquiry_payload(row) for row in rows]}


def _own_inquiry(conn, user, inquiry_id):
    row = conn.execute("SELECT * FROM inquiries WHERE id = ?", (inquiry_id,)).fetchone()
    if not row:
        raise APIError(404, "not_found", "That inquiry does not exist.")
    if user["role"] != "admin" and row["author_id"] != user["id"]:
        raise APIError(403, "role_required", "That inquiry belongs to someone else.")
    if user["role"] not in ("staff", "admin"):
        raise APIError(403, "role_required", "Only @bau.edu staff can edit inquiries.")
    return row


def update_inquiry(conn, user, inquiry_id, body):
    row = _own_inquiry(conn, user, inquiry_id)
    if row["status"] != "submitted":
        raise APIError(409, "inquiry_locked", "Only a submitted inquiry can be edited.")
    fields = _inquiry_fields(body, partial=True)
    if not fields:
        return _inquiry_payload(row)
    assignments = ", ".join(f"{key} = ?" for key in fields)
    conn.execute(
        f"UPDATE inquiries SET {assignments} WHERE id = ?",
        (*fields.values(), inquiry_id),
    )
    return _inquiry_payload(conn.execute("SELECT * FROM inquiries WHERE id = ?", (inquiry_id,)).fetchone())


def delete_inquiry(conn, user, inquiry_id):
    row = _own_inquiry(conn, user, inquiry_id)
    if row["status"] != "submitted":
        raise APIError(409, "inquiry_locked", "Only a submitted inquiry can be deleted.")
    conn.execute("DELETE FROM inquiries WHERE id = ?", (inquiry_id,))
    return None


def approve_inquiry(conn, user, inquiry_id):
    _admin(user)
    row = conn.execute("SELECT * FROM inquiries WHERE id = ?", (inquiry_id,)).fetchone()
    if not row:
        raise APIError(404, "not_found", "That inquiry does not exist.")
    if row["status"] != "submitted":
        raise APIError(409, "invalid_state", "Only a submitted inquiry can be approved.")
    conn.execute("UPDATE inquiries SET status = 'approved' WHERE id = ?", (inquiry_id,))
    return _inquiry_payload(conn.execute("SELECT * FROM inquiries WHERE id = ?", (inquiry_id,)).fetchone())


def publish_inquiry(conn, user, inquiry_id):
    _admin(user)
    row = conn.execute("SELECT * FROM inquiries WHERE id = ?", (inquiry_id,)).fetchone()
    if not row:
        raise APIError(404, "not_found", "That inquiry does not exist.")
    if row["status"] != "approved":
        raise APIError(409, "invalid_state", "Approve the inquiry before publishing it.")
    project_id = new_id()
    reviewer = "professor of the class" if row["reviewer_type"] == "professor" else "dev lead"
    conn.execute(
        """INSERT INTO projects (
            id, department, class_id, title, description, bounty, difficulty, min_score, due_at,
            urgent, open_to_all, joining, reviewed_by, reviewer_label, repo_provider, repo_name,
            art_key, status, inquiry_id, template_tasks
        ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'github', NULL, ?, 'open', ?, '[]')""",
        (
            project_id, row["department"], row["title"], row["description"], row["bounty"],
            row["difficulty"], row["min_score"], row["due_at"], row["is_urgent"], row["joining"],
            row["reviewer_type"], reviewer, "inquiry", row["id"],
        ),
    )
    conn.execute(
        "UPDATE inquiries SET status = 'published', project_id = ? WHERE id = ?",
        (project_id, inquiry_id),
    )
    return {
        "inquiry": _inquiry_payload(conn.execute("SELECT * FROM inquiries WHERE id = ?", (inquiry_id,)).fetchone()),
        "project_id": project_id,
    }


def my_work(conn, user):
    members = conn.execute(
        """SELECT p.*, m.status,
                  (SELECT COUNT(*) FROM tasks t WHERE t.project_id = p.id AND t.user_id = ? AND t.lane = 'assigned' AND t.status != 'done') AS open_tasks,
                  (SELECT COUNT(*) FROM tasks t WHERE t.project_id = p.id AND t.user_id = ?) AS task_count
           FROM memberships m JOIN projects p ON p.id = m.project_id
           WHERE m.user_id = ? AND m.status = 'member'
           ORDER BY p.title""",
        (user["id"], user["id"], user["id"]),
    ).fetchall()
    pending = conn.execute(
        """SELECT p.*, m.status FROM memberships m JOIN projects p ON p.id = m.project_id
           WHERE m.user_id = ? AND m.status = 'pending' ORDER BY p.title""",
        (user["id"],),
    ).fetchall()
    return {
        "in_progress": [
            {
                "id": row["id"],
                "title": row["title"],
                "department": row["department"],
                "bounty": row["bounty"],
                "task_count": row["task_count"],
                "open_task_count": row["open_tasks"],
            }
            for row in members
        ],
        "awaiting_approval": [
            {
                "id": row["id"],
                "title": row["title"],
                "status": "pending_approval",
                "reviewer_label": row["reviewer_label"],
                "bounty": row["bounty"],
            }
            for row in pending
        ],
    }


def list_tasks(conn, user, project_id, assignee):
    _require_member(conn, user, project_id)
    if assignee not in (None, "", "me"):
        raise APIError(400, "invalid_query", "assignee=me is the supported filter.")
    rows = conn.execute(
        "SELECT * FROM tasks WHERE project_id = ? AND user_id = ? ORDER BY lane, title",
        (project_id, user["id"]),
    ).fetchall()
    return {"items": [_task_payload(row) for row in rows]}


def _task_payload(row):
    return {
        "id": row["id"],
        "project_id": row["project_id"],
        "title": row["title"],
        "status": row["status"],
        "lane": row["lane"],
        "due_at": row["due_at"],
    }


def _require_member(conn, user, project_id):
    _require_project(conn, project_id)
    membership = _membership(conn, user["id"], project_id)
    if not membership or membership["status"] != "member":
        raise APIError(403, "not_a_member", "Join the project before using its workspace.")
    return membership


def update_task(conn, user, task_id, body):
    row = conn.execute("SELECT * FROM tasks WHERE id = ? AND user_id = ?", (task_id, user["id"])).fetchone()
    if not row:
        raise APIError(404, "not_found", "That task is not yours.")
    status = (body or {}).get("status") if isinstance(body, dict) else None
    if status not in TASK_STATUS:
        raise APIError(400, "invalid_body", "status must be todo, in_progress, or done.")
    conn.execute("UPDATE tasks SET status = ? WHERE id = ?", (status, task_id))
    return _task_payload(conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone())


def changes(conn, user, project_id):
    project = _require_project(conn, project_id)
    _require_member(conn, user, project_id)
    rows = conn.execute(
        """SELECT * FROM submissions WHERE project_id = ? AND user_id = ? ORDER BY created_at DESC""",
        (project_id, user["id"]),
    ).fetchall()
    branches = {}
    for row in rows:
        branches.setdefault(row["branch"], []).append(
            {
                "id": row["id"],
                "message": row["branch"],
                "state": row["lead_status"],
                "diff_url": row["diff_url"],
                "created_at": row["created_at"],
            }
        )
    github = []
    if user["github_token"] and project["repo_name"]:
        github = _github_commits(user["github_token"], project["repo_name"])
    return {
        "connected": bool(user["github_token"]),
        "repo": project["repo_name"],
        "branches": [{"name": name, "commits": commits} for name, commits in branches.items()],
        "github_commits": github,
    }


def _github_commits(token, repo):
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/commits?per_page=10",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "bau-build",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = json.loads(response.read().decode())
    except Exception:
        return []
    return [
        {
            "sha": item.get("sha"),
            "message": (item.get("commit") or {}).get("message"),
            "url": item.get("html_url"),
        }
        for item in payload
        if isinstance(item, dict)
    ]


def github_status(user):
    return {"connected": bool(user["github_token"]), "login": user["github_login"]}


def github_connect(conn, user):
    client_id = os.environ.get("GITHUB_CLIENT_ID", "").strip()
    if not client_id:
        raise APIError(503, "github_not_configured", "GitHub sign-in is not configured on this server.")
    state = secrets.token_urlsafe(24)
    conn.execute(
        "INSERT INTO oauth_states (state, user_id, created_at) VALUES (?, ?, ?)",
        (state, user["id"], iso(now())),
    )
    redirect = os.environ.get("GITHUB_REDIRECT_URI", "https://api.vesperconnect.org/v1/integrations/github/callback")
    query = urllib.parse.urlencode(
        {
            "client_id": client_id,
            "redirect_uri": redirect,
            "scope": "read:user repo",
            "state": state,
        }
    )
    return {"authorize_url": f"https://github.com/login/oauth/authorize?{query}"}


def github_disconnect(conn, user):
    conn.execute(
        "UPDATE users SET github_login = NULL, github_token = NULL WHERE id = ?",
        (user["id"],),
    )
    return {"connected": False, "login": None}


def github_callback(conn, query):
    state = query.get("state")
    code = query.get("code")
    row = conn.execute("SELECT * FROM oauth_states WHERE state = ?", (state,)).fetchone()
    if not row or not code:
        raise APIError(400, "invalid_oauth", "The GitHub callback is missing a valid state.")
    client_id = os.environ.get("GITHUB_CLIENT_ID", "").strip()
    secret = os.environ.get("GITHUB_CLIENT_SECRET", "").strip()
    if not client_id or not secret:
        raise APIError(503, "github_not_configured", "GitHub sign-in is not configured on this server.")
    form = urllib.parse.urlencode(
        {
            "client_id": client_id,
            "client_secret": secret,
            "code": code,
            "redirect_uri": os.environ.get(
                "GITHUB_REDIRECT_URI", "https://api.vesperconnect.org/v1/integrations/github/callback"
            ),
        }
    ).encode()
    request = urllib.request.Request(
        "https://github.com/login/oauth/access_token",
        data=form,
        headers={"Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            token = json.loads(response.read().decode())
        access = token["access_token"]
        profile_req = urllib.request.Request(
            "https://api.github.com/user",
            headers={"Authorization": f"Bearer {access}", "User-Agent": "bau-build"},
        )
        with urllib.request.urlopen(profile_req, timeout=15) as response:
            profile = json.loads(response.read().decode())
    except Exception as exc:
        raise APIError(401, "github_rejected", "GitHub did not accept that connection.") from exc
    conn.execute("DELETE FROM oauth_states WHERE state = ?", (state,))
    conn.execute(
        "UPDATE users SET github_login = ?, github_token = ? WHERE id = ?",
        (profile.get("login"), access, row["user_id"]),
    )
    return {"connected": True, "login": profile.get("login")}


def mentor_history(conn, user, project_id):
    _require_member(conn, user, project_id)
    rows = conn.execute(
        """SELECT * FROM mentor_messages WHERE project_id = ? AND user_id = ? ORDER BY created_at""",
        (project_id, user["id"]),
    ).fetchall()
    return {"items": [{"id": row["id"], "role": row["role"], "text": row["body"], "created_at": row["created_at"]} for row in rows]}


def mentor_reply(text):
    folded = text.casefold()
    if any(word in folded for word in ("date", "string", "sort")):
        return "Compare Date objects, or timestamps from those objects. String order only matches calendar order when every part is zero-padded."
    if "test" in folded:
        return "Add one case for yesterday, one for today, and one for a blank field. Assert the message the form shows, not only true or false."
    if any(word in folded for word in ("screen", "reader", "aria", "announce")):
        return "Put the message in the form and give that node role=\"alert\", or aria-live=\"assertive\", so a screen reader speaks it when it appears."
    if any(word in folded for word in ("csv", "attendance", "export")):
        return "Write one row of headers, then one row per person. Keep the club name in its own column so staff can filter it."
    return "Say what you expected and what the code did instead. You write the change, then push the branch."


def post_mentor(conn, user, project_id, body):
    _require_member(conn, user, project_id)
    text = ((body or {}).get("text") if isinstance(body, dict) else "") or ""
    text = text.strip()
    if not text or len(text) > 500:
        raise APIError(400, "invalid_body", "Ask a question of 1 to 500 characters.")
    moment = iso(now())
    student_id = new_id()
    reply = mentor_reply(text)
    reply_id = new_id()
    conn.execute(
        "INSERT INTO mentor_messages (id, project_id, user_id, role, body, created_at) VALUES (?, ?, ?, 'student', ?, ?)",
        (student_id, project_id, user["id"], text, moment),
    )
    conn.execute(
        "INSERT INTO mentor_messages (id, project_id, user_id, role, body, created_at) VALUES (?, ?, ?, 'mentor', ?, ?)",
        (reply_id, project_id, user["id"], reply, moment),
    )
    return reply_id, reply


def ai_findings(branch):
    folded = branch.casefold()
    findings = [{"kind": "pass", "text": "The branch is attached to this project and ready for a dev lead."}]
    if any(word in folded for word in ("date", "fix", "valid")):
        findings.append({"kind": "warn", "text": "Worth a look: announce the error to screen readers."})
    else:
        findings.append({"kind": "warn", "text": "Worth a look: add a test for the failure case."})
    return findings


def create_submission(conn, user, project_id, body):
    project = _require_project(conn, project_id)
    _require_member(conn, user, project_id)
    branch = ((body or {}).get("branch") if isinstance(body, dict) else "") or ""
    branch = branch.strip()
    if not branch or len(branch) > 120 or " " in branch:
        raise APIError(400, "invalid_body", "branch is required and cannot contain spaces.")
    findings = ai_findings(branch)
    submission_id = new_id()
    repo = project["repo_name"] or project_id
    diff_url = f"https://github.com/{repo}/compare/{urllib.parse.quote(branch)}"
    conn.execute(
        """INSERT INTO submissions (
            id, project_id, user_id, branch, ai_findings, lead_status, reviewer_id, comment,
            diff_url, bounty_amount, paid_at, created_at
        ) VALUES (?, ?, ?, ?, ?, 'pending', NULL, NULL, ?, ?, NULL, ?)""",
        (
            submission_id, project_id, user["id"], branch, json.dumps(findings),
            diff_url, project["bounty"], iso(now()),
        ),
    )
    _notify(conn, user["id"], f"AI review finished on {branch}. A dev lead has the summary.")
    return submission_detail(conn, conn.execute("SELECT * FROM submissions WHERE id = ?", (submission_id,)).fetchone())


def _submission_steps(conn, row):
    reviewer = None
    if row["reviewer_id"]:
        person = conn.execute("SELECT id, name, initials, role FROM users WHERE id = ?", (row["reviewer_id"],)).fetchone()
        if person:
            reviewer = {"id": person["id"], "name": person["name"], "initials": person["initials"]}
    lead_status = {"pending": "pending", "approved": "approved", "sent_back": "sent_back"}[row["lead_status"]]
    return [
        {"step": "pushed", "branch": row["branch"]},
        {"step": "ai_review", "status": "complete", "findings": json.loads(row["ai_findings"])},
        {"step": "lead_decision", "status": lead_status, "reviewer": reviewer, "comment": row["comment"]},
        {"step": "bounty", "amount": row["bounty_amount"], "paid_at": row["paid_at"]},
    ]


def submission_detail(conn, row):
    return {
        "id": row["id"],
        "project_id": row["project_id"],
        "diff_url": row["diff_url"],
        "created_at": row["created_at"],
        "steps": _submission_steps(conn, row),
    }


def my_submissions(conn, user):
    rows = conn.execute(
        "SELECT * FROM submissions WHERE user_id = ? ORDER BY created_at DESC",
        (user["id"],),
    ).fetchall()
    return {"items": [submission_detail(conn, row) for row in rows]}


def get_submission(conn, user, submission_id):
    row = conn.execute("SELECT * FROM submissions WHERE id = ?", (submission_id,)).fetchone()
    if not row:
        raise APIError(404, "not_found", "That submission does not exist.")
    if row["user_id"] != user["id"] and user["role"] not in ("lead", "admin"):
        raise APIError(403, "role_required", "That submission is not yours.")
    return submission_detail(conn, row)


def lead_queue(conn, user):
    if user["role"] not in ("lead", "admin"):
        raise APIError(403, "role_required", "Only a lead can open the review queue.")
    rows = conn.execute(
        "SELECT * FROM submissions WHERE lead_status = 'pending' ORDER BY created_at"
    ).fetchall()
    return {"items": [submission_detail(conn, row) for row in rows]}


def decide_submission(conn, user, submission_id, body):
    if user["role"] not in ("lead", "admin"):
        raise APIError(403, "role_required", "Only a lead can decide a review.")
    action = (body or {}).get("action") if isinstance(body, dict) else None
    comment = ((body or {}).get("comment") if isinstance(body, dict) else "") or ""
    if action not in ("approve", "send_back"):
        raise APIError(400, "invalid_body", "action must be approve or send_back.")
    row = conn.execute("SELECT * FROM submissions WHERE id = ?", (submission_id,)).fetchone()
    if not row:
        raise APIError(404, "not_found", "That submission does not exist.")
    if row["lead_status"] != "pending":
        raise APIError(409, "already_decided", "This submission already has a decision.")
    if row["user_id"] == user["id"]:
        raise APIError(403, "role_required", "A lead cannot decide their own submission.")
    if action == "send_back":
        conn.execute(
            "UPDATE submissions SET lead_status = 'sent_back', reviewer_id = ?, comment = ? WHERE id = ?",
            (user["id"], comment.strip() or None, submission_id),
        )
        _notify(conn, row["user_id"], "A dev lead sent the branch back with notes.")
    else:
        conn.execute(
            "UPDATE submissions SET lead_status = 'approved', reviewer_id = ?, comment = ? WHERE id = ?",
            (user["id"], comment.strip() or None, submission_id),
        )
        _pay_if_ready(conn, row)
    return submission_detail(conn, conn.execute("SELECT * FROM submissions WHERE id = ?", (submission_id,)).fetchone())


def _pay_if_ready(conn, submission):
    open_waiting = conn.execute(
        """SELECT 1 FROM submissions
           WHERE project_id = ? AND user_id = ? AND lead_status != 'approved'""",
        (submission["project_id"], submission["user_id"]),
    ).fetchone()
    if open_waiting:
        return
    already = conn.execute(
        """SELECT 1 FROM submissions
           WHERE project_id = ? AND user_id = ? AND paid_at IS NOT NULL""",
        (submission["project_id"], submission["user_id"]),
    ).fetchone()
    if already:
        return
    project = conn.execute("SELECT * FROM projects WHERE id = ?", (submission["project_id"],)).fetchone()
    paid = iso(now())
    conn.execute(
        """UPDATE submissions SET paid_at = ?, bounty_amount = ?
           WHERE project_id = ? AND user_id = ? AND lead_status = 'approved' AND paid_at IS NULL""",
        (paid, project["bounty"], submission["project_id"], submission["user_id"]),
    )
    conn.execute(
        """UPDATE users
           SET earnings = earnings + ?, score = score + ?, approved_projects = approved_projects + 1
           WHERE id = ?""",
        (project["bounty"], project["bounty"], submission["user_id"]),
    )
    _notify(
        conn,
        submission["user_id"],
        f"“{project['title']}” was approved. ${project['bounty']} was added to your record and your score.",
    )


def lead_application(conn, user):
    row = conn.execute("SELECT * FROM lead_applications WHERE user_id = ?", (user["id"],)).fetchone()
    exam_done = bool(row and row["exam_passed"])
    in_person = bool(row and row["in_person"])
    is_lead = user["role"] == "lead" or (exam_done and in_person)
    if is_lead:
        current = "lead_status"
    elif exam_done:
        current = "in_person"
    elif row:
        current = "exam"
    else:
        current = None
    return {
        "started": row is not None,
        "current": current,
        "stages": [
            {"id": "exam", "done": exam_done},
            {"id": "in_person", "done": in_person},
            {"id": "lead_status", "done": is_lead},
        ],
    }


def start_lead_application(conn, user):
    if user["role"] == "lead":
        return lead_application(conn, user)
    if user["role"] not in ("student", "admin"):
        raise APIError(403, "role_required", "Students apply to become a lead.")
    existing = conn.execute("SELECT 1 FROM lead_applications WHERE user_id = ?", (user["id"],)).fetchone()
    if not existing:
        conn.execute(
            "INSERT INTO lead_applications (user_id, exam_passed, exam_attempts, in_person, started_at) VALUES (?, 0, 0, 0, ?)",
            (user["id"], iso(now())),
        )
    return lead_application(conn, user)


def exam_questions():
    return [
        {"id": question["id"], "prompt": question["prompt"], "choices": list(question["choices"])}
        for question in EXAM
    ]


def start_exam(conn, user):
    start_lead_application(conn, user)
    row = conn.execute("SELECT * FROM lead_applications WHERE user_id = ?", (user["id"],)).fetchone()
    if row["exam_passed"]:
        raise APIError(409, "exam_passed", "You already passed the proctored exam.")
    token = secrets.token_urlsafe(24)
    session_id = new_id()
    expires = iso(now() + timedelta(hours=2))
    conn.execute(
        "INSERT INTO exam_sessions (id, user_id, token_hash, expires_at, submitted_at) VALUES (?, ?, ?, ?, NULL)",
        (session_id, user["id"], hash_token(token), expires),
    )
    return {
        "session_id": session_id,
        "token": token,
        "expires_at": expires,
        "pass_mark": PASS_MARK,
        "questions": exam_questions(),
        "submit_path": f"/v1/me/lead-application/exam/sessions/{session_id}",
    }


def submit_exam(conn, user, session_id, body):
    if not isinstance(body, dict):
        raise APIError(400, "invalid_body", "Send the session token and answers.")
    token = (body.get("token") or "").strip()
    answers = body.get("answers")
    row = conn.execute(
        "SELECT * FROM exam_sessions WHERE id = ? AND user_id = ?",
        (session_id, user["id"]),
    ).fetchone()
    if not row or hash_token(token) != row["token_hash"]:
        raise APIError(401, "unauthorized", "That exam session is not valid.")
    if row["submitted_at"] or parse_iso(row["expires_at"]) <= now():
        raise APIError(409, "exam_closed", "That exam session is closed.")
    if not isinstance(answers, list) or len(answers) != len(EXAM):
        raise APIError(400, "invalid_body", "Answer every question.")
    correct = 0
    for question, choice in zip(EXAM, answers):
        if isinstance(choice, int) and not isinstance(choice, bool) and choice == question["answer"]:
            correct += 1
    passed = correct >= PASS_MARK
    conn.execute("UPDATE exam_sessions SET submitted_at = ? WHERE id = ?", (iso(now()), session_id))
    conn.execute(
        """UPDATE lead_applications
           SET exam_attempts = exam_attempts + 1, last_exam_score = ?, exam_passed = CASE WHEN ? THEN 1 ELSE exam_passed END
           WHERE user_id = ?""",
        (correct, 1 if passed else 0, user["id"]),
    )
    if passed:
        _notify(conn, user["id"], "You passed the proctored exam. The next stage is in person with Ylyas.")
    return {"correct": correct, "passed": passed, "pass_mark": PASS_MARK, "application": lead_application(conn, user)}


def confirm_in_person(conn, admin, user_id):
    _admin(admin)
    row = conn.execute("SELECT * FROM lead_applications WHERE user_id = ?", (user_id,)).fetchone()
    if not row or not row["exam_passed"]:
        raise APIError(409, "invalid_state", "The exam comes before the in-person stage.")
    conn.execute("UPDATE lead_applications SET in_person = 1 WHERE user_id = ?", (user_id,))
    conn.execute("UPDATE users SET role = 'lead' WHERE id = ? AND role != 'admin'", (user_id,))
    _notify(conn, user_id, "Ylyas confirmed the meeting. Your role is now Lead.")
    user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    return {"role": user["role"], "application": lead_application(conn, user)}


def earnings(conn, user):
    rows = conn.execute(
        """SELECT s.paid_at, s.bounty_amount, p.title, p.id AS project_id
           FROM submissions s JOIN projects p ON p.id = s.project_id
           WHERE s.user_id = ? AND s.paid_at IS NOT NULL
           ORDER BY s.paid_at DESC""",
        (user["id"],),
    ).fetchall()
    seen = set()
    items = []
    for row in rows:
        if row["project_id"] in seen:
            continue
        seen.add(row["project_id"])
        items.append(
            {
                "project_id": row["project_id"],
                "title": row["title"],
                "amount": row["bounty_amount"],
                "paid_at": row["paid_at"],
            }
        )
    return {"total": user["earnings"], "items": items}


def get_payout(conn, user):
    row = conn.execute("SELECT * FROM payout_methods WHERE user_id = ?", (user["id"],)).fetchone()
    if not row:
        return {"active": False, "method": None}
    return {"active": False, "method": json.loads(row["payload"]), "updated_at": row["updated_at"]}


def put_payout(conn, user, body):
    if not isinstance(body, dict) or not (body.get("type") or "").strip():
        raise APIError(400, "invalid_body", "type is required. Payouts are not sent yet.")
    payload = {"type": body["type"].strip(), "label": (body.get("label") or "").strip()}
    conn.execute(
        """INSERT INTO payout_methods (user_id, payload, updated_at) VALUES (?, ?, ?)
           ON CONFLICT(user_id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at""",
        (user["id"], json.dumps(payload), iso(now())),
    )
    return get_payout(conn, user)


def _notify(conn, user_id, text):
    moment = iso(now())
    note_id = new_id()
    conn.execute(
        "INSERT INTO notifications (id, user_id, text, read_at, created_at) VALUES (?, ?, ?, NULL, ?)",
        (note_id, user_id, text, moment),
    )
    conn.execute(
        "INSERT INTO events (user_id, kind, payload, created_at) VALUES (?, 'notification', ?, ?)",
        (user_id, json.dumps({"id": note_id, "text": text}), moment),
    )


def list_notifications(conn, user, cursor):
    offset = _cursor_offset(cursor)
    rows = conn.execute(
        """SELECT * FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?""",
        (user["id"], PAGE + 1, offset),
    ).fetchall()
    items = rows[:PAGE]
    return {
        "items": [
            {"id": row["id"], "text": row["text"], "read": row["read_at"] is not None, "created_at": row["created_at"]}
            for row in items
        ],
        "next_cursor": str(offset + PAGE) if len(rows) > PAGE else None,
    }


def unread_count(conn, user):
    count = conn.execute(
        "SELECT COUNT(*) AS n FROM notifications WHERE user_id = ? AND read_at IS NULL",
        (user["id"],),
    ).fetchone()["n"]
    return {"unread": count}


def mark_read(conn, user, body):
    if not isinstance(body, dict):
        raise APIError(400, "invalid_body", "Send ids or all.")
    moment = iso(now())
    if body.get("all") is True:
        conn.execute(
            "UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL",
            (moment, user["id"]),
        )
    elif isinstance(body.get("ids"), list):
        for note_id in body["ids"]:
            conn.execute(
                "UPDATE notifications SET read_at = ? WHERE id = ? AND user_id = ? AND read_at IS NULL",
                (moment, note_id, user["id"]),
            )
    else:
        raise APIError(400, "invalid_body", "Send {\"all\": true} or {\"ids\": [...]}.")
    return unread_count(conn, user)


def register_device(conn, user, body):
    if not isinstance(body, dict):
        raise APIError(400, "invalid_body", "platform, push_token, provider, and app_version are required.")
    platform = body.get("platform")
    provider = body.get("provider")
    token = (body.get("push_token") or "").strip()
    version = (body.get("app_version") or "").strip()
    if platform not in PLATFORMS or provider not in PUSH_PROVIDERS or not token or not version:
        raise APIError(400, "invalid_body", "platform, push_token, provider, and app_version are required.")
    if provider == "apns" and platform not in ("ios", "macos"):
        raise APIError(400, "invalid_body", "apns is for ios and macos.")
    if provider == "fcm" and platform != "android":
        raise APIError(400, "invalid_body", "fcm is for android.")
    if provider == "wns" and platform != "windows":
        raise APIError(400, "invalid_body", "wns is for windows. Linux uses the event stream.")
    existing = conn.execute(
        "SELECT * FROM devices WHERE user_id = ? AND push_token = ?",
        (user["id"], token),
    ).fetchone()
    if existing:
        conn.execute(
            "UPDATE devices SET platform = ?, provider = ?, app_version = ? WHERE id = ?",
            (platform, provider, version, existing["id"]),
        )
        device_id = existing["id"]
    else:
        device_id = new_id()
        conn.execute(
            """INSERT INTO devices (id, user_id, platform, push_token, provider, app_version, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (device_id, user["id"], platform, token, provider, version, iso(now())),
        )
    return {"id": device_id, "platform": platform, "provider": provider}


def delete_device(conn, user, device_id):
    row = conn.execute(
        "SELECT * FROM devices WHERE id = ? AND user_id = ?",
        (device_id, user["id"]),
    ).fetchone()
    if not row:
        raise APIError(404, "not_found", "That device is not registered.")
    conn.execute("DELETE FROM devices WHERE id = ?", (device_id,))
    return None


def events_since(conn, user, last_id):
    try:
        last = int(last_id or 0)
    except ValueError:
        last = 0
    rows = conn.execute(
        "SELECT * FROM events WHERE user_id = ? AND id > ? ORDER BY id LIMIT 50",
        (user["id"], last),
    ).fetchall()
    return [
        {"id": row["id"], "kind": row["kind"], "payload": json.loads(row["payload"])}
        for row in rows
    ]


def billing_plans():
    return {
        "items": [
            {
                "id": "outside_builder",
                "name": "Outside builder",
                "tier": "paid",
                "interval": "month",
                "stores": ["apple", "google", "stripe"],
                "active": False,
            }
        ]
    }


def billing_not_ready():
    raise APIError(
        503,
        "billing_not_enabled",
        "Paid plans are not on yet. BAU students stay on bau_free.",
    )


def enroll(conn, admin, body):
    _admin(admin)
    if not isinstance(body, dict):
        raise APIError(400, "invalid_body", "user_id and class_id are required.")
    user = conn.execute("SELECT * FROM users WHERE id = ?", (body.get("user_id"),)).fetchone()
    course = class_by_id(conn, body.get("class_id") or "")
    if not user:
        raise APIError(404, "not_found", "That user does not exist.")
    conn.execute(
        "INSERT OR IGNORE INTO enrollments (user_id, class_id) VALUES (?, ?)",
        (user["id"], course["id"]),
    )
    return {"user_id": user["id"], "class_id": course["id"], "code": course["code"]}


def share_project(conn, project_id):
    row = conn.execute("SELECT * FROM projects WHERE id = ?", (project_id,)).fetchone()
    if not row:
        return None
    return row
