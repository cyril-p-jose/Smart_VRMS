$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
. (Join-Path $PSScriptRoot 'server-classpath.ps1')
java -cp "contracts/target/classes;application/target/classes;desktop/target/classes;$($approvedJars[0]);$($approvedJars[1])" in.smartvrms.desktop.DesktopMain
if ($LASTEXITCODE -ne 0) { throw 'Preview launch failed. Run scripts/verify.ps1 first.' }
