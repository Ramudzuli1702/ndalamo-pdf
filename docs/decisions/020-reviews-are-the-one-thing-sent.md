# 020. Reviews and welcome answers are the only things NdalamoPDF sends
Date: 2026-11-11

## Context
The owner wants people to rate NdalamoPDF and write a review, collected in a spreadsheet, with the
whole thing feeling natural and in the app: five stars, a text box, Submit, and the rest done in
the background, queued until there's a connection if there isn't one. Until now NdalamoPDF made no
network calls at all, and the README, terms and Settings said so.

## Decision
Make reviews the single exception to "offline only", and keep it narrow:
- Sent only when the person presses Submit: the stars, the text, NdalamoPDF's version, the date and
  a random id for the review (so a review sent twice is kept once). No files, file names, device
  or usage details, and no other network call anywhere in the app.
- Sent with the JDK's own HttpClient (no new library) to a Google Apps Script web app attached to
  the owner's spreadsheet (`packaging/feedback`). Its address is in `feedback.properties`; with no
  address the feature is hidden.
- Written to feedback-queue.json first; sent straight away, and retried at start-up and every 15
  minutes while it waits (offline, or the script not answering).
- The box is offered after the fifth successful tool run, then at most once a week after five more,
  and never again after "Never show this again" or a sent review. It's always in Settings › Help.
- The terms (section 3), Settings › Privacy, the README and the Home badge ("Private · files never
  leave this PC") say exactly this.

## Consequences
- "Offline only" no longer describes the app on its own; "your files never leave this PC" still does.
- The spreadsheet's URL is public inside the app; anyone could post junk rows. The script accepts
  only a 1-5 rating and short text, and junk rows can be deleted.
- Reviews are stored by Google in the owner's spreadsheet.

## Addendum (2026-11-11): the welcome question
At the owner's request, the first start (after the tour) asks one optional question: which best
describes the person (student, teacher or lecturer, employee, business owner, public sector, job
seeker, other) and what they'll use NdalamoPDF for (tick any). Send queues the answers like a
review (kind "about"); the script writes them to a "Users" sheet. Skip sends nothing, and the
question is asked only once. Terms section 3 and Settings › Privacy say so.
