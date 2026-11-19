"""Build the BAU BUILD audience deck as a PowerPoint file."""

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.util import Emu, Inches, Pt

INK = RGBColor(0x0D, 0x12, 0x20)
PAPER = RGBColor(0xF3, 0xF4, 0xEF)
YELLOW = RGBColor(0xFF, 0xD6, 0x0A)
BLUE = RGBColor(0x2B, 0x50, 0xE0)
MUTED = RGBColor(0x4A, 0x54, 0x68)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
LINE = RGBColor(0xD9, 0xDC, 0xD3)
PROBLEM = RGBColor(0x9A, 0x34, 0x12)
NAV = RGBColor(0xC9, 0xD1, 0xE2)
CARD_DARK = RGBColor(0x16, 0x1B, 0x2C)

W = Inches(13.333)
H = Inches(7.5)

NOTES = [
    "Open on the title and leave the URL up. Say this is the live portal, not a slide about a future product. The next two slides are the problems, then the solution for each one.",
    "Read all four before you click ahead. These are the projects already on the board: the shuttle widget, the library dates, attendance copied by hand, plus the score gate, the missing reviewer, and the missing record. Let the room feel the gap.",
    "Match the numbers. 01 is the board. 02 is eligibility: open join, request, or unchanged. 03 is AI summary, then a dev lead or the professor, and only sent-back work can be resubmitted. 04 is the bounty once, and the exam does not make a lead. The slides after this are the proof.",
    "Point at the live site if you can, or stay on this slide. Mention the filters in one breath: difficulty, my classes, urgent, and open to my score. Search covers title, summary, department, class, and difficulty. Filters combine.",
    "Use the library card as I’m In, course search as Request, and the transcript’s 250 gate as Unchanged for Sam at 180. Urgent and open-to-every-class does not waive a minimum score.",
    "If you are live, open the library project here. Read the mentor line. The point is that the mentor points at the bug. The student still writes the code.",
    "Walk the four beats with your hand. Stress the last two: only sent-back work can be resubmitted, and the bounty is added once, when the last waiting commit is approved. On a class project, the professor decides.",
    "Sam Carter is the demo student, at 180, Builder. Tiers are 0 Rookie, 100 Builder, 200 Skilled, 300 Expert, 400 Honored. Hard projects can ask for 250 or 300. Many stay open so a student can earn the next status.",
    "Say this sentence out loud: three of four does not make you a lead. The in-person stage with Ylyas is confirmed outside the portal. Only then does the role become Lead, and only then can they approve or send work back.",
    "This is your run of show. If the room has clicked around, Profile then Reset demo data restores Sam’s starting board. Do the score block live if you have time. It is the clearest proof.",
    "One slide for anyone who asks whether the browser can be talked into a yes. Keep it short. The rules and the tests are the same module. Exam answers never ship to the page.",
    "Stop talking after the URL. Open the live portal and start on Projects.",
]


def set_run(run, text, size, color, bold=False):
    run.text = text
    run.font.size = Pt(size)
    run.font.color.rgb = color
    run.font.bold = bold
    run.font.name = "Calibri"


def add_text(slide, left, top, width, height, lines, anchor=MSO_ANCHOR.TOP):
    box = slide.shapes.add_textbox(left, top, width, height)
    frame = box.text_frame
    frame.word_wrap = True
    frame.auto_size = None
    frame.margin_left = Emu(0)
    frame.margin_right = Emu(0)
    frame.margin_top = Emu(0)
    frame.margin_bottom = Emu(0)
    try:
        frame._txBody.bodyPr.set("anchor", {MSO_ANCHOR.TOP: "t", MSO_ANCHOR.MIDDLE: "ctr", MSO_ANCHOR.BOTTOM: "b"}[anchor])
    except Exception:
        pass
    for index, line in enumerate(lines):
        paragraph = frame.paragraphs[0] if index == 0 else frame.add_paragraph()
        paragraph.alignment = line.get("align", PP_ALIGN.LEFT)
        paragraph.space_before = Pt(line.get("before", 0))
        paragraph.space_after = Pt(line.get("after", 0))
        set_run(paragraph.add_run(), line["text"], line["size"], line["color"], line.get("bold", False))
    return box


def rect(slide, left, top, width, height, fill, line=None, radius=True):
    shape = slide.shapes.add_shape(
        MSO_SHAPE.ROUNDED_RECTANGLE if radius else MSO_SHAPE.RECTANGLE,
        left, top, width, height,
    )
    shape.fill.solid()
    shape.fill.fore_color.rgb = fill
    if line is None:
        shape.line.fill.background()
    else:
        shape.line.color.rgb = line
        shape.line.width = Pt(1)
    if radius:
        shape.adjustments[0] = 0.08
    return shape


def kicker(slide, text, color, top=Inches(0.38)):
    add_text(slide, Inches(0.55), top, Inches(8), Inches(0.32), [
        {"text": text.upper(), "size": 13, "color": color, "bold": True},
    ])


def heading(slide, text, color, top=Inches(0.68), size=32, width=Inches(12.2)):
    add_text(slide, Inches(0.55), top, width, Inches(1.15), [
        {"text": text, "size": size, "color": color, "bold": True},
    ])


def card_copy(slide, left, top, width, height, label, title, body, label_color, problem=None):
    rect(slide, left, top, width, height, WHITE, LINE)
    y = top + Inches(0.16)
    add_text(slide, left + Inches(0.22), y, width - Inches(0.4), Inches(0.26), [
        {"text": label.upper(), "size": 11, "color": label_color, "bold": True},
    ])
    y += Inches(0.28)
    if problem:
        add_text(slide, left + Inches(0.22), y, width - Inches(0.4), Inches(0.36), [
            {"text": problem, "size": 13, "color": INK},
        ])
        y += Inches(0.34)
    add_text(slide, left + Inches(0.22), y, width - Inches(0.4), Inches(0.58), [
        {"text": title, "size": 18, "color": INK, "bold": True},
    ])
    add_text(slide, left + Inches(0.22), y + Inches(0.52), width - Inches(0.4), height - (y - top) - Inches(0.62), [
        {"text": body, "size": 14, "color": MUTED},
    ])


def notes(slide, text):
    slide.notes_slide.notes_text_frame.text = text


def blank(prs, color):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    fill = slide.background.fill
    fill.solid()
    fill.fore_color.rgb = color
    return slide


def title_slide(prs, note):
    slide = blank(prs, INK)
    kicker(slide, "BAU BUILD", YELLOW, Inches(1.35))
    add_text(slide, Inches(0.55), Inches(1.75), Inches(11.5), Inches(2.3), [
        {"text": "Real campus work.", "size": 54, "color": WHITE, "bold": True},
        {"text": "A record that stays.", "size": 54, "color": WHITE, "bold": True, "before": 0},
    ])
    add_text(slide, Inches(0.55), Inches(4.25), Inches(9.5), Inches(0.9), [
        {"text": "Students find a university project, ship the change, and leave a score a reviewer can stand behind.", "size": 20, "color": NAV},
    ])
    add_text(slide, Inches(0.55), Inches(6.15), Inches(10), Inches(0.4), [
        {"text": "baubuild.vesperconnect.org", "size": 22, "color": YELLOW, "bold": True},
    ])
    add_text(slide, Inches(0.55), Inches(6.6), Inches(10), Inches(0.35), [
        {"text": "Live portal  ·  for faculty, students, and campus partners", "size": 16, "color": NAV},
    ])
    notes(slide, note)


def pair_slide(prs, kicker_text, title, items, label_color, note, with_problem=False):
    slide = blank(prs, PAPER)
    kicker(slide, kicker_text, BLUE)
    heading(slide, title, INK, size=30)
    left0 = Inches(0.55)
    top0 = Inches(2.05)
    gap = Inches(0.16)
    width = Inches(6.03)
    height = Inches(2.45)
    for index, item in enumerate(items):
        col = index % 2
        row = index // 2
        card_copy(
            slide,
            left0 + col * (width + gap),
            top0 + row * (height + gap),
            width,
            height,
            item["label"],
            item["title"],
            item["body"],
            label_color,
            item.get("problem") if with_problem else None,
        )
    notes(slide, note)


def board_slide(prs, note):
    slide = blank(prs, PAPER)
    kicker(slide, "Projects", BLUE)
    heading(slide, "The board is the front door.", INK, size=30, width=Inches(8))
    rect(slide, Inches(0.55), Inches(2.05), Inches(6.7), Inches(4.9), INK, radius=True)
    add_text(slide, Inches(0.82), Inches(3.55), Inches(6.1), Inches(0.3), [
        {"text": "URGENT  ·  HARD  ·  ANY SCORE WELCOME", "size": 12, "color": YELLOW, "bold": True},
    ])
    add_text(slide, Inches(0.82), Inches(3.95), Inches(6.1), Inches(1.35), [
        {"text": "Build a shuttle timetable widget for the student portal", "size": 26, "color": WHITE, "bold": True},
    ])
    add_text(slide, Inches(0.82), Inches(5.4), Inches(6.1), Inches(0.7), [
        {"text": "Campus Ops needs it within 48 hours. Open to every class and every score.", "size": 15, "color": NAV},
    ])
    pill = rect(slide, Inches(0.82), Inches(6.2), Inches(2.15), Inches(0.42), YELLOW, radius=True)
    pill.adjustments[0] = 0.2
    add_text(slide, Inches(0.82), Inches(6.26), Inches(2.15), Inches(0.32), [
        {"text": "View project  ·  $35", "size": 13, "color": INK, "bold": True, "align": PP_ALIGN.CENTER},
    ])

    cards = [
        ("LIBRARY", "$18", "Fix form validation on the room-booking page", "Easy   ·   Any score   ·   I’m In"),
        ("CMPS433", "$60", "Rebuild course-search ranking", "Hard   ·   Score 300+   ·   Professor"),
        ("REGISTRAR", "$45", "Speed up the transcript request form", "Hard   ·   Score 250+   ·   Dev lead"),
    ]
    top = Inches(2.05)
    for dept, bounty, title, meta in cards:
        rect(slide, Inches(7.45), top, Inches(5.3), Inches(1.52), WHITE, LINE)
        add_text(slide, Inches(7.65), top + Inches(0.12), Inches(3.4), Inches(0.24), [
            {"text": dept, "size": 11, "color": MUTED, "bold": True},
        ])
        add_text(slide, Inches(10.7), top + Inches(0.08), Inches(1.85), Inches(0.32), [
            {"text": bounty, "size": 18, "color": INK, "bold": True, "align": PP_ALIGN.RIGHT},
        ])
        add_text(slide, Inches(7.65), top + Inches(0.4), Inches(4.9), Inches(0.6), [
            {"text": title, "size": 15, "color": INK, "bold": True},
        ])
        add_text(slide, Inches(7.65), top + Inches(1.08), Inches(4.9), Inches(0.28), [
            {"text": meta, "size": 12, "color": MUTED},
        ])
        top += Inches(1.68)
    notes(slide, note)


def three_cards(prs, kicker_text, title, cards, note):
    slide = blank(prs, PAPER)
    kicker(slide, kicker_text, BLUE)
    heading(slide, title, INK, size=30)
    width = Inches(3.9)
    gap = Inches(0.22)
    left = Inches(0.55)
    top = Inches(2.35)
    height = Inches(4.4)
    for index, (label, name, body) in enumerate(cards):
        x = left + index * (width + gap)
        rect(slide, x, top, width, height, WHITE, LINE)
        add_text(slide, x + Inches(0.28), top + Inches(0.32), width - Inches(0.5), Inches(0.3), [
            {"text": label.upper(), "size": 13, "color": BLUE, "bold": True},
        ])
        add_text(slide, x + Inches(0.28), top + Inches(0.8), width - Inches(0.5), Inches(1.1), [
            {"text": name, "size": 24, "color": INK, "bold": True},
        ])
        add_text(slide, x + Inches(0.28), top + Inches(2.1), width - Inches(0.5), Inches(1.9), [
            {"text": body, "size": 16, "color": MUTED},
        ])
    notes(slide, note)


def workspace_slide(prs, note):
    slide = blank(prs, PAPER)
    kicker(slide, "My Work", BLUE)
    heading(slide, "The workspace is where the work moves.", INK, size=30)
    rect(slide, Inches(0.55), Inches(2.15), Inches(6.0), Inches(4.75), WHITE, LINE)
    add_text(slide, Inches(0.82), Inches(2.4), Inches(5.4), Inches(0.4), [
        {"text": "Tasks", "size": 22, "color": INK, "bold": True},
    ])
    add_text(slide, Inches(0.82), Inches(3.0), Inches(5.4), Inches(3.4), [
        {"text": "Status moves To Do, then In Progress, then Done. Done reopens to To Do.", "size": 16, "color": MUTED},
        {"text": "Assigned work sits apart from the backlog. On the library project, rejecting past dates is in progress. The screen-reader announcement is still to do.", "size": 16, "color": MUTED, "before": 12},
    ])
    rect(slide, Inches(6.75), Inches(2.15), Inches(6.0), Inches(4.75), WHITE, LINE)
    add_text(slide, Inches(7.02), Inches(2.4), Inches(5.4), Inches(0.4), [
        {"text": "Mentor", "size": 22, "color": INK, "bold": True},
    ])
    add_text(slide, Inches(7.02), Inches(3.0), Inches(5.4), Inches(1.1), [
        {"text": "The mentor sits inside a project you have joined. It points at the check. The student writes the change.", "size": 16, "color": MUTED},
    ])
    rect(slide, Inches(7.02), Inches(4.4), Inches(5.45), Inches(1.9), INK, radius=True)
    add_text(slide, Inches(7.24), Inches(4.6), Inches(5.05), Inches(1.55), [
        {"text": "Your date check compares strings, so “2026-1-5” sorts after “2026-12-01”. What could you compare instead?", "size": 15, "color": WHITE},
    ])
    notes(slide, note)


def review_slide(prs, note):
    slide = blank(prs, PAPER)
    kicker(slide, "Review", BLUE)
    heading(slide, "A push is not a merge.", INK, size=32)
    steps = [
        ("Pushed", "The student describes the change. The branch is recorded on the project."),
        ("AI review", "A plain-language summary is written. The commit stays with the dev lead."),
        ("A person decides", "A vetted dev lead, or the professor on a class project, approves or sends it back. Only work that was sent back can be resubmitted."),
        ("Bounty and record", "When the last waiting commit is approved, the bounty is added to earnings and to the score, once."),
    ]
    top = Inches(2.05)
    for index, (title, body) in enumerate(steps):
        dot = slide.shapes.add_shape(MSO_SHAPE.OVAL, Inches(0.62), top + Inches(0.06), Inches(0.22), Inches(0.22))
        dot.fill.solid()
        dot.fill.fore_color.rgb = YELLOW
        dot.line.color.rgb = INK
        dot.line.width = Pt(1.5)
        if index < len(steps) - 1:
            line = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.70), top + Inches(0.3), Inches(0.045), Inches(0.85))
            line.fill.solid()
            line.fill.fore_color.rgb = LINE
            line.line.fill.background()
        add_text(slide, Inches(1.1), top, Inches(11.4), Inches(0.32), [
            {"text": title, "size": 18, "color": INK, "bold": True},
        ])
        add_text(slide, Inches(1.1), top + Inches(0.32), Inches(11.4), Inches(0.55), [
            {"text": body, "size": 15, "color": MUTED},
        ])
        top += Inches(1.2)
    notes(slide, note)


def score_slide(prs, note):
    slide = blank(prs, PAPER)
    kicker(slide, "Score", BLUE)
    heading(slide, "The score is both the gate and the reward.", INK, size=30)
    names = ["ROOKIE", "BUILDER", "SKILLED", "EXPERT", "HONORED", ""]
    ticks = ["0", "100", "200", "300", "400", "500"]
    left = Inches(0.55)
    span = Inches(12.2)
    for index, name in enumerate(names):
        add_text(slide, left + index * Inches(2.03), Inches(2.15), Inches(2.0), Inches(0.28), [
            {"text": name, "size": 11, "color": MUTED, "bold": True, "align": PP_ALIGN.LEFT if index < 5 else PP_ALIGN.RIGHT},
        ])
    track = rect(slide, left, Inches(2.55), span, Inches(0.12), RGBColor(0xE4, 0xE6, 0xDF), radius=True)
    track.adjustments[0] = 0.5
    fill = rect(slide, left, Inches(2.55), Inches(4.39), Inches(0.12), YELLOW, radius=True)
    fill.adjustments[0] = 0.5
    marker = slide.shapes.add_shape(MSO_SHAPE.OVAL, left + Inches(4.28), Inches(2.51), Inches(0.2), Inches(0.2))
    marker.fill.solid()
    marker.fill.fore_color.rgb = INK
    marker.line.fill.background()
    for index, tick in enumerate(ticks):
        align = PP_ALIGN.RIGHT if index == 5 else PP_ALIGN.LEFT
        add_text(slide, left + index * Inches(2.03), Inches(2.78), Inches(2.0), Inches(0.26), [
            {"text": tick, "size": 12, "color": MUTED, "bold": True, "align": align},
        ])
    add_text(slide, left, Inches(3.2), Inches(12), Inches(0.4), [
        {"text": "180 / 500    ·    Builder    ·    Sam Carter, the demo student", "size": 18, "color": INK, "bold": True},
    ])
    stats = [("$38", "Earnings already on the record."), ("2", "Projects already approved."), ("Once", "A bounty is added to earnings and score a single time.")]
    width = Inches(3.9)
    for index, (value, body) in enumerate(stats):
        x = left + index * (width + Inches(0.22))
        rect(slide, x, Inches(4.05), width, Inches(2.75), WHITE, LINE)
        add_text(slide, x + Inches(0.28), Inches(4.35), width - Inches(0.5), Inches(0.6), [
            {"text": value, "size": 32, "color": INK, "bold": True},
        ])
        add_text(slide, x + Inches(0.28), Inches(5.15), width - Inches(0.5), Inches(1.2), [
            {"text": body, "size": 16, "color": MUTED},
        ])
    notes(slide, note)


def demo_slide(prs, note):
    slide = blank(prs, PAPER)
    kicker(slide, "In the room", BLUE)
    heading(slide, "Five clicks tell the story.", INK, size=30)
    steps = [
        "Open the board. Filter Urgent, then open the shuttle project.",
        "Request the CMPS433 ranking project. Score 180 does not clear 300.",
        "My Work shows two memberships: the library fix and the attendance export.",
        "Review shows tasks, the mentor, and the path from push to bounty.",
        "Profile shows the score scale and the three steps to Lead.",
    ]
    top = Inches(2.05)
    for index, text in enumerate(steps):
        add_text(slide, Inches(0.55), top, Inches(0.55), Inches(0.35), [
            {"text": f"{index + 1:02d}", "size": 16, "color": BLUE, "bold": True},
        ])
        add_text(slide, Inches(1.15), top, Inches(6.3), Inches(0.7), [
            {"text": text, "size": 15, "color": INK},
        ])
        rule = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.55), top + Inches(0.72), Inches(6.9), Inches(0.01))
        rule.fill.solid()
        rule.fill.fore_color.rgb = LINE
        rule.line.fill.background()
        top += Inches(0.95)
    rect(slide, Inches(8.15), Inches(2.05), Inches(4.6), Inches(4.75), WHITE, LINE)
    add_text(slide, Inches(8.42), Inches(2.35), Inches(4.1), Inches(0.7), [
        {"text": "The person on screen", "size": 20, "color": INK, "bold": True},
    ])
    add_text(slide, Inches(8.42), Inches(3.2), Inches(4.1), Inches(3.1), [
        {"text": "Sam Carter", "size": 18, "color": INK, "bold": True},
        {"text": "Computer Science  ·  CMPS433", "size": 15, "color": MUTED, "before": 8},
        {"text": "GitHub connected", "size": 15, "color": MUTED, "before": 4},
        {"text": "Score 180, Builder", "size": 15, "color": MUTED, "before": 4},
        {"text": "Profile, then Reset demo data, returns the board to this start.", "size": 15, "color": MUTED, "before": 16},
    ])
    notes(slide, note)


def close_slide(prs, note):
    slide = blank(prs, INK)
    kicker(slide, "BAU BUILD", YELLOW, Inches(1.35))
    add_text(slide, Inches(0.55), Inches(1.8), Inches(11.8), Inches(2.4), [
        {"text": "Put a real campus", "size": 48, "color": WHITE, "bold": True},
        {"text": "problem on the board.", "size": 48, "color": WHITE, "bold": True},
    ])
    add_text(slide, Inches(0.55), Inches(4.4), Inches(10), Inches(0.8), [
        {"text": "Let a student ship it. Let the record show who did.", "size": 22, "color": NAV},
    ])
    add_text(slide, Inches(0.55), Inches(6.15), Inches(10), Inches(0.4), [
        {"text": "baubuild.vesperconnect.org", "size": 22, "color": YELLOW, "bold": True},
    ])
    add_text(slide, Inches(0.55), Inches(6.6), Inches(10), Inches(0.35), [
        {"text": "Open the live portal after this slide.", "size": 16, "color": NAV},
    ])
    notes(slide, note)


def build():
    prs = Presentation()
    prs.slide_width = W
    prs.slide_height = H
    prs.core_properties.title = "BAU BUILD"
    prs.core_properties.subject = "Audience presentation for baubuild.vesperconnect.org"
    title_slide(prs, NOTES[0])
    pair_slide(prs, "Problems", "Four problems sit between the campus and the student.", [
        {"label": "Problem 01", "title": "The work never meets the student", "body": "Campus Ops needs a shuttle widget in 48 hours. The library form accepts past dates. Staff copy attendance by hand. That work stays outside class."},
        {"label": "Problem 02", "title": "The wrong student can take it", "body": "A hard project or a class project has no gate. If Request always works, a score of 180 can claim work that asks for 300."},
        {"label": "Problem 03", "title": "A push has no owner", "body": "Submitted code can sit with no named reviewer. Work that was sent back looks the same as a new push."},
        {"label": "Problem 04", "title": "Finishing leaves no record", "body": "There is no score a department can trust. A short exam would be enough to become the person who approves everyone else."},
    ], PROBLEM, NOTES[1])
    pair_slide(prs, "Solutions", "Each problem has a rule in the portal.", [
        {"label": "Solution 01", "problem": "The work never meets the student.", "title": "One board of real projects", "body": "Campus Ops, the Library, the Registrar, Student Affairs, and classes post the work. A student finds it and joins it."},
        {"label": "Solution 02", "problem": "The wrong student can take it.", "title": "Joining is a rule", "body": "Open projects take you in at once. A professor or dev lead project is only a request. A low score or the wrong class changes nothing. Urgent does not waive the score."},
        {"label": "Solution 03", "problem": "A push has no owner.", "title": "A person still decides", "body": "The AI writes a summary. A dev lead, or the professor on a class project, approves or sends it back. Only sent-back work can be resubmitted."},
        {"label": "Solution 04", "problem": "Finishing leaves no record.", "title": "The record moves once", "body": "An approved bounty is added to earnings and to the score a single time. Three of four on the exam does not make a lead. That takes the meeting with Ylyas."},
    ], BLUE, NOTES[2], with_problem=True)
    board_slide(prs, NOTES[3])
    three_cards(prs, "Membership", "Joining is a rule. The button does not always win.", [
        ("I’m In", "Open projects", "The library fix is $18 and open to any score. Membership is immediate, and the tasks are assigned."),
        ("Request", "Someone decides", "A professor or dev lead project creates a request. No tasks are assigned until that person says yes."),
        ("Unchanged", "The gate holds", "A score under the minimum, or a class project outside your classes, changes nothing. Urgent does not waive the score."),
    ], NOTES[4])
    workspace_slide(prs, NOTES[5])
    review_slide(prs, NOTES[6])
    score_slide(prs, NOTES[7])
    three_cards(prs, "Leadership", "Passing the exam does not make a lead.", [
        ("01", "Proctored exam", "Four questions in the portal. Three correct is a pass. The student can try again."),
        ("02", "In person", "Meet Ylyas, Tech Lead Engineer. That confirmation is recorded outside this portal."),
        ("03", "Lead", "The role changes. A lead can approve student code or send it back."),
    ], NOTES[8])
    demo_slide(prs, NOTES[9])
    three_cards(prs, "How it holds", "The screen displays the decision. It does not make it.", [
        ("Rules", "One set of rules", "Join, score gates, tasks, review, bounty, and the exam live in a single module. The same rules are what the tests run."),
        ("Exam", "Answers stay server-side", "The exam questions reach the browser. The answer key does not."),
        ("Gate", "A gate cannot be talked past", "A score of 180 cannot become a 300 because someone pressed Request. Filters combine. They do not override eligibility."),
    ], NOTES[10])
    close_slide(prs, NOTES[11])
    path = "/Users/voko02/bau-build-portal/presentation/BAU-BUILD.pptx"
    prs.save(path)
    print(path, len(prs.slides))


if __name__ == "__main__":
    build()
