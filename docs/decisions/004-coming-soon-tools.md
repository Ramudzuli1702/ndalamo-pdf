# 004. Show every tool on Home from the start, as "Coming soon" placeholders
Date: 2026-11-04
## Context
The home screen builds its tool cards from the `Tools` list, but tools are built one chunk at a time; until chunk 3 none exist, and most stay unbuilt for many chunks.
## Decision
`Tools` lists every tool from the Home mockup. Tools that aren't built yet are `ComingSoonTool` placeholders: their card shows a "Coming soon" tag and can't be opened. Each chunk replaces its placeholders with the real tool classes.
## Consequences
- Home looks complete from chunk 2 and can be checked against the mockup.
- `PdfTool` gains `isReady()`, which is true for every real tool.
- Placeholder cards are not disabled (JavaFX would fade their text); they just ignore clicks.
- The list follows the mockup, which has no separate Rotate, Remove pages or Crop cards; those are reached through Page workspace unless a later chunk adds them.
