# 006. Noto Sans as a backup font; how passwords are handled
Date: 2026-11-04
## Context
Page numbers and watermarks write text into PDFs. The PDF format's built-in fonts only cover Western European letters, and the bundled IBM Plex Sans turned out to lack Tshivenda's ṱ, ḓ, ḽ, ṅ and ṋ. Protect and Unlock also handle passwords, which must never leak.
## Decision
- Text written into PDFs uses IBM Plex Sans, embedded (only the letters used). When the text has a letter Plex doesn't have, Noto Sans is used instead. Noto Sans Regular and Bold (SIL OFL 1.1, licence in `src/main/resources/fonts/NotoSans-OFL.txt`) are bundled for this.
- Passwords are typed in the tool screen (Protect) or the password dialog (Unlock), passed to the tool for that one run, and never saved, logged or shown again.
- Protect uses AES-256 with the user's password to open the file, plus a random owner password that guards the print/copy settings.
- Unlock never guesses or works around a password. A file that opens without a password but has print/copy limits needs its full (owner) password to have them removed.
## Consequences
- Watermarks and page labels work in every Latin-script language, including all South African ones.
- The app is about 1.2 MB bigger.
- Organize tools still drop copy/print limits from the new files they make (decision 005), which is more lenient than Unlock. Worth revisiting if that matters.
