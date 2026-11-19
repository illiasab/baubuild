"""Route /v1 requests to the domain rules."""

from __future__ import annotations

import json
import os
import re
from urllib.parse import parse_qs

from api.db import DB, configure
from api.errors import APIError
from api import service

PUBLIC = {
    ("POST", "/v1/auth/microsoft/exchange"),
    ("POST", "/v1/auth/refresh"),
    ("GET", "/v1/config"),
    ("GET", "/v1/billing/plans"),
    ("POST", "/v1/billing/webhooks/apple"),
    ("POST", "/v1/billing/webhooks/google"),
    ("POST", "/v1/billing/webhooks/stripe"),
    ("GET", "/v1/integrations/github/callback"),
}


def ensure_db():
    if service_db_ready():
        return
    configure()


def service_db_ready():
    from api import db
    return db.DB_PATH is not None


class Result:
    def __init__(self, status, json_body=None, text=None, stream=None):
        self.status = status
        self.json_body = json_body
        self.text = text
        self.stream = stream


def handle_request(method, path, query, headers, body):
    ensure_db()
    if method == "OPTIONS":
        return Result(204)
    try:
        with DB() as conn:
            status, payload, extra = route(conn, method, path, query, headers, body)
    except APIError as exc:
        return Result(exc.status, json_body=exc.body())
    if extra == "html":
        return Result(status, text=payload)
    if isinstance(extra, list):
        return Result(status, stream=extra)
    if status == 204:
        return Result(204)
    return Result(status, json_body=payload)


def route(conn, method, path, query, headers, body):
    user = None
    session = None
    key = (method, path)
    dynamic = not _is_public(method, path)
    if dynamic and not (method == "GET" and path.startswith("/.well-known/")) and not path.startswith("/p/"):
        if key not in PUBLIC and not path.startswith("/v1/billing/webhooks/"):
            user, session = service.user_from_access(conn, headers.get("authorization"))

    if method == "GET" and path == "/v1":
        return 200, {
            "service": "bau-build",
            "version": "v1",
            "documentation": "/docs",
            "openapi": "/v1/openapi.json",
        }, None
    if method == "GET" and path == "/v1/config":
        return 200, service.config_payload(), None
    if method == "POST" and path == "/v1/auth/microsoft/exchange":
        return 200, service.exchange(conn, body), None
    if method == "POST" and path == "/v1/auth/refresh":
        return 200, service.refresh(conn, body), None
    if method == "POST" and path == "/v1/auth/logout":
        service.logout(conn, session)
        return 204, None, None
    if method == "GET" and path == "/v1/auth/sessions":
        return 200, service.list_sessions(conn, user, session["id"]), None
    match = re.fullmatch(r"/v1/auth/sessions/([A-Za-z0-9]+)", path)
    if method == "DELETE" and match:
        service.delete_session(conn, user, match.group(1))
        return 204, None, None

    if method == "GET" and path == "/v1/me":
        return 200, service.public_user(conn, user), None
    if method == "PATCH" and path == "/v1/me":
        return 200, service.update_me(conn, user, body), None
    if method == "GET" and path == "/v1/me/entitlement":
        return 200, service.entitlement_of(user), None
    if method == "GET" and path == "/v1/me/stats":
        return 200, service.stats_of(user), None
    match = re.fullmatch(r"/v1/users/([A-Za-z0-9]+)", path)
    if method == "GET" and match:
        row = conn.execute("SELECT * FROM users WHERE id = ?", (match.group(1),)).fetchone()
        if not row:
            raise APIError(404, "not_found", "That profile does not exist.")
        return 200, service.public_user(conn, row, include_email=False), None

    if method == "GET" and path == "/v1/home":
        return 200, service.home(conn, user), None
    if method == "GET" and path == "/v1/inquiries/urgent":
        return 200, service.urgent_inquiries(conn), None
    if method == "GET" and path == "/v1/projects":
        return 200, service.list_projects(conn, user, query), None
    if method == "GET" and path == "/v1/search":
        return 200, service.search(conn, user, query.get("q")), None
    if method == "GET" and path == "/v1/me/bookmarks":
        return 200, service.my_bookmarks(conn, user), None
    if method == "GET" and path == "/v1/me/classes":
        return 200, service.my_classes(conn, user), None
    if method == "GET" and path == "/v1/me/work":
        return 200, service.my_work(conn, user), None
    if method == "GET" and path == "/v1/me/submissions":
        return 200, service.my_submissions(conn, user), None
    if method == "GET" and path == "/v1/me/earnings":
        return 200, service.earnings(conn, user), None
    if method == "GET" and path == "/v1/me/payout-method":
        return 200, service.get_payout(conn, user), None
    if method == "PUT" and path == "/v1/me/payout-method":
        return 200, service.put_payout(conn, user, body), None
    if method == "GET" and path == "/v1/me/lead-application":
        return 200, service.lead_application(conn, user), None
    if method == "POST" and path == "/v1/me/lead-application":
        return 200, service.start_lead_application(conn, user), None
    if method == "POST" and path == "/v1/me/lead-application/exam/session":
        return 201, service.start_exam(conn, user), None
    match = re.fullmatch(r"/v1/me/lead-application/exam/sessions/([A-Za-z0-9]+)", path)
    if method == "POST" and match:
        return 200, service.submit_exam(conn, user, match.group(1), body), None

    match = re.fullmatch(r"/v1/classes/([^/]+)", path)
    if method == "GET" and match:
        return 200, service._class_payload(conn, service.class_by_id(conn, match.group(1))), None
    match = re.fullmatch(r"/v1/classes/([^/]+)/projects", path)
    if method == "GET" and match:
        return 200, service.class_projects(conn, user, match.group(1)), None

    match = re.fullmatch(r"/v1/projects/([A-Za-z0-9]+)", path)
    if method == "GET" and match:
        return 200, service.project_detail(conn, user, service._require_project(conn, match.group(1))), None
    match = re.fullmatch(r"/v1/projects/([A-Za-z0-9]+)/join", path)
    if method == "POST" and match:
        return 200, service.join_project(conn, user, match.group(1)), None
    match = re.fullmatch(r"/v1/projects/([A-Za-z0-9]+)/membership", path)
    if method == "DELETE" and match:
        service.leave_project(conn, user, match.group(1))
        return 204, None, None
    match = re.fullmatch(r"/v1/projects/([A-Za-z0-9]+)/bookmark", path)
    if method == "PUT" and match:
        service.bookmark(conn, user, match.group(1), True)
        return 204, None, None
    if method == "DELETE" and match:
        service.bookmark(conn, user, match.group(1), False)
        return 204, None, None
    match = re.fullmatch(r"/v1/projects/([A-Za-z0-9]+)/tasks", path)
    if method == "GET" and match:
        return 200, service.list_tasks(conn, user, match.group(1), query.get("assignee")), None
    match = re.fullmatch(r"/v1/tasks/([A-Za-z0-9]+)", path)
    if method == "PATCH" and match:
        return 200, service.update_task(conn, user, match.group(1), body), None
    match = re.fullmatch(r"/v1/projects/([A-Za-z0-9]+)/changes", path)
    if method == "GET" and match:
        return 200, service.changes(conn, user, match.group(1)), None
    match = re.fullmatch(r"/v1/projects/([A-Za-z0-9]+)/submissions", path)
    if method == "POST" and match:
        return 201, service.create_submission(conn, user, match.group(1), body), None
    match = re.fullmatch(r"/v1/projects/([A-Za-z0-9]+)/mentor/messages", path)
    if method == "GET" and match:
        return 200, service.mentor_history(conn, user, match.group(1)), None
    if method == "POST" and match:
        reply_id, reply = service.post_mentor(conn, user, match.group(1), body)
        return 200, None, _mentor_events(reply_id, reply)

    if method == "GET" and path == "/v1/integrations/github":
        return 200, service.github_status(user), None
    if method == "POST" and path == "/v1/integrations/github/connect":
        return 200, service.github_connect(conn, user), None
    if method == "DELETE" and path == "/v1/integrations/github":
        return 200, service.github_disconnect(conn, user), None
    if method == "GET" and path == "/v1/integrations/github/callback":
        return 200, service.github_callback(conn, query), None

    if method == "POST" and path == "/v1/inquiries":
        return 201, service.create_inquiry(conn, user, body), None
    if method == "GET" and path == "/v1/inquiries/mine":
        return 200, service.my_inquiries(conn, user), None
    match = re.fullmatch(r"/v1/inquiries/([A-Za-z0-9]+)", path)
    if method == "GET" and match:
        return 200, service._inquiry_payload(service._own_inquiry(conn, user, match.group(1))), None
    if method == "PATCH" and match:
        return 200, service.update_inquiry(conn, user, match.group(1), body), None
    if method == "DELETE" and match:
        service.delete_inquiry(conn, user, match.group(1))
        return 204, None, None
    match = re.fullmatch(r"/v1/admin/inquiries/([A-Za-z0-9]+)/approve", path)
    if method == "POST" and match:
        return 200, service.approve_inquiry(conn, user, match.group(1)), None
    match = re.fullmatch(r"/v1/admin/inquiries/([A-Za-z0-9]+)/publish", path)
    if method == "POST" and match:
        return 201, service.publish_inquiry(conn, user, match.group(1)), None
    if method == "POST" and path == "/v1/admin/enrollments":
        return 201, service.enroll(conn, user, body), None
    match = re.fullmatch(r"/v1/admin/lead-applications/([A-Za-z0-9]+)/in-person", path)
    if method == "POST" and match:
        return 200, service.confirm_in_person(conn, user, match.group(1)), None

    match = re.fullmatch(r"/v1/submissions/([A-Za-z0-9]+)", path)
    if method == "GET" and match:
        return 200, service.get_submission(conn, user, match.group(1)), None
    match = re.fullmatch(r"/v1/submissions/([A-Za-z0-9]+)/decision", path)
    if method == "POST" and match:
        return 200, service.decide_submission(conn, user, match.group(1), body), None
    if method == "GET" and path == "/v1/lead/queue":
        return 200, service.lead_queue(conn, user), None

    if method == "GET" and path == "/v1/notifications":
        return 200, service.list_notifications(conn, user, query.get("cursor")), None
    if method == "GET" and path == "/v1/notifications/unread-count":
        return 200, service.unread_count(conn, user), None
    if method == "POST" and path == "/v1/notifications/read":
        return 200, service.mark_read(conn, user, body), None
    if method == "POST" and path == "/v1/devices":
        return 201, service.register_device(conn, user, body), None
    match = re.fullmatch(r"/v1/devices/([A-Za-z0-9]+)", path)
    if method == "DELETE" and match:
        service.delete_device(conn, user, match.group(1))
        return 204, None, None
    if method == "GET" and path == "/v1/events/stream":
        last = headers.get("last-event-id")
        events = service.events_since(conn, user, last)
        return 200, None, _live_events(events)

    if method == "GET" and path == "/v1/billing/plans":
        return 200, service.billing_plans(), None
    if method == "GET" and path == "/v1/billing/subscription":
        return 200, {"tier": user["tier"], "expires_at": user["tier_expires_at"], "provider": None}, None
    if method == "POST" and path in (
        "/v1/billing/apple/verify",
        "/v1/billing/google/verify",
        "/v1/billing/checkout",
    ):
        service.billing_not_ready()
    match = re.fullmatch(r"/v1/billing/webhooks/(apple|google|stripe)", path)
    if method == "POST" and match:
        service.billing_not_ready()

    if method == "GET" and path == "/.well-known/apple-app-site-association":
        app_id = os.environ.get("APPLE_APP_ID", "").strip()
        details = []
        if app_id:
            details.append({"appIDs": [app_id], "components": [{"/": "/p/*"}]})
        return 200, {"applinks": {"apps": [], "details": details}}, None
    if method == "GET" and path == "/.well-known/assetlinks.json":
        package = os.environ.get("ANDROID_PACKAGE", "").strip()
        fingerprint = os.environ.get("ANDROID_SHA256", "").strip()
        if not package or not fingerprint:
            return 200, [], None
        return 200, [{
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {
                "namespace": "android_app",
                "package_name": package,
                "sha256_cert_fingerprints": [fingerprint],
            },
        }], None
    match = re.fullmatch(r"/p/([A-Za-z0-9]+)", path)
    if method == "GET" and match:
        project = service.share_project(conn, match.group(1))
        if not project:
            raise APIError(404, "not_found", "That project is not on the board.")
        return 200, _share_html(project), "html"

    raise APIError(404, "not_found", "No route matches that path.")


def _is_public(method, path):
    if (method, path) in PUBLIC:
        return True
    if method == "GET" and (
        path.startswith("/.well-known/") or path.startswith("/p/") or path in ("/v1", "/v1/config")
    ):
        return True
    if method == "POST" and path.startswith("/v1/billing/webhooks/"):
        return True
    return False


def _mentor_events(reply_id, reply):
    words = reply.split(" ")
    chunks = []
    for index, word in enumerate(words):
        piece = word if index == len(words) - 1 else word + " "
        chunks.append(f"event: delta\ndata: {json.dumps({'text': piece})}\n\n")
    chunks.append(f"event: done\ndata: {json.dumps({'id': reply_id, 'text': reply})}\n\n")
    return chunks


def _live_events(events):
    chunks = ["event: heartbeat\ndata: {}\n\n"]
    for event in events:
        chunks.append(
            f"id: {event['id']}\nevent: {event['kind']}\ndata: {json.dumps(event['payload'])}\n\n"
        )
    return chunks


def _share_html(project):
    title = _escape(project["title"])
    department = _escape(project["department"])
    description = _escape(project["description"])
    return f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>{title} · BAU BUILD</title>
<meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body><p>{department}</p><h1>{title}</h1><p>{description}</p>
<p><a href="https://baubuild.vesperconnect.org/">Open BAU BUILD</a></p></body></html>"""


def _escape(value):
    return (
        str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def query_map(raw):
    parsed = parse_qs(raw, keep_blank_values=False)
    return {key: values[-1] for key, values in parsed.items()}
