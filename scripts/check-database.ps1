$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
. (Join-Path $PSScriptRoot 'server-classpath.ps1')
java -cp $serverClassPath in.smartvrms.server.setup.DatabaseReadinessMain
if ($LASTEXITCODE -ne 0) { Write-Host 'Database readiness is not confirmed. See docs/SETUP-04-Database-Connection.md. Share only the sanitized result, never the password.' -ForegroundColor Yellow; exit 2 }
