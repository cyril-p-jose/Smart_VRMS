param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
. (Join-Path $PSScriptRoot 'server-classpath.ps1')
if ($CheckOnly) { java -cp $serverClassPath in.smartvrms.server.setup.RuntimeRoleSetupMain --check-only }
else { java -cp $serverClassPath in.smartvrms.server.setup.RuntimeRoleSetupMain }
if ($LASTEXITCODE -ne 0) { Write-Host 'SETUP-07 is not confirmed. Share only the sanitized message.' -ForegroundColor Yellow; exit 2 }
