# BAU BUILD portal

Web portal for the [BAU BUILD design](https://baybuild.vercel.app/): the project board, join and request rules, My Work, the project workspace, review status, and the profile score.

The rules live in `logic.py`. The browser only displays the state that module returns.

## Rules

- An open project joins immediately. A professor or dev lead project creates a request and does not assign tasks yet.
- A score below the project minimum, or a class project outside your classes, changes nothing.
- Urgent work can be open to every class. That does not waive a minimum score.
- Filters combine. Search matches the title, summary, department, class, and difficulty.
- Task status moves To Do → In Progress → Done, and Done reopens to To Do.
- A push runs the AI summary and leaves the commit with the dev lead. Only a commit that was sent back can be resubmitted.
- Passing the proctored exam (3 of 4) does not make you a lead. The in-person stage with Ylyas is recorded outside this portal.
- When a lead approves the last waiting commit, the bounty is added to earnings and to the score once.

## Run

```bash
python3 -m unittest test_logic.py
PORT=8080 python3 server.py
```

Open http://127.0.0.1:8080. Progress is stored in `data/store.json`. Profile → Reset demo data restores Sam Carter’s starting board.
