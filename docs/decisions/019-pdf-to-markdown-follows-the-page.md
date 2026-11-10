# 019. PDF to Markdown follows the page as shown
Date: 2026-11-10

## Context
The owner asked for Markdown that replicates the PDF exactly: same pages, fonts and everything.
A CV made by pypdf came out with every line woven into a hidden copy of itself, letter-spaced
headings as "PROFIL E", and label lines run together. Markdown is plain text: it has headings,
bold, italic, lists, tables, links and pictures, but no fonts, sizes, colours or positions.

## Decision
- Read only what the page shows. The text readers (PageText, PageContent) now follow the
  page's clipping (PageClip) and skip text a clip hides. Pages put together from pieces of
  others keep each piece's whole text and clip away the rest.
- Join letter-spaced words: pieces whose gap matches the spacing between their letters, and
  runs of four or more single letters with small even gaps. Never across a real space
  character, never for punctuation or signs (maths).
- Keep what Markdown can carry: links become [text](address); lines set one under another
  with no gap keep a line break; lines starting with a bold label stay separate lines; bold
  lines in body size stay bold text rather than `###` (which would print bigger); pages are
  separated by `<div style="page-break-after: always;"></div>`, which browsers, Markdown
  editors and Ndalamo's Markdown to PDF all treat as a new page.
- Fonts, sizes, colours and right-aligned columns are not carried. Markdown has no way to say
  them without raw HTML styling, which most Markdown readers ignore or strip, and which makes
  the file hard to read and edit. PDF to Word is the tool for an exact copy (decision 018).

## Consequences
The changes to PageText also improve PDF to Word, PDF to Excel, Compare and Redact. Redact now
writes back only words that were visible, so a hidden copy of redacted text can no longer
survive as invisible text. Checked on 70 real PDFs against the previous version: the only words
dropped are ones placed off the page or clipped out of view.
