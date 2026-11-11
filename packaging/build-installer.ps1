# Builds the NdalamoPDF setups (SPEC chunk 20): target\installer\NdalamoPDF-Setup-<version>[-with-LibreOffice].exe
#
#   powershell -ExecutionPolicy Bypass -File packaging\build-installer.ps1 [-SkipTests]
#
# Needs: JDK 22 (jpackage), Maven, WiX Toolset 3.14 (for the .exe), the .NET SDK (Word add-in).
# The installer carries its own Java runtime, so people don't need Java installed.
# Put LibreOffice's .msi in packaging\libreoffice (or pass -LibreOfficeMsi <path>) to also make the
# full setup with LibreOffice (decision 009). See packaging\README.md.

param([switch]$SkipTests, [string]$LibreOfficeMsi)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Version from the pom (the first <version> belongs to the project).
$version = ([regex]'<artifactId>ndalamo-pdf</artifactId>\s*<version>([^<]+)</version>').Match((Get-Content pom.xml -Raw)).Groups[1].Value
if (-not $version) { throw "Couldn't read the version from pom.xml" }
$jdk = if ($env:JPACKAGE_JDK) { $env:JPACKAGE_JDK } else { 'C:\Program Files\Java\jdk-22' }
$jpackage = Join-Path $jdk 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) { throw "jpackage not found at $jpackage (set JPACKAGE_JDK to a JDK 22 folder)" }

$out = Join-Path $root 'target\installer'
$inputDir = Join-Path $out 'input'
$content = Join-Path $out 'content'

Write-Host "1/4 Building NdalamoPDF $version"
$mvnArgs = @('-q', 'clean', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { throw 'The Maven build failed' }
# After the build: "clean" empties target\, this folder included.
New-Item -ItemType Directory -Force $inputDir, $content | Out-Null
Copy-Item "target\ndalamo-pdf-$version.jar" $inputDir
# The loading screen (logo and name), shown by Java the moment Ndalamo starts; @2x for high-DPI screens.
Copy-Item 'packaging\splash\splash.png', 'packaging\splash\splash@2x.png' $inputDir
# Every library the app uses, next to it (pinned plugin version, already in the local Maven cache).
& mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:copy-dependencies -DincludeScope=runtime "-DoutputDirectory=$inputDir"
if ($LASTEXITCODE -ne 0) { throw 'Copying the libraries failed' }

Write-Host '2/4 Building the Word add-in'
& dotnet build word-addin -c Release --nologo -v quiet
if ($LASTEXITCODE -ne 0) { throw 'The Word add-in build failed' }
New-Item -ItemType Directory -Force (Join-Path $content 'word-addin') | Out-Null
Copy-Item 'word-addin\bin\Release\net48\NdalamoWord.dll' (Join-Path $content 'word-addin')

Write-Host '3/4 Adding the licences'
# The licences of everything bundled, readable without opening the app (they're also in Settings > About).
Copy-Item -Recurse 'src\main\resources\licenses' (Join-Path $content 'licenses')

Write-Host '4/4 Making the setups (this takes a few minutes)'
# Where main.wxs finds the installer's pictures (ndalamo-banner.bmp, ndalamo-dialog.bmp).
$env:NDALAMO_WIX = Join-Path $root 'packaging\wix'
function Invoke-Jpackage([string]$type) {
    $jpackageArgs = @(
        '--type', $type,
        '--name', 'NdalamoPDF',
        '--app-version', $version,
        '--vendor', 'NdalamoPDF',
        '--description', 'NdalamoPDF: free PDF tools for Windows',
        '--input', $inputDir,
        '--main-jar', "ndalamo-pdf-$version.jar",
        '--main-class', 'com.ndalamo.Launcher',
        # $APPDIR is jpackage's own: the folder holding the jar and splash.png once installed.
        '--java-options', '-splash:$APPDIR\splash.png',
        # The installer's helper (packaging\NdalamoHelper.properties): Ndalamo without the loading screen.
        '--add-launcher', 'NdalamoHelper=packaging\NdalamoHelper.properties',
        '--icon', 'packaging\ndalamo.ico',
        '--app-content', ((Join-Path $content 'word-addin') + ',' + (Join-Path $content 'licenses')),
        '--resource-dir', 'packaging\wix',
        # For this Windows user (no administrator prompt), with a Start-menu shortcut. The setup
        # program (packaging\bundle) shows the terms and offers the desktop shortcut (main.wxs).
        '--win-per-user-install',
        '--license-file', 'packaging\terms.txt',
        '--win-menu', '--win-menu-group', 'NdalamoPDF',
        # Never change this: it's how Windows knows a newer installer upgrades the installed NdalamoPDF.
        '--win-upgrade-uuid', '3b1f6f0e-6a2c-4d7e-9f5b-0d1c8a4e2b77',
        '--dest', $out
    )
    & $jpackage @jpackageArgs
    if ($LASTEXITCODE -ne 0) { throw "jpackage ($type) failed" }
}

# The terms as rich text for the terms box: the title and the numbered headings in bold.
function ConvertTo-Rtf([string]$text) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('{\rtf1\ansi\ansicpg1252\deff0{\fonttbl{\f0\fswiss Segoe UI;}}{\colortbl;\red22\green24\blue29;\red58\green63\blue73;}')
    [void]$sb.Append('\viewkind4\uc1\f0\fs19\cf2 ')
    $first = $true
    foreach ($line in ($text -split "`r?`n")) {
        $escaped = New-Object System.Text.StringBuilder
        foreach ($ch in $line.ToCharArray()) {
            $code = [int]$ch
            if ($ch -eq '\' -or $ch -eq '{' -or $ch -eq '}') { [void]$escaped.Append('\' + $ch) }
            elseif ($code -gt 127) { [void]$escaped.Append('\u' + $(if ($code -gt 32767) { $code - 65536 } else { $code }) + '?') }
            else { [void]$escaped.Append($ch) }
        }
        $body = $escaped.ToString()
        if ($first -and $line.Trim()) { [void]$sb.Append('\pard\sa160\cf1\b\fs22 ' + $body + '\b0\fs19\cf2\par '); $first = $false }
        elseif ($line -match '^\d+\.\s+[A-Z][A-Z ]+$') { [void]$sb.Append('\pard\sb120\sa40\cf1\b ' + $body + '\b0\cf2\par ') }
        elseif ($line.Trim()) { [void]$sb.Append('\pard\sa80 ' + $body + '\par ') }
    }
    [void]$sb.Append('}')
    return $sb.ToString()
}

# The NdalamoPDF installer itself; both setups carry it.
Invoke-Jpackage 'msi'
$msi = Get-ChildItem $out -Filter 'NdalamoPDF-*.msi' | Select-Object -First 1
$wix = Join-Path ${env:ProgramFiles(x86)} 'WiX Toolset v3.14\bin'
$bundle = Join-Path $root 'packaging\bundle'
$termsRtf = Join-Path $out 'terms.rtf'
[System.IO.File]::WriteAllText($termsRtf, (ConvertTo-Rtf (Get-Content 'packaging\terms.txt' -Raw -Encoding UTF8)), [System.Text.Encoding]::ASCII)

# Builds one setup program around the installer. Without LibreOffice its option is left out of the window.
function New-Setup([string]$file, [string]$upgradeCode, [string]$loMsi, [string]$loVersion) {
    $theme = Get-Content (Join-Path $bundle 'Theme.xml') -Raw -Encoding UTF8
    if (-not $loMsi) { $theme = [regex]::Replace($theme, '(?s)\s*<!--LIBREOFFICE-->.*?<!--/LIBREOFFICE-->', '') }
    $themeFile = Join-Path $out ("theme-" + [IO.Path]::GetFileNameWithoutExtension($file) + ".xml")
    [System.IO.File]::WriteAllText($themeFile, $theme, (New-Object System.Text.UTF8Encoding $false))
    $obj = Join-Path $out ([IO.Path]::GetFileNameWithoutExtension($file) + '.wixobj')
    $candleArgs = @('-nologo', '-ext', 'WixBalExtension', '-ext', 'WixUtilExtension', "-dVersion=$version",
        "-dIcon=$root\packaging\ndalamo.ico", "-dTermsRtf=$termsRtf", "-dTheme=$themeFile", "-dBundleUpgradeCode=$upgradeCode",
        "-dNdalamoMsi=$($msi.FullName)")
    if ($loMsi) { $candleArgs += @("-dLibreOfficeMsi=$loMsi", "-dLibreOfficeVersion=$loVersion") }
    & (Join-Path $wix 'candle.exe') @candleArgs -out $obj (Join-Path $bundle 'Bundle.wxs')
    if ($LASTEXITCODE -ne 0) { throw "candle ($file) failed" }
    $setup = Join-Path $out $file
    # -b: where Theme.wxl and the window's pictures are found.
    & (Join-Path $wix 'light.exe') -nologo -ext WixBalExtension -ext WixUtilExtension -b $bundle -out $setup $obj
    if ($LASTEXITCODE -ne 0) { throw "light ($file) failed" }
    Write-Host ("{0} ({1:N0} MB)" -f $setup, ((Get-Item $setup).Length / 1MB))
}

# NdalamoPDF only (for this Windows user): always made.
New-Setup "NdalamoPDF-Setup-$version.exe" '6a1d8e3f-2b47-4c95-9e0a-5f3c7b2d8e16' $null $null

# The full setup with LibreOffice (decision 009): only when its .msi has been put in packaging\libreoffice.
if (-not $LibreOfficeMsi) {
    $found = Get-ChildItem 'packaging\libreoffice' -Filter '*.msi' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $LibreOfficeMsi = $found.FullName }
}
if (-not $LibreOfficeMsi) {
    Write-Host 'No LibreOffice .msi in packaging\libreoffice, so only the NdalamoPDF-only setup was made (see packaging\README.md).'
    return
}
# The bundled LibreOffice's version, read from its .msi: a PC with this or a newer one skips it.
$installer = New-Object -ComObject WindowsInstaller.Installer
$loDb = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($LibreOfficeMsi, 0))
$loView = $loDb.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $loDb, @("SELECT Value FROM Property WHERE Property='ProductVersion'"))
$loView.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $loView, $null) | Out-Null
$loRow = $loView.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $loView, $null)
$loVersion = $loRow.GetType().InvokeMember('StringData', 'GetProperty', $null, $loRow, @([int]1))
$loView.GetType().InvokeMember('Close', 'InvokeMethod', $null, $loView, $null) | Out-Null
[System.Runtime.InteropServices.Marshal]::ReleaseComObject($loDb) | Out-Null
Write-Host "LibreOffice in the full setup: $loVersion"
New-Setup "NdalamoPDF-Setup-$version-with-LibreOffice.exe" '9e4b2c7d-1f3a-4b6e-8d5c-7a2f0e9b3c41' $LibreOfficeMsi $loVersion
