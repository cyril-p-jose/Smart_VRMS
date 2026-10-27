$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$configPath = Join-Path (Get-Location) 'local-config/supabase-public.properties'
if (Test-Path -LiteralPath $configPath) { throw 'Public configuration already exists. Open local-config/supabase-public.properties to edit it; this script will not overwrite it.' }
$publishable = Read-Host 'Paste the PUBLISHABLE key (starts sb_publishable_). Never paste a secret/service-role key'
if ($publishable -cnotmatch '^sb_publishable_[A-Za-z0-9_-]{10,200}$') { throw 'Expected a publishable key. Nothing was saved.' }
New-Item -ItemType Directory -Force local-config | Out-Null
[System.IO.File]::WriteAllLines($configPath, @('supabase.url=https://wvtnepxynjjbazczkukh.supabase.co',('supabase.publishableKey=' + $publishable)), [System.Text.UTF8Encoding]::new($false))
$publishable = $null
Write-Output 'Public configuration saved locally. No secret or database password was requested. Next: ./scripts/check-supabase.ps1'
