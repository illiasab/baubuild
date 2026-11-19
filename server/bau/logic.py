"""BAU BUILD portal rules.

Membership, score gates, review, and the lead exam live here so the server
and the tests share one implementation.
"""

from __future__ import annotations

import copy

TIERS = (
    (400, "Honored"),
    (300, "Expert"),
    (200, "Skilled"),
    (100, "Builder"),
    (0, "Rookie"),
)

EXAM = (
    {
        "id": "dates",
        "prompt": "A booking form accepts past dates because it compares date strings. What should the check compare instead?",
        "choices": (
            "The length of the date string",
            "Date objects or timestamps",
            "The weekday name",
            "The user's score",
        ),
        "answer": 1,
    },
    {
        "id": "binary",
        "prompt": "Binary search only works when the collection is…",
        "choices": (
            "Stored in a linked list",
            "Sorted by the search key",
            "Shorter than ten items",
            "Hosted on GitHub",
        ),
        "answer": 1,
    },
    {
        "id": "review",
        "prompt": "After the AI review finishes, who decides whether the work merges?",
        "choices": (
            "The student who pushed",
            "A vetted dev lead, or the professor on a class project",
            "The bounty amount",
            "Whoever joined first",
        ),
        "answer": 1,
    },
    {
        "id": "score",
        "prompt": "A project requires a score of 300 and yours is 180. What should the portal do if you request it?",
        "choices": (
            "Join you immediately",
            "Refuse the request and leave your memberships unchanged",
            "Lower the project's minimum score",
            "Mark the project approved",
        ),
        "answer": 1,
    },
)

PASS_MARK = 3


def status_name(score: int) -> str:
    for minimum, name in TIERS:
        if score >= minimum:
            return name
    return "Rookie"


def _tasks(*rows):
    tasks = []
    for task_id, title, group, status, due in rows:
        tasks.append(
            {
                "id": task_id,
                "title": title,
                "group": group,
                "status": status,
                "due": due,
            }
        )
    return tasks


PROJECTS = (
    {
        "id": "shuttle",
        "department": "Campus Ops",
        "title": "Build a shuttle timetable widget for the student portal",
        "summary": "Campus Ops needs it within 48 hours. It is tough, but open to every class and every score: a chance to show you deserve a status raise.",
        "detail": "Campus Ops needs a timetable widget on the student portal this week. It is open even if you are not taking the class it would normally belong to.",
        "bounty": 35,
        "difficulty": "Hard",
        "minScore": 0,
        "due": "Due in 48 h",
        "urgent": True,
        "openToAllClasses": True,
        "classCode": None,
        "approval": "dev_lead",
        "reviewer": "dev lead",
        "repo": "campus-shuttle",
        "stack": ("React",),
        "tasks": (
            ("route-data", "Load the weekday shuttle stops", "assigned", "todo", "Due in 48 h"),
            ("widget", "Render the next three departures", "backlog", "todo", None),
        ),
    },
    {
        "id": "library",
        "department": "Library",
        "title": "Fix form validation on the room-booking page",
        "summary": "The booking form accepts dates in the past. Fix the check and add a test.",
        "detail": "The library’s booking form accepts dates in the past. Find the check, fix it, and add a test so it stays fixed.",
        "bounty": 18,
        "difficulty": "Easy",
        "minScore": 0,
        "due": "Due in 3 days",
        "urgent": False,
        "openToAllClasses": True,
        "classCode": None,
        "approval": "open",
        "reviewer": "dev lead",
        "repo": "library-booking",
        "stack": ("JavaScript", "HTML"),
        "tasks": (
            ("past-dates", "Reject past dates in the booking form", "assigned", "in_progress", "Due in 3 days"),
            ("date-test", "Add a test for the date check", "backlog", "todo", None),
            ("announce", "Announce the error to screen readers", "backlog", "todo", None),
        ),
    },
    {
        "id": "ranking",
        "department": "CMPS433",
        "title": "Rebuild course-search ranking",
        "summary": "Class project. Results should rank by relevance, not by course number.",
        "detail": "Search results currently follow the course number. Rank them by how well they match the query. The professor of CMPS433 approves the work.",
        "bounty": 60,
        "difficulty": "Hard",
        "minScore": 300,
        "due": "Due in 2 weeks",
        "urgent": False,
        "openToAllClasses": False,
        "classCode": "CMPS433",
        "approval": "professor",
        "reviewer": "professor of CMPS433",
        "repo": "course-search",
        "stack": ("Python",),
        "tasks": (
            ("rank", "Rank matches by query relevance", "assigned", "todo", "Due in 2 weeks"),
            ("tie", "Break ties with the course title", "backlog", "todo", None),
        ),
    },
    {
        "id": "attendance",
        "department": "Student Affairs",
        "title": "Export club attendance to CSV",
        "summary": "Staff copy attendance by hand each week. Write the script that does it.",
        "detail": "Staff copy attendance by hand each week. Write the script that does it in one click.",
        "bounty": 20,
        "difficulty": "Easy",
        "minScore": 0,
        "due": "Due in 5 days",
        "urgent": False,
        "openToAllClasses": True,
        "classCode": None,
        "approval": "open",
        "reviewer": "dev lead",
        "repo": "club-attendance",
        "stack": ("Python",),
        "tasks": (
            ("export", "Export the attendance table to CSV", "assigned", "in_progress", "Due in 5 days"),
            ("column", "Include the club name column", "assigned", "todo", "Due in 5 days"),
        ),
    },
    {
        "id": "darkmode",
        "department": "Events",
        "title": "Add dark mode to the events calendar",
        "summary": "Add a theme switch that remembers the visitor’s choice.",
        "detail": "Add a theme switch on the events calendar and remember the visitor’s choice on the next visit.",
        "bounty": 20,
        "difficulty": "Medium",
        "minScore": 0,
        "due": "Due in 1 week",
        "urgent": False,
        "openToAllClasses": True,
        "classCode": None,
        "approval": "open",
        "reviewer": "dev lead",
        "repo": "events-calendar",
        "stack": ("CSS", "JavaScript"),
        "tasks": (
            ("theme", "Add a theme switch to the calendar", "assigned", "todo", "Due in 1 week"),
            ("remember", "Remember the choice for the next visit", "backlog", "todo", None),
        ),
    },
    {
        "id": "faq",
        "department": "Admissions",
        "title": "Repair broken links on the FAQ",
        "summary": "Find every dead link on the FAQ pages and point it somewhere real.",
        "detail": "Find every dead link on the admissions FAQ and point it at a page that exists.",
        "bounty": 18,
        "difficulty": "Easy",
        "minScore": 0,
        "due": "Due in 4 days",
        "urgent": False,
        "openToAllClasses": True,
        "classCode": None,
        "approval": "open",
        "reviewer": "dev lead",
        "repo": "admissions-faq",
        "stack": ("HTML",),
        "tasks": (
            ("links", "Replace each dead FAQ link", "assigned", "todo", "Due in 4 days"),
            ("check", "Add a check that fails on a 404", "backlog", "todo", None),
        ),
    },
    {
        "id": "transcript",
        "department": "Registrar",
        "title": "Speed up the transcript request form",
        "summary": "The form reloads on every field. Profile it and remove the reloads.",
        "detail": "The transcript form reloads the page on every field. Find the cause and remove the reloads. A dev lead approves the change.",
        "bounty": 45,
        "difficulty": "Hard",
        "minScore": 250,
        "due": "Due in 10 days",
        "urgent": False,
        "openToAllClasses": True,
        "classCode": None,
        "approval": "dev_lead",
        "reviewer": "dev lead",
        "repo": "transcript-form",
        "stack": ("JavaScript",),
        "tasks": (
            ("profile", "Find which field triggers the reload", "assigned", "todo", "Due in 10 days"),
            ("fix", "Submit the form without reloading", "backlog", "todo", None),
        ),
    },
)

PROJECT_BY_ID = {project["id"]: project for project in PROJECTS}

LIBRARY_DIFFS = {
    "c1": "--- a/booking-form.js\n+++ b/booking-form.js\n@@\n-if (date) return true;\n+if (!date || date < today()) {\n+  return fail('Pick a future date');\n+}",
    "c2": "--- a/booking-form.test.js\n+++ b/booking-form.test.js\n@@\n+test('rejects a date before today', () => {\n+  expect(validate('2000-01-01')).toBe(false);\n+});",
    "c3": "--- a/booking-form.js\n+++ b/booking-form.js\n@@\n+showError('Pick a future date');",
}


def _library_membership():
    return {
        "status": "member",
        "rewarded": False,
        "branch": "fix-date-validation",
        "aiSummary": "Past dates are rejected and a test covers it. Worth a look: the error is not announced to screen readers.",
        "tasks": _tasks(*PROJECT_BY_ID["library"]["tasks"]),
        "commits": [
            {
                "id": "c1",
                "title": "Reject dates before today",
                "branch": "fix-date-validation",
                "state": "approved",
                "diff": LIBRARY_DIFFS["c1"],
            },
            {
                "id": "c2",
                "title": "Add test for past-date check",
                "branch": "fix-date-validation",
                "state": "with_dev_lead",
                "diff": LIBRARY_DIFFS["c2"],
            },
            {
                "id": "c3",
                "title": "Show inline error message",
                "branch": "fix-date-validation",
                "state": "changes_requested",
                "diff": LIBRARY_DIFFS["c3"],
            },
        ],
    }


def _attendance_membership():
    return {
        "status": "member",
        "rewarded": False,
        "branch": None,
        "aiSummary": None,
        "tasks": _tasks(*PROJECT_BY_ID["attendance"]["tasks"]),
        "commits": [],
    }


def default_state():
    return {
        "user": {
            "name": "Sam Carter",
            "initials": "SC",
            "bio": "Computer Science student. Learning by shipping real work for BAU.",
            "skills": ["JavaScript", "Python"],
            "classes": ["CMPS433"],
            "github": "connected",
            "role": "student",
            "score": 180,
            "earnings": 38,
            "approvedProjects": 2,
            "lead": {
                "examPassed": False,
                "examAttempts": 0,
                "lastExamScore": None,
                "inPerson": False,
            },
        },
        "memberships": {
            "library": _library_membership(),
            "attendance": _attendance_membership(),
        },
        "messages": {
            "library": [
                {
                    "role": "mentor",
                    "text": "Your date check compares strings, so “2026-1-5” sorts after “2026-12-01”. What could you compare instead?",
                }
            ]
        },
        "notifications": [
            {
                "id": "n1",
                "text": "A dev lead is reading your diff on fix-date-validation.",
                "read": False,
            }
        ],
        "seq": 10,
    }


def project_public(project):
    data = {key: project[key] for key in project if key != "tasks"}
    data["stack"] = list(project["stack"])
    return data


def _clone_tasks(project):
    return _tasks(*project["tasks"])


def eligibility(user, project):
    """Return (allowed, reason). Reason is None when the student may join or request."""
    if project["classCode"] and not project["openToAllClasses"]:
        if project["classCode"] not in user["classes"]:
            return False, f"This project is for students in {project['classCode']}."
    if user["score"] < project["minScore"]:
        return (
            False,
            f"This project needs a score of {project['minScore']}. Yours is {user['score']}.",
        )
    return True, None


def _notify(state, text):
    state["seq"] += 1
    state["notifications"].insert(
        0, {"id": f"n{state['seq']}", "text": text, "read": False}
    )


def _membership(state, project_id):
    return state["memberships"].get(project_id)


def join(state, project_id):
    project = PROJECT_BY_ID.get(project_id)
    if project is None:
        return {"ok": False, "error": "That project is not on the board."}
    current = _membership(state, project_id)
    if current and current["status"] == "member":
        return {"ok": True, "result": "member"}
    if current and current["status"] == "pending":
        return {"ok": True, "result": "pending"}
    allowed, reason = eligibility(state["user"], project)
    if not allowed:
        return {"ok": False, "error": reason}
    if project["approval"] == "open":
        state["memberships"][project_id] = {
            "status": "member",
            "rewarded": False,
            "branch": None,
            "aiSummary": None,
            "tasks": _clone_tasks(project),
            "commits": [],
        }
        _notify(state, f"You’re in “{project['title']}”.")
        return {"ok": True, "result": "member"}
    state["memberships"][project_id] = {
        "status": "pending",
        "rewarded": False,
        "branch": None,
        "aiSummary": None,
        "tasks": [],
        "commits": [],
    }
    who = "the professor" if project["approval"] == "professor" else "a dev lead"
    _notify(state, f"Request sent for “{project['title']}”. Waiting on {who}.")
    return {"ok": True, "result": "pending"}


TASK_STATUSES = ("todo", "in_progress", "done")


def update_task(state, project_id, task_id, status):
    if status not in TASK_STATUSES:
        return {"ok": False, "error": "Unknown task status."}
    current = _membership(state, project_id)
    if not current or current["status"] != "member":
        return {"ok": False, "error": "Join the project before updating its tasks."}
    for task in current["tasks"]:
        if task["id"] == task_id:
            task["status"] = status
            return {"ok": True}
    return {"ok": False, "error": "That task is not on this project."}


def _ai_summary(title):
    folded = title.casefold()
    if "date" in folded or "past" in folded:
        return "Past dates are rejected and a test covers it. Worth a look: the error is not announced to screen readers."
    if "test" in folded:
        return "A test was added. Worth a look: cover the empty input as well as a bad one."
    if "screen" in folded or "aria" in folded or "error" in folded:
        return "The error is easier to see. Worth a look: announce it to screen readers."
    return "The change is small and stays inside this project. Worth a look: add a test for the failure case."


def push_commit(state, project_id, title):
    title = (title or "").strip()
    if not title:
        return {"ok": False, "error": "Describe the change before you push it."}
    if len(title) > 120:
        return {"ok": False, "error": "Keep the commit title to 120 characters."}
    current = _membership(state, project_id)
    if not current or current["status"] != "member":
        return {"ok": False, "error": "Join the project before pushing code."}
    project = PROJECT_BY_ID[project_id]
    state["seq"] += 1
    branch = current["branch"] or f"{project['repo']}-work"
    current["branch"] = branch
    commit = {
        "id": f"c{state['seq']}",
        "title": title,
        "branch": branch,
        "state": "with_dev_lead",
        "diff": f"--- a/{project['repo']}\n+++ b/{project['repo']}\n@@\n+{title}",
    }
    current["commits"].insert(0, commit)
    current["aiSummary"] = _ai_summary(title)
    _notify(state, f"AI review finished on {branch}. A dev lead has the summary.")
    return {"ok": True, "commitId": commit["id"]}


def resubmit(state, project_id, commit_id):
    current = _membership(state, project_id)
    if not current or current["status"] != "member":
        return {"ok": False, "error": "Join the project before resubmitting."}
    for commit in current["commits"]:
        if commit["id"] == commit_id:
            if commit["state"] != "changes_requested":
                return {"ok": False, "error": "Only work that was sent back can be resubmitted."}
            commit["state"] = "with_dev_lead"
            current["aiSummary"] = _ai_summary(commit["title"])
            _notify(state, f"“{commit['title']}” is back with the dev lead.")
            return {"ok": True}
    return {"ok": False, "error": "That commit is not on this project."}


def ask_mentor(state, project_id, text):
    text = (text or "").strip()
    if not text:
        return {"ok": False, "error": "Ask a question first."}
    if len(text) > 500:
        return {"ok": False, "error": "Keep the question to 500 characters."}
    current = _membership(state, project_id)
    if not current or current["status"] != "member":
        return {"ok": False, "error": "The mentor sits in a project you have joined."}
    thread = state["messages"].setdefault(project_id, [])
    thread.append({"role": "student", "text": text})
    thread.append({"role": "mentor", "text": _mentor_reply(text)})
    return {"ok": True}


def _mentor_reply(text):
    folded = text.casefold()
    if any(word in folded for word in ("date", "string", "sort", "2026")):
        return "Compare Date objects, or timestamps from those objects. String order only matches calendar order when every part is zero-padded."
    if "test" in folded:
        return "Add one case for yesterday, one for today, and one for a blank field. Assert the message the form shows, not only true or false."
    if any(word in folded for word in ("screen", "reader", "aria", "announce")):
        return "Put the message in the form and give that node role=\"alert\", or aria-live=\"assertive\", so a screen reader speaks it when it appears."
    if any(word in folded for word in ("csv", "attendance", "export")):
        return "Write one row of headers, then one row per person. Keep the club name in its own column so staff can filter it."
    return "Say what you expected and what the code did instead. I’ll point you at the check. You write the change, then push it."


def update_bio(state, bio):
    bio = (bio or "").strip()
    if not bio:
        return {"ok": False, "error": "The bio can’t be blank."}
    if len(bio) > 280:
        return {"ok": False, "error": "Keep the bio to 280 characters."}
    state["user"]["bio"] = bio
    return {"ok": True}


def submit_exam(state, answers):
    lead = state["user"]["lead"]
    if lead["examPassed"]:
        return {"ok": False, "error": "You already passed the proctored exam."}
    if not isinstance(answers, list) or len(answers) != len(EXAM):
        return {"ok": False, "error": "Answer every question."}
    correct = 0
    for question, choice in zip(EXAM, answers):
        if not isinstance(choice, int) or choice != question["answer"]:
            continue
        correct += 1
    lead["examAttempts"] += 1
    lead["lastExamScore"] = correct
    if correct >= PASS_MARK:
        lead["examPassed"] = True
        _notify(state, "You passed the proctored exam. The next stage is in person with Ylyas.")
        return {"ok": True, "correct": correct, "passed": True}
    return {
        "ok": True,
        "correct": correct,
        "passed": False,
        "error": f"You scored {correct} of {len(EXAM)}. {PASS_MARK} are required. You can try again.",
    }


def record_in_person(state):
    """Ylyas confirms the meeting. Students cannot call this from the portal."""
    lead = state["user"]["lead"]
    if not lead["examPassed"]:
        return {"ok": False, "error": "The exam comes before the in-person stage."}
    lead["inPerson"] = True
    state["user"]["role"] = "lead"
    _notify(state, "Ylyas confirmed the meeting. Your role is now Lead.")
    return {"ok": True}


def decide(state, project_id, verdict):
    """A lead approves or sends back the commits currently with the dev lead."""
    if state["user"]["role"] != "lead":
        return {"ok": False, "error": "Only a lead can decide a review."}
    if verdict not in ("approve", "changes"):
        return {"ok": False, "error": "Verdict must be approve or changes."}
    current = _membership(state, project_id)
    if not current or current["status"] != "member":
        return {"ok": False, "error": "That student is not in this project."}
    waiting = [commit for commit in current["commits"] if commit["state"] == "with_dev_lead"]
    if not waiting:
        return {"ok": False, "error": "Nothing is waiting on a dev lead."}
    if verdict == "changes":
        for commit in waiting:
            commit["state"] = "changes_requested"
        return {"ok": True, "rewarded": False}
    for commit in waiting:
        commit["state"] = "approved"
    rewarded_now = False
    if not current["rewarded"] and current["commits"] and all(
        commit["state"] == "approved" for commit in current["commits"]
    ):
        project = PROJECT_BY_ID[project_id]
        current["rewarded"] = True
        state["user"]["earnings"] += project["bounty"]
        state["user"]["score"] += project["bounty"]
        state["user"]["approvedProjects"] += 1
        rewarded_now = True
        _notify(
            state,
            f"“{project['title']}” was approved. ${project['bounty']} was added to your record and your score.",
        )
    return {"ok": True, "rewarded": rewarded_now}


def mark_notifications_read(state):
    for note in state["notifications"]:
        note["read"] = True
    return {"ok": True}


def visible_projects(state, query="", difficulty="All", my_classes=False, urgent=False, open_score=False):
    user = state["user"]
    folded = (query or "").strip().casefold()
    found = []
    for project in PROJECTS:
        if difficulty != "All" and project["difficulty"] != difficulty:
            continue
        if my_classes and project["classCode"] not in user["classes"]:
            continue
        if urgent and not project["urgent"]:
            continue
        if open_score and user["score"] < project["minScore"]:
            continue
        if folded:
            haystack = " ".join(
                (
                    project["title"],
                    project["summary"],
                    project["department"],
                    project["classCode"] or "",
                    project["difficulty"],
                )
            ).casefold()
            if folded not in haystack:
                continue
        found.append(project)
    return found


def work_summary(state):
    member = 0
    pending = 0
    assigned = 0
    for membership in state["memberships"].values():
        if membership["status"] == "member":
            member += 1
            assigned += sum(
                1
                for task in membership["tasks"]
                if task["group"] == "assigned" and task["status"] != "done"
            )
        elif membership["status"] == "pending":
            pending += 1
    return {"member": member, "pending": pending, "assigned": assigned}


def review_view(membership):
    commits = membership["commits"] if membership else []
    if not commits:
        return {
            "pushed": False,
            "branch": None,
            "aiDone": False,
            "aiSummary": None,
            "lead": "waiting",
            "rewarded": False,
        }
    if any(commit["state"] == "with_dev_lead" for commit in commits):
        lead = "deciding"
    elif any(commit["state"] == "changes_requested" for commit in commits):
        lead = "changes"
    elif all(commit["state"] == "approved" for commit in commits):
        lead = "approved"
    else:
        lead = "waiting"
    return {
        "pushed": True,
        "branch": membership.get("branch"),
        "aiDone": True,
        "aiSummary": membership.get("aiSummary"),
        "lead": lead,
        "rewarded": bool(membership.get("rewarded")),
    }


def public_state(state):
    """Shape sent to the browser. Exam answer keys stay on the server."""
    user = state["user"]
    projects = []
    for project in PROJECTS:
        membership = _membership(state, project["id"])
        allowed, reason = eligibility(user, project)
        projects.append(
            {
                **project_public(project),
                "membership": membership["status"] if membership else "none",
                "eligible": allowed,
                "blockReason": reason,
            }
        )
    work = []
    for project in PROJECTS:
        membership = _membership(state, project["id"])
        if not membership:
            continue
        assigned_open = sum(
            1
            for task in membership["tasks"]
            if task["group"] == "assigned" and task["status"] != "done"
        )
        work.append(
            {
                "id": project["id"],
                "title": project["title"],
                "department": project["department"],
                "bounty": project["bounty"],
                "status": membership["status"],
                "reviewer": project["reviewer"],
                "assignedOpen": assigned_open,
            }
        )
    rooms = {}
    for project_id, membership in state["memberships"].items():
        if membership["status"] != "member":
            continue
        rooms[project_id] = {
            "tasks": copy.deepcopy(membership["tasks"]),
            "commits": [
                {
                    "id": commit["id"],
                    "title": commit["title"],
                    "branch": commit["branch"],
                    "state": commit["state"],
                    "diff": commit["diff"],
                }
                for commit in membership["commits"]
            ],
            "review": review_view(membership),
            "messages": copy.deepcopy(state["messages"].get(project_id, [])),
        }
    return {
        "user": {
            **copy.deepcopy(user),
            "status": status_name(user["score"]),
        },
        "projects": projects,
        "work": work,
        "summary": work_summary(state),
        "rooms": rooms,
        "notifications": copy.deepcopy(state["notifications"]),
        "exam": [
            {"id": question["id"], "prompt": question["prompt"], "choices": list(question["choices"])}
            for question in EXAM
        ],
        "examPassMark": PASS_MARK,
    }


def dispatch(state, action):
    kind = action.get("type")
    if kind == "join":
        return join(state, action.get("projectId"))
    if kind == "task":
        return update_task(state, action.get("projectId"), action.get("taskId"), action.get("status"))
    if kind == "push":
        return push_commit(state, action.get("projectId"), action.get("title"))
    if kind == "resubmit":
        return resubmit(state, action.get("projectId"), action.get("commitId"))
    if kind == "mentor":
        return ask_mentor(state, action.get("projectId"), action.get("text"))
    if kind == "bio":
        return update_bio(state, action.get("bio"))
    if kind == "exam":
        return submit_exam(state, action.get("answers"))
    if kind == "read":
        return mark_notifications_read(state)
    if kind == "reset":
        fresh = default_state()
        state.clear()
        state.update(fresh)
        return {"ok": True}
    return {"ok": False, "error": "Unknown action."}
