$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$archivePath = 'dist/SmartVRMS-Windows-x64.zip'
$archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
$exeHash = (Get-FileHash -LiteralPath 'dist/package-win64/SmartVRMS/SmartVRMS.exe' -Algorithm SHA256).Hash
$serverHash = (Get-FileHash -LiteralPath 'dist/server-bundle/server.jar' -Algorithm SHA256).Hash
$archiveBytes = (Get-Item -LiteralPath $archivePath).Length
$versionMatch = Select-String -Path 'desktop/src/main/java/in/smartvrms/desktop/infrastructure/AppVersion.java' -Pattern 'CURRENT = "([^"]+)"'
if (-not $versionMatch) { throw 'AppVersion.CURRENT could not be read.' }
$version = $versionMatch.Matches[0].Groups[1].Value
if ($version -notmatch '^\d+\.\d+\.\d+-preview$') { throw 'The manifest writer only accepts an explicitly classified preview version.' }
$manifest = @"
# Smart VRMS release manifest

Version: **$version**. Classification: **authenticated rental-management beta preview**.
Status: **PREVIEW PUBLICATION AUTHORIZED; PRODUCTION BLOCKED**. This is a beta preview; production readiness is not established. Operator acceptance confirmed hosted staff sign-in and fleet reading, and migration 009 indexes; authenticated live write journeys and full lifecycle acceptance remain pending.

| Artifact | SHA-256 | Bytes |
|---|---|---|
| SmartVRMS-Windows-x64.zip | $archiveHash | $archiveBytes |
| SmartVRMS.exe | $exeHash | Native Windows launcher |
| server.jar | $serverHash | Server build input; excluded from the desktop package |

Only the client ZIP and SHA256SUMS.txt are intended as public preview assets. The server JAR is listed to identify local verification inputs; it is not bundled in the client.
These hashes identify the build that generated this file. CI rebuilds publish their own SHA256SUMS.txt and Release-Manifest.md assets; use those release assets to verify the downloaded ZIP, not a different local build's hash.

| Platform | Evidence |
|---|---|
| Windows 11 x64 | App-image build, native keyboard smoke and launcher process test; unsigned |
| macOS Intel / Apple Silicon | PENDING RUNNER — no native device acceptance |
| Ubuntu 24.04 GNOME x64 | PENDING RUNNER — no native device acceptance |

The desktop bundle contains the public API endpoint only. User credentials and session tokens are never packaged. The client contains the current rental workflow implementation, but live write journeys beyond staff authentication and fleet reads remain unaccepted. See [Release-Audit.md](Release-Audit.md).
The stable latest-release URL will not resolve until a stable release exists. Preview downloads use their versioned release tags. Already downloaded executables are replaced by downloading a later build.
"@
[IO.File]::WriteAllText((Join-Path (Get-Location) 'docs/Release-Manifest.md'),$manifest,(New-Object Text.UTF8Encoding($false)))
Write-Output 'Release manifest written from current artifact hashes.'
