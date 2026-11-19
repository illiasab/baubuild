import unittest

import logic


def fresh():
    return logic.default_state()


class StatusTests(unittest.TestCase):
    def test_boundaries(self):
        self.assertEqual(logic.status_name(0), "Rookie")
        self.assertEqual(logic.status_name(99), "Rookie")
        self.assertEqual(logic.status_name(100), "Builder")
        self.assertEqual(logic.status_name(180), "Builder")
        self.assertEqual(logic.status_name(200), "Skilled")
        self.assertEqual(logic.status_name(299), "Skilled")
        self.assertEqual(logic.status_name(300), "Expert")
        self.assertEqual(logic.status_name(400), "Honored")
        self.assertEqual(logic.status_name(500), "Honored")


class FilterTests(unittest.TestCase):
    def test_filters_combine_and_search_is_case_insensitive(self):
        state = fresh()
        hard_open = logic.visible_projects(state, difficulty="Hard", open_score=True)
        self.assertEqual([item["id"] for item in hard_open], ["shuttle"])
        classes = logic.visible_projects(state, my_classes=True)
        self.assertEqual([item["id"] for item in classes], ["ranking"])
        urgent = logic.visible_projects(state, urgent=True, difficulty="Easy")
        self.assertEqual(urgent, [])
        found = logic.visible_projects(state, query="CSV")
        self.assertEqual([item["id"] for item in found], ["attendance"])

    def test_blank_search_does_not_hide_projects(self):
        self.assertEqual(len(logic.visible_projects(fresh(), query="   ")), len(logic.PROJECTS))


class JoinTests(unittest.TestCase):
    def test_open_project_joins_immediately_and_is_idempotent(self):
        state = fresh()
        first = logic.join(state, "darkmode")
        self.assertEqual(first["result"], "member")
        self.assertEqual(state["memberships"]["darkmode"]["status"], "member")
        self.assertGreaterEqual(len(state["memberships"]["darkmode"]["tasks"]), 1)
        again = logic.join(state, "darkmode")
        self.assertEqual(again["result"], "member")
        self.assertEqual(logic.work_summary(state)["member"], 3)

    def test_approval_project_stays_pending(self):
        state = fresh()
        state["user"]["score"] = 300
        result = logic.join(state, "ranking")
        self.assertEqual(result["result"], "pending")
        self.assertEqual(state["memberships"]["ranking"]["tasks"], [])
        self.assertEqual(logic.work_summary(state)["pending"], 1)
        self.assertEqual(logic.join(state, "ranking")["result"], "pending")

    def test_score_gate_does_not_change_memberships(self):
        state = fresh()
        before = logic.work_summary(state)
        result = logic.join(state, "ranking")
        self.assertFalse(result["ok"])
        self.assertIn("300", result["error"])
        self.assertNotIn("ranking", state["memberships"])
        self.assertEqual(logic.work_summary(state), before)

    def test_class_gate(self):
        state = fresh()
        state["user"]["classes"] = []
        state["user"]["score"] = 400
        result = logic.join(state, "ranking")
        self.assertFalse(result["ok"])
        self.assertIn("CMPS433", result["error"])

    def test_unknown_project(self):
        self.assertFalse(logic.join(fresh(), "nope")["ok"])

    def test_shuttle_is_open_to_a_low_score(self):
        state = fresh()
        result = logic.join(state, "shuttle")
        self.assertTrue(result["ok"])
        self.assertEqual(result["result"], "pending")


class WorkspaceTests(unittest.TestCase):
    def test_task_updates_require_membership_and_a_real_status(self):
        state = fresh()
        self.assertFalse(logic.update_task(state, "faq", "links", "done")["ok"])
        self.assertFalse(logic.update_task(state, "library", "past-dates", "shipped")["ok"])
        moved = logic.update_task(state, "library", "past-dates", "done")
        self.assertTrue(moved["ok"])
        task = state["memberships"]["library"]["tasks"][0]
        self.assertEqual(task["status"], "done")
        self.assertEqual(logic.work_summary(state)["assigned"], 2)

    def test_push_and_resubmit(self):
        state = fresh()
        self.assertFalse(logic.push_commit(state, "library", "  ")["ok"])
        pushed = logic.push_commit(state, "library", "Compare dates as objects")
        self.assertTrue(pushed["ok"])
        commit = state["memberships"]["library"]["commits"][0]
        self.assertEqual(commit["state"], "with_dev_lead")
        self.assertIn("screen readers", state["memberships"]["library"]["aiSummary"])
        blocked = logic.resubmit(state, "library", pushed["commitId"])
        self.assertFalse(blocked["ok"])
        sent_back = next(
            item for item in state["memberships"]["library"]["commits"] if item["id"] == "c3"
        )
        self.assertEqual(sent_back["state"], "changes_requested")
        self.assertTrue(logic.resubmit(state, "library", "c3")["ok"])
        self.assertEqual(sent_back["state"], "with_dev_lead")

    def test_mentor_rejects_empty_and_answers_the_date_question(self):
        state = fresh()
        self.assertFalse(logic.ask_mentor(state, "library", " ")["ok"])
        self.assertFalse(logic.ask_mentor(state, "faq", "help")["ok"])
        self.assertTrue(logic.ask_mentor(state, "library", "Are the dates sorted as strings?")["ok"])
        reply = state["messages"]["library"][-1]["text"]
        self.assertIn("Date objects", reply)


class ProfileTests(unittest.TestCase):
    def test_bio_rules(self):
        state = fresh()
        self.assertFalse(logic.update_bio(state, "   ")["ok"])
        self.assertFalse(logic.update_bio(state, "x" * 281)["ok"])
        self.assertTrue(logic.update_bio(state, "Shipping real work.")["ok"])
        self.assertEqual(state["user"]["bio"], "Shipping real work.")

    def test_exam_fail_then_pass_then_lock(self):
        state = fresh()
        failed = logic.submit_exam(state, [0, 0, 0, 0])
        self.assertFalse(failed["passed"])
        self.assertEqual(state["user"]["role"], "student")
        self.assertFalse(state["user"]["lead"]["examPassed"])
        passed = logic.submit_exam(state, [1, 1, 1, 1])
        self.assertTrue(passed["passed"])
        self.assertEqual(state["user"]["role"], "student")
        self.assertTrue(state["user"]["lead"]["examPassed"])
        self.assertFalse(logic.submit_exam(state, [1, 1, 1, 1])["ok"])
        self.assertFalse(logic.record_in_person(fresh())["ok"])
        self.assertTrue(logic.record_in_person(state)["ok"])
        self.assertEqual(state["user"]["role"], "lead")

    def test_exam_requires_every_answer(self):
        self.assertFalse(logic.submit_exam(fresh(), [1, 1])["ok"])

    def test_approval_pays_once_and_only_for_a_lead(self):
        state = fresh()
        self.assertFalse(logic.decide(state, "library", "approve")["ok"])
        state["user"]["lead"]["examPassed"] = True
        logic.record_in_person(state)
        for commit in state["memberships"]["library"]["commits"]:
            if commit["state"] == "changes_requested":
                commit["state"] = "with_dev_lead"
        first = logic.decide(state, "library", "approve")
        self.assertTrue(first["rewarded"])
        self.assertEqual(state["user"]["earnings"], 56)
        self.assertEqual(state["user"]["score"], 198)
        self.assertEqual(state["user"]["approvedProjects"], 3)
        self.assertEqual(logic.status_name(state["user"]["score"]), "Builder")
        second = logic.decide(state, "library", "approve")
        self.assertFalse(second["ok"])
        self.assertEqual(state["user"]["earnings"], 56)


class PublicStateTests(unittest.TestCase):
    def test_exam_answers_are_not_in_the_payload(self):
        payload = logic.public_state(fresh())
        self.assertNotIn("answer", str(payload["exam"]))
        self.assertEqual(payload["user"]["status"], "Builder")
        self.assertEqual(payload["summary"], {"member": 2, "pending": 0, "assigned": 3})


if __name__ == "__main__":
    unittest.main()
