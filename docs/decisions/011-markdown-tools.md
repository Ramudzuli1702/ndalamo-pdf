# 011. Markdown tools: commonmark-java to read, our own renderer to draw
Date: 2026-11-06
## Context
The owner asked for Markdown to PDF and PDF to Markdown, which weren't in the original plan. Reading Markdown properly means following the CommonMark spec, which has many edge cases (nested lists, emphasis rules, tables). Turning it into a PDF can go three ways: Markdown → HTML → an HTML-to-PDF library (another large dependency), Markdown → Word → LibreOffice (would make the tool need LibreOffice), or drawing it directly with PDFBox.
## Decision
- Read Markdown with **commonmark-java 0.31.0** (BSD-2), plus its tables, strikethrough and task-list add-ons. The owner approved this library on 2026-11-06 (it wasn't in SPEC §2). 0.32.0 was under two weeks old.
- Draw the PDF with our own small layout engine (`MarkdownRenderer`) on PDFBox. It doesn't need LibreOffice and adds no further libraries. Fonts: IBM Plex Sans with Noto Sans as backup (decision 006). Italics are Plex slanted, as no italic font is bundled. Code uses Courier, which every PDF reader has built in.
- Pictures come only from files on this PC, found relative to the .md file. A picture on the web is not downloaded (offline rule); its description is shown instead, and the result is marked partial. Links become clickable areas: clicking one opens the reader's browser, while Ndalamo itself never goes online.
- PDF to Markdown writes from the same reading as PDF to Word (`DocumentReader`): headings, lists, bold/italic, pipe tables. Pictures go in a folder next to the .md file. Markdown can only number lists with digits, so lettered and Roman items keep their marker as text after a bullet.
## Consequences
- About 130 KB of extra libraries.
- The renderer covers what Markdown notes and READMEs use. Raw HTML in a Markdown file is shown as its words only, and there are no footnotes or maths.
- Fonts, colours and centring in a PDF have no Markdown equivalent and are left out of PDF to Markdown.
