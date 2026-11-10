# Building Ndalamo's Windows installer

    powershell -ExecutionPolicy Bypass -File packaging\build-installer.ps1

Add `-SkipTests` if the tests have just run. Results go to `target\installer\`:

- `Ndalamo-<version>.exe`: Ndalamo only (about 115 MB). Carries its own Java runtime, so
  people don't need Java. Office to PDF explains how to add LibreOffice if it's missing.
- `Ndalamo-<version>-with-LibreOffice.exe`: the full setup (decision 009), made only when a
  LibreOffice installer is present (below). Its first page has "Also install LibreOffice",
  ticked by default.

Needs JDK 22 (for `jpackage`; set `JPACKAGE_JDK` if it isn't in `C:\Program Files\Java\jdk-22`),
Maven, WiX Toolset 3.14, and the .NET SDK (for the Word add-in).

## Adding LibreOffice to the full setup

1. Download LibreOffice's official Windows x64 installer (`LibreOffice_<version>_Win_x86-64.msi`)
   from libreoffice.org. Use it unchanged: its licence (MPL 2.0) allows passing it on as it is.
2. Put it in `packaging\libreoffice\` (it's never committed), or pass `-LibreOfficeMsi <path>`.
3. Run the build script again.

LibreOffice is installed silently with: no Office file types taken over, no update checks, no
desktop icon, no quick-starter. It stays installed if Ndalamo is removed, and has its own entry
in Windows' Apps list. Ship a current LibreOffice with each Ndalamo release for its security fixes.

## What's in here

- `build-installer.ps1`: the build (Maven, the Word add-in, `jpackage`, and the setup bundle).
- `ndalamo.ico`: the app icon at 16 to 256 px, drawn from the same shapes as `LogoMark`.
- `wix\main.wxs`: `jpackage`'s own installer template plus one addition at the end: Ndalamo in
  Explorer's "Open with" menu for PDFs, Office files, pictures and Markdown. It uses
  `OpenWithProgids`, which adds to the list without changing anyone's default program.
- `bundle\`: the full setup (WiX Burn): `Bundle.wxs`, its look (`Theme.xml`, `Theme.wxl`,
  `logo.png`).

Never change the upgrade codes in `build-installer.ps1` and `bundle\Bundle.wxs`: Windows uses
them to know that a newer installer replaces the installed Ndalamo.
