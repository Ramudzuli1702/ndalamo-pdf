# Building the NdalamoPDF setups

    powershell -ExecutionPolicy Bypass -File packaging\build-installer.ps1

Add `-SkipTests` if the tests have just run. Results go to `target\installer\`:

- `NdalamoPDF-Setup-<version>.exe` (about 115 MB): NdalamoPDF and Delete Page for Word, installed
  for the current Windows user, with no administrator prompt. Carries its own Java runtime.
- `NdalamoPDF-Setup-<version>-with-LibreOffice.exe` (about 455 MB): the same plus LibreOffice
  (decision 009), made only when a LibreOffice installer is present (below). LibreOffice installs
  for every user of the PC, so this setup asks for administrator permission once.

Needs JDK 22 (for `jpackage`; set `JPACKAGE_JDK` if it isn't in `C:\Program Files\Java\jdk-22`),
Maven, WiX Toolset 3.14, and the .NET SDK (for the Word add-in).

## What installing looks like

Both setups use the same window (`bundle\`): the blue brand panel on the left, and on the right:

1. The terms of use (from `terms.txt`, shown as formatted text) with **I accept the terms of use**;
   Install stays off until it's ticked. Below them, the options, all ticked to begin with:
   add a shortcut to the desktop, open NdalamoPDF when setup finishes, add Delete Page to
   Microsoft Word, and (full setup) install LibreOffice.
2. Progress, with a slim brand-coloured bar.
3. **NdalamoPDF is ready**, with Open NdalamoPDF and Close.

Running the setup again, or uninstalling from Windows' Apps list, shows "NdalamoPDF is installed"
with Uninstall. There's one "NdalamoPDF" entry in the Apps list.

The options reach the NdalamoPDF installer (`wix\main.wxs`) as `DESKTOPSHORTCUT`, `LAUNCHAPP` and
`WORDADDIN` (each compared with "1"). Delete Page is added by the installed app itself:
`NdalamoHelper.exe --install-word-addin` (the same code as Settings › Word add-in; nothing to do
without Word), and uninstalling runs `--remove-word-addin`.

## Adding LibreOffice to the full setup

1. Download LibreOffice's official Windows x64 installer (`LibreOffice_<version>_Win_x86-64.msi`)
   from libreoffice.org, and check it against the SHA-256 published next to it. Use it unchanged:
   its licence (MPL 2.0) allows passing it on as it is.
2. Put it in `packaging\libreoffice\` (it's never committed), or pass `-LibreOfficeMsi <path>`.
3. Run the build script again.

LibreOffice is installed silently with: no Office file types taken over, no update checks, no
desktop icon, no quick-starter. A PC that already has the same or a newer LibreOffice keeps it.
It stays installed if NdalamoPDF is removed, and has its own entry in Windows' Apps list. Ship a
current LibreOffice with each release for its security fixes.

## What's in here

- `build-installer.ps1`: the build (Maven, the Word add-in, `jpackage` for the installer, the terms
  as rich text, then both setups).
- `bundle\`: the setup program (WiX Burn): `Bundle.wxs`, its window (`Theme.xml`, `Theme.wxl`) and
  pictures (drawn from the logo; colours in `Theme.xml` are written blue-green-red).
- `wix\main.wxs`: `jpackage`'s installer template plus NdalamoPDF's additions: Explorer's
  "Open with" entries (`OpenWithProgids`, never changing default programs), the optional desktop
  shortcut, opening the app when setup finishes, and the Word add-in steps.
- `splash\`: the loading screen shown while NdalamoPDF starts.
- `feedback\`: the spreadsheet script that receives reviews and welcome answers (decision 020).
- `ndalamo.ico`: the app icon at 16 to 256 px. `terms.txt`: the terms of use.

Never change the upgrade codes in `build-installer.ps1`: Windows uses them to know that a newer
setup replaces the installed NdalamoPDF.
