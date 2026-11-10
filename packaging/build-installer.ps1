# Builds Ndalamo's Windows installer (SPEC chunk 20): target\installer\Ndalamo-<version>.exe
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

Write-Host "1/4 Building Ndalamo $version"
$mvnArgs = @('-q', 'clean', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { throw 'The Maven build failed' }
# After the build: "clean" empties target\, this folder included.
New-Item -ItemType Directory -Force $inputDir, $content | Out-Null
Copy-Item "target\ndalamo-pdf-$version.jar" $inputDir
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

Write-Host '4/4 Making the installer (this takes a minute or two)'
# Where main.wxs finds the installer's pictures (ndalamo-banner.bmp, ndalamo-dialog.bmp).
$env:NDALAMO_WIX = Join-Path $root 'packaging\wix'
function Invoke-Jpackage([string]$type) {
    $jpackageArgs = @(
        '--type', $type,
        '--name', 'Ndalamo',
        '--app-version', $version,
        '--vendor', 'Ndalamo',
        '--description', 'Ndalamo PDF: a free, offline PDF toolkit',
        '--input', $inputDir,
        '--main-jar', "ndalamo-pdf-$version.jar",
        '--main-class', 'com.ndalamo.Launcher',
        '--icon', 'packaging\ndalamo.ico',
        '--app-content', ((Join-Path $content 'word-addin') + ',' + (Join-Path $content 'licenses')),
        '--resource-dir', 'packaging\wix',
        # A smooth install: for this Windows user (no administrator prompt), the terms to accept,
        # Start-menu and desktop shortcuts without extra questions, and "Open Ndalamo now" at the end.
        '--win-per-user-install',
        '--license-file', 'packaging\terms.txt',
        '--win-menu', '--win-menu-group', 'Ndalamo',
        '--win-shortcut',
        # Never change this: it's how Windows knows a newer installer upgrades the installed Ndalamo.
        '--win-upgrade-uuid', '3b1f6f0e-6a2c-4d7e-9f5b-0d1c8a4e2b77',
        '--dest', $out
    )
    & $jpackage @jpackageArgs
    if ($LASTEXITCODE -ne 0) { throw "jpackage ($type) failed" }
}

# The small "Ndalamo only" setup: always made.
Invoke-Jpackage 'exe'
$exe = Get-ChildItem $out -Filter 'Ndalamo-*.exe' | Select-Object -First 1
Write-Host ("Ndalamo only: {0} ({1:N0} MB)" -f $exe.FullName, ($exe.Length / 1MB))

# The full setup with LibreOffice (decision 009): only when its .msi has been put in packaging\libreoffice.
if (-not $LibreOfficeMsi) {
    $found = Get-ChildItem 'packaging\libreoffice' -Filter '*.msi' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $LibreOfficeMsi = $found.FullName }
}
if (-not $LibreOfficeMsi) {
    Write-Host 'No LibreOffice .msi in packaging\libreoffice, so only the Ndalamo-only setup was made (see packaging\README.md).'
    return
}
Write-Host "Making the full setup with $(Split-Path -Leaf $LibreOfficeMsi)"
Invoke-Jpackage 'msi'
$msi = Get-ChildItem $out -Filter 'Ndalamo-*.msi' | Select-Object -First 1
$wix = Join-Path ${env:ProgramFiles(x86)} 'WiX Toolset v3.14\bin'
$bundle = Join-Path $root 'packaging\bundle'
$obj = Join-Path $out 'bundle.wixobj'
& (Join-Path $wix 'candle.exe') -nologo -ext WixBalExtension "-dVersion=$version" "-dIcon=$root\packaging\ndalamo.ico" "-dTerms=$root\packaging\terms.txt" `
    "-dNdalamoMsi=$($msi.FullName)" "-dLibreOfficeMsi=$LibreOfficeMsi" -out $obj (Join-Path $bundle 'Bundle.wxs')
if ($LASTEXITCODE -ne 0) { throw 'candle (setup bundle) failed' }
$setup = Join-Path $out "Ndalamo-$version-with-LibreOffice.exe"
# -b: where Theme.xml, Theme.wxl and logo.png are found.
& (Join-Path $wix 'light.exe') -nologo -ext WixBalExtension -b $bundle -out $setup $obj
if ($LASTEXITCODE -ne 0) { throw 'light (setup bundle) failed' }
Write-Host ("Full setup: {0} ({1:N0} MB)" -f $setup, ((Get-Item $setup).Length / 1MB))
