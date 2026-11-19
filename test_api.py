import os
import tempfile
import unittest

from api import service
from api.db import DB, configure
from api.dispatch import handle_request


def device():
    return {"platform": "ios", "app_version": "1.0.0"}


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["BAU_ADMIN_EMAILS"] = "lead.admin@bau.edu"
        configure(os.path.join(self.tmp.name, "bau.sqlite"))
        self.original = service.microsoft_identity
        service.microsoft_identity = lambda code, verifier, redirect: ("unused@stu.bau.edu", "Unused")

    def tearDown(self):
        service.microsoft_identity = self.original
        self.tmp.cleanup()

    def sign_in(self, email, name, platform="ios"):
        service.microsoft_identity = lambda code, verifier, redirect: (email, name)
        result = handle_request(
            "POST",
            "/v1/auth/microsoft/exchange",
            {},
            {},
            {
                "code": "code",
                "code_verifier": "verifier",
                "redirect_uri": "baubuild://auth",
                "device": {"platform": platform, "app_version": "1.0.0"},
            },
        )
        self.assertEqual(result.status, 200, result.json_body)
        return result.json_body

    def authed(self, method, path, token, body=None, query=None):
        return handle_request(
            method,
            path,
            query or {},
            {"authorization": f"Bearer {token}"},
            body,
        )

    def test_domain_gate(self):
        self.assertEqual(service.classify_email("A@STU.BAU.EDU"), "student")
        self.assertEqual(service.classify_email("a@bau.edu"), "staff")
        self.assertIsNone(service.classify_email("a@stu.bau.edu.evil.com"))
        self.assertIsNone(service.classify_email("a@gmail.com"))
        service.microsoft_identity = lambda code, verifier, redirect: ("person@gmail.com", "Outsider")
        denied = handle_request(
            "POST",
            "/v1/auth/microsoft/exchange",
            {},
            {},
            {
                "code": "code",
                "code_verifier": "verifier",
                "redirect_uri": "http://127.0.0.1:53123/callback",
                "device": device(),
            },
        )
        self.assertEqual(denied.status, 403)
        self.assertEqual(denied.json_body["error"]["code"], "domain_not_allowed")

    def test_roles_and_session_rotation(self):
        student = self.sign_in("ada@stu.bau.edu", "Ada Lovelace")
        self.assertEqual(student["user"]["role"], "student")
        self.assertEqual(student["entitlement"]["tier"], "bau_free")
        staff = self.sign_in("sam@bau.edu", "Sam Staff")
        self.assertEqual(staff["user"]["role"], "staff")
        admin = self.sign_in("lead.admin@bau.edu", "Admin User")
        self.assertEqual(admin["user"]["role"], "admin")

        refreshed = handle_request(
            "POST", "/v1/auth/refresh", {}, {}, {"refresh_token": student["refresh_token"]}
        )
        self.assertEqual(refreshed.status, 200)
        self.assertNotEqual(refreshed.json_body["refresh_token"], student["refresh_token"])
        reused = handle_request(
            "POST", "/v1/auth/refresh", {}, {}, {"refresh_token": student["refresh_token"]}
        )
        self.assertEqual(reused.status, 401)
        self.assertEqual(reused.json_body["error"]["code"], "refresh_reused")
        blocked = self.authed("GET", "/v1/me", refreshed.json_body["access_token"])
        self.assertEqual(blocked.status, 401)

        again = self.sign_in("ada@stu.bau.edu", "Ada Lovelace")
        sessions = self.authed("GET", "/v1/auth/sessions", again["access_token"])
        self.assertGreaterEqual(len(sessions.json_body["sessions"]), 1)
        gone = self.authed("POST", "/v1/auth/logout", again["access_token"])
        self.assertEqual(gone.status, 204)
        self.assertEqual(self.authed("GET", "/v1/me", again["access_token"]).status, 401)

    def test_config_is_public_and_profile_updates(self):
        config = handle_request("GET", "/v1/config", {}, {}, None)
        self.assertEqual(config.status, 200)
        self.assertIn("linux", config.json_body["min_supported"])
        student = self.sign_in("ada@stu.bau.edu", "Ada Lovelace")
        me = self.authed(
            "PATCH",
            "/v1/me",
            student["access_token"],
            {"bio": "Shipping real work.", "skills": ["Python"]},
        )
        self.assertEqual(me.status, 200)
        self.assertEqual(me.json_body["skills"], ["Python"])
        self.assertEqual(me.json_body["classes"], [])
        stats = self.authed("GET", "/v1/me/stats", student["access_token"])
        self.assertEqual(stats.json_body["status"], "rookie")
        self.assertEqual(stats.json_body["max"], 500)
        blank = self.authed("PATCH", "/v1/me", student["access_token"], {"bio": "  "})
        self.assertEqual(blank.status, 400)

    def test_join_score_payment_and_class_gates(self):
        student = self.sign_in("ada@stu.bau.edu", "Ada Lovelace")
        token = student["access_token"]
        low = self.authed("POST", "/v1/projects/transcript/join", token)
        self.assertEqual(low.status, 403)
        self.assertEqual(low.json_body["error"]["code"], "score_too_low")
        self.assertEqual(self.authed("GET", "/v1/me/work", token).json_body["in_progress"], [])
        joined = self.authed("POST", "/v1/projects/library/join", token)
        self.assertEqual(joined.json_body["result"], "joined")
        tasks = self.authed("GET", "/v1/projects/library/tasks", token, query={"assignee": "me"})
        self.assertGreaterEqual(len(tasks.json_body["items"]), 1)
        pending = self.authed("POST", "/v1/projects/shuttle/join", token)
        self.assertEqual(pending.json_body["result"], "pending_approval")
        work = self.authed("GET", "/v1/me/work", token).json_body
        self.assertEqual(len(work["in_progress"]), 1)
        self.assertEqual(work["awaiting_approval"][0]["reviewer_label"], "dev lead")
        closed = self.authed("POST", "/v1/projects/ranking/join", token)
        self.assertEqual(closed.json_body["error"]["code"], "class_not_enrolled")

        with DB() as conn:
            conn.execute("UPDATE users SET tier = 'payment_required' WHERE email = ?", ("ada@stu.bau.edu",))
        blocked = self.authed("POST", "/v1/projects/faq/join", token)
        self.assertEqual(blocked.json_body["error"]["code"], "payment_required")

    def test_staff_inquiry_publish_and_search(self):
        student = self.sign_in("ada@stu.bau.edu", "Ada Lovelace")
        denied = self.authed("POST", "/v1/inquiries", student["access_token"], {"title": "x"})
        self.assertEqual(denied.json_body["error"]["code"], "role_required")
        staff = self.sign_in("sam@bau.edu", "Sam Staff")
        created = self.authed(
            "POST",
            "/v1/inquiries",
            staff["access_token"],
            {
                "title": "Fix the lab printer",
                "description": "The queue drops jobs.",
                "department": "Labs",
                "difficulty": "easy",
                "bounty": 18,
                "is_urgent": False,
                "min_score": 0,
                "joining": "everyone",
                "reviewer_type": "ai_then_lead",
            },
        )
        self.assertEqual(created.status, 201)
        self.assertEqual(created.json_body["status"], "submitted")
        inquiry_id = created.json_body["id"]
        admin = self.sign_in("lead.admin@bau.edu", "Admin User")
        early = self.authed("POST", f"/v1/admin/inquiries/{inquiry_id}/publish", admin["access_token"])
        self.assertEqual(early.status, 409)
        approved = self.authed("POST", f"/v1/admin/inquiries/{inquiry_id}/approve", admin["access_token"])
        self.assertEqual(approved.json_body["status"], "approved")
        published = self.authed("POST", f"/v1/admin/inquiries/{inquiry_id}/publish", admin["access_token"])
        self.assertEqual(published.status, 201)
        found = self.authed("GET", "/v1/search", student["access_token"], query={"q": "printer"})
        self.assertEqual(found.json_body["projects"][0]["title"], "Fix the lab printer")
        home = self.authed("GET", "/v1/home", student["access_token"])
        self.assertEqual(home.json_body["greeting_name"], "Ada")
        self.assertTrue(home.json_body["urgent"])

    def test_review_pays_once_and_lead_exam_does_not_promote(self):
        student = self.sign_in("ada@stu.bau.edu", "Ada Lovelace")
        token = student["access_token"]
        self.authed("POST", "/v1/projects/library/join", token)
        submission = self.authed(
            "POST", "/v1/projects/library/submissions", token, {"branch": "fix-date-validation"}
        )
        self.assertEqual(submission.status, 201)
        steps = {step["step"]: step for step in submission.json_body["steps"]}
        self.assertEqual(steps["ai_review"]["status"], "complete")
        self.assertEqual(steps["lead_decision"]["status"], "pending")
        own = self.authed(
            "POST",
            f"/v1/submissions/{submission.json_body['id']}/decision",
            token,
            {"action": "approve"},
        )
        self.assertEqual(own.status, 403)
        admin = self.sign_in("lead.admin@bau.edu", "Admin User")
        queue = self.authed("GET", "/v1/lead/queue", student["access_token"])
        self.assertEqual(queue.status, 403)
        decided = self.authed(
            "POST",
            f"/v1/submissions/{submission.json_body['id']}/decision",
            admin["access_token"],
            {"action": "approve", "comment": "Looks right."},
        )
        self.assertEqual(decided.status, 200)
        bounty = {step["step"]: step for step in decided.json_body["steps"]}["bounty"]
        self.assertIsNotNone(bounty["paid_at"])
        stats = self.authed("GET", "/v1/me/stats", token)
        self.assertEqual(stats.json_body["score"], 18)
        self.assertEqual(stats.json_body["earnings_total"], 18)
        self.assertEqual(stats.json_body["status"], "rookie")
        again = self.authed(
            "POST",
            f"/v1/submissions/{submission.json_body['id']}/decision",
            admin["access_token"],
            {"action": "approve"},
        )
        self.assertEqual(again.status, 409)
        earnings = self.authed("GET", "/v1/me/earnings", token)
        self.assertEqual(earnings.json_body["total"], 18)
        self.assertEqual(len(earnings.json_body["items"]), 1)

        started = self.authed("POST", "/v1/me/lead-application/exam/session", token)
        self.assertEqual(started.status, 201)
        self.assertNotIn("answer", str(started.json_body["questions"]))
        passed = self.authed(
            "POST",
            started.json_body["submit_path"],
            token,
            {"token": started.json_body["token"], "answers": [1, 1, 1, 1]},
        )
        self.assertTrue(passed.json_body["passed"])
        self.assertEqual(self.authed("GET", "/v1/me", token).json_body["role"], "student")
        promoted = self.authed(
            "POST",
            f"/v1/admin/lead-applications/{student['user']['id']}/in-person",
            admin["access_token"],
        )
        self.assertEqual(promoted.json_body["role"], "lead")

    def test_mentor_stream_notifications_and_bookmarks(self):
        student = self.sign_in("ada@stu.bau.edu", "Ada Lovelace")
        token = student["access_token"]
        self.authed("POST", "/v1/projects/library/join", token)
        self.authed("PUT", "/v1/projects/library/bookmark", token)
        saved = self.authed("GET", "/v1/me/bookmarks", token)
        self.assertEqual(saved.json_body["items"][0]["id"], "library")
        streamed = self.authed(
            "POST",
            "/v1/projects/library/mentor/messages",
            token,
            {"text": "Are the dates sorted as strings?"},
        )
        self.assertIn("Date objects", "".join(streamed.stream))
        notes = self.authed("GET", "/v1/notifications", token)
        self.assertGreaterEqual(notes.json_body["items"][0]["text"].__len__(), 1)
        unread = self.authed("GET", "/v1/notifications/unread-count", token)
        self.assertGreaterEqual(unread.json_body["unread"], 1)
        cleared = self.authed("POST", "/v1/notifications/read", token, {"all": True})
        self.assertEqual(cleared.json_body["unread"], 0)
        live = self.authed("GET", "/v1/events/stream", token)
        self.assertTrue(live.stream[0].startswith("event: heartbeat"))
        device = self.authed(
            "POST",
            "/v1/devices",
            token,
            {"platform": "ios", "push_token": "abc", "provider": "apns", "app_version": "1.0.0"},
        )
        self.assertEqual(device.status, 201)
        linux = self.authed(
            "POST",
            "/v1/devices",
            token,
            {"platform": "linux", "push_token": "nope", "provider": "fcm", "app_version": "1.0.0"},
        )
        self.assertEqual(linux.status, 400)
        billing = self.authed("POST", "/v1/billing/checkout", token, {"plan": "outside_builder"})
        self.assertEqual(billing.json_body["error"]["code"], "billing_not_enabled")
        plans = handle_request("GET", "/v1/billing/plans", {}, {}, None)
        self.assertEqual(plans.status, 200)


if __name__ == "__main__":
    unittest.main()
