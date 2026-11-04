# UI mockups

These are the source files of the Ndalamo design canvas. Each `.dc.html` file is one screen. They use a design-canvas template format, so they won't render on their own in a browser — **read them as reference** for layout, exact wording, colours (hex values), font sizes, spacing and component structure. SPEC.md §6 describes the same screens in words; where they differ, follow the mockup for visuals and SPEC.md for behaviour.

| File | Screen | SPEC section | Build chunk |
|---|---|---|---|
| `Main.dc.html` | Home | 6.2 · 1 | 2 |
| `Workspace.dc.html` | Page workspace | 6.2 · 2 | 8 |
| `DropSuggest.dc.html` | File dropped — suggested tools | 6.2 · 3 | 15 |
| `Tool.dc.html` | Tool screen — files and options (Compress example) | 6.2 · 4 | 3, 5 |
| `Progress.dc.html` | Tool screen — progress | 6.2 · 5 | 3 |
| `Result.dc.html` | Tool screen — result | 6.2 · 6 | 3 |
| `Pipelines.dc.html` | Pipelines list | 6.2 · 7 | 16 |
| `PipelineBuilder.dc.html` | Pipeline builder | 6.2 · 8 | 16 |
| `WatchFolders.dc.html` | Watch folders + activity log | 6.2 · 9 | 17 |
| `Settings.dc.html` | Settings | 6.2 · 10 | 18 |
| `Notifications.dc.html` | Toasts + system notification | 6.2 · 11 | 3, 17 |
| `Dialogs.dc.html` | Password, confirm delete, file error, LibreOffice missing, unsaved changes, quit while running | 6.2 · 12 | 3, 6, 8, 10, 17 |
| `Logo.dc.html` | Logo options — **V1 "Growth bar with a leaf" is the chosen logo** | 6.0 | 2, 20 |

## Reading tips
- The visible markup sits inside `<x-dc> … </x-dc>`. Inline `style="…"` holds the exact CSS values to translate into `app.css`.
- `{{accent}}` = brand blue `#2F5BEA`. Lists shown with `<sc-for>` are sample data defined in the `renderVals()` script at the bottom of each file.
- Mockups are drawn at 1280 px wide; the real window should be resizable down to ~1024 px.
