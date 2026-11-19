package main

import "strings"

func portalStatus(score int) string {
	switch {
	case score >= 400:
		return "Honored"
	case score >= 300:
		return "Expert"
	case score >= 200:
		return "Skilled"
	case score >= 100:
		return "Builder"
	default:
		return "Rookie"
	}
}

func pint(v any) int {
	switch n := v.(type) {
	case int:
		return n
	case int64:
		return int(n)
	case float64:
		return int(n)
	default:
		return 0
	}
}

func pmap(v any) map[string]any {
	m, _ := v.(map[string]any)
	return m
}

func plist(v any) []any {
	list, _ := v.([]any)
	if list == nil {
		return []any{}
	}
	return list
}

func pstr(v any) string {
	s, _ := v.(string)
	return s
}

func pbool(v any) bool {
	b, _ := v.(bool)
	return b
}

type portalProject struct {
	fields map[string]any
	tasks  [][5]string
}

func (p portalProject) id() string { return pstr(p.fields["id"]) }

var portalProjects = []portalProject{
	{map[string]any{"id": "shuttle", "department": "Campus Ops", "title": "Build a shuttle timetable widget for the student portal", "summary": "Campus Ops needs it within 48 hours. It is tough, but open to every class and every score: a chance to show you deserve a status raise.", "detail": "Campus Ops needs a timetable widget on the student portal this week. It is open even if you are not taking the class it would normally belong to.", "bounty": 35, "difficulty": "Hard", "minScore": 0, "due": "Due in 48 h", "urgent": true, "openToAllClasses": true, "classCode": nil, "approval": "dev_lead", "reviewer": "dev lead", "repo": "campus-shuttle", "stack": []any{"React"}}, [][5]string{{"route-data", "Load the weekday shuttle stops", "assigned", "todo", "Due in 48 h"}, {"widget", "Render the next three departures", "backlog", "todo", ""}}},
	{map[string]any{"id": "library", "department": "Library", "title": "Fix form validation on the room-booking page", "summary": "The booking form accepts dates in the past. Fix the check and add a test.", "detail": "The library’s booking form accepts dates in the past. Find the check, fix it, and add a test so it stays fixed.", "bounty": 18, "difficulty": "Easy", "minScore": 0, "due": "Due in 3 days", "urgent": false, "openToAllClasses": true, "classCode": nil, "approval": "open", "reviewer": "dev lead", "repo": "library-booking", "stack": []any{"JavaScript", "HTML"}}, [][5]string{{"past-dates", "Reject past dates in the booking form", "assigned", "in_progress", "Due in 3 days"}, {"date-test", "Add a test for the date check", "backlog", "todo", ""}, {"announce", "Announce the error to screen readers", "backlog", "todo", ""}}},
	{map[string]any{"id": "ranking", "department": "CMPS433", "title": "Rebuild course-search ranking", "summary": "Class project. Results should rank by relevance, not by course number.", "detail": "Search results currently follow the course number. Rank them by how well they match the query. The professor of CMPS433 approves the work.", "bounty": 60, "difficulty": "Hard", "minScore": 300, "due": "Due in 2 weeks", "urgent": false, "openToAllClasses": false, "classCode": "CMPS433", "approval": "professor", "reviewer": "professor of CMPS433", "repo": "course-search", "stack": []any{"Python"}}, [][5]string{{"rank", "Rank matches by query relevance", "assigned", "todo", "Due in 2 weeks"}, {"tie", "Break ties with the course title", "backlog", "todo", ""}}},
	{map[string]any{"id": "attendance", "department": "Student Affairs", "title": "Export club attendance to CSV", "summary": "Staff copy attendance by hand each week. Write the script that does it.", "detail": "Staff copy attendance by hand each week. Write the script that does it in one click.", "bounty": 20, "difficulty": "Easy", "minScore": 0, "due": "Due in 5 days", "urgent": false, "openToAllClasses": true, "classCode": nil, "approval": "open", "reviewer": "dev lead", "repo": "club-attendance", "stack": []any{"Python"}}, [][5]string{{"export", "Export the attendance table to CSV", "assigned", "in_progress", "Due in 5 days"}, {"column", "Include the club name column", "assigned", "todo", "Due in 5 days"}}},
	{map[string]any{"id": "darkmode", "department": "Events", "title": "Add dark mode to the events calendar", "summary": "Add a theme switch that remembers the visitor’s choice.", "detail": "Add a theme switch on the events calendar and remember the visitor’s choice on the next visit.", "bounty": 20, "difficulty": "Medium", "minScore": 0, "due": "Due in 1 week", "urgent": false, "openToAllClasses": true, "classCode": nil, "approval": "open", "reviewer": "dev lead", "repo": "events-calendar", "stack": []any{"CSS", "JavaScript"}}, [][5]string{{"theme", "Add a theme switch to the calendar", "assigned", "todo", "Due in 1 week"}, {"remember", "Remember the choice for the next visit", "backlog", "todo", ""}}},
	{map[string]any{"id": "faq", "department": "Admissions", "title": "Repair broken links on the FAQ", "summary": "Find every dead link on the FAQ pages and point it somewhere real.", "detail": "Find every dead link on the admissions FAQ and point it at a page that exists.", "bounty": 18, "difficulty": "Easy", "minScore": 0, "due": "Due in 4 days", "urgent": false, "openToAllClasses": true, "classCode": nil, "approval": "open", "reviewer": "dev lead", "repo": "admissions-faq", "stack": []any{"HTML"}}, [][5]string{{"links", "Replace each dead FAQ link", "assigned", "todo", "Due in 4 days"}, {"check", "Add a check that fails on a 404", "backlog", "todo", ""}}},
	{map[string]any{"id": "transcript", "department": "Registrar", "title": "Speed up the transcript request form", "summary": "The form reloads on every field. Profile it and remove the reloads.", "detail": "The transcript form reloads the page on every field. Find the cause and remove the reloads. A dev lead approves the change.", "bounty": 45, "difficulty": "Hard", "minScore": 250, "due": "Due in 10 days", "urgent": false, "openToAllClasses": true, "classCode": nil, "approval": "dev_lead", "reviewer": "dev lead", "repo": "transcript-form", "stack": []any{"JavaScript"}}, [][5]string{{"profile", "Find which field triggers the reload", "assigned", "todo", "Due in 10 days"}, {"fix", "Submit the form without reloading", "backlog", "todo", ""}}},
}

func projectByID(id string) (portalProject, bool) {
	for _, project := range portalProjects {
		if project.id() == id {
			return project, true
		}
	}
	return portalProject{}, false
}

func cloneTasks(project portalProject) []any {
	tasks := []any{}
	for _, row := range project.tasks {
		var due any
		if row[4] != "" {
			due = row[4]
		}
		tasks = append(tasks, map[string]any{"id": row[0], "title": row[1], "group": row[2], "status": row[3], "due": due})
	}
	return tasks
}

func defaultPortal() map[string]any {
	library, _ := projectByID("library")
	attendance, _ := projectByID("attendance")
	return map[string]any{
		"user": map[string]any{
			"name": "Sam Carter", "initials": "SC",
			"bio":    "Computer Science student. Learning by shipping real work for BAU.",
			"skills": []any{"JavaScript", "Python"}, "classes": []any{"CMPS433"},
			"github": "connected", "role": "student", "score": 180, "earnings": 38, "approvedProjects": 2,
			"lead": map[string]any{"examPassed": false, "examAttempts": 0, "lastExamScore": nil, "inPerson": false},
		},
		"memberships": map[string]any{
			"library": map[string]any{
				"status": "member", "rewarded": false, "branch": "fix-date-validation",
				"aiSummary": "Past dates are rejected and a test covers it. Worth a look: the error is not announced to screen readers.",
				"tasks":     cloneTasks(library),
				"commits": []any{
					map[string]any{"id": "c1", "title": "Reject dates before today", "branch": "fix-date-validation", "state": "approved", "diff": "--- a/booking-form.js\n+++ b/booking-form.js\n@@\n-if (date) return true;\n+if (!date || date < today()) {\n+  return fail('Pick a future date');\n+}"},
					map[string]any{"id": "c2", "title": "Add test for past-date check", "branch": "fix-date-validation", "state": "with_dev_lead", "diff": "--- a/booking-form.test.js\n+++ b/booking-form.test.js\n@@\n+test('rejects a date before today', () => {\n+  expect(validate('2000-01-01')).toBe(false);\n+});"},
					map[string]any{"id": "c3", "title": "Show inline error message", "branch": "fix-date-validation", "state": "changes_requested", "diff": "--- a/booking-form.js\n+++ b/booking-form.js\n@@\n+showError('Pick a future date');"},
				},
			},
			"attendance": map[string]any{"status": "member", "rewarded": false, "branch": nil, "aiSummary": nil, "tasks": cloneTasks(attendance), "commits": []any{}},
		},
		"messages":      map[string]any{"library": []any{map[string]any{"role": "mentor", "text": "Your date check compares strings, so “2026-1-5” sorts after “2026-12-01”. What could you compare instead?"}}},
		"notifications": []any{map[string]any{"id": "n1", "text": "A dev lead is reading your diff on fix-date-validation.", "read": false}},
		"seq":           10,
	}
}

func memberships(state map[string]any) map[string]any {
	m := pmap(state["memberships"])
	if m == nil {
		m = map[string]any{}
		state["memberships"] = m
	}
	return m
}

func portalUser(state map[string]any) map[string]any { return pmap(state["user"]) }

func eligible(user map[string]any, project portalProject) (bool, any) {
	classCode := pstr(project.fields["classCode"])
	if classCode != "" && !pbool(project.fields["openToAllClasses"]) {
		found := false
		for _, item := range plist(user["classes"]) {
			if pstr(item) == classCode {
				found = true
			}
		}
		if !found {
			return false, "This project is for students in " + classCode + "."
		}
	}
	minScore := pint(project.fields["minScore"])
	if pint(user["score"]) < minScore {
		return false, "This project needs a score of " + intString(minScore) + ". Yours is " + intString(pint(user["score"])) + "."
	}
	return true, nil
}

func notifyPortal(state map[string]any, text string) {
	state["seq"] = pint(state["seq"]) + 1
	notes := plist(state["notifications"])
	state["notifications"] = append([]any{map[string]any{"id": "n" + intString(pint(state["seq"])), "text": text, "read": false}}, notes...)
}

func dispatchPortal(state map[string]any, action map[string]any) map[string]any {
	switch pstr(action["type"]) {
	case "join":
		return joinPortal(state, pstr(action["projectId"]))
	case "task":
		return taskPortal(state, pstr(action["projectId"]), pstr(action["taskId"]), pstr(action["status"]))
	case "push":
		return pushPortal(state, pstr(action["projectId"]), pstr(action["title"]))
	case "resubmit":
		return resubmitPortal(state, pstr(action["projectId"]), pstr(action["commitId"]))
	case "mentor":
		return mentorPortal(state, pstr(action["projectId"]), pstr(action["text"]))
	case "bio":
		return bioPortal(state, pstr(action["bio"]))
	case "exam":
		return examPortal(state, action["answers"])
	case "read":
		for _, note := range plist(state["notifications"]) {
			if item := pmap(note); item != nil {
				item["read"] = true
			}
		}
		return map[string]any{"ok": true}
	case "reset":
		fresh := defaultPortal()
		for key := range state {
			delete(state, key)
		}
		for key, value := range fresh {
			state[key] = value
		}
		return map[string]any{"ok": true}
	default:
		return map[string]any{"ok": false, "error": "Unknown action."}
	}
}

func joinPortal(state map[string]any, projectID string) map[string]any {
	project, ok := projectByID(projectID)
	if !ok {
		return map[string]any{"ok": false, "error": "That project is not on the board."}
	}
	current := pmap(memberships(state)[projectID])
	if current != nil && pstr(current["status"]) == "member" {
		return map[string]any{"ok": true, "result": "member"}
	}
	if current != nil && pstr(current["status"]) == "pending" {
		return map[string]any{"ok": true, "result": "pending"}
	}
	allowed, reason := eligible(portalUser(state), project)
	if !allowed {
		return map[string]any{"ok": false, "error": reason}
	}
	if pstr(project.fields["approval"]) == "open" {
		memberships(state)[projectID] = map[string]any{"status": "member", "rewarded": false, "branch": nil, "aiSummary": nil, "tasks": cloneTasks(project), "commits": []any{}}
		notifyPortal(state, "You’re in “"+pstr(project.fields["title"])+"”.")
		return map[string]any{"ok": true, "result": "member"}
	}
	memberships(state)[projectID] = map[string]any{"status": "pending", "rewarded": false, "branch": nil, "aiSummary": nil, "tasks": []any{}, "commits": []any{}}
	who := "a dev lead"
	if pstr(project.fields["approval"]) == "professor" {
		who = "the professor"
	}
	notifyPortal(state, "Request sent for “"+pstr(project.fields["title"])+"”. Waiting on "+who+".")
	return map[string]any{"ok": true, "result": "pending"}
}

func taskPortal(state map[string]any, projectID, taskID, status string) map[string]any {
	if status != "todo" && status != "in_progress" && status != "done" {
		return map[string]any{"ok": false, "error": "Unknown task status."}
	}
	current := pmap(memberships(state)[projectID])
	if current == nil || pstr(current["status"]) != "member" {
		return map[string]any{"ok": false, "error": "Join the project before updating its tasks."}
	}
	for _, task := range plist(current["tasks"]) {
		item := pmap(task)
		if item != nil && pstr(item["id"]) == taskID {
			item["status"] = status
			return map[string]any{"ok": true}
		}
	}
	return map[string]any{"ok": false, "error": "That task is not on this project."}
}

func pushPortal(state map[string]any, projectID, title string) map[string]any {
	title = strings.TrimSpace(title)
	if title == "" {
		return map[string]any{"ok": false, "error": "Describe the change before you push it."}
	}
	if len(title) > 120 {
		return map[string]any{"ok": false, "error": "Keep the commit title to 120 characters."}
	}
	current := pmap(memberships(state)[projectID])
	if current == nil || pstr(current["status"]) != "member" {
		return map[string]any{"ok": false, "error": "Join the project before pushing code."}
	}
	project, _ := projectByID(projectID)
	state["seq"] = pint(state["seq"]) + 1
	branch := pstr(current["branch"])
	if branch == "" {
		branch = pstr(project.fields["repo"]) + "-work"
	}
	current["branch"] = branch
	commit := map[string]any{"id": "c" + intString(pint(state["seq"])), "title": title, "branch": branch, "state": "with_dev_lead", "diff": "--- a/" + pstr(project.fields["repo"]) + "\n+++ b/" + pstr(project.fields["repo"]) + "\n@@\n+" + title}
	current["commits"] = append([]any{commit}, plist(current["commits"])...)
	current["aiSummary"] = aiSummary(title)
	notifyPortal(state, "AI review finished on "+branch+". A dev lead has the summary.")
	return map[string]any{"ok": true, "commitId": commit["id"]}
}

func aiSummary(title string) string {
	folded := strings.ToLower(title)
	switch {
	case strings.Contains(folded, "date") || strings.Contains(folded, "past"):
		return "Past dates are rejected and a test covers it. Worth a look: the error is not announced to screen readers."
	case strings.Contains(folded, "test"):
		return "A test was added. Worth a look: cover the empty input as well as a bad one."
	case strings.Contains(folded, "screen") || strings.Contains(folded, "aria") || strings.Contains(folded, "error"):
		return "The error is easier to see. Worth a look: announce it to screen readers."
	default:
		return "The change is small and stays inside this project. Worth a look: add a test for the failure case."
	}
}

func resubmitPortal(state map[string]any, projectID, commitID string) map[string]any {
	current := pmap(memberships(state)[projectID])
	if current == nil || pstr(current["status"]) != "member" {
		return map[string]any{"ok": false, "error": "Join the project before resubmitting."}
	}
	for _, commit := range plist(current["commits"]) {
		item := pmap(commit)
		if item == nil || pstr(item["id"]) != commitID {
			continue
		}
		if pstr(item["state"]) != "changes_requested" {
			return map[string]any{"ok": false, "error": "Only work that was sent back can be resubmitted."}
		}
		item["state"] = "with_dev_lead"
		current["aiSummary"] = aiSummary(pstr(item["title"]))
		notifyPortal(state, "“"+pstr(item["title"])+"” is back with the dev lead.")
		return map[string]any{"ok": true}
	}
	return map[string]any{"ok": false, "error": "That commit is not on this project."}
}

func mentorPortal(state map[string]any, projectID, text string) map[string]any {
	text = strings.TrimSpace(text)
	if text == "" {
		return map[string]any{"ok": false, "error": "Ask a question first."}
	}
	if len(text) > 500 {
		return map[string]any{"ok": false, "error": "Keep the question to 500 characters."}
	}
	current := pmap(memberships(state)[projectID])
	if current == nil || pstr(current["status"]) != "member" {
		return map[string]any{"ok": false, "error": "The mentor sits in a project you have joined."}
	}
	messages := pmap(state["messages"])
	if messages == nil {
		messages = map[string]any{}
		state["messages"] = messages
	}
	thread := plist(messages[projectID])
	reply, _ := mentorReply(text, nil, nil, nil, 1, nil)
	thread = append(thread, map[string]any{"role": "student", "text": text}, map[string]any{"role": "mentor", "text": reply})
	messages[projectID] = thread
	return map[string]any{"ok": true}
}

func bioPortal(state map[string]any, bio string) map[string]any {
	bio = strings.TrimSpace(bio)
	if bio == "" {
		return map[string]any{"ok": false, "error": "The bio can’t be blank."}
	}
	if len(bio) > 280 {
		return map[string]any{"ok": false, "error": "Keep the bio to 280 characters."}
	}
	portalUser(state)["bio"] = bio
	return map[string]any{"ok": true}
}

func examPortal(state map[string]any, raw any) map[string]any {
	lead := pmap(portalUser(state)["lead"])
	if pbool(lead["examPassed"]) {
		return map[string]any{"ok": false, "error": "You already passed the proctored exam."}
	}
	answers, ok := raw.([]any)
	if !ok || len(answers) != len(exam) {
		return map[string]any{"ok": false, "error": "Answer every question."}
	}
	correct := 0
	for i, question := range exam {
		if whole, ok := whole(answers[i]); ok && whole == question.Answer {
			correct++
		}
	}
	lead["examAttempts"] = pint(lead["examAttempts"]) + 1
	lead["lastExamScore"] = correct
	if correct >= passMark {
		lead["examPassed"] = true
		notifyPortal(state, "You passed the proctored exam. The next stage is in person with Ylyas.")
		return map[string]any{"ok": true, "correct": correct, "passed": true}
	}
	return map[string]any{"ok": true, "correct": correct, "passed": false, "error": "You scored " + intString(correct) + " of " + intString(len(exam)) + ". " + intString(passMark) + " are required. You can try again."}
}

func reviewView(membership map[string]any) map[string]any {
	commits := plist(membership["commits"])
	if len(commits) == 0 {
		return map[string]any{"pushed": false, "branch": nil, "aiDone": false, "aiSummary": nil, "lead": "waiting", "rewarded": false}
	}
	lead := "waiting"
	allApproved := true
	for _, commit := range commits {
		item := pmap(commit)
		switch pstr(item["state"]) {
		case "with_dev_lead":
			lead = "deciding"
			allApproved = false
		case "changes_requested":
			if lead != "deciding" {
				lead = "changes"
			}
			allApproved = false
		case "approved":
		default:
			allApproved = false
		}
	}
	if allApproved {
		lead = "approved"
	}
	return map[string]any{"pushed": true, "branch": membership["branch"], "aiDone": true, "aiSummary": membership["aiSummary"], "lead": lead, "rewarded": pbool(membership["rewarded"])}
}

func publicPortal(state map[string]any) map[string]any {
	user := portalUser(state)
	projects := []any{}
	work := []any{}
	for _, project := range portalProjects {
		membership := pmap(memberships(state)[project.id()])
		allowed, reason := eligible(user, project)
		card := map[string]any{}
		for key, value := range project.fields {
			card[key] = value
		}
		status := "none"
		if membership != nil {
			status = pstr(membership["status"])
		}
		card["membership"] = status
		card["eligible"] = allowed
		card["blockReason"] = reason
		projects = append(projects, card)
		if membership == nil {
			continue
		}
		open := 0
		for _, task := range plist(membership["tasks"]) {
			item := pmap(task)
			if pstr(item["group"]) == "assigned" && pstr(item["status"]) != "done" {
				open++
			}
		}
		work = append(work, map[string]any{"id": project.id(), "title": project.fields["title"], "department": project.fields["department"], "bounty": project.fields["bounty"], "status": membership["status"], "reviewer": project.fields["reviewer"], "assignedOpen": open})
	}
	rooms := map[string]any{}
	for id, raw := range memberships(state) {
		membership := pmap(raw)
		if membership == nil || pstr(membership["status"]) != "member" {
			continue
		}
		commits := []any{}
		for _, commit := range plist(membership["commits"]) {
			item := pmap(commit)
			commits = append(commits, map[string]any{"id": item["id"], "title": item["title"], "branch": item["branch"], "state": item["state"], "diff": item["diff"]})
		}
		messages := pmap(state["messages"])
		rooms[id] = map[string]any{"tasks": plist(membership["tasks"]), "commits": commits, "review": reviewView(membership), "messages": plist(messages[id])}
	}
	member, pending, assigned := 0, 0, 0
	for _, raw := range memberships(state) {
		membership := pmap(raw)
		if pstr(membership["status"]) == "member" {
			member++
			for _, task := range plist(membership["tasks"]) {
				item := pmap(task)
				if pstr(item["group"]) == "assigned" && pstr(item["status"]) != "done" {
					assigned++
				}
			}
		} else if pstr(membership["status"]) == "pending" {
			pending++
		}
	}
	shown := map[string]any{}
	for key, value := range user {
		shown[key] = value
	}
	shown["status"] = portalStatus(pint(user["score"]))
	questions := []any{}
	for _, question := range exam {
		questions = append(questions, map[string]any{"id": question.ID, "prompt": question.Prompt, "choices": question.Choices})
	}
	return map[string]any{
		"user": shown, "projects": projects, "work": work,
		"summary": map[string]any{"member": member, "pending": pending, "assigned": assigned},
		"rooms":   rooms, "notifications": plist(state["notifications"]),
		"exam": questions, "examPassMark": passMark,
	}
}
