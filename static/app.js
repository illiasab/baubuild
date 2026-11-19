const ui = {
  view: "projects",
  query: "",
  difficulty: "All",
  myClasses: false,
  urgent: false,
  openScore: false,
  projectId: "library",
  tab: "tasks",
  notes: false,
  diffId: null,
};

let state = null;
let fingerArmed = false;

const app = document.getElementById("app");
const toastEl = document.getElementById("toast");

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  }[ch]));
}

function toast(message) {
  if (!message) return;
  toastEl.textContent = message;
  toastEl.classList.add("show");
  clearTimeout(toastEl._t);
  toastEl._t = setTimeout(() => toastEl.classList.remove("show"), 4200);
}

async function load() {
  const response = await fetch("/api/state");
  if (!response.ok) throw new Error("The board did not load.");
  state = await response.json();
  render();
}

async function act(action) {
  const response = await fetch("/api/action", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(action),
  });
  const body = await response.json();
  if (body.state) state = body.state;
  if (!body.ok) toast(body.error || "That didn’t go through.");
  render();
  return body;
}

function projectById(id) {
  return state.projects.find((project) => project.id === id);
}

function filtered() {
  const query = ui.query.trim().toLowerCase();
  return state.projects.filter((project) => {
    if (ui.difficulty !== "All" && project.difficulty !== ui.difficulty) return false;
    if (ui.myClasses && !state.user.classes.includes(project.classCode)) return false;
    if (ui.urgent && !project.urgent) return false;
    if (ui.openScore && state.user.score < project.minScore) return false;
    if (!query) return true;
    const hay = [project.title, project.summary, project.department, project.classCode, project.difficulty]
      .join(" ")
      .toLowerCase();
    return hay.includes(query);
  });
}

function logo() {
  return `<svg aria-hidden="true" width="28" height="28" viewBox="0 0 28 28"><rect x="2" y="16" width="24" height="9" fill="#FFD60A"></rect><rect x="2" y="4" width="11" height="9" fill="#FFD60A"></rect><rect x="15.75" y="4.75" width="9.5" height="7.5" fill="none" stroke="#FFD60A" stroke-width="1.5" stroke-dasharray="2.5 2"></rect></svg>`;
}

function icon(name) {
  const common = `width="20" height="20" viewBox="0 0 22 22" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"`;
  if (name === "projects") return `<svg aria-hidden="true" ${common}><rect x="3" y="12" width="16" height="6" rx="1.5"></rect><rect x="3" y="4" width="7" height="6" rx="1.5"></rect><rect x="12" y="4" width="7" height="6" rx="1.5"></rect></svg>`;
  if (name === "work") return `<svg aria-hidden="true" ${common}><path d="M8 6l-5 5 5 5M14 6l5 5-5 5"></path></svg>`;
  if (name === "review") return `<svg aria-hidden="true" ${common}><circle cx="11" cy="11" r="8"></circle><path d="M7.5 11.5l2.5 2.5 4.5-5"></path></svg>`;
  return `<svg aria-hidden="true" ${common}><circle cx="11" cy="8" r="3.5"></circle><path d="M4 19c1-3.5 4-5 7-5s6 1.5 7 5"></path></svg>`;
}

function shell(title, body, extraTools = "") {
  const user = state.user;
  const unread = state.notifications.some((note) => !note.read);
  const item = (view, label, glyph) => {
    const current = ui.view === view || (view === "projects" && ui.view === "project");
    return `<button type="button" data-action="nav" data-view="${view}" ${current ? 'aria-current="page"' : ""}>${icon(glyph)}<span>${label}</span></button>`;
  };
  const notes = ui.notes
    ? `<div class="notes" role="dialog" aria-label="Notifications">${
        state.notifications.length
          ? state.notifications.map((note) => `<p class="${note.read ? "" : "unread"}">${esc(note.text)}</p>`).join("")
          : "<p>No notifications yet.</p>"
      }</div>`
    : "";
  return `<div class="shell">
    <aside class="sidebar">
      <a class="brand" href="/" data-action="nav" data-view="projects">${logo()}<span>BAU BUILD</span></a>
      <nav class="nav" aria-label="Portal">
        ${item("projects", "Projects", "projects")}
        ${item("work", "My Work", "work")}
        ${item("review", "Review", "review")}
        ${item("profile", "Profile", "profile")}
      </nav>
      <button class="who" type="button" data-action="nav" data-view="profile">
        <span class="avatar">${esc(user.initials)}</span>
        <span><strong>${esc(user.name)}</strong><small>Score ${user.score} · ${esc(user.status)}</small></span>
      </button>
    </aside>
    <section class="main">
      <header class="top">
        <h1>${esc(title)}</h1>
        <div class="tools">
          ${extraTools}
          <button class="icon-btn" type="button" data-action="notes" aria-expanded="${ui.notes}" aria-label="Notifications">${unread ? '<span class="dot"></span>' : ""}<svg aria-hidden="true" width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 8a5 5 0 0110 0c0 4 1.5 5 1.5 5h-13S5 12 5 8zM8.5 16a1.6 1.6 0 003 0"></path></svg></button>
          ${notes}
        </div>
      </header>
      <div class="content">${body}</div>
    </section>
  </div>`;
}

function joinLabel(project) {
  if (project.membership === "member") return "I’m In";
  if (project.membership === "pending") return "Requested";
  return project.approval === "open" ? "I’m In" : "Request";
}

function policy(project) {
  if (project.approval === "open") return "Everyone welcome";
  if (project.approval === "professor") return "Professor approves";
  return "Dev lead approves";
}

function scoreLabel(project) {
  return project.minScore <= 0 ? "Any score" : `Score ${project.minScore}+`;
}

function heroMark() {
  return `<svg aria-hidden="true" viewBox="0 0 120 148" width="130" height="160"><polygon points="60,18 84,31 60,44 36,31" fill="#FFE76B"></polygon><polygon points="36,31 60,44 60,70 36,57" fill="#FFD60A"></polygon><polygon points="84,31 60,44 60,70 84,57" fill="#D9B400"></polygon><polygon points="36,80 60,93 60,119 36,106" fill="#1A2444" stroke="#8FA8FF"></polygon><polygon points="84,94 108,107 108,133 84,120" fill="#131B36" stroke="#8FA8FF"></polygon></svg>`;
}

function projectsView() {
  const matches = filtered();
  const featured = matches.find((project) => project.urgent);
  const cards = matches.filter((project) => !featured || project.id !== featured.id);
  const pressed = (name) => (ui.difficulty === name ? "true" : "false");
  const filters = `<div class="filters">
      <div class="segment" role="group" aria-label="Difficulty">
        ${["All", "Easy", "Medium", "Hard"].map((name) => `<button type="button" data-action="difficulty" data-value="${name}" aria-pressed="${pressed(name)}">${name}</button>`).join("")}
      </div>
      <button type="button" class="chip" data-action="toggle" data-flag="myClasses" aria-pressed="${ui.myClasses}">My Classes</button>
      <button type="button" class="chip" data-action="toggle" data-flag="urgent" aria-pressed="${ui.urgent}">Urgent</button>
      <button type="button" class="chip" data-action="toggle" data-flag="openScore" aria-pressed="${ui.openScore}">Open to my score</button>
    </div>`;
  const banner = featured
    ? `<section class="hero">
        <div>
          <span class="kicker">${featured.urgent ? "URGENT · " : ""}${esc(featured.difficulty.toUpperCase())} · ${esc(scoreLabel(featured).toUpperCase())}${featured.openToAllClasses ? " WELCOME" : ""}</span>
          <h2>${esc(featured.title)}</h2>
          <p>${esc(featured.summary)}</p>
          <button class="solid" type="button" data-action="open" data-project="${featured.id}">View project · $${featured.bounty}</button>
        </div>
        ${heroMark()}
      </section>`
    : "";
  fingerArmed = true;
  const grid = cards.length
    ? `<div class="grid">${cards.map(card).join("")}</div>`
    : `<div class="card empty"><h3>No projects match</h3><p>Clear a filter or search for something else on the board.</p></div>`;
  const search = `<input class="search" id="search" type="search" aria-label="Search projects" placeholder="Search projects" value="${esc(ui.query)}">`;
  return shell("Projects", filters + banner + grid, search);
}

function card(project) {
  const pending = project.membership === "pending";
  const guide = fingerArmed && !pending && project.membership !== "member";
  if (guide) fingerArmed = false;
  return `<article class="card">
    <div class="row"><span class="kicker" style="color:#4A5468">${esc(project.department.toUpperCase())}</span><span class="price">$${project.bounty}</span></div>
    <h3>${esc(project.title)}</h3>
    <p>${esc(project.summary)}</p>
    <div class="pills"><span class="pill">${esc(project.difficulty)}</span><span class="pill">${esc(scoreLabel(project))}</span><span class="pill">${esc(project.due)}</span></div>
    <div class="foot"><span>${esc(policy(project))}</span>
      <button class="${project.approval === "open" && !pending ? "solid" : "ghost"}" type="button" data-action="join" data-project="${project.id}" ${guide ? "data-finger" : ""} ${pending ? "disabled" : ""}>${joinLabel(project)}</button>
    </div>
  </article>`;
}

function workView() {
  const summary = state.summary;
  const stats = `<div class="stats">
      <div class="stat"><b>${summary.member}</b> I’m in</div>
      <div class="stat"><b>${summary.pending}</b> Waiting for approval</div>
      <div class="stat"><b>${summary.assigned}</b> Tasks assigned to me</div>
    </div>`;
  const rows = state.work.map((item) => {
    const standing = item.status === "member"
      ? `${item.assignedOpen} ${item.assignedOpen === 1 ? "task" : "tasks"} assigned to you`
      : `Reviewing: ${item.reviewer}`;
    const status = item.status === "member" ? "I’m In" : "In Review";
    const open = item.status === "member"
      ? `<button class="ghost" type="button" data-action="workspace" data-project="${item.id}">Open</button>`
      : `<span class="quiet">Waiting</span>`;
    return `<tr><td>${open}</td><td><strong>${esc(item.title)}</strong></td><td>${esc(item.department)}</td><td>${status}</td><td>${esc(standing)}</td><td>$${item.bounty}</td></tr>`;
  }).join("");
  const table = `<table class="table"><thead><tr><th></th><th>Project</th><th>From</th><th>Status</th><th>Where it stands</th><th>Bounty</th></tr></thead><tbody>${rows}</tbody></table>
    <p class="quiet">Most projects let you in straight away. A few need a professor or dev lead to approve you first. A request never lowers the score gate.</p>`;
  return shell("My Work", stats + table);
}

function detailView() {
  const project = projectById(ui.projectId);
  if (!project) return shell("Project", `<p>That project is not on the board.</p>`);
  const action = project.membership === "pending"
    ? `<button class="ghost" type="button" disabled>Requested</button>`
    : `<button class="${project.approval === "open" ? "solid" : "ghost"}" type="button" data-action="join" data-project="${project.id}">${joinLabel(project)}</button>`;
  const block = project.eligible ? "" : `<p>${esc(project.blockReason)}</p>`;
  const body = `<p class="crumbs"><button type="button" data-action="nav" data-view="projects">Projects</button> / ${esc(project.department)}</p>
    <article class="panel">
      <span class="kicker">${esc(project.department.toUpperCase())} · ${esc(project.difficulty.toUpperCase())}</span>
      <h2>${esc(project.title)}</h2>
      <p>${esc(project.detail)}</p>
      <div class="pills" style="margin:14px 0"><span class="pill">Bounty $${project.bounty}</span><span class="pill">${esc(project.difficulty)}</span><span class="pill">${esc(project.due)}</span><span class="pill">${esc(scoreLabel(project))}</span></div>
      <p>GitHub repo ${esc(project.repo)} · Reviewed by ${project.approval === "open" ? "AI, then a dev lead" : esc(project.reviewer)} · ${esc(policy(project))}</p>
      ${block}
      <div style="margin-top:16px">${action}</div>
    </article>`;
  return shell("Project", body);
}

function statusWord(status) {
  if (status === "in_progress") return "In Progress";
  if (status === "done") return "Done";
  if (status === "approved") return "Approved";
  if (status === "with_dev_lead") return "With Dev Lead";
  if (status === "changes_requested") return "Changes Requested";
  return "To Do";
}

function nextTaskStatus(status) {
  if (status === "todo") return "in_progress";
  if (status === "in_progress") return "done";
  return "todo";
}

function workspaceView() {
  const room = state.rooms[ui.projectId];
  const project = projectById(ui.projectId);
  if (!project || !room) {
    const members = state.projects.filter((item) => item.membership === "member");
    if (!members.length) {
      return shell("Review", `<div class="card empty"><h3>You are not in a project yet</h3><p>Join one from the board. The mentor and the review timeline show up after that.</p></div>`);
    }
    ui.projectId = members[0].id;
    return workspaceView();
  }
  const options = state.projects
    .filter((item) => item.membership === "member")
    .map((item) => `<option value="${item.id}" ${item.id === project.id ? "selected" : ""}>${esc(item.title)}</option>`)
    .join("");
  const tasks = room.tasks.map((task) => {
    const verb = task.status === "done" ? "Reopen" : task.status === "todo" ? "Start" : "Mark done";
    return `<div class="task"><div><strong>${esc(task.title)}</strong><div class="quiet">${task.due ? esc(task.due) + " · " : ""}${statusWord(task.status)}</div></div><button class="ghost" type="button" data-action="task" data-project="${project.id}" data-task="${task.id}" data-status="${nextTaskStatus(task.status)}">${verb}</button></div>`;
  });
  const assigned = room.tasks.filter((task) => task.group === "assigned");
  const backlog = room.tasks.filter((task) => task.group !== "assigned");
  const taskPane = `<div class="panel"><h2>My Tasks</h2>
      <p class="group-label">ASSIGNED TO YOU</p>${assigned.map((_, i) => tasks[room.tasks.indexOf(assigned[i])]).join("") || "<p>Nothing assigned.</p>"}
      <p class="group-label">BACKLOG</p>${backlog.map((_, i) => tasks[room.tasks.indexOf(backlog[i])]).join("") || "<p>Backlog is clear.</p>"}
    </div>`;
  const commits = room.commits.map((commit) => {
    const extra = commit.state === "changes_requested"
      ? `<button class="solid" type="button" data-action="resubmit" data-project="${project.id}" data-commit="${commit.id}">Resubmit</button>`
      : "";
    const open = ui.diffId === commit.id ? `<pre class="diff">${esc(commit.diff)}</pre>` : "";
    return `<div class="commit"><div><strong>${esc(commit.title)}</strong><div class="quiet">${esc(commit.branch)} · ${statusWord(commit.state)}</div>${open}</div><div style="display:flex;gap:8px"><button class="ghost" type="button" data-action="diff" data-commit="${commit.id}">View diff</button>${extra}</div></div>`;
  }).join("") || "<p>Nothing pushed yet. Describe a change below and the AI review will run on it.</p>";
  const changePane = `<div class="panel"><h2>Code you pushed</h2>${commits}
      <form class="composer" data-action="push" data-project="${project.id}" style="margin-top:16px">
        <input class="field" id="push-title" name="title" maxlength="120" placeholder="Commit title" aria-label="Commit title">
        <button class="solid" type="submit">Push</button>
      </form>
    </div>`;
  const review = room.review;
  const steps = [
    ["Pushed to GitHub", review.pushed, review.pushed && !review.aiDone, review.branch ? `Your latest commit is on ${review.branch}.` : "Push from this project and the branch is recorded here."],
    ["AI review complete", review.aiDone, review.aiDone && review.lead === "waiting", review.aiSummary || "The AI writes a plain-language summary after a push."],
    ["Dev lead is deciding", review.lead === "approved" || review.lead === "changes" || review.lead === "deciding", review.lead === "deciding", review.lead === "changes" ? "Sent back with notes. Resubmit when the change is ready." : "A vetted dev lead is reading the AI summary and your diff."],
    ["Bounty and record", review.rewarded, false, review.rewarded ? "Paid. This project is on your verified record." : `$${project.bounty} is paid and the project joins your verified record once every commit is approved.`],
  ];
  const timeline = steps.map(([label, done, current, copy]) => `<div class="step"><span class="mark ${done ? "on" : ""} ${current ? "now" : ""}"></span><div><strong>${label}</strong><div class="quiet">${esc(copy)}</div></div></div>`).join("");
  const messages = room.messages.map((message) => `<div class="bubble ${message.role}">${esc(message.text)}</div>`).join("");
  const side = `<div class="panel"><h2>Review status</h2>${timeline}</div>
    <div class="panel"><h2>Mentor</h2><div class="chat">${messages}</div>
      <form class="composer" data-action="mentor" data-project="${project.id}">
        <input class="field" id="mentor-text" name="text" maxlength="500" placeholder="Ask the mentor" aria-label="Ask the mentor">
        <button class="solid" type="submit">Send</button>
      </form>
    </div>`;
  const head = `<p class="crumbs"><button type="button" data-action="nav" data-view="work">My Work</button> / ${esc(project.department)}</p>
    <div class="row"><div><span class="badge">I’m In</span><h2 style="margin-top:8px">${esc(project.title)}</h2><p class="quiet">${esc(project.repo)} · ${esc(project.difficulty)} · $${project.bounty} · ${esc(project.due)}</p></div>
    <label class="quiet">Project <select id="project-switch" data-action="switch">${options}</select></label></div>
    <div class="tabs" role="tablist">
      <button type="button" data-action="tab" data-tab="tasks" aria-selected="${ui.tab === "tasks"}">My Tasks</button>
      <button type="button" data-action="tab" data-tab="changes" aria-selected="${ui.tab === "changes"}">My Changes</button>
    </div>`;
  return shell("Project", head + `<div class="layout">${ui.tab === "tasks" ? taskPane : changePane}${side}</div>`);
}

function profileView() {
  const user = state.user;
  const lead = user.lead;
  const marks = [0, 100, 200, 300, 400, 500];
  const left = Math.max(0, Math.min(100, (user.score / 500) * 100));
  const steps = [
    ["Proctored exam", "A short coding and analytical-thinking exam, taken here.", lead.examPassed],
    ["In-person stage", "Pass the exam, then meet Ylyas, Tech Lead Engineer, in person. This step is confirmed by Ylyas, not from the portal.", lead.inPerson],
    ["Lead status", "Your role changes to Lead and you can approve or send back code.", user.role === "lead"],
  ];
  const examLink = lead.examPassed
    ? `<p>You passed${lead.lastExamScore != null ? ` with ${lead.lastExamScore} of ${state.exam.length}` : ""}. You stay a student builder until the in-person stage is confirmed.</p>`
    : `<button class="solid" type="button" data-action="nav" data-view="exam">Start proctored exam</button>`;
  const body = `<div class="layout">
    <div class="panel">
      <div class="row"><div><span class="avatar">${esc(user.initials)}</span></div><div style="flex:1"><h2 style="margin:0">${esc(user.name)}</h2><p>${esc(user.bio)}</p></div></div>
      <form data-action="bio" style="margin-top:16px">
        <label for="bio">Edit bio</label>
        <textarea class="field" id="bio" name="bio" maxlength="280">${esc(user.bio)}</textarea>
        <button class="solid" type="submit" style="margin-top:8px">Save bio</button>
      </form>
      <h3 style="margin-top:24px">Professional</h3>
      <p>Skills ${esc(user.skills.join(", "))}</p>
      <p>Classes ${esc(user.classes.join(", "))}</p>
      <p>GitHub ${esc(user.github === "connected" ? "Connected" : "Not connected")}</p>
      <p>Role ${esc(user.role === "lead" ? "Lead" : "Student")}</p>
    </div>
    <div>
      <div class="panel">
        <h2>BAU BUILD score</h2>
        <div class="names">${["Rookie", "Builder", "Skilled", "Expert", "Honored"].map((name) => `<span>${name.toUpperCase()}</span>`).join("")}</div>
        <div class="scale" aria-hidden="true"><i style="left:calc(${left}% - 8px)"></i></div>
        <div class="ticks">${marks.map((mark) => `<span>${mark}</span>`).join("")}</div>
        <p><strong>${user.score} / 500</strong> · My status ${esc(user.status)}</p>
        <p>Hard projects can ask for a minimum score. Many stay open to everyone, so you can take one on and earn a status raise. An approved project adds its bounty to your score and to your earnings, once.</p>
        <div class="stats"><div class="stat"><b>$${user.earnings}</b> My earnings</div><div class="stat"><b>${user.approvedProjects}</b> Approved projects</div></div>
      </div>
      <div class="panel" style="margin-top:16px">
        <h2>Become a Lead</h2>
        <p>Leads review student code and decide what gets approved.</p>
        ${steps.map(([title, copy, done], index) => `<div class="step"><span class="mark ${done ? "on" : ""}"></span><div><strong>0${index + 1} ${title}</strong><div class="quiet">${copy}</div></div></div>`).join("")}
        ${examLink}
        <button class="ghost" type="button" data-action="reset" style="margin-top:16px">Reset demo data</button>
      </div>
    </div>
  </div>`;
  return shell("Profile", body);
}

function examView() {
  if (state.user.lead.examPassed) {
    return shell("Proctored exam", `<div class="panel"><h2>You already passed</h2><p>The next stage is in person with Ylyas. Your role stays Student until then.</p></div>`);
  }
  const questions = state.exam.map((question, index) => `<fieldset><legend><strong>${index + 1}. ${esc(question.prompt)}</strong></legend>${
    question.choices.map((choice, choiceIndex) => `<label class="choice"><input type="radio" name="q${index}" value="${choiceIndex}" required> <span>${esc(choice)}</span></label>`).join("")
  }</fieldset>`).join("");
  return shell("Proctored exam", `<form class="panel" data-action="exam"><p>Answer all four. ${state.examPassMark} correct answers pass. A failed attempt stays on your record and you can try again.</p>${questions}<button class="solid" type="submit">Submit exam</button></form>`);
}

function render() {
  const active = document.activeElement;
  const activeId = active && active.id;
  const start = active && "selectionStart" in active ? active.selectionStart : null;
  let html = "";
  if (ui.view === "projects") html = projectsView();
  else if (ui.view === "work") html = workView();
  else if (ui.view === "project") html = detailView();
  else if (ui.view === "review") html = workspaceView();
  else if (ui.view === "exam") html = examView();
  else html = profileView();
  app.innerHTML = html;
  if (typeof mountFingers === "function") mountFingers();
  if (activeId) {
    const next = document.getElementById(activeId);
    if (next) {
      next.focus();
      if (start != null && next.setSelectionRange) {
        const pos = Math.min(start, next.value.length);
        next.setSelectionRange(pos, pos);
      }
    }
  }
}

app.addEventListener("click", async (event) => {
  const brand = event.target.closest("a.brand");
  if (brand) event.preventDefault();
  const node = event.target.closest("[data-action]");
  if (!node || !state) return;
  const action = node.dataset.action;
  if (action === "nav") {
    ui.view = node.dataset.view;
    ui.notes = false;
    render();
  } else if (action === "notes") {
    ui.notes = !ui.notes;
    if (ui.notes) await act({ type: "read" });
    else render();
  } else if (action === "difficulty") {
    ui.difficulty = node.dataset.value;
    render();
  } else if (action === "toggle") {
    ui[node.dataset.flag] = !ui[node.dataset.flag];
    render();
  } else if (action === "open") {
    const project = projectById(node.dataset.project);
    ui.projectId = project.id;
    ui.view = project.membership === "member" ? "review" : "project";
    ui.notes = false;
    render();
  } else if (action === "workspace") {
    ui.projectId = node.dataset.project;
    ui.view = "review";
    render();
  } else if (action === "join") {
    const project = projectById(node.dataset.project);
    if (project.membership === "member") {
      ui.projectId = project.id;
      ui.view = "review";
      render();
      return;
    }
    const body = await act({ type: "join", projectId: project.id });
    if (!body.ok) return;
    ui.projectId = project.id;
    if (body.result === "member") {
      ui.view = "review";
      toast(`You’re in. ${project.title}`);
    } else {
      toast(`Request sent. Waiting on ${project.reviewer}.`);
    }
    render();
  } else if (action === "tab") {
    ui.tab = node.dataset.tab;
    render();
  } else if (action === "task") {
    await act({ type: "task", projectId: node.dataset.project, taskId: node.dataset.task, status: node.dataset.status });
  } else if (action === "diff") {
    ui.diffId = ui.diffId === node.dataset.commit ? null : node.dataset.commit;
    render();
  } else if (action === "resubmit") {
    const body = await act({ type: "resubmit", projectId: node.dataset.project, commitId: node.dataset.commit });
    if (body.ok) toast("Back with the dev lead.");
  } else if (action === "reset") {
    const body = await act({ type: "reset" });
    if (body.ok) {
      ui.view = "projects";
      ui.projectId = "library";
      toast("Demo data restored.");
      render();
    }
  }
});

app.addEventListener("input", (event) => {
  if (event.target.id === "search") {
    ui.query = event.target.value;
    render();
  }
});

app.addEventListener("change", (event) => {
  if (event.target.id === "project-switch") {
    ui.projectId = event.target.value;
    ui.diffId = null;
    render();
  }
});

app.addEventListener("submit", async (event) => {
  const form = event.target.closest("form");
  if (!form) return;
  event.preventDefault();
  const action = form.dataset.action;
  if (action === "push") {
    const title = String(new FormData(form).get("title") || "").trim();
    if (!title) { toast("Describe the change before you push it."); return; }
    const body = await act({ type: "push", projectId: form.dataset.project, title });
    if (body.ok) toast("Pushed. The AI review is with the dev lead.");
  } else if (action === "mentor") {
    const text = String(new FormData(form).get("text") || "").trim();
    if (!text) { toast("Ask a question first."); return; }
    await act({ type: "mentor", projectId: form.dataset.project, text });
  } else if (action === "bio") {
    const body = await act({ type: "bio", bio: new FormData(form).get("bio") });
    if (body.ok) toast("Bio saved.");
  } else if (action === "exam") {
    const answers = state.exam.map((_, index) => Number(new FormData(form).get(`q${index}`)));
    const body = await act({ type: "exam", answers });
    if (body.passed) toast(`Passed, ${body.correct} of ${state.exam.length}. The next stage is in person.`);
    else if (body.ok) toast(body.error);
  }
});

load().catch(() => {
  app.textContent = "The portal could not reach the server.";
});
