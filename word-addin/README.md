# Ndalamo add-in for Microsoft Word

Adds **Delete Page** to Word for Windows: a button on the Home tab (group "Ndalamo"),
**Delete Pages...** for a list such as `3, 5-7`, and **Delete This Page** on the right-click
menu. Each action is one undo step. See SPEC section 7 and decision 015.

## Build

    dotnet build -c Release

Needs the .NET SDK and Microsoft Word installed (the Office interop assemblies are read from
the GAC and embedded). Output: `bin/Release/net48/NdalamoWord.dll`.

## Install

From the Ndalamo app: Settings → Word add-in → Install add-in. While developing, the app
picks up the build output above; a packaged app ships the DLL in `word-addin/` next to its
files. Restart Word afterwards.

## Guide in the app

Settings → Word add-in → "How to use it in Word" (also offered after installing) walks through
the ribbon group, Delete Page, Delete Pages and the right-click menu, with screenshots of Word
in `src/main/resources/help/word`. Retake them if the buttons change: crop below Word's title
bar (it shows the signed-in account) and ring the part each step is about in brand blue.

## Files

- `Connect.cs`: the COM entry point Word loads, the ribbon and its button callbacks.
- `PageDeleter.cs`: finds each page's stretch of the document and deletes it, with the edge
  cases (last page, page breaks, section breaks, tables carrying on, the only page).
- `PageRanges.cs`: reads page lists like `3, 5-7`.
- `DeletePagesForm.cs`: the Delete Pages dialog.
- `Ribbon.xml`: the buttons and menu entries.
