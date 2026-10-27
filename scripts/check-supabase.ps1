$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
. (Join-Path $PSScriptRoot 'server-classpath.ps1')
java -cp $serverClassPath in.smartvrms.server.setup.AuthReadinessMain
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Setup is not ready yet. If "Public signup disabled" is false, turn OFF "Allow new users to sign up" in Supabase Authentication settings, save, and rerun this check.' -ForegroundColor Yellow
    Write-Host 'Anonymous sign-in is a separate setting. No need to enter the publishable key again.'
    exit 2
}
