param([string]$Version = '0.2.1-preview')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$workspaceRoot = [IO.Path]::GetFullPath((Get-Location).Path)
if ($Version -notmatch '^\d+\.\d+\.\d+(-preview)?$') { throw 'Use a semantic version, optionally ending in -preview.' }
function Reset-BuildFolder([string]$Relative) {
    $resolved = [IO.Path]::GetFullPath((Join-Path $workspaceRoot $Relative))
    $allowedRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot 'dist')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Build directory escaped dist.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    New-Item -ItemType Directory -Path $resolved -Force | Out-Null
    return $resolved
}
$apiUrl = $env:SMART_VRMS_RELEASE_API_URL
if ([string]::IsNullOrWhiteSpace($apiUrl)) { $apiUrl = 'https://smart-vrms.onrender.com' }
if ($apiUrl -notmatch '^https://[a-z0-9.-]+(?::\d{1,5})?/?$') { throw 'SMART_VRMS_RELEASE_API_URL must be a public HTTPS API URL without credentials, query or fragment.' }
mvn -o -B package "-Dsmart.vrms.api.url=$apiUrl"
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
$inputDir = Reset-BuildFolder 'dist/client-jars'
foreach ($module in @('contracts','application','desktop')) {
    Copy-Item -LiteralPath "$module/target/$module-0.1.0-SNAPSHOT.jar" -Destination $inputDir
}
. (Join-Path $PSScriptRoot 'server-classpath.ps1')
Copy-Item -LiteralPath $approvedJars[0],$approvedJars[1] -Destination $inputDir
$jdkPath = $env:JAVA_HOME
if (-not $jdkPath) { $jdkPath = (Get-Item (Get-Command java.exe).Source).Directory.Parent.FullName }
$runtimeDir = [IO.Path]::GetFullPath((Join-Path $workspaceRoot 'dist/runtime-win64'))
$outputDir = Reset-BuildFolder 'dist/package-win64'
$checkedRuntime = Reset-BuildFolder 'dist/runtime-win64'
Remove-Item -LiteralPath $checkedRuntime
$jpackageTemp = Join-Path ([IO.Path]::GetTempPath()) ("smart-vrms-jpackage-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $jpackageTemp | Out-Null
& (Join-Path $jdkPath 'bin/jlink.exe') --no-header-files --no-man-pages --strip-debug --compress=2 --add-modules java.base,java.desktop,java.logging,java.net.http,java.sql,java.xml,jdk.crypto.ec,jdk.charsets --output $runtimeDir
if ($LASTEXITCODE -ne 0) { throw 'jlink failed.' }
& (Join-Path $jdkPath 'bin/jpackage.exe') --type app-image --input $inputDir --dest $jpackageTemp --name SmartVRMS --main-jar desktop-0.1.0-SNAPSHOT.jar --main-class in.smartvrms.desktop.DesktopMain --runtime-image $runtimeDir --app-version ($Version -split '-')[0] --vendor 'Smart VRMS' --description 'Smart VRMS authenticated rental-management beta preview'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
$builtImage = Join-Path $jpackageTemp 'SmartVRMS'
$image = Join-Path $outputDir 'SmartVRMS'
Copy-Item -LiteralPath $builtImage -Destination $image -Recurse
Remove-Item -LiteralPath $jpackageTemp -Recurse -Force
Copy-Item -LiteralPath 'docs/Third-Party-Notices.md','docs/Release-Audit.md','docs/Demo-Data-Catalog.md' -Destination $image
@'
SMART VRMS - AUTHENTICATED RENTAL-MANAGEMENT BETA PREVIEW

Extract the entire archive, then open SmartVRMS.exe.
Windows 11 x64. Java is bundled; no Java installation is needed.
This build is unsigned. Its publisher identity is not code-signed.

Choose Explore interface preview at sign-in to browse fictional examples across
fleet, customers, drivers, personnel, rentals, finance, and reviews. These
examples are read-only presentation data; nothing is saved or sent to the API.
See Demo-Data-Catalog.md in this folder for the sample scenarios and totals.

Staff sign-in and the fleet screen were confirmed against the hosted API.
This build includes the current rental workflow implementation and local
fixture/regression checks. Live business journeys beyond staff sign-in and
fleet reading have not been independently accepted against the hosted database.
Before relying on it for real rentals or money, validate every workflow using
authorized staff and approved test records. Payment entry is manual; this is
not statutory payroll or a production-certified accounting system. Email
delivery and disaster-recovery drills are not confirmed operational.

Use Check for updates to find a newer GitHub release. Installing an
update means downloading/extracting the new archive. Already downloaded
executables do not change automatically. The public API endpoint is bundled
as a default and may be overridden in your user profile; passwords/tokens are
never saved.

Project: https://github.com/cyril-p-jose/Smart_VRMS
The free API host can sleep while idle; first sign-in may take about a minute.
Read Release-Audit.md for remaining requirements before relying on the system.
'@ | Set-Content -LiteralPath (Join-Path $image 'START-HERE.txt') -Encoding utf8
$archive = Join-Path $workspaceRoot 'dist/SmartVRMS-Windows-x64.zip'
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
Compress-Archive -Path (Join-Path $outputDir 'SmartVRMS') -DestinationPath $archive -CompressionLevel Optimal
$archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $workspaceRoot 'dist/SHA256SUMS.txt'), "$archiveHash  SmartVRMS-Windows-x64.zip`n", (New-Object Text.UTF8Encoding($false)))
Write-Output "Windows preview packaged: $archive"
Write-Output "SHA256: $archiveHash"
