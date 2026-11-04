# 003. One project with screens / tools / services / models / utils
Date: 2026-11-04
## Context
The original spec split the code into three Maven modules (`core`, `tools`, `app`) with many small interfaces and records. A first attempt at the skeleton built that way was hard to follow, so it was discarded before being committed.
## Decision
Use one Maven project, package `com.ndalamo`, with five folders: `screens/` (what the user sees), `tools/` (PDF work, one class per tool), `services/` (background jobs and saving data), `models/` (plain data) and `utils/` (small helpers). Two rules keep it tidy: tools contain no JavaFX code, and screens contain no PDF code. Settings and pipelines are stored as JSON with Gson. On-screen text is written in the screens directly.
## Consequences
- One build file and one place to look for each kind of code.
- The tools can still be reused by pipelines and watch folders, because they don't depend on any screen.
- Nothing enforces the two rules at build time; they are kept by review (SPEC §3).
- Translating the app later would mean moving on-screen text into a resource bundle.
