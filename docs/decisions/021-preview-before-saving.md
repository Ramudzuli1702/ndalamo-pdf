# 021. Preview results before saving
Date: 2026-11-11

## Context
The owner asked to see results before they are saved, for every tool. Until now a run
wrote straight into the user's folders and the result screen came after.

## Decision
A run started from the app writes into a private staging folder under %TEMP%
(`NdalamoPDF-preview\run-…`). The tool is given the user's own `SaveTo` pointed at that
folder, so names and subfolders (Split's `name_split`, PDF to JPG's `name_jpg`) come out
exactly as a direct run would make them; no tool changed. `PreviewScreen` shows PDFs page
by page (drawn as they scroll into view, at most 300 pages), pictures as they are, and
offers "Open to check" for Word, Excel, Markdown and ZIP files, which the app can't draw.
Save copies each file into place with `SafeSave`, applying the "a file with that name
exists" rule and never replacing an input; Discard deletes the folder and goes back to
the tool with the same files and choices. History and "Continue with these files" use the
final paths.

On by default; Settings › Saving › "Preview results before saving" turns it off. Watch
folders never preview: they run unattended. A run finished in the background shows
"ready to check" instead of "saved". Staging folders left behind (app closed during a
preview) are deleted after a day.

## Consequences
One extra click per run for people who keep it on. Results take space in %TEMP% until
saved or discarded, and Save on another drive is a copy. Two inputs with the same file
name from different folders get "(2)" in the staging folder, which carries over to the
saved name.
