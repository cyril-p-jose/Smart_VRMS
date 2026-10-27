$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
mvn -o -B -pl server -am compile
if ($LASTEXITCODE -ne 0) { throw 'Live check could not compile the current server. No login attempted.' }
. (Join-Path $PSScriptRoot 'server-classpath.ps1')
java -cp $serverClassPath in.smartvrms.server.setup.LiveStaffCheckMain
if ($LASTEXITCODE -ne 0) { Write-Host 'Live staff check is not confirmed. Share only the sanitized result; never passwords or tokens.' -ForegroundColor Yellow; exit 2 }
