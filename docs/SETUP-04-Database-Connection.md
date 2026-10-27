# SETUP-04 — Development database connection

Purpose: verify the approved JDBC driver reaches the existing Free project's Session pooler with certificate and hostname verification. This setup-only checker executes one SELECT in a read-only transaction and rolls back. It creates no schema, accounts or business data. No new software, paid service or deployment is required.

Prerequisites: your active smart-vrms-dev project, its database password chosen at project creation, and the existing Java installation. Never send this password in chat. The checker prompts privately, does not save the password or put it in command arguments. JDBC necessarily holds a password string temporarily in process memory.

Official references: [Supabase connections](https://supabase.com/docs/guides/database/connecting-to-postgres), [Supabase SSL](https://supabase.com/docs/guides/platform/ssl-enforcement), [pgJDBC SSL](https://jdbc.postgresql.org/documentation/ssl/).

## Numbered steps

1. Open [Supabase Dashboard](https://supabase.com/dashboard) and **smart-vrms-dev**.
2. Click **Connect**, select **Session pooler**, and inspect its parameters. Expected port: `5432`, database: `postgres`, username: `postgres.wvtnepxynjjbazczkukh`. Copy only **Host**, not the complete connection string. Use the exact dashboard value; do not guess. If Session pooler is unavailable, stop and report that fact.
3. In the project's **Database settings**, find **SSL certificate / Download certificate**. Download the project's database root certificate and save it as `C:\Users\HP\Desktop\smart-VRMS\local-config\supabase-ca.crt`. That folder already exists. Enable file extensions in File Explorer to avoid `.crt.txt`. If the control is missing, report that; do not substitute an unrelated certificate.
4. Open Windows PowerShell directly and run:

```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
Test-Path '.\local-config\supabase-ca.crt'
.\scripts\check-database.ps1
```

5. The file check should return `True`. At the Host prompt paste only the Session pooler hostname from step 2. At the hidden password prompt enter the **database password** and press Enter. No characters appearing is normal. Do not enter your Supabase account password or an API key.
6. Expected result:

```text
Database connection: PASS
TLS certificate and hostname verification: PASS
Read-only transaction: PASS
No schema or business data changed. Application role, membership and RLS: not configured.
```

7. Send the safe reply below. After this passes, continue with schema, restricted server role and membership/RLS setup and live isolation verification. The desktop still cannot sign in or save bookings.

## Troubleshooting

- File check `False`: correct the certificate location/name.
- Host rejected: the checker accepts only `aws-<number>-ap-northeast-1.pooler.supabase.com`, matching your reported region. Do not include a port or URL. If Dashboard shows a different format, share only the hostname for review.
- Password rejected: use the database password chosen during project creation. If lost, reset it in Database settings; this invalidates previous database credentials. Keep the replacement private.
- Connection failed: check active project status, exact host, password, downloaded certificate and outbound TCP port 5432 access. Do not disable TLS verification. Share only the sanitized checker output for diagnosis.
- No console: use Windows PowerShell directly rather than an editor output panel. There is deliberately no visible-password fallback.
- PowerShell policy blocks the script: report that error; do not weaken machine execution policy or paste passwords into commands.

## Safe reply

```text
DONE SETUP-04
Session pooler available: yes/no
Certificate saved: yes/no
Database connection: PASS / sanitized error
TLS certificate and hostname verification: PASS / not reached
Read-only transaction: PASS / not reached
Any setup problem: none / description without passwords or connection strings
```

## Local implementation verification — 25 October 2026

Offline Maven reactor passed. Existing 30 foundation assertions/17 Swing renders, 237 permission checks and 80 Auth/HTTP checks passed. Ten new host-validation checks passed, rejecting connection strings, URL query injection, alternate regions and suffix spoofing. These checks do not prove a live database connection. No SQL has been run against Supabase by this stage. The diagnostic uses the setup administrator only; it must never become the runtime application connection.
