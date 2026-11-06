# 015. Word add-in as a plain COM add-in, not VSTO
Date: 2026-11-07
## Context
SPEC section 7 planned the "Delete Page" add-in as a C# VSTO add-in. Building VSTO needs
Visual Studio's Office workload (its build targets make and sign the ClickOnce manifests),
and installing it goes through the VSTO installer with its trust prompts. The Java app has
to install and remove the add-in from Settings, ideally without administrator rights.
## Decision
Write it as a plain COM add-in in C# on .NET Framework 4.8: one class implementing Office's
IDTExtensibility2 (declared in the project) and IRibbonExtensibility, with Office's interop
types embedded. It builds with `dotnet build` alone, offline, with no NuGet packages.
Settings → Word add-in copies NdalamoWord.dll to %LOCALAPPDATA%\Ndalamo\word-addin and writes
the same registry entries .NET's regasm would, but under HKEY_CURRENT_USER, plus Word's
Addins entry (LoadBehavior 3). Remove deletes them. All through Windows' own "reg" command.
## Consequences
- The ribbon button, right-click entry and single undo step work as SPEC section 7 describes.
- No administrator rights, certificates or trust prompts; it is per Windows user.
- The DLL isn't signed. Word loads it like any registered COM add-in; if a company's policy
  allows only signed add-ins it won't load there.
- The class ID in Connect.cs and WordAddIn.java must match (a test checks this).
- Word keeps the DLL open while running, so installing over an old copy asks the person to
  close Word first.
