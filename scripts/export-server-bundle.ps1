# Smart VRMS — Headless Server & Cloud Hosting Bundle Export (Stage 9)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

Write-Host "=== Exporting Smart VRMS Headless Server Deployment Bundle ===" -ForegroundColor Cyan

# 1. Package Maven server module
mvn -o -B package -pl server -am
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

$bundleDir = "dist\server-bundle"
$libDir = Join-Path $bundleDir "lib"
New-Item -ItemType Directory -Force $libDir | Out-Null

# 2. Copy server and dependency JARs
Copy-Item "server\target\server-0.1.0-SNAPSHOT.jar" (Join-Path $bundleDir "server.jar") -Force
Copy-Item "contracts\target\contracts-0.1.0-SNAPSHOT.jar" $libDir -Force
Copy-Item "application\target\application-0.1.0-SNAPSHOT.jar" $libDir -Force

. (Join-Path $PSScriptRoot 'server-classpath.ps1')
foreach ($dependencyJar in $approvedJars) { Copy-Item -LiteralPath $dependencyJar -Destination $libDir -Force }

# 3. Create Dockerfile
$dockerfile = @"
# Smart VRMS - Headless API & Background Outbox Worker
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="Smart VRMS Engineering"
LABEL description="Smart VRMS Headless Java API & Background Worker"

RUN addgroup -S vrms && adduser -S vrms -G vrms
WORKDIR /app

COPY lib/ /app/lib/
COPY server.jar /app/server.jar

RUN chown -R vrms:vrms /app
USER vrms

ENV PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:`${PORT:-8080}/health || exit 1

ENTRYPOINT ["java", "-cp", "/app/server.jar:/app/lib/*", "in.smartvrms.server.ServerMain"]
"@

Set-Content (Join-Path $bundleDir "Dockerfile") $dockerfile -Encoding UTF8

# 4. Create Render free-tier deployment specification
$renderYaml = @"
# Smart VRMS - Render Free Tier Specification
services:
  - type: web
    name: smart-vrms-api
    runtime: docker
    dockerfilePath: ./Dockerfile
    plan: free
    region: singapore
    healthCheckPath: /health
    autoDeploy: false
    envVars:
      - key: PORT
        value: 8080
      - key: SUPABASE_URL
        sync: false
      - key: SUPABASE_PUBLISHABLE_KEY
        sync: false
      - key: SMART_VRMS_DB_HOST
        value: aws-0-ap-northeast-1.pooler.supabase.com
      - key: SMART_VRMS_DB_PORT
        value: 5432
      - key: SMART_VRMS_DB_PASSWORD
        sync: false
"@

Set-Content (Join-Path $bundleDir "render.yaml") $renderYaml -Encoding UTF8

Write-Host "=== SUCCESS: Server deployment bundle exported to $bundleDir ===" -ForegroundColor Green
Get-ChildItem -Recurse $bundleDir | Select-Object Name, Length | Format-Table -AutoSize
